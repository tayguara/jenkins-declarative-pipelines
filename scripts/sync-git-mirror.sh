#!/usr/bin/env bash
# Create or refresh the bare mirror that the git-server container serves to Jenkins.
#
# - refs/heads/demo points at the commit currently checked out here, so the job builds
#   exactly what you have locally. Uncommitted changes are NOT included, unless you run
#   INCLUDE_WORKTREE=1 make sync: then a throw-away snapshot commit of the working tree
#   (tracked and untracked, minus ignored files) is pushed to the mirror only. Nothing is
#   committed in this repository.
# - The library tag pinned in the Jenkinsfile (@Library('ci-common@vX.Y.Z')) is created
#   in the MIRROR ONLY when it does not exist yet. The real tag is created at release time.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
mirror_dir=$root/.demo/git
mirror=$mirror_dir/jenkins-declarative-pipelines.git

mkdir -p "$mirror_dir"

if [ ! -d "$mirror" ]; then
    echo "sync: creating mirror at ${mirror#"$root"/}"
    git clone --quiet --mirror --no-local "$root" "$mirror"
else
    echo "sync: refreshing mirror"
    git -C "$mirror" fetch --quiet --prune origin '+refs/*:refs/*'
fi

head=$(git -C "$root" rev-parse HEAD)
if [ "${INCLUDE_WORKTREE:-0}" = 1 ]; then
    index=$(mktemp -u)
    trap 'rm -f "$index"' EXIT
    GIT_INDEX_FILE=$index git -C "$root" read-tree HEAD
    GIT_INDEX_FILE=$index git -C "$root" add -A
    tree=$(GIT_INDEX_FILE=$index git -C "$root" write-tree)
    snapshot=$(git -C "$root" commit-tree "$tree" -p "$head" -m "demo: snapshot of the working tree")
    # Push into the local mirror only (a directory on this machine), never to a remote.
    git -C "$root" push --quiet --force "$mirror" "$snapshot:refs/heads/demo"
    head=$snapshot
    echo "sync: refs/heads/demo -> ${head:0:7} (snapshot of the working tree, not committed here)"
else
    git -C "$mirror" update-ref refs/heads/demo "$head"
    echo "sync: refs/heads/demo -> ${head:0:7}"
    if [ -n "$(git -C "$root" status --porcelain --untracked-files=no)" ]; then
        echo "sync: WARNING: uncommitted changes are NOT included; Jenkins builds commit ${head:0:7} (INCLUDE_WORKTREE=1 to snapshot them)" >&2
    fi
fi

jenkinsfile=$root/Jenkinsfile
if [ -f "$jenkinsfile" ]; then
    tag=$(sed -nE "s/.*@Library\\('ci-common@(v[0-9]+\\.[0-9]+\\.[0-9]+)'\\).*/\\1/p" "$jenkinsfile" | head -n 1)
    if [ -z "$tag" ]; then
        echo "sync: WARNING: no pinned @Library('ci-common@vX.Y.Z') found in Jenkinsfile" >&2
    elif git -C "$mirror" rev-parse -q --verify "refs/tags/$tag^{commit}" > /dev/null; then
        echo "sync: library tag $tag exists in the mirror"
    else
        git -C "$mirror" update-ref "refs/tags/$tag" "$head"
        echo "sync: DEMO ONLY: tag $tag not found; created in the local mirror at HEAD. Create the real tag at release." >&2
    fi
else
    echo "sync: WARNING: Jenkinsfile not found, skipping library tag check" >&2
fi

# The git-server container runs as an unprivileged user and mounts the mirror read-only.
chmod -R a+rX "$mirror_dir"
