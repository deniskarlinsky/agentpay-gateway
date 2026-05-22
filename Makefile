SHELL := /usr/bin/bash
.SHELLFLAGS := -c
.PHONY: up down demo test eval logs langfuse grafana trace build

COMPOSE = docker compose
GRADLEW = bash gradlew

# ── Infrastructure ────────────────────────────────────────────────────────────

up:
	$(GRADLEW) :services:sanctions-mcp:bootJar :services:mock-psp:bootJar :services:gateway:bootJar :services:orchestrator:bootJar :services:buyer-client:bootJar
	$(COMPOSE) up -d
	@echo "Stack is up. Use 'make logs SERVICE=<name>' to follow logs."
	$(COMPOSE) ps

down:
	$(COMPOSE) down -v

# ── Build ─────────────────────────────────────────────────────────────────────

build:
	$(GRADLEW) build

# ── Demo ─────────────────────────────────────────────────────────────────────

BUYER_CLIENT_JAR = services/buyer-client/build/libs/buyer-client.jar

# Iter 5 (FR-B-001..004, NFR-DX-002): drives Scenario A (happy) and Scenario B (compliance-fail)
# back-to-back through the live stack. The --scenario=review path is supported by the CLI but
# kept out of `make demo` — its outcome is best-effort against the live Sonnet 4.6 model.
demo: $(BUYER_CLIENT_JAR)
	@echo "── Scenario A (happy) ────────────────────────────────────────────────"
	java -jar $(BUYER_CLIENT_JAR) --merchant=merchant-acme --amount=42.50 --scenario=happy
	@echo
	@echo "── Scenario B (compliance-fail) ──────────────────────────────────────"
	java -jar $(BUYER_CLIENT_JAR) --merchant=merchant-acme --amount=42.50 --scenario=compliance-fail

# Builds the buyer-client jar on demand so `make demo` works from a clean checkout without
# requiring a prior `make up`.
$(BUYER_CLIENT_JAR):
	$(GRADLEW) :services:buyer-client:bootJar

# ── Tests ────────────────────────────────────────────────────────────────────

test:
	$(GRADLEW) test

eval:
	$(GRADLEW) :evals:test

# ── Logs ─────────────────────────────────────────────────────────────────────

logs:
	$(COMPOSE) logs -f $(SERVICE)

# ── Observability UIs ─────────────────────────────────────────────────────────

langfuse:
	@echo "Langfuse → http://localhost:3000"
	@start http://localhost:3000 2>/dev/null || open http://localhost:3000 2>/dev/null || true

grafana:
	@echo "Grafana → http://localhost:3001"
	@start http://localhost:3001 2>/dev/null || open http://localhost:3001 2>/dev/null || true

trace:
	@echo "Traces → http://localhost:3000/traces"
	@start http://localhost:3000/traces 2>/dev/null || open http://localhost:3000/traces 2>/dev/null || true
