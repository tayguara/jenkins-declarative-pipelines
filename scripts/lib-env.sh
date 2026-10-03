#!/usr/bin/env bash
# Sourced helper: read values from a dotenv file WITHOUT executing it.
# Supports KEY=VALUE, optional single/double quotes and trailing " # comment" on
# unquoted values (the same rules Docker Compose applies to .env files).

# env_get FILE KEY -> prints the value (empty if unset). Never prints anything else.
env_get() {
    local file=$1 key=$2 line value
    [ -f "$file" ] || return 0
    line=$(grep -E "^[[:space:]]*${key}=" "$file" | tail -n 1) || true
    [ -n "$line" ] || return 0
    value=${line#*=}
    # trim leading whitespace
    value=${value#"${value%%[![:space:]]*}"}
    case $value in
        \"*\") value=${value#\"}; value=${value%\"} ;;
        \'*\') value=${value#\'}; value=${value%\'} ;;
        *)
            # drop an inline comment (whitespace followed by #), then trailing whitespace
            case $value in \#*) value= ;; esac
            value=${value%%[[:space:]]#*}
            value=${value%"${value##*[![:space:]]}"}
            ;;
    esac
    printf '%s' "$value"
}
