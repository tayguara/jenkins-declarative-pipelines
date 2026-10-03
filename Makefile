# Thin wrapper over the scripts. Run `make help` for the list of targets.
SHELL := /bin/bash
.DEFAULT_GOAL := help

# The git-server container (local mirror) is only needed while REPO_URL points to it.
LOCAL_GIT := $(shell scripts/use-local-git.sh && echo yes)
PROFILE   := $(if $(LOCAL_GIT),--profile local-git,)
IMAGES    := agent jenkins $(if $(LOCAL_GIT),git-server,)

.PHONY: help up build sync smoke validate down clean test lint

help: ## List the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-10s %s\n", $$1, $$2}'

up: ## Validate .env, sync the git mirror, build changed images and start the stack (safe to repeat)
	@scripts/check-env.sh
	@$(if $(LOCAL_GIT),scripts/sync-git-mirror.sh,echo "up: REPO_URL is not the local git-server, skipping the mirror sync")
	@scripts/build-images.sh $(IMAGES)
	docker compose $(PROFILE) up -d --wait

build: ## Force a rebuild of the images (also pulls newer base images)
	@scripts/build-images.sh --force $(IMAGES)

sync: ## Refresh the local git mirror (demo branch = current HEAD)
	@scripts/sync-git-mirror.sh

smoke: ## End-to-end test: 3 builds through the REST API plus leak checks
	@scripts/smoke-test.sh

validate: ## Lint the Jenkinsfile with the real declarative linter
	@scripts/validate-jenkinsfile.sh

down: ## Stop the stack (volumes are kept)
	docker compose --profile local-git down

clean: ## Stop the stack and DELETE volumes and .demo/ (all local Jenkins state)
	@echo "WARNING: this deletes the Jenkins home, the deployed releases and .demo/"
	docker compose --profile local-git down -v
	rm -rf .demo

test: ## Library tests (JPU) and the PHP app checks
	./gradlew test
	composer -d app check

lint: ## groovy-lint, hadolint, shellcheck, actionlint and compose config
	npm run lint:groovy
	for f in controller agent git-server; do \
		docker run --rm -i -v "$$PWD/.hadolint.yaml":/.hadolint.yaml:ro hadolint/hadolint:v2.15.1 hadolint --config /.hadolint.yaml - < jenkins/$$f/Dockerfile || exit 1; \
	done
	docker run --rm -v "$$PWD":/mnt -w /mnt koalaman/shellcheck:stable scripts/*.sh app/bin/package
	docker run --rm -v "$$PWD":/repo -w /repo rhysd/actionlint:1.7.12 -color
	docker compose --profile local-git config -q
