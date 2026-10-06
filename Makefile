SHELL := /bin/bash
JAVA_HOME ?= /home/arthur/dev/engenize/.tools/corretto-25
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)
.PHONY: db-up db-down api-run api-test web-run web-build
db-up:
	docker compose --env-file .env up -d postgres
db-down:
	docker compose --env-file .env down
api-run:
	set -a; source .env; set +a; cd apps/api && ./mvnw spring-boot:run
api-test:
	cd apps/api && ./mvnw verify
web-run:
	cd apps/web && npm start
web-build:
	cd apps/web && npm run build
