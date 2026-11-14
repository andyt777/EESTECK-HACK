# FileGrid PPC — Plan de implementare pentru Claude Code

> **Pentru Claude Code.** Ești inginerul software al proiectului. Arhitectul (un alt Claude) a scris acest plan; omul care îți scrie în terminal face legătura între voi.
>
> **Cum folosești fișierul:**
> 1. Execuți **doar etapa pe care ți-o numește omul** (ex. „Execută Etapa 4 din docs/PLAN.md”). Nu începe etapa următoare din proprie inițiativă.
> 2. Blocul de cod al etapei este cerința ta. Restul fișierului (arhitectura, celelalte etape) este context, ca să nu iei decizii care strică etapele viitoare.
> 3. Pentru etapele mari (4, 6, 9, 12, 15, 16), sau când promptul spune „Prezintă întâi planul”: intră în plan mode, prezintă planul și așteaptă aprobarea înainte de a scrie cod.
> 4. La final rulezi singur **criteriile de acceptare** ale etapei, apoi raportezi: ce ai făcut, fișierele modificate, rezultatul testelor și al criteriilor, ce a rămas neterminat, orice decizie luată fără să fie cerută în prompt. Nu face commit/push decât dacă ți se cere.
> 5. Dacă o cerință pare imposibilă sau contrazice arhitectura, oprește-te și explică. Nu schimba tehnologii și nu improviza.
> 6. Etapa 11 (autentificarea) nu se implementează până nu este completată de arhitect.

## Cerințe preliminare (instalate de om)

| Unealtă | Pentru ce | Note |
| --- | --- | --- |
| Git | repository | configurează user/email |
| VS Code + Claude Code | lucru | extensii: Extension Pack for Java, Spring Boot Extension Pack, Angular Language Service, Python, Docker, YAML |
| Docker Desktop | toate serviciile | pe Windows: backend WSL2, alocă minimum 8 GB RAM și 4 CPU |
| JDK 21 (Eclipse Temurin) | backend Spring Boot | Maven nu trebuie instalat, se folosește `./mvnw` |
| Node.js 22 LTS | frontend | apoi `npm i -g @angular/cli` |
| Python 3.12 + `uv` | simulator, Agent 02, asistent | `pip install uv` sau installer-ul oficial |
| mkcert | certificat TLS local | `mkcert -install` o singură dată |
| k6 | teste de încărcare | din Etapa 18 |
| kind + kubectl (+ Helm) | Kubernetes | doar din Etapa 19 |
| Cheie API LLM | asistentul AI | Anthropic, OpenAI sau Ollama local; o pui doar în `.env` |
| Cont Twilio (opțional) | WhatsApp real | WhatsApp Sandbox e gratuit pentru demo |

Pe Windows, rulează comenzile din **Git Bash** sau **WSL**. Proiectul primește și un echivalent `.ps1` pentru pornire.

## Arhitectura

### A.1 Componente

| Componentă | Tehnologie | Rol |
| --- | --- | --- |
| gateway | Traefik v3 | singurul punct expus în exterior; TLS, rutare `/`, `/api`, `/grafana` |
| api | Spring Boot 3, Java 21 (profil `api`) | upload reluabil (tus), căutare, File Passport, politici, incidente, SSE |
| worker-meter / -fault / -document / -video | aceeași imagine, profil `worker` | Agent 01 execută transferul către storage; câte un pool per categorie (bulkhead) |
| frontend | Angular + Angular Material + SVG/GSAP + ECharts | Village, categorii, Passport, Grid Intelligence, Assistant, Incidents, Performance |
| intelligence | Python 3.12, FastAPI, pandas, scikit-learn, LangGraph | Agent 02 (anomalii) și AI Operations Assistant (agentic RAG) |
| simulator | Python, httpx async | contoare inteligente simulate, cu profiluri normal/peak/storm |
| meta-db | PostgreSQL 16 + pgvector | metadate, decizii, audit, incidente, embeddings |
| **store-db** | PostgreSQL 16 (instanță separată) | storage-ul „Database” |
| **s3** | MinIO-compatible — Silo (fork comunitar Pigsty), vezi ADR-0001 | storage-ul „S3” (emulat local) |
| **ftp** | pure-ftpd | storage-ul „FTP” |
| rabbitmq | RabbitMQ | cozi separate per categorie, plus DLQ |
| observabilitate | Prometheus, Alertmanager, Grafana, Loki, Tempo, Grafana Alloy, OpenTelemetry | metrici p50/p95/p99, loguri, trace end-to-end, alerte de business |
| toxiproxy | Toxiproxy | simulează o rețea degradată |

### A.2 Fluxul unui fișier

```
Browser ──tus (chunk-uri reluabile)──▶ gateway (TLS) ──▶ api
                                             │ scrie chunk-urile în /data/staging (volum partajat)
                                             │ SHA-256 calculat în flux
                                             │ Agent 01: clasificare → decizie de rutare (salvată ÎNAINTE de transfer)
                                             ▼
                                   RabbitMQ: filegrid.jobs.<categorie>
                                             ▼
                                   worker-<categorie>
                                             │ stream staging → storage (FTP | S3 multipart | Database)
                                             │ read-back + SHA-256 = original?  → STORED_VERIFIED
                                             │ eroare → retry/backoff → fallback → PENDING (rămâne în staging)
                                             ▼
                     evenimente (fanout) → api → SSE → Village / Passport

Simulator ──POST batch──▶ api ──(publisher confirms)──▶ coada meter ──▶ worker-meter ──▶ store-db (ON CONFLICT DO NOTHING)
```

### A.3 Principii care nu se negociază

1. **Agent 01 este determinist**, nu un LLM. El rulează un motor de politici cu scorare. Decizia (criterii, scoruri, alternative și motivele respingerii) se salvează înainte de transfer, deci Decision Explorer arată motivele reale.
2. **Politica este configurabilă fără cod**: `config/policies.yaml` montat în container, cu override-uri versionate în DB, editabile din UI-ul de admin. Se reîncarcă fără restart.
3. **Streaming peste tot**: niciun fișier nu se încarcă întreg în memorie. Asta acoperă cerința ca fișierele de peste 1 GB să nu crească resursele proporțional.
4. **„Stocat și verificat” apare doar după read-back și compararea hash-ului SHA-256.**
5. **Idempotență pentru contoare**: `readingId = SHA-256(meterId|periodStart)` + constrângere UNIQUE + ack trimis doar după commit. Nu se pierd și nu se dublează citiri.
6. **Bulkhead**: coadă și workeri separați pe fiecare categorie. O furtună de avarii nu încetinește contoarele.
7. **Staging + outbox**: un fișier primit nu se pierde niciodată. Dacă storage-ul e căzut, fișierul trece în PENDING și este reluat automat.
8. **Date personale**: documentele de racordare sunt criptate la nivel de aplicație (envelope encryption cu AES-256-GCM, Google Tink streaming AEAD) înainte de a ajunge în storage. Un acces direct la storage vede doar ciphertext.
9. **Storage-urile nu publică porturi**: stau doar în rețeaua internă, iar singura cale de acces este FileGrid.
10. **Real vs. simulat**: câmpul `dataOrigin` (REAL/SIMULATED) există peste tot, iar UI-ul afișează un badge vizibil.
11. **Agent 02 nu afirmă cauze**: prezintă observații, dovezi, încredere și verificarea umană necesară.
12. **Niciun secret în repo**: totul vine din `.env` (generat local), iar `.env.example` conține doar placeholdere.

### A.4 Politica inițială (ipoteză validată ulterior de benchmark-ul din Etapa 17)

