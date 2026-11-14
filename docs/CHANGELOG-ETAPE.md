# Jurnalul etapelor

## Etapa 3 — Agent 01: motorul de politici și rutare (2026-11-14)

### Ce s-a făcut
- `config/policies.yaml`: politică externă (montată în `api` la `/config/policies.yaml`, neinclusă în JAR) cu definiții per storage (`DATABASE`, `S3`, `FTP`) și per categorie (`METER_READING`, `FAULT_RECORD`, `CONNECTION_DOCUMENT`, `DRONE_VIDEO`) conform `ARCHITECTURE.md` §A.4 (`detection`, `primary`, `fallbacks`, `constraints`, `weights`, `retry`, `sla`).
- Migrația Flyway `V2__policy_version.sql` în `meta-db`: tabel append-only `policy_version` (versiune, sursă `FILE`/`ADMIN`, autor, conținut YAML, `content_sha256`, `diff` JSONB).
- Pachetul `ro.filegrid.policy`:
  - `Policy`, `PolicyProperties`, `PolicyParser` (parsare YAML strictă: chei necunoscute/duplicate și durate), `PolicyValidator` (raportează toate erorile în română), `PolicyDiff` (diff structural per cale punctată);
  - `PolicyService`: versionare tranzacțională cu blocare exclusivă pe tabel, păstrarea versiunii anterioare la eroare, sincronizare periodică din DB între instanțe;
  - `PolicyFileWatcher` (profil `api`): reîncarcă `config/policies.yaml` fără restart prin `WatchService` și verificare periodică SHA-256 (`FILEGRID_POLICY_CHECK_INTERVAL`, implicit `10s`) pentru bind mount-urile Windows; o versiune `ADMIN` mai nouă păstrează prioritatea față de fișierul nemodificat;
  - `PolicyController`: `GET /api/policies`, `GET /api/policies/history`, `PUT /api/admin/policies` (cu `CurrentActorProvider` și `AccessPolicy`, răspuns `ProblemDetail` în română la politică invalidă).
- Pachetul `ro.filegrid.classification`: `ClassificationService`, `ClassificationRequest`, `ClassificationResult` — combină categoria declarată, tipul MIME detectat cu Apache Tika din primii 64 KB (magic bytes), extensia și câmpurile de context obligatorii; înregistrează avertismente (`DECLARED_DIFFERS_FROM_DETECTED`, `AMBIGUOUS_DETECTION`, `MISSING_REQUIRED_FIELDS`, `UNCLASSIFIED`) fără a bloca încărcarea.
- Pachetul `ro.filegrid.routing`:
  - `RoutingEngine`: motor determinist și fără I/O; verifică constrângerile hard (`maxSizeBytes` din storage/categorie/adaptor, `needsResumable`, `needsImmediateRead`, `idempotent`), disponibilitatea din `storage_health`, calculează scorul ponderat și construiește explicația în română exclusiv din motive structurate prin template-uri;
  - `HealthTableStorageStateProvider`: citește `storage_health` și tratează ca `UNKNOWN` probele mai vechi decât `FILEGRID_ROUTING_HEALTH_MAX_AGE` (implicit `30s`);
  - `RoutingService`: salvează `routing_decision` înainte de transfer, trece fișierul în `DESTINATION_SELECTED` (cu cheie UUID din `StorageKeys`) sau `PENDING` (când niciun storage nu este eligibil și disponibil) și scrie evenimentul în `file_event`;
  - `DecisionController`: `GET /api/files/{id}/decision`.
- Pachetul `ro.filegrid.text` (`Texts`, `Sizes`) și `i18n/texts_ro.properties`: mesaje în română centralizate pentru clasificator, rutare și validarea politicii.
- Configurare: variabilele `FILEGRID_POLICY_CHECK_INTERVAL` și `FILEGRID_ROUTING_HEALTH_MAX_AGE` adăugate în `.env.example` și `infra/docker-compose.yml`.
- Teste: unitare (`PolicyValidationTest`, `ClassificationServiceTest`, `RoutingEngineTest`) și de integrare cu Testcontainers (`PolicyAndRoutingIT`).

### Cum se verifică
- `cd backend && ./mvnw verify` (sau `.\mvnw.cmd verify` pe Windows) → 75 de teste trec (36 unitare, 39 de integrare cu Testcontainers).
- `.\start.ps1` / `./start.sh` → toate serviciile (inclusiv `api` și cele 4 servicii `worker-*`) sunt `healthy`.
- `curl -k https://localhost/api/policies` → returnează politica activă (`v1`). Modificarea `maxSizeBytes` în `config/policies.yaml` creează o nouă versiune în ~10 s, vizibilă în `GET /api/policies` și `GET /api/policies/history` fără restart.

