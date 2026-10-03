#!/usr/bin/env bash
# End-to-end smoke test against the running local stack (`make up` first).
#
#   1. wait for the admin login, the php-agent node and the job
#   2. lint the Jenkinsfile with the real declarative linter
#   3. build 1 (defaults): SUCCESS, deploys to the idle slot, tests/coverage/summary published
#   4. build 2: SUCCESS, deploys to the other slot
#   5. build 3 (ROLLBACK=true): SUCCESS, gates skipped by `when`, previous slot live again
#   6. no secret (admin password, deploy tokens) in any console log or in `docker inspect`
#
# Credentials go to curl through stdin (`--config -`), never through argv.
# Needs: curl, jq, docker.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=scripts/jenkins-api.sh
. "$root/scripts/jenkins-api.sh"

for tool in curl jq docker; do
    command -v "$tool" > /dev/null || ja_die "missing prerequisite: $tool"
done

staging_port=$(env_get "$JA_ENV_FILE" WEB_STAGING_PORT)
staging_url=http://127.0.0.1:${staging_port:-8081}
staging_credential=$(env_get "$JA_ENV_FILE" DEPLOY_TOKEN_STAGING)
repo_url=$(env_get "$JA_ENV_FILE" REPO_URL)
repo_url=${repo_url:-git://git-server/jenkins-declarative-pipelines.git}
smoke_dir=$root/.demo/smoke
mkdir -p "$smoke_dir"
rm -f "$smoke_dir"/build-*.log

# Secret values to look for in every log and config we read or save (grep -F -f, never argv).
# Empty values are skipped: an empty pattern line would match everything.
umask 077
patterns=$JA_TMP/patterns
: > "$patterns"
for secret in "$JA_PASS" "$staging_credential" "$(env_get "$JA_ENV_FILE" DEPLOY_TOKEN_PRODUCTION)"; do
    if [ -n "$secret" ]; then printf '%s\n' "$secret" >> "$patterns"; fi
done
[ -s "$patterns" ] || ja_die "no secret values found in ${JA_ENV_FILE#"$root"/}"

# The commit the job is expected to build: the demo ref of the local mirror (it may be a
# working-tree snapshot, see sync-git-mirror.sh) or, when Jenkins reads from GitHub, HEAD.
if "$root/scripts/use-local-git.sh"; then
    expected_commit=$(git -C "$root/.demo/git/jenkins-declarative-pipelines.git" rev-parse refs/heads/demo) \
        || ja_die "cannot read refs/heads/demo of the local mirror (run: make sync)"
else
    expected_commit=$(git -C "$root" rev-parse HEAD) || ja_die "cannot read HEAD"
fi
expected_commit=${expected_commit:0:7}

rows=()
started=$SECONDS

summary() {
    local status=$?
    echo
    echo "Smoke test summary"
    echo "------------------"
    printf '%-44s %-6s %s\n' CHECK RESULT DETAIL
    local row
    for row in "${rows[@]}"; do
        IFS='|' read -r name result detail <<< "$row"
        printf '%-44s %-6s %s\n' "$name" "$result" "$detail"
    done
    echo "------------------"
    if [ "$status" -eq 0 ]; then
        echo "PASSED in $((SECONDS - started))s"
    else
        echo "FAILED after $((SECONDS - started))s (console logs, when free of secrets, are in ${smoke_dir#"$root"/}/)" >&2
    fi
    ja_cleanup
}
trap summary EXIT

pass() { rows+=("$1|ok|${2:-}"); echo "ok    $1${2:+ ($2)}"; }

# fail NAME DETAIL [BUILD_NUMBER]: record the failure, keep the console log only if it holds no secret, exit.
fail() {
    rows+=("$1|FAIL|$2")
    echo "FAIL  $1: $2" >&2
    if [ -n "${3:-}" ]; then
        local text
        if text=$(jenkins_get "/job/$JA_JOB/$3/consoleText" 2> /dev/null) && [ -n "$text" ]; then
            if grep -qFf "$patterns" <<< "$text"; then
                echo "      console log of build #$3 contains a secret value: not saved" >&2
            else
                printf '%s\n' "$text" > "$smoke_dir/build-$3.log"
            fi
        fi
    fi
    exit 1
}

# web PATH [with-token]: GET against web-staging; prints "<http status> <body>".
web() {
    local path=$1 with_token=${2:-} out
    if [ -n "$with_token" ]; then
        out=$(printf 'header = "X-Deploy-Token: %s"\n' "$staging_credential" \
            | curl -sS --config - --max-time 10 -w '\n%{http_code}' "$staging_url$path") || return 1
    else
        out=$(curl -sS --max-time 10 -w '\n%{http_code}' "$staging_url$path") || return 1
    fi
    printf '%s %s' "${out##*$'\n'}" "${out%$'\n'*}"
}

live_color() {
    web / | sed -nE 's/^200 .* on (blue|green)$/\1/p'
}

other_color() { if [ "$1" = blue ]; then echo green; else echo blue; fi; }

# run_build NAME QUERY: start, wait and echo "<number> <result>".
run_build() {
    local queue number result
    queue=$(ja_start_build "$2")
    number=$(ja_build_number "$queue")
    echo "      $1: build #$number started" >&2
    result=$(ja_build_result "$number" 900)
    printf '%s %s' "$number" "$result"
}

console() { jenkins_get "/job/$JA_JOB/$1/consoleText"; }

# ---------------------------------------------------------------- 1. readiness
ja_wait "Jenkins to accept the admin login" 180 ja_whoami_ok
pass "Admin login (credentials from secrets)"
ja_wait "node php-agent to be online" 120 ja_agent_online
pass "Agent php-agent online"
ja_wait "job $JA_JOB to exist" 60 ja_job_exists
pass "Job $JA_JOB created by Job DSL"
ja_fetch_crumb || fail "CSRF crumb" "could not fetch a crumb"

# ---------------------------------------------------------------- 2. declarative linter
lint=$(jenkins_curl -X POST --data-urlencode "jenkinsfile@$root/Jenkinsfile" \
    "$JA_JENKINS_URL/pipeline-model-converter/validate") || fail "Declarative linter" "the linter request failed"
if grep -q 'Jenkinsfile successfully validated.' <<< "$lint"; then
    pass "Declarative linter"
else
    fail "Declarative linter" "Jenkinsfile was not validated (run: make validate)"
fi

# ---------------------------------------------------------------- 3. build 1
before=$(live_color || true)
color1=$(other_color "${before:-green}")
out=$(run_build 'build 1 (defaults)' '')
read -r b1 r1 <<< "$out"
[ "$r1" = SUCCESS ] || fail "Build 1 result" "expected SUCCESS, got $r1" "$b1"
pass "Build 1 result" "#$b1 SUCCESS"

log1=$(console "$b1") || fail "Build 1 console" "could not read the console log of build #$b1" "$b1"
grep -q 'Running on php-agent' <<< "$log1" || fail "Build 1 runs on php-agent" "console has no 'Running on php-agent'" "$b1"
pass "Build 1 runs on php-agent"
pin=$(grep -oE 'ci-common@v[0-9]+\.[0-9]+\.[0-9]+' <<< "$log1" | head -n 1 || true)
[ -n "$pin" ] || fail "Library loaded by pinned tag" "console has no ci-common@vX.Y.Z" "$b1"
# A repeated run on a kept stack reuses the cached library and never contacts the repository.
if ! grep -qE "Library $pin is cached" <<< "$log1"; then
    grep -qF "$repo_url" <<< "$log1" || fail "Library fetched from REPO_URL" "console never mentions $repo_url" "$b1"
fi
pass "Library ci-common resolved from the pinned tag" "$pin"
grep -qF 'Stage "Rollback" skipped due to when conditional' <<< "$log1" || fail "Rollback stage skipped by when" "expected 'Stage \"Rollback\" skipped due to when conditional'" "$b1"
pass "Rollback stage skipped by when"

# The JUnit action exposes totalCount/failCount on the build itself.
report=$(jenkins_get "/job/$JA_JOB/$b1/api/json?tree=actions[totalCount,failCount,skipCount]" \
    | jq -c '[.actions[] | select(.totalCount != null)] | first // {}') || fail "JUnit report" "could not read build #$b1" "$b1"
[ "$(printf '%s' "$report" | jq '(.failCount == 0) and (.totalCount > 0)')" = true ] \
    || fail "JUnit report" "unexpected counts: $report" "$b1"
pass "JUnit published" "$(printf '%s' "$report" | jq -r '"\(.totalCount) tests, \(.failCount) failed"')"

coverage=$(jenkins_get "/job/$JA_JOB/$b1/coverage/api/json?depth=1" | jq -r '.projectStatistics.line // empty') \
    || fail "Coverage published" "no coverage action on build #$b1" "$b1"
[ -n "$coverage" ] || fail "Coverage published" "coverage action has no line statistics" "$b1"
pass "Coverage visible on the build" "line $coverage"

jenkins_get "/job/$JA_JOB/$b1/artifact/app/reports/build-summary.md" > /dev/null || fail "build-summary.md archived" "artifact not found on build #$b1" "$b1"
pass "build-summary.md archived"

result=$(web /) || fail "Staging after build 1" "web-staging not reachable at $staging_url" "$b1"
case $result in
    "200 release $expected_commit (build $b1) on $color1") pass "Staging serves commit $expected_commit on slot $color1" "$result" ;;
    *) fail "Staging serves commit $expected_commit on slot $color1" "got: $result" "$b1" ;;