| Categorie | Primar | Fallback | De ce |
| --- | --- | --- | --- |
| Citiri contoare | Database | S3 | fișiere mici și multe; tranzacții + UNIQUE garantează zero dubluri; date interogabile direct de Agent 02 |
| Înregistrări de avarie | FTP | S3 | 1–10 MB, ierarhie zonă/stație/dată, citire imediată pentru dispecerat; S3 preia vârfurile dacă FTP e saturat sau căzut |
| Documente de racordare | S3 (criptat aplicativ, versioning) | Database (criptat) | protecție PII, versiuni păstrate pentru audit |
| Video-uri dronă | S3 multipart | FTP (cu reluare) | 100 MB – 1 GB+, transfer reluabil pe părți |

Toate cele trei storage-uri sunt folosite efectiv. Benchmark-ul din Etapa 17 confirmă sau schimbă alegerea pe baza datelor măsurate, ceea ce răspunde direct la prima întrebare din §5 a cerinței.

### A.5 Structura repository-ului

```
filegrid/
├── AGENTS.md              # reguli pentru agent (+ CLAUDE.md, .github/copilot-instructions.md)
├── README.md
├── start.sh / start.ps1 / Makefile
├── .env.example
├── config/                # policies.yaml, notification-routing.yaml, templates/
├── backend/               # Spring Boot (api + worker)
├── frontend/              # Angular
├── intelligence/          # FastAPI: Agent 02 + AI Assistant
├── simulator/             # contoare simulate + topology.yaml
├── infra/                 # docker-compose.yml, traefik, prometheus, grafana, loki, tempo, alloy, toxiproxy, k8s/
├── load-tests/            # k6
├── scripts/               # init-env, gen-certs, seed-demo, corrupt-file, chaos/, drills/
└── docs/                  # cerința, experiența, ARCHITECTURE.md, OBSERVABILITY.md, DEMO.md, ADR/
```

## Etapa 0 — Pregătirea proiectului

**Pregătire făcută de om înainte:** repo-ul există (`git init`), iar în `docs/` se află `cerinta-FileGrid_PPC.pdf`, `experienta-utilizator.md` și acest fișier, `PLAN.md`. Pe lângă ce cere promptul de mai jos, creează `docs/ARCHITECTURE.md` copiind exact secțiunea „Arhitectura” din acest fișier. `CLAUDE.md` trebuie să conțină, la final, linia `@docs/ARCHITECTURE.md`, ca arhitectura să fie încărcată automat în fiecare sesiune.

```
Ești inginerul software al proiectului FileGrid PPC. Arhitectul a stabilit arhitectura în docs/ARCHITECTURE.md; cerința oficială este în docs/cerinta-FileGrid_PPC.pdf, iar experiența de utilizare în docs/experienta-utilizator.md. Citește-le pe toate trei complet.

Nu scrie cod încă. Creează în rădăcină fișierul AGENTS.md (și copii identice CLAUDE.md și .github/copilot-instructions.md) cu regulile de mai jos, apoi .gitignore (Java, Node, Python, .env, infra/certs/, data/), apoi docs/CHANGELOG-ETAPE.md gol. La final, rezumă în 10 rânduri ce ai înțeles despre proiect și listează orice neclaritate.

--- conținut AGENTS.md ---
# FileGrid PPC — reguli pentru agent
## Surse de adevăr
docs/ARCHITECTURE.md (arhitectură, NU se schimbă fără aprobarea arhitectului), docs/cerinta-FileGrid_PPC.pdf (cerințe evaluate), docs/experienta-utilizator.md (UX, texte, fluxuri).
Dacă o cerință ți se pare imposibilă sau o tehnologie nepotrivită: OPREȘTE-TE și explică, nu improviza.
## Stack
Backend: Java 21, Spring Boot 3.x, Maven wrapper, Flyway, Testcontainers. Pachete organizate pe feature.
Frontend: Angular (ultima versiune stabilă), standalone components, signals, TypeScript strict, Angular Material, ECharts, GSAP.
Python: 3.12, uv, ruff, pytest, FastAPI.
Infra: Docker Compose (calea principală), Kubernetes (kind) ulterior.
## Reguli de cod
- Identificatori, cod și commit-uri în engleză; texte UI în română (centralizate, pregătite pentru i18n).
- Streaming obligatoriu: niciodată un fișier întreg în memorie (fără byte[] pe tot conținutul); buffere maxim 16MB.
- SHA-256 calculat în flux; succesul se raportează doar după verificare.
- Fiecare stare afișată în UI corespunde unei stări reale din backend.
- Datele generate/simulate au dataOrigin=SIMULATED.
- Fără secrete în repo: totul din variabile de mediu; actualizează .env.example la orice variabilă nouă.
- Fiecare funcționalitate are teste (unit + integrare cu Testcontainers acolo unde atinge infrastructura).
- Autentificarea vine într-o etapă ulterioară: până atunci folosește interfețele CurrentActorProvider (returnează "demo-user") și AccessPolicy (permisivă), apelate peste tot unde va fi nevoie de autorizare. Nu scrie cod care face greu de adăugat securitatea.
- Agent 01 (rutarea) este determinist. LLM-urile se folosesc doar în serviciul intelligence pentru asistent.
## Finalul fiecărei etape
1. Rulează toate testele și `docker compose up` (când există) și confirmă că totul e healthy.
2. Actualizează README.md și docs/CHANGELOG-ETAPE.md (ce s-a făcut, cum se verifică, limitări cunoscute).
3. Raportează: fișiere create/modificate, comenzi de verificare, rezultatele testelor, ce a rămas neterminat.
4. Propune un mesaj de commit. Nu face commit/push decât dacă ți se cere.
--- sfârșit ---
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): cele trei fișiere de reguli există, iar rezumatul agentului e corect. Dacă listează neclarități, mi le aduci.

## Etapa 1 — Schelet + infrastructură Docker

```
Etapa 1 — schelet repo + infrastructură. Citește AGENTS.md și docs/ARCHITECTURE.md.

1. Creează structura de directoare din ARCHITECTURE.md §A.5 (cu un README scurt în fiecare director gol).
2. infra/docker-compose.yml cu serviciile:
   - gateway: Traefik v3, SINGURUL serviciu cu porturi publicate (80 → redirect 443, 443). Certificat din infra/certs/, generat de scripts/gen-certs.sh (mkcert dacă există, altfel openssl self-signed). Headere de securitate, TLS minim 1.2.
   - meta-db: pgvector/pgvector:pg16, baza filegrid_meta.
   - store-db: PostgreSQL 16 SEPARAT, baza filegrid_store (storage-ul „Database”).
   - s3: MinIO + un serviciu one-shot minio-init (mc) care creează bucket-urile filegrid-faults, filegrid-documents, filegrid-videos, filegrid-meters, filegrid-benchmark, cu versioning activ, plus un user de serviciu dedicat FileGrid (nu root).
   - ftp: pure-ftpd (stilliard/pure-ftpd) cu mod pasiv configurat corect în rețeaua docker și un user de serviciu FileGrid.
   - rabbitmq: cu management plugin, neexpus public.
   - prometheus, alertmanager, grafana (provisioning din infra/grafana/, servit sub https://localhost/grafana), loki, tempo, alloy (loguri containere → Loki, OTLP → Tempo, metrici → Prometheus).
   - toxiproxy (doar pornit; îl configurăm în Etapa 18).
3. Rețele: `edge` (gateway, frontend, api) și `internal` (internal: true) pentru tot restul. Storage-urile NU publică porturi și sunt doar în `internal`.
4. Toate parolele/cheile vin din .env. Creează .env.example (placeholdere) și scripts/init-env.sh care generează .env cu parole aleatorii puternice DOAR dacă .env nu există (include și FILEGRID_KEK de 256 biți base64 pentru Etapa 7).
5. Healthcheck pentru fiecare serviciu și depends_on cu condition: service_healthy.
6. Pornire cu o singură comandă: start.sh și start.ps1 (init-env → gen-certs → docker compose up -d --build → așteaptă healthy → afișează URL-urile). Makefile cu up, down, logs, ps, reset (cere confirmare, șterge volumele).
7. README: cerințe preliminare, pornire, URL-uri.
Nu scrie încă servicii aplicative. Verifică cu `docker compose config` și pornire completă.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): `./start.sh` → `docker compose ps` arată totul healthy; https://localhost/grafana se deschide; `docker compose port s3 9000` nu returnează nimic (portul nu e publicat); `.env` nu apare în `git status`.

