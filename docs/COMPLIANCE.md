# Conformitate cu cerința — FileGrid PPC

Fiecare cerință din `docs/cerinta-FileGrid_PPC.pdf` → unde este implementată → cum se demonstrează. Căile au fost verificate în repository la data scrierii (2026-11-14). Căile Java sunt relative la `backend/src/main/java/ro/filegrid/` (cod) și `backend/src/test/java/ro/filegrid/` (teste), dacă nu e scris altfel.

Legenda stării:

- **✅ implementat** — există cod și teste/demo care îl arată;
- **🟡 parțial** — funcționează, dar lipsește o parte din dovadă sau din acoperire (detaliat în coloana „Cum se demonstrează”);
- **🔧 în lucru** — se implementează acum în etapa indicată; nu se prezintă ca finalizat.

Ce este „în lucru” în paralel la data scrierii: observabilitatea completă (Etapa 8: tracing, loguri JSON, dashboard-uri, histograme de latență), benchmark-ul de storage (Etapa 17), reziliența pe rețea degradată și drill-urile (Etapa 18), Kubernetes (Etapa 19).

## §2 / §2.1 — Categorii și garanțiile de business

| Cerință | Stare | Unde | Cum se demonstrează |
| --- | --- | --- | --- |
| FileGrid este punctul de intrare; video-urile, avariile și documentele se încarcă din interfață | ✅ | `upload/TusUploadController.java`, `upload/MultipartFileController.java`; UI `frontend/src/app/pages/control/village.ts` (drop pe Agent 01), `frontend/src/app/core/upload.service.ts` | https://localhost → trage fișierul pe Agent 01 în Control Center |
| Citirile de contor sunt trimise automat de contoare simulate | ✅ | `simulator/src/filegrid_simulator/` (`client.py`, `generator.py`), `meter/MeterController.java` | serviciul `simulator` pornește odată cu stack-ul; `make sim-stats` / `GET /api/meters/stats` |
| Politică per categorie: unde și cum se scrie fiecare fișier | ✅ | `config/policies.yaml`, `routing/RoutingEngine.java`, `routing/RoutingService.java` ([ADR-0005](ADR/0005-deterministic-agent-01.md), [ADR-0006](ADR/0006-initial-storage-policy.md)) | Decision Explorer (`/decisions`, `GET /api/files/{id}/decision`) |
| Contoare: nicio citire nu se pierde și nu se dublează | ✅ | `readingId = SHA-256(meterId\|periodStart)`; `meter/MeterIngestionService.java` (`Idempotency-Key`), `meter/MeterReadingPublisher.java` (publisher confirms), `meter/MeterReadingWriter.java` (`ON CONFLICT DO NOTHING`, ack după commit), `meter/MeterReconciliation.java`; migrațiile `db/migration/store/V2__meter_reading_blob.sql`, `db/migration/meta/V4__meter_ingestion.sql` | `MeterIngestionIT` (același lot trimis de 3 ori → o singură înregistrare); live: `duplicatesWritten = 0`, `duplicatesRejected > 0` în `/api/meters/stats` (simulatorul retrimite intenționat ~1%) |
| Avarii: disponibile imediat pentru dispecerat | 🟡 | politica `FAULT_RECORD` (FTP primar, `needsImmediateRead`), coada și workerul dedicat (`worker-fault`), SSE `events/EventStreamController.java` | fișierul apare `STORED_VERIFIED` în timeline-ul live și în `/files`; metrica „timp până la disponibilitate” și alerta `FaultRecordsNotImmediatelyAvailable` sunt **în lucru** (Etapele 8 și 17) |
| Documente de racordare: date personale protejate și verificabile la audit | ✅ | `crypto/EnvelopeEncryption.java`, migrația `V7__file_key.sql`, audit append-only `file_event` (`V1__core_schema.sql`), `docs/ENCRYPTION.md` ([ADR-0004](ADR/0004-envelope-encryption-personal-data.md)) | `EnvelopeEncryptionIT`; `bash scripts/direct-access-demo.sh` (în S3 doar ciphertext); auditul în File Passport. Restricția `pii:read` doar pentru racordări + admin: `security/RolePermissions.java` ([ADR-0007](ADR/0007-keycloak-oidc-authentication.md)); fără `pii:read` numele e mascat și conținutul refuzat (403) — `AuthenticationIT`, `RolePermissionsTest` |
| Video-uri: transferul rezistă pe conexiuni lente sau instabile | ✅ (test pe rețea degradată 🔧) | tus 1.0 + S3 multipart reluabil (`storage/s3/S3StorageAdapter.java`, `storage/s3/MultipartCheckpoint.java`, migrația `V3__s3_multipart_transfer.sql`) ([ADR-0002](ADR/0002-streaming-and-tus-uploads.md)) | `LargeResumableUploadIT` (1,5 GB întrerupt la 40% și reluat), `S3MultipartResumptionIT`; rulare prin Toxiproxy cu latență/pierdere de pachete: **în lucru** (Etapa 18) |