### Decizii luate în afara promptului
- **`StreamReadFeature.STRICT_DUPLICATE_DETECTION` în `PolicyParser`:** `DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY` verifică doar `JsonNode`, nu și deserializarea directă în record-ul `Policy`; activarea `STRICT_DUPLICATE_DETECTION` garantează respingerea cheilor YAML duplicate.
- **`String` în `PolicyProperties` (`filePath()`):** legarea directă la `java.nio.file.Path` în `ServletWebServerApplicationContext` determina Tomcat să interpreteze valoarea implicită `../config/policies.yaml` ca `ServletContextResource` (eșuând la pornirea containerelor `worker-*`). Folosirea `String` + `Path.of(...)` evită rezolvarea ca resursă servlet în orice profil.
- **Rafinarea `ClassificationService`:** detecția MIME folosește `tika.detect(head, fileName)` fără suprascrierea manuală a `text/plain`/`octet-stream` din extensie (o extensie falsă `.mp4` pe text/PDF nu mai ascunde magic bytes), iar avertismentul `DECLARED_DIFFERS_FROM_DETECTED` verifică `isTopMatch` (scor maxim + potrivire MIME).
- **Fix `start.ps1` pentru Windows PowerShell 5.1:** setarea temporară a `$ErrorActionPreference = 'Continue'` în jurul apelurilor `docker` / `docker compose` cu stderr redirectat sau informativ (`No stopped containers`), verificând `$LASTEXITCODE`.
- **Latența folosită la scorare (`HEALTH_PROBE`):** până la introducerea metricilor reale p95 de transfer (Etapa 8), `HealthTableStorageStateProvider` furnizează latența ultimului probe din `storage_health`, iar fiecare decizie înregistrează explicit sursa (`HEALTH_PROBE`).

### Limitări cunoscute
- `RoutingService` și `ClassificationService` sunt apelate direct din teste și API-ul de decizie; conectarea automată în pipeline-ul de upload (`tus` + RabbitMQ) vine în Etapa 4.
- Latența din factorul `measuredLatencyP95` provine din probe-ul de stare (`HEALTH_PROBE`); p95 măsurat pe transferuri reale vine în Etapa 8.
- Pe bind mount-uri Windows, detectarea modificărilor din `config/policies.yaml` se face prin verificarea periodică a checksum-ului SHA-256 (implicit la 10 s).

## Etapa 2 — Backend: model de date + adaptoare de storage (2026-11-14)

### Ce s-a făcut
- `backend/`: Spring Boot 3.5.16, Java 21, Maven wrapper. O singură aplicație cu două roluri: profilul `api` și profilul `worker` (`WORKER_CATEGORIES` alege categoriile; un worker fără categorii sau cu una necunoscută refuză să pornească).
- Dockerfile multi-stage, utilizator non-root (uid 10001), JVM dimensionată din limita containerului.
- Compose: `api` + `worker-meter`, `worker-fault`, `worker-document`, `worker-video` (profil `full`) și `worker-all` (profil `lite`), toate din aceeași imagine `filegrid/backend:local`.
- Migrații Flyway pentru meta-db (`file_record`, `file_context`, `routing_decision`, `file_event`, `storage_health`) și, separat, pentru store-db (`stored_blob`). Stările sunt protejate cu constrângeri CHECK, iar `file_event` respinge UPDATE (audit append-only).
- Entități JPA + repository-uri; Hibernate rulează cu `ddl-auto=validate`.
- Interfața `StorageAdapter` și cele trei implementări, toate cu SHA-256 calculat în flux:
  - `S3StorageAdapter`: PUT simplu până la 16 MB, multipart cu părți de 16 MB peste, abort la eroare;
  - `FtpStorageAdapter`: binar, mod pasiv, directoare `categorie/an/lună/zi`, scriere în `.part` + rename, pool de conexiuni cu reconectare, reluare prin offset REST;
  - `DatabaseStorageAdapter`: `bytea` scris în flux, citit în felii de 4 MB; limită configurabilă (implicit 64 MB), peste care aruncă `StorageEligibilityException`.
- Cheile de storage au aceeași formă peste tot: `<categorie>/<an>/<lună>/<zi>/<uuid>` (fără nume de fișier sau date personale); cheile invalide (path traversal) sunt respinse.
- Verificare programată la 10 s a fiecărui storage → tabelul `storage_health` + metrica `filegrid_storage_up{storage}` (rulează în rolul `api`).
- `CurrentActorProvider` („demo-user”) și `AccessPolicy` (permisivă), pregătite pentru autentificare.
- Gateway: ruta `/api` și, separat, doar `/api/actuator/health`. Prometheus descoperă api-ul și workerii prin DNS (inclusiv replicile scalate).
- Decizia de memorie a arhitectului pentru backend: api 640M, fiecare worker 384M, `JAVA_TOOL_OPTIONS` (MaxRAMPercentage=70, G1, Metaspace 128m, Xss512k), pool-uri DB de 5 pe worker.

