#!/usr/bin/env bash
# Sourced helpers for talking to the local Jenkins REST API.
#
# Credentials are read from .env into shell variables and handed to curl through
# `--config -` on stdin, so they never appear in argv (ps) or in the output.
# The session uses a cookie jar plus a CSRF crumb.

JA_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=scripts/lib-env.sh
. "$JA_ROOT/scripts/lib-env.sh"

JA_ENV_FILE=${JA_ENV_FILE:-$JA_ROOT/.env}
JA_JOB=${JA_JOB:-php-app-pipeline}
JA_USER=$(env_get "$JA_ENV_FILE" JENKINS_ADMIN_ID)
JA_PASS=$(env_get "$JA_ENV_FILE" JENKINS_ADMIN_PASSWORD)
JA_PORT=$(env_get "$JA_ENV_FILE" JENKINS_HTTP_PORT)
# Built from .env only: an ambient JENKINS_URL (set on many CI hosts) must never receive the admin login.
JA_JENKINS_URL=http://127.0.0.1:${JA_PORT:-8080}
JA_TMP=$(mktemp -d)
JA_COOKIES=$JA_TMP/cookies
JA_CRUMB_FIELD=
JA_CRUMB=

ja_cleanup() { rm -rf "$JA_TMP"; }
trap ja_cleanup EXIT

ja_die() {
    echo "ERROR: $*" >&2
    exit 1
}

if [ -z "$JA_USER" ] || [ -z "$JA_PASS" ]; then
    ja_die "JENKINS_ADMIN_ID / JENKINS_ADMIN_PASSWORD are not set in ${JA_ENV_FILE#"$JA_ROOT"/}"
fi

# jenkins_curl [curl args...]: authenticated request (cookie session, crumb header when known).
jenkins_curl() {
    local extra=()
    if [ -n "$JA_CRUMB" ]; then
        extra=(-H "$JA_CRUMB_FIELD: $JA_CRUMB")
    fi
    printf 'user = "%s:%s"\n' "$JA_USER" "$JA_PASS" \
        | curl -sSg --config - -b "$JA_COOKIES" -c "$JA_COOKIES" --max-time 60 "${extra[@]}" "$@"
}

# jenkins_get PATH: GET, fails on HTTP errors, prints the body.
jenkins_get() { jenkins_curl -f "$JA_JENKINS_URL$1"; }

# jenkins_json PATH JQ_FILTER: GET a JSON endpoint and print the filtered value.
jenkins_json() { jenkins_get "$1" | jq -r "$2"; }

# ja_fetch_crumb: crumb tied to the session cookie (the controller uses the standard crumb issuer).
ja_fetch_crumb() {
    local body
    body=$(JA_CRUMB='' jenkins_get '/crumbIssuer/api/json') || return 1
    JA_CRUMB_FIELD=$(printf '%s' "$body" | jq -r '.crumbRequestField')
    JA_CRUMB=$(printf '%s' "$body" | jq -r '.crumb')
}

# ja_wait DESCRIPTION TIMEOUT_SECONDS COMMAND...: poll every 3 s until COMMAND succeeds.
ja_wait() {
    local what=$1 timeout=$2 waited=0
    shift 2
    while ! "$@" > /dev/null 2>&1; do
        if [ "$waited" -ge "$timeout" ]; then
            ja_die "timed out after ${timeout}s waiting for: $what"
        fi
        sleep 3
        waited=$((waited + 3))
    done
}

ja_whoami_ok() {
    [ "$(jenkins_json /whoAmI/api/json '.authenticated' 2> /dev/null)" = true ]
}

ja_agent_online() {
    [ "$(jenkins_json '/computer/php-agent/api/json?tree=offline' .offline 2> /dev/null)" = false ]
}

ja_job_exists() { jenkins_get "/job/$JA_JOB/api/json?tree=name" > /dev/null 2>&1; }

# ja_start_build QUERY: trigger a build ("" or "TARGET_ENV=staging&ROLLBACK=true"); prints the queue item id.
# The first build of a declarative job has no parameter definitions yet, so it has to use
# /build; once the job knows its parameters, /buildWithParameters is required.
ja_start_build() {
    local query=${1:-} endpoint headers location
    if [ -z "$query" ] && [ "$(jenkins_json "/job/$JA_JOB/api/json?tree=property[parameterDefinitions[name]]" \
        '[.property[]? | select(.parameterDefinitions) | .parameterDefinitions[]] | length')" -gt 0 ]; then
        query='TARGET_ENV=staging&ROLLBACK=false'
    fi
    if [ -n "$query" ]; then
        endpoint="/job/$JA_JOB/buildWithParameters?$query"
    else
        endpoint="/job/$JA_JOB/build"
    fi
    headers=$(jenkins_curl -f -X POST -D - -o /dev/null "$JA_JENKINS_URL$endpoint") || ja_die "could not start a build ($endpoint)"
    location=$(printf '%s' "$headers" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p' | tail -n 1)
    printf '%s' "$location" | sed -E 's#.*/queue/item/([0-9]+)/?#\1#'
}

# ja_build_number QUEUE_ID: wait (<= 120 s) until the queue item becomes a build; prints its number.
ja_build_number() {
    local id=$1 waited=0 number
    while :; do
        number=$(jenkins_json "/queue/item/$id/api/json?tree=executable[number]" . 2> /dev/null | jq -r '.executable.number // empty') || true
        if [ -n "$number" ]; then
            printf '%s' "$number"
            return 0
        fi
        [ "$waited" -lt 120 ] || ja_die "queue item $id did not start within 120s"
        sleep 2
        waited=$((waited + 2))
    done
}

# ja_build_result NUMBER TIMEOUT_SECONDS: wait for the build to finish; prints SUCCESS, FAILURE, ...
ja_build_result() {
    local number=$1 timeout=$2 waited=0 json
    while :; do
        json=$(jenkins_get "/job/$JA_JOB/$number/api/json?tree=building,result" 2> /dev/null) || json=
        if [ -n "$json" ] && [ "$(printf '%s' "$json" | jq -r '.building')" = false ]; then
            printf '%s' "$json" | jq -r '.result'
            return 0
        fi
        [ "$waited" -lt "$timeout" ] || ja_die "build #$number still running after ${timeout}s"
        sleep 3
        waited=$((waited + 3))
    done
}