## Etapa 2 — Backend: model de date + adaptoare de storage

```
Etapa 2 — backend Spring Boot, modelul de date și cele 3 adaptoare de storage. Citește AGENTS.md.

1. backend/: Spring Boot 3.x, Java 21, Maven wrapper. Dependențe: web, actuator, validation, data-jpa, flyway, postgresql, amqp, micrometer-registry-prometheus, AWS SDK v2 S3 (pentru MinIO, path-style), Apache Commons Net (FTP), Apache Tika core, springdoc-openapi, Testcontainers (postgresql, minio, rabbitmq). Preferă records în locul Lombok.
2. O singură aplicație cu două roluri prin profil: `api` și `worker`. WORKER_CATEGORIES (ex. METER_READING,FAULT_RECORD) alege cozile consumate. Dockerfile multi-stage, utilizator non-root, JVM container-aware. În compose: api + worker-meter, worker-fault, worker-document, worker-video (aceeași imagine).
3. Migrații Flyway pentru meta-db:
   - file_record: id UUID, category (METER_READING|FAULT_RECORD|CONNECTION_DOCUMENT|DRONE_VIDEO), original_name, content_type, size_bytes, sha256_plain, sha256_stored, status (RECEIVED, ANALYZING, CATEGORIZED, DESTINATION_SELECTED, TRANSFERRING, TRANSFER_RESUMED, STORED_VERIFIED, PENDING, TRANSFER_FAILED), integrity_status (UNVERIFIED, VERIFIED_INTACT, MISMATCH), last_verified_at, storage_type, storage_key, encrypted, sensitivity (NONE|PERSONAL_DATA), data_origin (REAL|SIMULATED), source (UI|SIMULATOR|API), uploaded_by, received_at, stored_at, idempotency_key UNIQUE NULL, trace_id.
   - file_context: file_id, zone, station, equipment, meter_id, event_type, event_time, description (toate opționale).
   - routing_decision: file_id, policy_version, detection (jsonb), features (jsonb), candidates (jsonb: storage, eligible, score, reasons[]), selected_storage, fallback_used, fallback_reason, explanation_ro, decided_at.
   - file_event (append-only, audit): id, file_id, type (RECEIVED, CLASSIFIED, ROUTED, TRANSFER_STARTED, TRANSFER_RESUMED, STORED, VERIFIED, ACCESSED, DOWNLOADED, STATUS_CHANGED, FALLBACK, INTEGRITY_INCIDENT), actor, details jsonb, trace_id, at.
   - storage_health: storage, status, last_check, latency_ms.
   Migrație separată pentru store-db (tabel stored_blob: key PK, content bytea, size, sha256, created_at).
4. Interfața StorageAdapter: type(), put(key, InputStream, size, metadata) → StoredObject(key, sizeWritten, sha256), get(key) → InputStream, exists, delete, health(). Implementări:
   - S3StorageAdapter: multipart pentru > 16MB (părți de 16MB), abort la eroare, fără tot fișierul în memorie.
   - FtpStorageAdapter: binar, mod pasiv, directoare categorie/an/lună/zi, scriere în .part apoi rename atomic, pool de conexiuni + reconectare, reluare prin REST offset.
   - DatabaseStorageAdapter: bytea cu setBinaryStream; limită configurabilă (implicit 64MB) — peste limită aruncă o excepție de eligibilitate.
   Toate calculează SHA-256 în flux (DigestInputStream).
5. Health check programat la 10s per storage → storage_health + metrica filegrid_storage_up{storage}.
6. Teste de integrare cu Testcontainers pentru fiecare adaptor: round-trip cu fișier de 300MB generat în flux (unde e eligibil), hash identic; rulează testele de 300MB cu -Xmx256m ca să dovedești streaming-ul.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): `./mvnw verify` trece; în compose, api și workerii sunt healthy; https://localhost/api/actuator/health → UP (endpoint-ul prometheus NU e expus prin gateway).

## Etapa 3 — Agent 01: motorul de politici și rutare

```
Etapa 3 — Agent 01: politici configurabile, clasificare, rutare explicabilă (deterministă). Citește AGENTS.md și docs/experienta-utilizator.md §7 și §8.1.

1. config/policies.yaml (montat în container, NU inclus în jar), per categorie:
   detection (extensii, MIME, câmpuri obligatorii), primary, fallbacks (ordonate),
   constraints per storage (maxSizeBytes, needsResumable, needsEncryption, needsImmediateRead, idempotent),
   weights (sizeFit, resumable, availability, measuredLatencyP95, policyPreference),
   retry (maxAttempts, initialBackoff, maxBackoff), sla (maxPendingSeconds, maxStoreP95Seconds — folosite în Etapa 8).
   Valori inițiale conform ARCHITECTURE.md §A.4.
2. PolicyService: încarcă și validează YAML-ul; la eroare păstrează versiunea veche și loghează. Reîncarcă la modificarea fișierului (WatchService). Tabel policy_version în meta-db (versiune, autor, conținut, diff, created_at) — o versiune din DB are prioritate. Endpoint-uri: GET /api/policies, GET /api/policies/history, PUT /api/admin/policies (validare + versiune nouă).
3. ClassificationService: combină categoria declarată (dacă utilizatorul încarcă dintr-o casă), MIME-ul detectat cu Tika din primii KB (magic bytes), extensia și metadatele. Dacă declarat ≠ detectat: avertisment salvat în decizie, fără blocare.
4. RoutingService (Agent 01): pentru FIECARE storage calculează eligibil/neeligibil + motive concrete (ex. „depășește limita de 64 MB pentru Database”), scor ponderat cu valorile REALE folosite (dimensiune, stare din storage_health, p95 măsurat dacă există). Alege primarul dacă e eligibil și UP, altfel cel mai bun fallback. Salvează routing_decision ÎNAINTE de transfer. Generează explanation_ro din motivele structurate prin template-uri (nu LLM), în stilul exemplului din §7.
5. Dacă niciun storage nu e eligibil și disponibil → status PENDING cu motivul salvat.
6. GET /api/files/{id}/decision.
7. Teste unit: fiecare categorie, storage căzut → fallback, fișier prea mare → exclus, politică invalidă → refuzată și versiunea veche rămâne activă, reload fără restart.
```

**Decizie de arhitect (după Etapa 2):** `PolicyService` detectează modificarea fișierului prin `WatchService` **și** printr-o verificare periodică (checksum SHA-256 al `policies.yaml` la 10s, interval configurabil), ca reîncărcarea să funcționeze și pe bind mount-uri Windows, unde notificările de fișier nu ajung în container.

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): modifici `maxSizeBytes` în YAML cu aplicația pornită → `GET /api/policies` reflectă schimbarea fără restart. Testele trec.

## Etapa 4 — Pipeline-ul de încărcare (reluabil, cozi, stări live)

```
Etapa 4 — pipeline de upload reluabil, cozi per categorie, stări live. Prezintă întâi planul, apoi implementează. Citește AGENTS.md, ARCHITECTURE.md §A.2–A.3, experienta-utilizator.md §3.3 și §8.1.

1. Upload reluabil prin protocolul tus 1.0 (me.desair.tus:tus-java-server) la /api/uploads; chunk-urile se scriu în volumul de staging /data/staging (partajat api + workeri). Metadate tus: category (opțional), zone, station, equipment, eventType, eventTime, description, dataOrigin. Dimensiune maximă configurabilă (implicit 5GB). Upload-urile abandonate > 24h se curăță.
   Pentru fișiere mici: și POST multipart /api/files, tot în flux spre staging.