esac

# ---------------------------------------------------------------- 4. build 2
out=$(run_build 'build 2 (staging)' 'TARGET_ENV=staging')
read -r b2 r2 <<< "$out"
[ "$r2" = SUCCESS ] || fail "Build 2 result" "expected SUCCESS, got $r2" "$b2"
color2=$(other_color "$color1")
result=$(web /) || fail "Staging after build 2" "web-staging not reachable" "$b2"
case $result in
    "200 release $expected_commit (build $b2) on $color2") pass "Build 2 deployed, staging serves slot $color2" "#$b2 SUCCESS" ;;
    *) fail "Build 2 deployed, staging serves slot $color2" "got: $result" "$b2" ;;
esac

# ---------------------------------------------------------------- 5. build 3 (rollback)
out=$(run_build 'build 3 (rollback)' 'TARGET_ENV=staging&ROLLBACK=true')
read -r b3 r3 <<< "$out"
[ "$r3" = SUCCESS ] || fail "Build 3 result" "expected SUCCESS, got $r3" "$b3"
log3=$(console "$b3") || fail "Build 3 console" "could not read the console log of build #$b3" "$b3"
grep -qF 'Stage "Quality gates" skipped due to when conditional' <<< "$log3" \
    || fail "Gates skipped on rollback" "expected 'Stage \"Quality gates\" skipped due to when conditional'" "$b3"
