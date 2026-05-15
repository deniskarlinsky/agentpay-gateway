.PHONY: up down demo test eval eval-watch logs langfuse grafana trace build

COMPOSE = docker compose
GRADLEW = ./gradlew

# ── Infrastructure ────────────────────────────────────────────────────────────

up:
	$(GRADLEW) :services:sanctions-mcp:bootJar :services:mock-psp:bootJar :services:gateway:bootJar :services:orchestrator:bootJar
	$(COMPOSE) up -d
	@echo "Stack is up. Use 'make logs SERVICE=<name>' to follow logs."
	$(COMPOSE) ps

down:
	$(COMPOSE) down -v

# ── Build ─────────────────────────────────────────────────────────────────────

build:
	$(GRADLEW) build

# ── Demo ─────────────────────────────────────────────────────────────────────

demo:
	@echo "Iter 3 demo — happy path end-to-end via stub supervisor (Scenario A)."
	@echo "Drives a payment from intent-token through Saga to COMMITTED, including"
	@echo "Postgres persistence and a Kafka PaymentEvent. The polished CLI demo with"
	@echo "compliance-fail arrives in Iter 5 (buyer-client)."
	$(GRADLEW) :services:orchestrator:test --tests "com.agentpay.orchestrator.e2e.ScenarioA_HappyPathIT"

# ── Tests ────────────────────────────────────────────────────────────────────

test:
	$(GRADLEW) test

eval:
	$(GRADLEW) :evals:test

eval-watch:
	@echo "(not implemented yet — available in Iteration 6)"

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
