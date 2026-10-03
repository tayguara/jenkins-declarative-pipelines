FROM alpine:3.24

# The stock alpine/git image does not ship the daemon: it lives in its own package.
RUN apk add --no-cache git git-daemon

# Runs unprivileged (see compose.yaml); serves the read-only mirror mounted at /srv/git.
USER 65534:65534
ENTRYPOINT ["git"]