## §3 — Cerințe funcționale minime (eliminatorii)

| Cerință | Stare | Unde | Cum se demonstrează |
| --- | --- | --- | --- |
| Stochează și recuperează fișiere din toate cele patru categorii | ✅ | `worker/TransferWorker.java` (stocare), `passport/FileContentController.java` / `FileContentService.java` (`GET /api/files/{id}/content`) | `FullPipelineE2EIT`, `RetrievalAndPassportIT`; live: încărcare + descărcare din File Passport pentru fiecare categorie |
| FTP, S3 și bază de date folosite efectiv, fiecare serviciu separat; S3 emulat local | ✅ | servicii `ftp` (pure-ftpd), `s3` (Silo, [ADR-0001](ADR/0001-s3-storage-silo-fork.md)), `store-db` (PostgreSQL separat) în `infra/docker-compose.yml`; adaptoare `storage/ftp/`, `storage/s3/`, `storage/database/` | `docker compose ps`; `FtpStorageAdapterIT`, `S3StorageAdapterIT`, `DatabaseStorageAdapterIT`; politica folosește fiecare storage ca primar pentru cel puțin o categorie |
| Politica per categorie configurabilă fără modificarea codului | ✅ | `config/policies.yaml` (montat), `policy/PolicyFileWatcher.java`, `policy/PolicyService.java`, `policy/PolicyController.java` (`PUT /api/admin/policies`), migrația `V2__policy_version.sql` | se editează `config/policies.yaml` → versiune nouă în ~10 s în `GET /api/policies/history`, fără restart; pagina `/policies`; `PolicyAndRoutingIT` |
| Fișierul recuperat este identic cu cel stocat și FileGrid o poate demonstra | ✅ | SHA-256 în flux (`storage/Sha256.java`), read-back după scriere în `worker/TransferWorker.java` (`STORED_VERIFIED` doar după potrivire), verificare la cerere `POST /api/files/{id}/verify`, `passport/IntegrityScrubber.java` | File Passport → „Verifică integritatea”; `bash scripts/corrupt-file.sh <fileId>` → `MISMATCH` + incident; `WorkerTransferIT`, `RetrievalAndPassportIT` |
| Pornește pe o mașină curată cu o singură comandă, urmând README-ul | 🟡 | `start.sh`, `start.ps1`, `Makefile` (`make up`), `scripts/init-env.sh`, `scripts/gen-certs.sh`, `scripts/wait-healthy.sh` | `./start.sh` / `.\start.ps1` → „All services are healthy.” Testul pe mașină curată (clonare nouă, fără `.env`, fără imagini locale) din Etapa 20 nu este încă raportat |
| Interfață pentru încărcare, căutare și descărcare (categoriile manuale) | ✅ | `frontend/src/app/pages/control/village.ts` (încărcare prin drop pe Agent 01), `frontend/src/app/pages/category/` (căutare cu filtre per casă), căutarea globală Ctrl+K (`app.ts`), `frontend/src/app/pages/passport/` (descărcare/previzualizare); API `GET /api/files`, `GET /api/files/{id}/content` | https://localhost → drop pe Agent 01, click pe o casă sau Ctrl+K, File Passport. Fără teste automate de frontend încă (verificat manual) |
| Simulator de contoare cu volum configurabil (zi normală și zi de vârf) | ✅ | `simulator/` (moduri `normal`, `peak` x10, `storm`; `SIM_METERS`, `SIM_INTERVAL_SECONDS`), țintele `sim-*` din `Makefile` | `make sim-normal` / `make sim-peak` / `make sim-storm` + `make sim-stats` (`readingsLastMinute` crește de ~10 ori); `docker build --target test simulator` |
| Tot codul este în repository-ul Git | ✅ | aplicație, simulator, `config/`, `infra/`, `scripts/` versionate; `.env` și `infra/certs/` ignorate | `git ls-files`; job-ul `gitleaks` din CI |

