#!/usr/bin/env bash
# Build the local images only when their build context changed.
#
# `docker compose up --build` rebuilds every image on every run, and BuildKit gives each
# rebuild a new image id even when every layer comes from the cache. Compose then recreates
# the containers that use it (agent, keygen and, through depends_on, jenkins). Skipping the
# rebuild keeps `make up` idempotent on a running stack.
#
# Usage: build-images.sh [--force] SERVICE...   (services: agent, jenkins, git-server)
#   --force  rebuild even if nothing changed and pull newer base images (make build)
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
stamp_dir=$root/.demo/images
force=0
if [ "${1:-}" = --force ]; then
    force=1
    shift
fi

# service -> "<context dir> <image tag>"
context_of() {
    case $1 in
        agent) echo "jenkins/agent jenkins-declarative-pipelines-agent:local" ;;
        jenkins) echo "jenkins/controller jenkins-declarative-pipelines-controller:local" ;;
        git-server) echo "jenkins/git-server jenkins-declarative-pipelines-git-server:local" ;;
        *) echo "build-images: unknown service '$1'" >&2; return 1 ;;
    esac
}

mkdir -p "$stamp_dir"
cd "$root"
for service in "$@"; do
    read -r dir image <<< "$(context_of "$service")"
    hash=$(find "$dir" -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
    stamp=$stamp_dir/$service
    if [ "$force" -eq 0 ] && docker image inspect "$image" > /dev/null 2>&1 \
        && [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$hash" ]; then
        echo "build-images: $service is up to date"
        continue
    fi
    echo "build-images: building $service"
    if [ "$force" -eq 1 ]; then
        docker compose --profile local-git build --pull "$service"
    else
        docker compose --profile local-git build "$service"
    fi
    printf '%s\n' "$hash" > "$stamp"
done