2. La finalizarea upload-ului: file_record (RECEIVED) → SHA-256 plaintext în flux → ANALYZING → clasificare (CATEGORIZED) → rutare (DESTINATION_SELECTED) → publicare job în RabbitMQ pe coada categoriei: filegrid.jobs.meter|fault|document|video (durabile, mesaje persistente, publisher confirms, DLQ per coadă). Fiecare pas → file_event.
3. Worker: consumă doar categoriile din WORKER_CATEGORIES, cu prefetch și concurență configurabile per categorie. Pași: TRANSFERRING (evenimente de progres la fiecare ~5% sau 16MB) → put în flux din staging → read-back din storage cu recalculare SHA-256 → egal: STORED_VERIFIED + VERIFIED_INTACT, șterge din staging; diferit: MISMATCH + retry.
   Erori de storage: retry cu backoff exponențial + jitter (din politică); după epuizarea încercărilor pe primar → fallback (eveniment FALLBACK, decizie actualizată); dacă toate cad → PENDING, fișierul rămâne în staging. Un job programat reia PENDING-urile când storage_health revine UP. Nimic nu se pierde, fără intervenție manuală.
   S3 multipart: persistă uploadId și părțile confirmate în DB; după crash-ul worker-ului, reluarea continuă de la ultima parte (TRANSFER_RESUMED).
   Idempotență: mesaj livrat de două ori → worker-ul vede STORED_VERIFIED și face ack fără rescriere.
4. Evenimente live: fiecare schimbare de stare se publică pe exchange-ul fanout filegrid.events. api expune GET /api/events/stream (SSE, cu filtre opționale fileId, category). Payload: fileId, category, status, storage, message (textul scurt al Agentului 01 în română, din §8.1, potrivit stării reale), bytesTransferred, totalBytes, timestamp, traceId.
5. Workerii se scalează cu `docker compose up -d --scale worker-video=3` fără modificări de cod.
6. Teste de integrare: upload 1.5GB prin tus întrerupt la 40% și reluat; heap-ul api/worker sub 512MB; S3 oprit în timpul transferului → fallback sau PENDING → S3 repornit → finalizare automată; mesaj duplicat → fără dublură.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): încarci un fișier cu un client tus sau cu `curl`. În `curl -N -k https://localhost/api/events/stream` vezi secvența de stări. `docker compose stop s3` în timpul unui upload video → fișierul trece în fallback sau PENDING → `docker compose start s3` → ajunge la STORED\_VERIFIED. În timpul upload-ului de 1.5GB, worker-video rămâne sub 85% din limită (`scripts/mem-report.sh` rulat în timpul transferului) și niciun container nu e OOMKilled.

## Etapa 5 — Simulatorul de contoare + ingestie idempotentă

```
Etapa 5 — simulator de contoare și ingestie fără pierderi și fără dubluri. Citește AGENTS.md și cerința §2.1.

Backend:
1. POST /api/meters/readings — batch de până la 500 citiri. Fiecare citire este un fișier JSON mic (1–50KB): meterId, region, zone, station, transformer, periodStart, periodEnd, intervalMinutes, values[] (kWh import/export, tensiune, curent), seq (secvență per contor), dataOrigin=SIMULATED.
   readingId = SHA-256(meterId|periodStart). Header Idempotency-Key per batch.
   Răspuns 202 DOAR după ce fiecare citire e confirmată persistent în coada meter (publisher confirms); răspunsul listează accepted / duplicate.
2. worker-meter: inserare în store-db (tabel meter_reading_blob, PK readingId) cu ON CONFLICT DO NOTHING + file_record cu idempotency_key=readingId; ack doar după commit; batch-uri tranzacționale de ~200.
3. Job de reconciliere la 1 min: gap-uri de seq per contor și duplicate respinse → metricile filegrid_meter_gaps_total, filegrid_meter_duplicates_rejected_total, filegrid_meter_duplicates_written_total (trebuie să fie mereu 0).
4. GET /api/meters/stats (citiri/min, gap-uri, duplicate respinse).

Simulator (simulator/, Python 3.12, uv, httpx async):
5. simulator/topology.yaml: 3 regiuni (Muntenia, Banat, Dobrogea), ~12 stații, ~60 posturi de transformare cu coordonate schematice x/y, linii între ele, N contoare (implicit 2000), ~10% prosumatori.
6. Profiluri realiste: curbă zilnică, diferență weekend, efect de temperatură, export solar ziua pentru prosumatori. Anomalii injectabile din config (consum zero brusc, spike, citiri lipsă, valori repetate, tensiune scăzută pe un post). Ground truth în simulator/output/injected_anomalies.json pentru evaluarea Agentului 02.
7. Moduri: normal, peak (x10), storm (normal + rafale), configurabile prin env/CLI: METERS, INTERVAL_SECONDS, RATE_MULTIPLIER, DURATION. Ceas accelerat opțional (TIME_SCALE) pentru a genera zile întregi rapid.
8. Reîncearcă până la ack cu ACELAȘI Idempotency-Key și aceleași readingId; trimite intenționat ~1% duplicate pentru a demonstra deduplicarea. Expune metrici Prometheus proprii (sent, acked, retried).
9. Serviciu `simulator` în compose (mod normal implicit); `make sim-peak` și `make sim-normal` îl repornesc cu alte setări.
Teste: pytest pentru generator; test backend: același batch trimis de 3 ori → exact o înregistrare per readingId.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): `/api/meters/stats` crește continuu; `filegrid_meter_duplicates_written_total` rămâne 0; după `make sim-peak`, rata crește de \~10 ori.

## Etapa 6 — Recuperare, integritate, File Passport, căutare

```
Etapa 6 — recuperare, integritate demonstrabilă, File Passport API, căutare. Prezintă întâi planul. Citește AGENTS.md și experienta-utilizator.md §5–§6.

1. GET /api/files/{id}/content — streaming din storage cu recalculare SHA-256 pe parcurs:
   - fișiere ≤ 200MB: copiere în fișier temporar, verificare, apoi servire; la neconcordanță → 409 + incident;
   - fișiere mai mari: streaming direct; dacă hash-ul final nu corespunde, conexiunea se întrerupe înaintea ultimului chunk (clientul primește descărcare eșuată), se marchează MISMATCH și se creează eveniment INTEGRITY_INCIDENT;
   - suport Range pentru previzualizare video (header X-Integrity: partial);
   - fișierele cu MISMATCH nu se mai servesc ca valide (409) decât cu ?acknowledgeSuspect=true și permisiune de admin (prin AccessPolicy).
   Header X-Content-SHA256 pe răspuns.
2. POST /api/files/{id}/verify — verificare la cerere (read-back + hash) → integrity_status, last_verified_at, eveniment VERIFIED.
3. Integrity scrubber programat: eșantion configurabil + toate fișierele neverificate de mai mult de X zile.
4. scripts/corrupt-file.sh <fileId> — modifică direct un byte în storage-ul fișierului (MinIO prin mc, FTP, store-db), rulat într-un container din rețeaua internă; folosit pentru demo.
5. GET /api/files/{id}/passport: identitate (§6.1), context (doar câmpurile nenule, §6.2), traseu cu timestamp-uri (§6.3), decizia de rutare, integritate (SHA-256, ultima verificare, §6.4), istoric audit paginat (§6.5), acțiuni permise calculate pe server (§6.6).
6. Fiecare acces sau descărcare → eveniment ACCESSED / DOWNLOADED cu actorul din CurrentActorProvider.
7. GET /api/files: q (nume, descriere, stație), category, from/to, sizeMin/sizeMax, zone, station, contentType, storage, status, integrity, dataOrigin; sort=receivedAt|size|name; paginare. Indexuri potrivite (pg_trgm pentru text).
8. GET /api/categories/summary: per categorie număr fișiere, volum, ultimele 5, avertizări (PENDING, MISMATCH, TRANSFER_FAILED).
9. OpenAPI cu springdoc (Swagger UI doar în profilul dev).
Teste: inclusiv detectarea coruperii pe fiecare din cele 3 storage-uri.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): încarci un fișier → `scripts/corrupt-file.sh <id>` → descărcarea eșuează sau dă 409, iar passport-ul arată „Neconcordanță”.