## §4 — Cerințe nefuncționale

### Scalabilitate

| Cerință | Stare | Unde | Cum se demonstrează |
| --- | --- | --- | --- |
| Vârf x10 într-o categorie nu degradează celelalte | 🟡 (dovada cantitativă 🔧) | cozi și workeri per categorie: `messaging/Queues.java`, `worker/WorkerListenerConfiguration.java`, serviciile `worker-*` ([ADR-0003](ADR/0003-per-category-queues-bulkhead.md)) | arhitectura izolează categoriile; dovada măsurată (k6 `storm-faults-10x.js`, p95 per categorie în Grafana, drill „furtună x10”) este **în lucru** (Etapele 8, 17, 18) |
| Capacitatea crește prin instanțe, fără modificări de cod | ✅ (Kubernetes 🔧) | aceeași imagine, roluri prin profil; `worker/TransferLock.java` (un singur worker per fișier între replici) | `docker compose up -d --scale worker-video=3`; KEDA/HPA pe kind: **în lucru** (Etapa 19, `infra/k8s/` conține doar README) |
| Fișiere > 1 GB fără creșterea proporțională a resurselor | ✅ | streaming pe tot drumul, buffere ≤ 16 MB ([ADR-0002](ADR/0002-streaming-and-tus-uploads.md)) | `LargeResumableUploadIT` (1,5 GB cu heap de 256 MB); live: `bash scripts/mem-report.sh` în timpul upload-ului |

### Observabilitate

| Cerință | Stare | Unde | Cum se demonstrează |
| --- | --- | --- | --- |
| Latență p50/p95/p99, throughput, rată de erori per categorie și storage | 🔧 | Prometheus + Grafana pornesc (`infra/prometheus/prometheus.yml`, `infra/grafana/provisioning/`); histogramele `filegrid_store_duration_seconds` / `filegrid_retrieve_duration_seconds` și dashboard-urile (`infra/grafana/dashboards/` conține doar README) sunt **în lucru** (Etapa 8) | după Etapa 8: dashboard „categorie × storage” în https://localhost/grafana |
| O operație urmărită de la cerere până la storage | 🔧 | `traceId` există deja în evenimentele SSE; Tempo + Alloy pornesc; agentul OpenTelemetry și propagarea prin RabbitMQ sunt **în lucru** (Etapa 8) | după Etapa 8: trace în Tempo de la `PATCH /api/uploads` până la `storage.put` / `storage.verify` |
| Echipa anunțată automat când o cerință de business e în pericol | 🟡 | `infra/prometheus/rules/business.yml` (`StorageDown`, `IntegrityMismatch`, `MeterReadingsAtRisk`, `PersonalDataAtRisk`), `infra/alertmanager/alertmanager.yml` → `incident/AlertmanagerWebhookController.java` → incident + WhatsApp simulat (`incident/IncidentService.java`, `config/notification-routing.yaml`, `config/templates/`) | `bash scripts/drills/storage-outage.sh` (PASS: incident deschis și rezolvat automat); pagina `/incidents`. Alertele pentru avarii (`FaultRecordsNotImmediatelyAvailable`) și video (`DroneTransfersFailing`) sunt **în lucru** (Etapa 8) |

