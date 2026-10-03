#!/usr/bin/env bash
# Validate .env before the stack starts. Values are never printed: only variable names.
#
# Fails when .env is missing, a required secret is empty / shorter than 16 characters /
# a known placeholder, or the two deploy tokens are identical.
#
# On success it also writes the secrets as files under .demo/secrets/ (directory 0700,
# git-ignored). Docker Compose mounts them under /run/secrets, where JCasC and the web
# router read them. The files are 0644 inside the private directory so the unprivileged
# container users (uid 1000) can read them whatever the host uid is.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
env_file=${1:-$root/.env}
# shellcheck source=scripts/lib-env.sh
. "$root/scripts/lib-env.sh"

if [ ! -f "$env_file" ]; then
    echo "ERROR: ${env_file#"$root"/} not found. Copy .env.example to .env and fill every value." >&2
    exit 1
fi

errors=0
fail() {
    echo "ERROR: $*" >&2
    errors=$((errors + 1))
}

is_placeholder() {
    local lower
    lower=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
    case $lower in
        *changeme* | *change-me* | *change_me* | *replace-me* | *replaceme* | *placeholder* | *your-* | *your_* | \
            *example* | *xxxxxxxx* | *password123* | *12345678* | *'<'*'>'* | secret* | password* | token*)
            return 0 ;;
    esac
    return 1
}

# The values reach curl as `user = "id:password"` / `header = "..."` config lines (see
# jenkins-api.sh), where a double quote or a backslash would break the line or inject options.
check_chars() {
    local name=$1 value
    value=$(env_get "$env_file" "$name")
    case $value in
        *'"'* | *$'\\'*) fail "$name must not contain a double quote or a backslash" ;;
    esac
}

check_secret() {
    local name=$1 value
    value=$(env_get "$env_file" "$name")
    if [ -z "$value" ]; then
        fail "$name is required but empty or missing"
    elif [ "${#value}" -lt 16 ]; then
        fail "$name must be at least 16 characters (hint: openssl rand -hex 24)"
    elif is_placeholder "$value"; then
        fail "$name still looks like a placeholder"
    fi
}

check_secret JENKINS_ADMIN_PASSWORD
check_secret DEPLOY_TOKEN_STAGING
check_secret DEPLOY_TOKEN_PRODUCTION

if [ -z "$(env_get "$env_file" JENKINS_ADMIN_ID)" ]; then
    fail "JENKINS_ADMIN_ID is required"
fi
for name in JENKINS_ADMIN_ID JENKINS_ADMIN_PASSWORD DEPLOY_TOKEN_STAGING DEPLOY_TOKEN_PRODUCTION; do
    check_chars "$name"
done

staging=$(env_get "$env_file" DEPLOY_TOKEN_STAGING)
production=$(env_get "$env_file" DEPLOY_TOKEN_PRODUCTION)
if [ -n "$staging" ] && [ "$staging" = "$production" ]; then
    fail "DEPLOY_TOKEN_STAGING and DEPLOY_TOKEN_PRODUCTION must be different"
fi

for port in JENKINS_HTTP_PORT WEB_STAGING_PORT WEB_PRODUCTION_PORT; do
    value=$(env_get "$env_file" "$port")
    if [ -n "$value" ] && ! printf '%s' "$value" | grep -Eq '^[0-9]{2,5}$'; then
        fail "$port must be a number"
    fi
done

# The file holds the real secrets: keep it private to the owner.
chmod 600 "$env_file"

if [ "$errors" -gt 0 ]; then
    echo "check-env: $errors problem(s) found in ${env_file#"$root"/}" >&2
    exit 1
fi

secrets_dir=$root/.demo/secrets
mkdir -p "$secrets_dir"
chmod 0700 "$root/.demo" "$secrets_dir"
write_secret() {
    # printf is a builtin: the value never reaches argv. No trailing newline is added.
    printf '%s' "$(env_get "$env_file" "$2")" > "$secrets_dir/$1"
    chmod 0644 "$secrets_dir/$1"
}
write_secret jenkins_admin_password JENKINS_ADMIN_PASSWORD
write_secret deploy_token_staging DEPLOY_TOKEN_STAGING
write_secret deploy_token_production DEPLOY_TOKEN_PRODUCTION

echo "check-env: ${env_file#"$root"/} looks good; secrets written to .demo/secrets/"