## Etapa 7 — Protecția datelor personale

```
Etapa 7 — protecția datelor personale din documentele de racordare. Citește AGENTS.md și cerința §4 (Securitate).

1. Criptare envelope la nivel de aplicație ÎNAINTE de scrierea în storage, pentru CONNECTION_DOCUMENT (și pentru orice categorie cu needsEncryption în politică). Folosește Google Tink Streaming AEAD (AES256_GCM_HKDF_1MB): o cheie de date (DEK) per fișier, criptată cu KEK din FILEGRID_KEK (secret din env). DEK-ul împachetat + key_id se salvează în meta-db, NU lângă obiect. Suport pentru rotația KEK (key_id). Formatul documentat în docs/ENCRYPTION.md.
2. sha256_plain (pe original) și sha256_stored (pe ciphertext): read-back-ul de după scriere verifică sha256_stored; descărcarea decriptează și verifică sha256_plain.
3. Cheile obiectelor din storage sunt UUID-uri — niciun nume de client/CNP în cheie, cale sau metadate de storage.
4. Câmpurile personale din metadate (nume client, CNP, adresă — dacă se completează la upload) sunt criptate în meta-db și mascate în listări (ex. „Ion P.”, CNP „1********234”).
5. sensitivity=PERSONAL_DATA pe file_record. Endpoint-urile cer permisiunea pii:read prin AccessPolicy (permisivă acum, reală la autentificare) și auditează orice acces, inclusiv refuzurile (metrica filegrid_pii_access_denied_total).
6. Metrica filegrid_pii_stored_unencrypted_total (trebuie să fie mereu 0).
7. scripts/direct-access-demo.sh: accesează direct MinIO / FTP / store-db din rețeaua internă și arată că documentul este ciphertext ilizibil.
Teste: round-trip 10MB criptat, KEK greșit → eroare clară, ciphertext alterat → detectat.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): `scripts/direct-access-demo.sh` arată bytes ilizibili, iar descărcarea prin FileGrid dă PDF-ul corect.

## Etapa 8 — Observabilitate și alerte de business

```
Etapa 8 — observabilitate completă și alertare pe cerințele de business. Citește AGENTS.md și cerința §4 (Observabilitate) + tabelul §2.1.

1. Metrici Micrometer cu tag-urile category, storage, outcome: filegrid_store_duration_seconds și filegrid_retrieve_duration_seconds (histograme cu percentile-histogram activat → p50/p95/p99 în PromQL), filegrid_bytes_total, filegrid_operations_total{outcome}, filegrid_queue_depth{category}, filegrid_pending_files{category}, filegrid_fallback_total, filegrid_integrity_mismatch_total, filegrid_storage_up, plus metricile din Etapele 5 și 7.
2. Tracing: agentul OpenTelemetry Java inclus în imagine (configurat prin OTEL_*) → Alloy → Tempo. Propagă contextul prin RabbitMQ (header traceparent) astfel încât o singură operație să fie urmărită de la cererea HTTP, prin coadă, până la storage. Span-uri manuale: classify, route, storage.put, storage.verify, encrypt. Simulatorul Python este instrumentat la fel.
3. Loguri JSON (logstash-logback-encoder) cu traceId, fileId, category, storage → Loki. În Grafana: derived field traceId → Tempo și link Tempo → Loki.
4. Dashboard-uri Grafana provizionate (JSON în infra/grafana/dashboards/): (a) categorie × storage: latență p50/p95/p99, throughput, rată erori; (b) contoare: rată, gap-uri, duplicate; (c) cozi și workeri per categorie (dovada bulkhead); (d) starea storage-urilor; (e) securitate și PII.
5. Reguli Prometheus în infra/prometheus/rules/business.yml, mapate pe tabelul §2.1, fiecare cu labels severity, category, requirement și o adnotare în română:
   - MeterReadingsAtRisk: gap-uri noi, coada meter peste prag 5 min, sau duplicate scrise > 0;
   - FaultRecordsNotImmediatelyAvailable: p95 stocare avarii > SLA din politică sau PENDING avarii > 0 timp de 2 min;
   - PersonalDataAtRisk: refuzuri PII repetate sau document stocat necriptat;
   - DroneTransfersFailing: rată eșec video > 5% în 15 min sau reluări excesive;
   - StorageDown, IntegrityMismatch.
6. Alertmanager → webhook intern POST /api/internal/alerts (doar în rețeaua internă, autentificat cu token din secret) — creează sau actualizează incidente (modelul vine în Etapa 9; acum doar salvează alerta și loghează), cu deduplicare pe fingerprint.
7. docs/OBSERVABILITY.md: pentru fiecare cerință de observabilitate, cum se demonstrează live (ce dashboard, ce query, ce trace).
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): în Grafana vezi p50/p95/p99 pe categorie × storage. În Explore → Tempo găsești un trace al unui upload care trece prin api → RabbitMQ → worker → S3. `docker compose stop ftp` → alerta StorageDown apare în Alertmanager în \~1 min.

## Etapa 9 — Security & Incident Center + WhatsApp

```
Etapa 9 — Security & Incident Center și notificări WhatsApp. Prezintă întâi planul. Citește AGENTS.md și experienta-utilizator.md §9–§10 integral.

1. Model incident: cod uman (INC-2026-000123), type (UNAUTHORIZED_ACCESS, DIRECT_STORAGE_ACCESS_ATTEMPT, INTEGRITY_MISMATCH, STORAGE_UNAVAILABLE, TRANSFER_FAILED, BUSINESS_SLA_AT_RISK, GRID_FAULT_MAJOR, FIRE_REPORTED, FIELD_EMERGENCY, CYBER_SECURITY), severity (LOW…CRITICAL), status (NEW, NOTIFIED, ACKNOWLEDGED, IN_PROGRESS, RESOLVED, CLOSED), zone, station, asset, description, source (ALERTMANAGER, SYSTEM, USER, SENSOR), certainty (CONFIRMED, REPORTED, SUSPECTED), fișiere asociate, owner, timeline.
2. Creare automată (alerte Alertmanager din Etapa 8, MISMATCH, storage căzut, eșecuri de transfer) și manuală (avarie majoră, incendiu raportat, urgență din teren). FIRE_REPORTED se creează DOAR cu source USER/SENSOR, niciodată dedus automat.
3. config/notification-routing.yaml: tip + severitate + zonă → roluri (dispecerat, manager tehnic, responsabil incident, echipa de teren a zonei, IT/security, admin platformă, responsabil date) și lanț de escaladare cu timeout (ex. 10 min pentru CRITICAL). Contactele (numere de telefon) sunt în DB/env, NU în repo.
4. Template-uri în config/templates/ cu EXACT textele din §10.3–§10.7 și §10.9. Renderer-ul omite rândurile fără valoare (nu inventează cauza, clienții afectați sau durata). Pentru incendiu, titlul și corpul reflectă certainty („posibil incendiu” / „semnalare de verificat”). Sanitizare: niciun secret, token sau dată personală în mesaj (test dedicat).
5. Interfața NotificationChannel: WhatsAppSimulatedChannel (implicit; mesajul apare în UI, într-un „telefon” simulat), WhatsAppTwilioChannel (activ doar dacă există TWILIO_* în env; Twilio WhatsApp Sandbox). NOTIFY_MODE=SIMULATED | PREPARED_ONLY | REAL.
6. notification: destinatar, rol, incident, canal, sent_at, status (PREPARED, SIMULATED, SENT, DELIVERED, CONFIRMED, ESCALATED, CLOSED), callback de livrare (Twilio status webhook → DELIVERED), confirmed_by, confirmed_at, istoric.
7. Confirmare: răspuns WhatsApp „OK INC-…” (webhook inbound Twilio, cu validarea semnăturii), buton în UI sau link semnat cu expirare. „Citit” NU înseamnă confirmat.
8. Escaladare: scheduler — fără confirmare în intervalul regulii → ESCALATED + notifică nivelul următor, totul în timeline.
9. La RESOLVED → trimite actualizarea §10.9.
10. Detectarea accesului direct la storage: (a) audit/notificări MinIO pentru operații cu alte credențiale decât userul FileGrid; (b) loguri pure-ftpd și PostgreSQL (log_connections) parsate de Alloy/Loki ruler pentru login-uri cu alți utilizatori → incident DIRECT_STORAGE_ACCESS_ATTEMPT.
11. API: listare + filtre, detaliu cu fișiere asociate (link spre passport), schimbare stare, asignare, ack, SSE pentru incidente noi; conectează webhook-ul din Etapa 8 la crearea de incidente.
Teste: renderer (inclusiv câmpuri lipsă), escaladare cu ceas simulat, deduplicare alerte, sanitizare.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): rulezi `scripts/corrupt-file.sh` și descarci fișierul → apare incidentul de integritate, mesajul WhatsApp simulat are status SIMULATED, iar fără confirmare devine ESCALATED după timeout.

## Etapa 10 — Frontend: fundația Angular

```
Etapa 10 — fundația frontend-ului. Citește AGENTS.md și experienta-utilizator.md §12–§13.

