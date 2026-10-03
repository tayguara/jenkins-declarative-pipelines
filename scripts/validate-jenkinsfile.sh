#!/usr/bin/env bash
# Lint the Jenkinsfile with the real declarative linter served by the running Jenkins
# (the same one behind "Pipeline Syntax > Declarative Directive Generator").
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=scripts/jenkins-api.sh
. "$root/scripts/jenkins-api.sh"

jenkinsfile=${1:-$root/Jenkinsfile}
[ -f "$jenkinsfile" ] || ja_die "Jenkinsfile not found: $jenkinsfile"

ja_wait "Jenkins to accept the admin login" 180 ja_whoami_ok
ja_fetch_crumb || ja_die "could not fetch the CSRF crumb"

response=$(jenkins_curl -X POST --data-urlencode "jenkinsfile@$jenkinsfile" \
    "$JA_JENKINS_URL/pipeline-model-converter/validate") || ja_die "linter request failed"

if grep -q 'Jenkinsfile successfully validated.' <<< "$response"; then
    echo "validate: Jenkinsfile successfully validated."
else
    printf '%s\n' "$response" >&2
    ja_die "the declarative linter rejected the Jenkinsfile"
fi