### Securitate

| Cerință | Stare | Unde | Cum se demonstrează |
| --- | --- | --- | --- |
| Storage-urile nu sunt accesibile direct din afara sistemului | ✅ | rețeaua `internal` (`internal: true`) în `infra/docker-compose.yml`; doar `gateway` publică porturi | `docker compose port s3 9000` → nepublicat; `docker compose ps` (coloana PORTS) |
| Trafic extern criptat | ✅ | Traefik: `infra/traefik/traefik.yml`, `infra/traefik/dynamic/tls.yml` (`minVersion: VersionTLS12`, suite AEAD), redirect HTTP → HTTPS, headere în `infra/traefik/dynamic/middlewares.yml` | `curl -vk https://localhost/api/actuator/health`; `http://localhost:8080` → redirect |
| Accesul la FileGrid este autentificat | ✅ | Keycloak 26 OIDC (realm `infra/keycloak/realm-filegrid.json`), 6 roluri + conturi de serviciu; API-ul este resource server JWT (`security/SecurityConfiguration.java`: issuer, audience `filegrid-api`, semnătură), matricea rol → permisiune în `security/RolePermissions.java`, actorul din token în audit (`SecurityContextActorProvider`); linkuri de conținut semnate HMAC, de scurtă durată (`ContentLinkSigner`); UI: code + PKCE cu refresh token ([ADR-0007](ADR/0007-keycloak-oidc-authentication.md), `docs/AUTH-DESIGN.md`) | `AuthenticationIT` (fără token / expirat / audience sau issuer greșit → 401 problem details; `field` → 403 la documentul PII și nume mascat; link semnat alterat → 401), `ContentLinkSignerTest`, `RolePermissionsTest`; live: `curl -k https://localhost/api/files` → 401 |
| Acces neautorizat direct la storage nu expune datele personale | ✅ | envelope encryption Tink ([ADR-0004](ADR/0004-envelope-encryption-personal-data.md)); chei de obiect fără nume de client (`storage/StorageKeys.java`) | `bash scripts/direct-access-demo.sh`; `EnvelopeEncryptionIT`; metrica `filegrid_pii_stored_unencrypted_total = 0` |
| Nicio parolă sau cheie în repository | ✅ | `.env` generat de `scripts/init-env.sh`, ignorat de git; `.env.example` doar cu placeholder-e `__GENERATE_*__`; `.gitleaks.toml`, `.pre-commit-config.yaml`, job-ul `gitleaks` în `.github/workflows/ci.yml` | `docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --redact` → „no leaks found” (rulat pe tot istoricul la 2026-11-14) |

### Reziliență

| Cerință | Stare | Unde | Cum se demonstrează |
| --- | --- | --- | --- |
| Căderea unui storage nu oprește celelalte categorii și nu pierde fișiere confirmate | ✅ (drill-urile complete 🔧) | retry cu backoff (`worker/Backoff.java`), fallback pe lanțul politicii, `PENDING` + staging ca outbox, `ingestion/RecoveryScheduler.java`, `storage/health/StorageHealthMonitor.java` | `bash scripts/drills/storage-outage.sh` → PASS; `S3OutageRecoveryIT`. Drill-urile FTP căzut și worker omorât: **în lucru** (Etapa 18) |
| Pe latență mare și pierdere de pachete, transferurile se finalizează sau se reiau singure | 🟡 (test pe rețea degradată 🔧) | tus reluabil + `tus-js-client` cu reîncercări (`frontend/src/app/core/upload.service.ts`), S3 multipart cu checkpoint, reluare FTP prin offset | `LargeResumableUploadIT`, `S3MultipartResumptionIT`, `FtpStorageAdapterIT`; scenariile Toxiproxy (`infra/toxiproxy/`, `scripts/chaos/`) și k6 `video-large.js` sunt **în lucru** (Etapa 18) |
| Reîncercările nu dublează citirile de contor | ✅ | idempotență pe `Idempotency-Key` și `readingId` (vezi §2.1) | `MeterIngestionIT`, `JobIdempotencyIT`; `duplicatesWritten = 0` live |
| Un fișier corupt este detectat la recuperare | ✅ | verificare SHA-256 la descărcare (`passport/FileContentService.java`), scrubber periodic (`passport/IntegrityScrubber.java`), autentificare GCM pentru documentele criptate | `bash scripts/corrupt-file.sh <fileId>` → descărcarea refuzată, `MISMATCH` + incident `INTEGRITY_MISMATCH`; `RetrievalAndPassportIT` |

