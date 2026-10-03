#!/usr/bin/env bash
# Exit 0 when REPO_URL (from .env) points at the local git-server container,
# which is also the default when REPO_URL is unset. Used by the Makefile.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=scripts/lib-env.sh
. "$root/scripts/lib-env.sh"

repo_url=$(env_get "$root/.env" REPO_URL)
case $repo_url in
    '' | git://git-server*) exit 0 ;;
    *) exit 1 ;;
esac
