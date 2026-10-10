FROM composer:2.10.3 AS composer

FROM jenkins/ssh-agent:9.1.0-jdk21

# PHP 8.4 comes straight from Debian 13 (trixie), the base of this image.
# pcov provides line coverage for PHPUnit without the Xdebug overhead.
# The base image keeps root as the default user on purpose: its entrypoint
# (setup-sshd) starts sshd, which needs root. Builds run as the "jenkins" user.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        php8.4-cli php8.4-xml php8.4-mbstring php8.4-curl php8.4-zip php8.4-pcov \
        unzip git rsync curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=composer /usr/bin/composer /usr/local/bin/composer

# Named volumes inherit the owner of the directory they are mounted on, so the
# deploy tree and the key directories are created owned by uid 1000 (jenkins).
RUN install -d -o jenkins -g jenkins /srv/deploy /keys/private /keys/public