1. frontend/: Angular (ultima versiune stabilă), standalone, signals, strict, SCSS, Angular Material cu temă proprie (tehnic-întunecat, accente energetice; tokens în styles/_tokens.scss), ngx-echarts, GSAP.
2. Rute lazy: /village (implicit), /files, /files/:id (File Passport), /categories/:category, /decisions, /grid-intelligence, /assistant, /incidents, /incidents/:id, /performance, /admin/policies.
3. Shell: meniul principal din §12, buton permanent „Înapoi în Village”, căutare globală (Ctrl+K) care deschide direct File Passport, badge „DATE SIMULATE” când datele afișate sunt SIMULATED, indicator al conexiunii live, buton global „Oprește animațiile” + respectarea prefers-reduced-motion.
4. Client API generat din OpenAPI (openapi-generator typescript-angular, script npm gen:api); EventStreamService (SSE cu reconectare și backoff); interceptor de erori care afișează traceId.
5. Componente comune: StatusBadge (toate stările din §3.3), IntegrityBadge (§6.4), StorageChip (FTP/S3/Database), FileSize, DateTime (Europe/Bucharest), EmptyState, DataOriginBadge. Fiecare stare are text + icon + culoare.
6. Docker: build multi-stage → nginx servește SPA-ul în spatele gateway-ului. Dev: npm start cu proxy.conf către https://localhost.
7. Pagini placeholder pentru ce nu e implementat. Fără autentificare încă.
Teste: unit pentru componentele comune; Playwright smoke test (pornește, navighează prin meniu).
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): https://localhost se deschide cu meniul complet, iar căutarea globală găsește un fișier încărcat anterior.

## Etapa 11 — Autentificare (o discutăm atunci)

**Claude Code: NU implementa această etapă.** Dacă ți se cere Etapa 11, oprește-te și spune că profilurile și permisiunile se stabilesc mai întâi cu arhitectul; promptul exact va fi adăugat aici ulterior.

Direcția discutată (încă nedecisă): Keycloak ca serviciu separat (OIDC), Angular cu login OIDC + PKCE, Spring ca resource server JWT, roluri derivate din utilizatorii din §2 al documentului de experiență (teren, dispecer, racordări, analist, manager incident, admin), plus un client de tip „mașină” pentru simulator și unul pentru Alertmanager. Cerințele din PDF pe care le acoperă etapa: accesul la FileGrid este autentificat, iar documentele personale nu sunt expuse utilizatorilor fără drept.

Codul din etapele anterioare e deja pregătit pentru asta prin `CurrentActorProvider` și `AccessPolicy`.

## Etapa 12 — FileGrid Village

```
Etapa 12 — ecranul FileGrid Village. Prezintă întâi planul (structura componentelor și mașina de stări). Citește AGENTS.md și experienta-utilizator.md §3, §4, §7, §8.1, §13.

1. Scenă izometrică SVG (componente Angular, viewBox responsive): zona de intrare, Agent 01 în centru, 4 case — Contoare (contor/energie), Avarii (fulger), Documente (dosar), Drone (dronă/cameră) — cu etichete; 3 servere etichetate FTP, S3, Database în partea opusă; cabluri (path-uri SVG) de la fiecare casă la fiecare server; zona de activitate recentă și rezumatul. Sub sat, la scroll, intrarea spre Grid Intelligence cu Agent 02 (teaser).
2. Ilustrații ORIGINALE, construite din forme SVG. Agent 01: robot-curier prietenos cu un ecran-față care își schimbă expresia/culoarea pe stări (idle, scanning, walking, carrying, success, warning, error). Animații GSAP fluide și scurte.
3. Drag & drop pe Agent 01 (plus buton „Încarcă fișier” pentru accesibilitate) → dialog de confirmare: categorie opțională, zonă/stație/echipament/descriere → upload cu tus-js-client (progres, reluare automată).
4. Mașina de stări a animației este condusă EXCLUSIV de evenimentele SSE reale: RECEIVED → agentul merge la fișier; ANALYZING → scanare + „Analizez conținutul…”; CATEGORIZED → merge la casă, casa se evidențiază; DESTINATION_SELECTED → serverul ales se evidențiază; TRANSFERRING → pachet luminos pe cablul casă→server, cu poziția legată de bytesTransferred; STORED_VERIFIED → serverul pulsează, confirmare + buton „Deschide pașaportul”; TRANSFER_RESUMED, PENDING, TRANSFER_FAILED → stări de atenționare cu textele din §8.1. Succesul NU se afișează înainte de STORED_VERIFIED.
5. Coadă de animații: citirile de contor nu se animă individual — apar ca un flux agregat pe cablul Contoare→storage, cu un contor numeric. Fișierele încărcate de utilizator au prioritate la animație.
6. Decision Explorer drop-down (§7): sub agent, un panou expandabil cu decizia reală din GET /api/files/{id}/decision: categoria, informațiile extrase, caracteristicile decisive, storage-ul ales + motive, alternativele + de ce nu, fallback, rezultat final.
7. Case: click → /categories/:category; mini-card cu număr de fișiere, volum, activitate recentă, avertizări (din /api/categories/summary, actualizat live). Servere: starea live; un storage căzut are cablurile vizual întrerupte și eticheta „Indisponibil”.
8. Jurnal text lateral cu aceleași evenimente — utilizatorul poate ignora complet animația (§13).
Teste Playwright: încarcă un PDF mic, verifică secvența de stări din jurnal și link-ul spre passport.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): tragi un fișier pe agent și urmărești toată animația; oprești S3 și încarci un video, iar animația arată fallback sau așteptare, nu succes.

## Etapa 13 — Categorii, File Explorer, File Passport, Decision Explorer

```
Etapa 13 — paginile de categorie, File Explorer, File Passport, Decision Explorer. Citește AGENTS.md și experienta-utilizator.md §5, §6, §7.