## §5 — Statistici și interpretarea datelor

| Întrebare | Stare | Unde | Cum se demonstrează |
| --- | --- | --- | --- |
| Ce storage se potrivește fiecărei categorii și de ce? | 🟡 (date măsurate 🔧) | argumentare calitativă în [ADR-0006](ADR/0006-initial-storage-policy.md) și explicațiile Agentului 01 (Decision Explorer); benchmark-ul `POST /api/admin/benchmarks` și raportul sunt **în lucru** (Etapa 17) | astăzi: `/decisions` și `/policies`; după Etapa 17: pagina `/performance` cu recomandare susținută de cifre |
| Comportamentul într-o zi de vârf comparativ cu una normală | 🔧 | simulatorul are profilurile `normal` / `peak` / `storm`; `frontend/src/app/pages/performance/performance-page.ts` arată KPI-uri live; comparația pe rulări etichetate este **în lucru** (Etapele 17 și 18) | după Etapele 17–18: comparația normal vs. vârf în `/performance` |
| Ce spun datele despre rețea (tipare, anomalii, zone cu cele mai multe avarii) | 🟡 | Agent 02: `intelligence/src/filegrid_intelligence/agent02.py`, `detectors.py` (anomalii în citiri, cu dovezi și încredere, fără a afirma cauze); UI `frontend/src/app/pages/grid/`; AI Operations Assistant `intelligence/src/filegrid_intelligence/assistant.py` | https://localhost/grid; `GET /api/intelligence/summary`, `/findings`, `/evaluation` (precision/recall față de anomaliile injectate de simulator); `docker build --target test intelligence`. Detectorii de frecvență a avariilor pe zone și corelația spațio-temporală nu sunt încă implementați |

## §6 — Stack recomandat

| Zonă | Recomandat | Folosit |
| --- | --- | --- |
| Backend | Spring Boot | Spring Boot 3.5, Java 21 (`backend/pom.xml`) |
| Frontend | Angular | Angular 21 (`frontend/package.json`) |
| Infrastructură | Docker, Kubernetes | Docker Compose (calea principală, `compose.yaml` → `infra/docker-compose.yml`); Kubernetes **în lucru** (Etapa 19) |
| Observabilitate | Grafana, Loki, Prometheus | Prometheus, Alertmanager, Grafana, Loki, Tempo, Alloy (`infra/`) |
| Bază de date | SQL Server / PostgreSQL / MySQL | PostgreSQL 16 (`meta-db` cu pgvector, `store-db` separat) |

## Calitate și CI

| Ce | Unde | Stare |
| --- | --- | --- |
| Build + teste backend (unit + Testcontainers), build frontend, ruff + pytest (intelligence, simulator), gitleaks, build imagini, Trivy (doar raport) | `.github/workflows/ci.yml` | definit; **nu a fost încă rulat pe GitHub** la data scrierii |
| Hook pre-commit gitleaks | `.pre-commit-config.yaml`, `.gitleaks.toml` | definit |
| Teste automate frontend | — | lipsesc (verificare manuală); de adăugat |