### Cum se verifică
- `cd backend && ./mvnw verify` → 48 de teste trec (18 unitare, 30 de integrare cu Testcontainers), cu heap de 256 MB; S3 și FTP fac round-trip de 300 MB.
- `./start.sh` → api și workerii sunt `healthy`.
- https://localhost/api/actuator/health → `{"status":"UP"}`; `/api/actuator/prometheus` → 404.
- `docker compose up -d --scale worker-video=3` → 3 replici healthy, fără modificări de cod.
- `bash scripts/mem-report.sh` → consum per serviciu; niciun container OOMKilled.

### Decizii luate în afara promptului
- **Actuator pe port separat (8081)**: gateway-ul rutează către el doar calea de health, deci metricile nu pot ajunge în exterior nici printr-o greșeală de rutare.
- **Reluarea FTP** are nevoie de un conținut care poate fi redeschis: `StorageAdapter` are o a doua metodă `put(key, StreamSource, …)`, folosită de pipeline în Etapa 4.
- **Citirea din Database în felii** (`substring`) și coloană `STORAGE EXTERNAL`: driverul PostgreSQL ar încărca altfel tot obiectul în memorie.
- **Testul pentru Database folosește 48 MB**, nu 300 MB (peste limita de 64 MB obiectul nu este eligibil; există test că 300 MB este refuzat fără a citi conținutul).
- **Testcontainers 1.21.4** (versiunea minimă compatibilă cu Docker Engine 29).

### Decizii aprobate de arhitect după raportul Etapei 2
- **Aprobate așa cum au fost implementate:** actuator pe portul 8081 (în exterior ajunge doar health), `put` cu conținut redeschis (`StreamSource`) pentru reluare, citirea din Database în bucăți de 4 MB, Testcontainers 1.21.4 (de aici cerința Docker Engine 29+).
- **Memorie — valori explicite în loc de `MaxRAMPercentage`** (din fișierul de mediu, cu valori implicite în compose):
  - api: limită 640M, `-Xmx320m -XX:MaxDirectMemorySize=64m`;
  - worker-meter / -fault / -document: limită 448M, `-Xmx208m -XX:MaxDirectMemorySize=48m`;
  - worker-video (și worker-all în lite): limită 512M, `-Xmx224m -XX:MaxDirectMemorySize=96m`, cel mult 2 transferuri simultane (`WORKER_VIDEO_CONCURRENCY`);
  - comune: `-XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -Xss512k -XX:+UseG1GC`.
- **Reîncărcarea configurației** nu depinde de sistemul de fișiere: `scripts/reload-config.sh` / `make reload-config` (reload Prometheus prin `/-/reload`, restart doar pentru gateway). Pentru politici (Etapa 3): WatchService plus verificare periodică a checksum-ului, notată în PLAN.md.
- **Comutarea full ↔ lite:** scriptul de pornire oprește workerii celuilalt mod.
- **Teste adăugate:** fișier de 65 MB respins de Database fără a fi citit; upload FTP întrerupt la 50% și reluat, cu hash identic cu originalul (hash-ul acoperă tot fișierul); renunțare după numărul configurat de încercări.
- **PLAN.md, Etapa 4:** criteriu nou — worker-video rămâne sub 85% din limită în timpul upload-ului de 1.5 GB și niciun container nu e OOMKilled.

Consum măsurat în repaus (niciun container OOMKilled):

| Mod | api | workeri | Total stack |
| --- | --- | --- | --- |
| full | 326 / 640 MiB | meter 310, fault 324, document 300 (din 448); video 308 (din 512) | 2326 MiB |
| lite | 328 / 640 MiB | worker-all 296 (din 512) | 1368 MiB |

`worker-all` pornește healthy și raportează în log toate cele 4 categorii; consumul efectiv al cozilor există abia din Etapa 4.

### Limitări cunoscute
- Workerii nu consumă încă nimic: cozile și transferul vin în Etapa 4.
- Memoria workerilor este măsurată doar în repaus (60–72% din limită); comportamentul sub încărcare se verifică în Etapa 4.
- `WORKER_CONCURRENCY` este citit și validat, dar va fi folosit efectiv de consumatorii din Etapa 4.
- Agentul OpenTelemetry și `OTEL_JAVAAGENT_ENABLED` vin în Etapa 8.
- Traefik și Prometheus nu preiau singure modificările de configurație pe Windows; după o schimbare: `bash scripts/reload-config.sh`.
- Testul FTP folosește porturile fixe 31000–31009 pe mașina de test (cerință a modului pasiv).

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