1. /categories/:category: denumire și descriere, număr de fișiere și volum, cele mai recente fișiere, căutare, filtre (perioadă, dimensiune, nume, stație/zonă, tip, storage, stare, integritate), sortare, listă paginată cu toate câmpurile din §5.1, buton de upload (doar la categoriile cu upload manual — nu la contoare), cu același flux tus și formular de context potrivit categoriei. Lista se actualizează live prin SSE.
2. /files (File Explorer): aceeași listă, dar peste toate categoriile, cu filtrul de categorie; filtrele sunt păstrate în URL.
3. /files/:id (File Passport) conform §6: antet de identitate cu „Copiază ID”; context (fără câmpuri goale); traseul vizual în 7 pași cu timestamp-uri și storage-ul final; card de decizie cu explicația și alternativele; integritate (badge, SHA-256 copiabil, ultima verificare, buton „Verifică acum” cu rezultat live); cronologie audit filtrabilă; acțiuni: previzualizare (PDF/imagine/video cu Range; JSON formatat pentru citiri), descărcare, verificare, înapoi la categorie — vizibile conform acțiunilor permise primite de la server. Starea MISMATCH = banner roșu „Fișier suspect — nu este o copie validă”. Datele personale apar mascate dacă lipsește permisiunea.
4. /decisions (Decision Explorer complet): listă de decizii recente cu filtre (categorie, storage, fallback da/nu); detaliul afișează tabelul candidaților cu scorurile, motivele, versiunea de politică folosită, link spre passport.
5. /admin/policies: vizualizare și editare YAML cu validare, istoric versiuni, diff.
Teste Playwright pentru fluxul listă → passport → descărcare → verificare.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): din orice listă ajungi la passport; după corupere, passport-ul arată banner-ul de suspect.

## Etapa 14 — Incident Center UI

```
Etapa 14 — interfața Security & Incident Center. Citește AGENTS.md și experienta-utilizator.md §9–§10.

1. /incidents: listă cu filtre (tip, severitate, stare, zonă, perioadă), badge-uri cu text, actualizare live prin SSE + notificare toast pentru incidente CRITICAL.
2. /incidents/:id: toate câmpurile din §9; fișierele și dovezile asociate (link spre passport); timeline; panoul de notificări per destinatar cu stările din §10.8; previzualizarea mesajului WhatsApp exact cum arată, cu eticheta clară SIMULAT / PREGĂTIT / TRIMIS; butoane confirmare, asignare, schimbare stare, rezolvare (cu text de actualizare → mesaj §10.9).
3. Formular „Raportează incident” (avarie majoră, posibil incendiu, urgență teren): pentru incendiu obligă alegerea sursei și a certitudinii; previzualizează mesajul înainte de trimitere.
4. Un „telefon simulat” (panou lateral) care afișează mesajele WhatsApp SIMULATED, cu buton „Răspunde OK” pentru demo-ul confirmării.
5. /performance (temporar): link-uri spre dashboard-urile Grafana + câteva KPI-uri din API; pagina completă vine în Etapa 17.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): creezi manual un „posibil incendiu” → mesajul conține formularea corectă → confirmi din telefonul simulat → starea devine CONFIRMED.

## Etapa 15 — Agent 02: Grid Intelligence

```
Etapa 15 — serviciul intelligence și Agent 02 (Grid Intelligence). Prezintă întâi planul. Citește AGENTS.md și experienta-utilizator.md §8.2, §11.2, §11.4.

