# Jurnalul etapelor

## Etapa 1 — Schelet + infrastructură Docker (2026-11-14)

### Ce s-a făcut
- Structura de directoare din ARCHITECTURE.md §A.5, cu README în fiecare director gol.
- `infra/docker-compose.yml` (inclus de `compose.yaml` din rădăcină) cu: gateway (Traefik v3.6), meta-db (pgvector pg16), store-db (PostgreSQL 16 separat), s3 (MinIO) + minio-init, ftp (pure-ftpd), rabbitmq, prometheus, alertmanager, grafana, loki, tempo, alloy, toxiproxy.
- Rețele: `edge` și `internal` (`internal: true`). Doar gateway-ul publică porturi.
- TLS (minim 1.2), redirect HTTP → HTTPS, headere de securitate, Grafana sub `/grafana`.
- MinIO: 5 bucket-uri versionate (`filegrid-faults`, `-documents`, `-videos`, `-meters`, `-benchmark`) și user de serviciu cu politică limitată la `filegrid-*`.
- FTP: mod pasiv în rețeaua Docker (porturi 30000–30099), user de serviciu.
- Observabilitate: Prometheus scrapează toate serviciile de infrastructură; Alloy trimite logurile containerelor în Loki, OTLP traces în Tempo și OTLP metrics în Prometheus; datasource-uri Grafana provizionate.
- Healthcheck pe fiecare serviciu și `depends_on` cu `service_healthy`.
- `.env.example`, `scripts/init-env.sh`, `scripts/gen-certs.sh`, `scripts/wait-healthy.sh`, `start.sh`, `start.ps1`, `Makefile`, `.gitattributes`.

### Cum se verifică
- `./start.sh` (sau `.\start.ps1`) → „All services are healthy.”
- `docker compose ps -a` → toate `healthy`; `minio-init` și `health-tools` sunt one-shot (`exited 0`).
- https://localhost/grafana se deschide (login cu `GRAFANA_ADMIN_*` din `.env`).
- `docker compose port s3 9000` → `:0` (portul nu este publicat).
- `git status --ignored` → `.env` și `infra/certs/` apar ca ignorate.

### Decizii luate în afara promptului
- **Imaginea MinIO:** imaginile oficiale `minio/minio` și `minio/mc` au fost retrase din Docker Hub și Quay în 2026. Inițial s-a folosit `pgsty/minio` / `pgsty/mc`; înlocuite ulterior cu Silo (vezi „Decizii de arhitect după Etapa 1”).
- **Traefik cu file provider** (fără socket Docker montat în gateway): suprafață de atac mai mică; rutele se adaugă în `infra/traefik/dynamic/routes.yml`.
- **`compose.yaml` în rădăcină** care include `infra/docker-compose.yml`, ca `docker compose <comandă>` să funcționeze direct din rădăcină, cu `.env`-ul de acolo.
- **Porturile gateway-ului** configurabile în `.env`: `GATEWAY_HTTP_PORT` (implicit 8080, fiindcă 80 e adesea ocupat pe Windows) și `GATEWAY_HTTPS_PORT` (implicit 443).
- **`health-tools`**: Loki și Tempo nu au shell în imagine; un one-shot copiază un `busybox` static într-un volum folosit doar de healthcheck.
- **`.gitattributes`**: scripturile `.sh` rămân cu terminații LF și pe Windows.

### Decizii de arhitect după Etapa 1 (2026-11-14)
- **S3 = Silo** (`pgsty/silo:RELEASE.2026-11-12T00-00-00Z`, fixat și pe digest `sha256:c20d1123…f329`), folosit și pentru `minio-init`, fiindcă imaginea conține clientul `mc`. Detalii în `docs/ADR/0001-s3-storage-silo-fork.md`. Criteriile de acceptare au fost rulate din nou și trec.
- **Bugete de memorie:** fiecare serviciu are `deploy.resources.limits.memory`. Postgres: `shared_buffers=64MB`, `max_connections=50`, `work_mem=4MB` (din `.env`). RabbitMQ: `vm_memory_high_watermark.relative = 0.6`. Prometheus: retenție 3 zile.
- **Profiluri compose** `full` (implicit, `COMPOSE_PROFILES` în `.env`) și `lite`; `start.sh --lite`, `start.ps1 --lite`, `make up-lite`. Serviciile worker care le folosesc apar în Etapa 2.
- **`scripts/mem-report.sh`** (`make mem`): consum per serviciu față de limită + verificare OOMKilled.
- Măsurat pe infrastructura din Etapa 1 (fără aplicație): 667 MiB folosiți din 2720 MiB limită totală; niciun container OOMKilled. Cel mai aproape de limită: rabbitmq (164 / 256 MiB) și s3 (188 / 384 MiB).
- **Rămas pentru etapele următoare:** limitele și setările JVM pentru `api` și workeri (Etapa 2), `simulator` (Etapa 5), `frontend` (Etapa 10), `intelligence` (Etapa 15); măsurătoarea cu simulatorul pornit 5 minute se poate face abia din Etapa 5.

### Limitări cunoscute
- Fără mkcert, certificatul este self-signed (avertisment în browser).
- Alertmanager nu are încă receiver real (webhook-ul vine în Etapa 8); nu există încă reguli de alertare și nici dashboard-uri.
- Toxiproxy doar pornește; proxy-urile se definesc în Etapa 18.
- Rutele `/api` și `/` lipsesc până la Etapele 2 și 10.