result=$(web /) || fail "Staging after rollback" "web-staging not reachable" "$b3"
case $result in
    "200 release $expected_commit (build $b1) on $color1") pass "Rollback: gates skipped, staging back on slot $color1" "#$b3 SUCCESS" ;;
    *) fail "Rollback: gates skipped, staging back on slot $color1" "got: $result" "$b3" ;;
esac

# ---------------------------------------------------------------- web service assertions
[ "$(web /health | cut -d' ' -f1)" = 401 ] || fail "Health without token" "expected 401"
pass "Health without token is refused (401)"
health=$(web /health with-token)
if [ "${health%% *}" != 200 ] || [ "$(printf '%s' "${health#* }" | jq -r '.status')" != ok ]; then
    fail "Health with token" "got: $health"
fi
pass "Health with token is ok" "$(printf '%s' "${health#* }" | jq -c '{slot,version}')"
next=$(web '/api/next-version?current=1.2.3&bump=minor')
if [ "${next%% *}" != 200 ] || [ "$(printf '%s' "${next#* }" | jq -r '.next')" != 1.3.0 ]; then
    fail "App endpoint /api/next-version" "got: $next"
fi
pass "App endpoint /api/next-version"

# ---------------------------------------------------------------- 6. secret leakage
# Capture first, then scan the captured text: with `set -o pipefail` a `cmd | grep -q` can report
# "no leak" when grep exits early and cmd dies of SIGPIPE, and a failed capture must not pass.
for n in "$b1" "$b2" "$b3"; do
    text=$(console "$n") || fail "Console log of build #$n" "could not read it for the secret scan"
    [ -n "$text" ] || fail "Console log of build #$n" "it is empty, nothing to scan"
    if grep -qFf "$patterns" <<< "$text"; then
        fail "No secret in console log of build #$n" "a secret value appears in the console log (log not saved)"
    fi
done
pass "No secret in the console logs" "builds #$b1, #$b2, #$b3"

jenkins_container=$(cd "$root" && docker compose --profile local-git ps -q jenkins) \
    || fail "Controller container" "docker compose ps failed"
[ -n "$jenkins_container" ] || fail "Controller container" "the jenkins container id is empty: the stack is not running from this directory"
inspect=$(docker inspect "$jenkins_container") || fail "docker inspect" "could not inspect the controller container"
if grep -qFf "$patterns" <<< "$inspect"; then
    fail "No secret in docker inspect" "a secret value appears in the controller's container config"
fi
pass "No secret in docker inspect of the controller"