Backend intelligence (intelligence/, Python 3.12, uv, FastAPI, SQLAlchemy, pandas, numpy, scikit-learn, APScheduler):
1. Acces read-only la meta-db și store-db prin utilizatori DB dedicați, creați prin migrare Flyway în backend, cu acces doar la view-uri analitice (fără coloane PII). Serviciul e doar intern; api-ul Spring face proxy la /api/intelligence/** (păstrează traceId și, ulterior, autorizarea).
2. Topologia din simulator/topology.yaml (montat).
3. Detectori separați și testabili, evaluați față de simulator/output/injected_anomalies.json (raportează precision/recall în docs/AGENT02.md):
   a. consum: z-score robust (mediană/MAD) pe profil orar per contor și per post; spike, cădere la zero;
   b. citiri lipsă, repetate sau imposibile;
   c. prosumatori: export anormal (ex. export noaptea);
   d. tensiune anormală agregată per post de transformare;
   e. frecvența avariilor per zonă/stație/echipament față de un baseline (Poisson);
   f. corelări spațio-temporale (aceeași stație/post, fereastră ±N min) — prezentate DOAR ca „posibil corelate”.
4. Finding: id, type, title, observation, evidence (serii + id-uri de fișiere/citiri sursă), zone/station, period, confidence (LOW/MEDIUM/HIGH + scor + cum a fost calculat), human_check, data_origin, status (NEW, REVIEWED, CONFIRMED_INCIDENT, DISMISSED). Textele vin din template-uri în română, în stilul exemplului din §8.2. Niciun LLM nu adaugă fapte. Date insuficiente → finding INSUFFICIENT_DATA explicit.
5. API: GET /findings (filtre), GET /findings/{id}, POST /analyze?zone=&from=&to= (progres prin SSE), GET /grid/map (noduri, legături, stare, indicatori), GET /grid/stats, POST /findings/{id}/promote → creează incident în backend (doar la acțiunea unui om).
6. Analiză programată la 5 minute. Serviciu nou în compose, instrumentat cu OpenTelemetry.

Frontend /grid-intelligence:
7. Hartă schematică SVG (regiuni → stații → posturi, linii) cu indicatori pe noduri: citiri, avarii, anomalii; zoom/pan; selectarea unei zone filtrează tot.
8. Agent 02 — personaj original: tehnician digital cu cască și tabletă, mai tehnic decât Agent 01. Animații conform §8.2: în repaus consultă tableta; la analiză apar marcaje pe hartă; la o anomalie indică zona; la date insuficiente spune explicit.
9. Panou de constatări: listă filtrabilă; detaliul arată observația, dovezile (grafice ECharts), încrederea explicată, verificarea umană necesară, link-uri spre fișierele sursă și passport, buton „Înregistrează ca incident”.
10. Statistici în timp: consum agregat, avarii pe zone, top stații după incidente. Badge „DATE SIMULATE” vizibil.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): anomaliile injectate de simulator apar ca findings cu dovezi, iar textul nu afirmă nicio cauză sigură.

## Etapa 16 — AI Operations Assistant (agentic RAG)

```
Etapa 16 — AI Operations Assistant, agentic RAG, în serviciul intelligence. Prezintă întâi planul. Citește AGENTS.md și experienta-utilizator.md §8.3.

1. Agent LangGraph cu provider configurabil (LLM_PROVIDER=anthropic|openai|ollama; cheile doar în .env). Fără LLM configurat → asistentul spune că nu e disponibil, iar restul aplicației funcționează normal.
2. Tools (toate read-only, executate cu permisiunile utilizatorului care întreabă — token propagat):
   search_files, get_file_passport, query_incidents, query_findings, meter_stats, fault_stats,
   storage_metrics (Prometheus HTTP API, doar query-uri PromQL predefinite, parametrizate),
   semantic_search (pgvector în meta-db pe: descrieri de fișiere, text extras din înregistrările de avarie, findings, incidente, docs/ și politici — FĂRĂ documente PII; indexare incrementală la STORED_VERIFIED),
   sql_readonly (doar SELECT pe view-urile analitice, validat cu un parser SQL, limită de rânduri, timeout),
   explain_policy.
3. Comportament agentic: decide singur ce tools folosește (inclusiv mai multe, în pași), poate răspunde și la întrebări generale despre platformă sau domeniu, dar separă clar „din datele FileGrid” de „cunoștințe generale”. Fiecare afirmație bazată pe date are citări (fileId/incidentId/findingId), pe care UI-ul le transformă în link-uri. Informație inexistentă → spune explicit. Nu inventează cauze. Nu expune date personale. Nu execută scrieri — doar propune („Vrei să creez un incident?”), iar acțiunea necesită un click al utilizatorului.
4. Memorie de conversație per sesiune; răspuns în streaming (SSE); API: POST /assistant/chat.
5. Evaluare: intelligence/tests/eval_questions.yaml cu ~20 de întrebări (exemplele din §8.3 + capcane: informație inexistentă, cerere de CNP, cerere de ștergere) și scripts/eval-assistant.sh care raportează scorul.
6. Frontend: pagina /assistant + panou lateral global (deschis și din passport, incident sau finding, cu context pre-încărcat); afișează pașii agentului (tools apelate) într-o secțiune pliabilă, citările ca link-uri, badge date simulate.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): „Au existat probleme de integritate în ultimele 7 zile?” primește un răspuns cu link-uri spre incidente; „Dă-mi CNP-ul clientului X” e refuzat.

## Etapa 17 — Statistici, benchmark storage, Performance Overview

```
Etapa 17 — statistici, benchmark storage, Performance Overview. Citește AGENTS.md și cerința §5.

1. scripts/seed-demo.sh: generează fișiere de test cu date FICTIVE (PDF-uri de avarie și de racordare, fără persoane reale; video-uri de 100MB–1.2GB cu ffmpeg testsrc) și le încarcă prin API cu dataOrigin=SIMULATED.
2. Benchmark controlat: POST /api/admin/benchmarks — pentru fiecare categorie scrie și citește în TOATE storage-urile tehnic eligibile un set reprezentativ (ex. 1000 citiri de 10KB, 50 avarii de 5MB, 50 documente de 2MB, 3 video-uri de 500MB), în namespace-ul benchmark, apoi curăță. Măsoară p50/p95/p99 write/read, throughput, erori, CPU/memorie (Prometheus), comportament la reluare.
3. Raport „Ce storage se potrivește fiecărei categorii și de ce”: scor din date măsurate + criterii calitative (idempotență, reluare, criptare, interogabilitate), comparat cu politica activă. Dacă diferă → recomandare cu explicație, aplicată DOAR de un om din /admin/policies.
4. Comparație zi normală vs. zi de vârf: rulările etichetate (normal / peak / storm, din Etapa 18) salvează snapshot-uri de metrici pe fereastra lor (Prometheus range queries): latențe per categorie, cozi, erori, fallback-uri și efectul asupra celorlalte categorii (dovada bulkhead).
5. Statistici de business: citiri/zi, prosumatori și export, top zone/stații cu avarii, volum per categorie, timpul până la disponibilitatea avariilor pentru dispecerat, accesări de documente PII (din audit).
6. Pagina /performance: KPI-uri, grafice ECharts, iar sub fiecare grafic o interpretare scurtă „Ce înseamnă pentru decizie” (generată din reguli, cu cifrele reale); link-uri spre Grafana; export CSV/PDF.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): raportul de benchmark are o recomandare per categorie susținută de cifre, iar pagina arată comparația normal vs. vârf.

## Etapa 18 — Reziliență, rețea degradată, teste de încărcare

```
Etapa 18 — reziliență, rețea degradată și teste de încărcare. Citește AGENTS.md și cerința §4 (Scalabilitate, Reziliență).

1. Toxiproxy: un proxy „degraded-edge” în fața api-ului pentru clienții de test și proxy-uri între workeri și storage-uri (ftp, s3, store-db), definite în infra/toxiproxy.json. scripts/chaos/: latență 800ms ±300, pierdere de pachete / reset_peer / timeout, lățime de bandă 256KB/s, storage complet indisponibil.
2. Client CLI de upload scripts/upload-cli.py (tuspy) cu retry și reluare de la offset — folosit în teste pe conexiunea degradată.
3. k6 în load-tests/: normal.js, meters-peak.js, storm-faults-10x.js (avarii x10; verifică faptul că p95 la contoare și documente rămâne sub prag), video-large.js (1.2GB pe conexiune degradată), mixed.js. Fiecare rulare are o etichetă (normal/peak/storm) folosită de Etapa 17; rezultatele merg în Prometheus (remote write).
4. Drill-uri automate în scripts/drills/, fiecare cu verificări finale PASS/FAIL (prin API + Prometheus):
   1) S3 cade în timpul upload-ului video → fallback sau PENDING → revine → finalizat fără intervenție;
   2) FTP cade → avariile merg pe fallback, celelalte categorii neafectate;
   3) worker omorât în mijlocul transferului → reluare;
   4) mesaje duplicate de contor → 0 dubluri;
   5) corupere → detectată la recuperare → incident → WhatsApp simulat;
   6) furtună de avarii x10 → celelalte categorii își păstrează latențele;
   7) scalare: `--scale worker-fault=4` mărește throughput-ul fără modificări de cod.
5. docs/DEMO.md: scenariul de demo pas cu pas, mapat pe fiecare cerință din PDF (§3, §4, §5), cu comanda și ce trebuie arătat pe ecran.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): toate drill-urile dau PASS; `k6 run load-tests/storm-faults-10x.js` arată că p95 la contoare nu se degradează.

## Etapa 19 — Kubernetes

```
Etapa 19 — Kubernetes pentru demonstrarea scalării. Citește AGENTS.md.

1. infra/k8s/ cu Kustomize (base + overlays/kind): Deployments pentru api, worker-<categorie>, intelligence, frontend, simulator; StatefulSets pentru meta-db, store-db, minio, ftp, rabbitmq (manifeste simple); ingress-nginx cu TLS; Secrets generate dintr-un script din .env (nu în git); NetworkPolicies — storage-urile acceptă trafic doar de la api/workeri; volumul de staging documentat (kind cu un nod).
2. KEDA cu scaler RabbitMQ: workerii scalează după adâncimea cozii categoriei lor; HPA pe CPU pentru api.
3. Observabilitate: kube-prometheus-stack + Loki + Tempo prin Helm, cu dashboard-urile existente.
4. make k8s-up (creează clusterul kind, încarcă imaginile, aplică manifestele), make k8s-storm (rulează furtuna și arată scalarea DOAR a worker-fault), make k8s-down.
5. Docker Compose rămâne calea principală „o singură comandă”; README explică ambele.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): în timpul `make k8s-storm`, `kubectl get pods -w` arată că doar worker-fault crește numărul de replici.

## Etapa 20 — Finalizare și predare

```
Etapa 20 — finalizare. Citește AGENTS.md și cerința §3.

1. README final: cerințe, pornire cu o singură comandă (start.sh / start.ps1 / make up), URL-uri, conturi demo (din .env), testele, drill-urile, diagramă Mermaid a arhitecturii, cum se schimbă politica fără cod.
2. Test pe mașină curată: clonare într-un director nou, fără .env, fără imagini locale → pornește complet. Repară tot ce lipsește.
3. gitleaks în pre-commit și în CI. GitHub Actions: build + teste backend/frontend/python + gitleaks + build imagini + Trivy.
4. Revizie de securitate: niciun port de storage publicat, headere de securitate, TLS ≥ 1.2, CORS restrâns, rate limiting pe /api/meters și pe login, dependențe scanate.
5. docs/ARCHITECTURE.md actualizat + ADR-uri în docs/ADR/ pentru deciziile importante (streaming + tus, cozi per categorie, criptare envelope, Agent 01 determinist, politica inițială validată de benchmark).
6. Verifică fiecare cerință din PDF și produ docs/COMPLIANCE.md: cerință → unde e implementată → cum se demonstrează.
```

**Criterii de acceptare** (verifică-le tu, apoi raportează rezultatul): clonezi proiectul într-un alt folder (ideal pe alt calculator), rulezi `./start.sh` și totul pornește.
