# FileGrid PPC — convenience targets (wrappers around docker compose).
.PHONY: up up-lite down logs ps mem reset

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

reset: ## stop and DELETE all data volumes (asks for confirmation)
	@printf 'This deletes ALL FileGrid data volumes (files, databases, metrics). Type "yes" to continue: '; \
	read answer; \
	if [ "$$answer" = "yes" ]; then \
		docker compose down -v --remove-orphans; \
	else \
		echo "Aborted."; \
	fi
