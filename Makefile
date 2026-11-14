# FileGrid PPC — convenience targets (wrappers around docker compose).
.PHONY: up up-lite down logs ps mem reload-config reset sim-normal sim-peak sim-storm sim-stats

up: ## full mode: init-env, gen-certs, build, start, wait until healthy
	./start.sh

up-lite: ## lite mode: a single worker-all instead of one worker per category
	./start.sh --lite

down: ## stop containers (data volumes are kept)
	docker compose down

logs: ## follow logs; one service: make logs s=gateway
	docker compose logs -f --tail=100 $(s)

ps: ## container status and health
	docker compose ps -a

mem: ## memory usage per service against its limit
	bash scripts/mem-report.sh

reload-config: ## apply changes under infra/ (Prometheus reload, gateway restart)
	bash scripts/reload-config.sh

reset: ## stop and DELETE all data volumes (asks for confirmation)
	@printf 'This deletes ALL FileGrid data volumes (files, databases, metrics). Type "yes" to continue: '; \
	read answer; \
	if [ "$$answer" = "yes" ]; then \
		docker compose down -v --remove-orphans; \
	else \
		echo "Aborted."; \
	fi

# --- Meter simulator (Stage 5): recreate the simulator with another mode ---
sim-normal: ## simulator in normal mode (default rate)
	SIM_MODE=normal docker compose up -d --no-deps --force-recreate simulator

sim-peak: ## simulator in peak mode (x10 readings per minute)
	SIM_MODE=peak docker compose up -d --no-deps --force-recreate simulator

sim-storm: ## simulator in storm mode (normal rate + x10 bursts)
	SIM_MODE=storm docker compose up -d --no-deps --force-recreate simulator

sim-stats: ## meter ingestion statistics (GET /api/meters/stats)
	curl -sk https://localhost:$${GATEWAY_HTTPS_PORT:-443}/api/meters/stats; echo
