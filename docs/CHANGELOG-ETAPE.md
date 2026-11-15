# Jurnalul etapelor

## Etapa 11 — autentificare Keycloak OIDC: backend, frontend, verificare cap-coadă (2026-11-15)

Contractul: `docs/AUTH-DESIGN.md` (aprobat), ADR-0007. Partea Python (intelligence, simulator) e descrisă mai jos, la 2026-11-14.

### Ce s-a făcut
- **Infra:** serviciile `keycloak-db-init` (rol + bază `keycloak` în meta-db) și `keycloak` 26; realm `filegrid` cu 6 roluri, conturi de serviciu (simulator, intelligence, Alertmanager), clientul public `filegrid-web` (code + PKCE); rutele Traefik `/auth/realms` și `/auth/resources` (consola admin nu e rutată); Alertmanager trimite webhook-ul cu OAuth2 client credentials. Utilizatorii demo au email verificat, altfel Keycloak 26 cere „Verifică profilul” la primul login.
- **Backend:** resource server JWT (issuer public + intern, audience `filegrid-api`); `RolePermissions` (matricea §3) și `RolePermissionAccessPolicy`, cu metrica `filegrid_access_denied_total`; actorul din token (`preferred_username`) în audit; `GET /api/me`; 401/403 ca problem details; linkuri de conținut semnate HMAC (`POST /api/files/{id}/content-link`, 5 min pentru descărcare, 60 min pentru preview); SSE se închide la expirarea tokenului; numele documentelor PII mascate fără `pii:read` (`/files`, passport, `/decisions`, incidente).
- **Frontend:** `angular-auth-oidc-client` 21.0.4. Loginul se face înainte de pornirea aplicației (app initializer), revenind la pagina cerută; interceptorul adaugă tokenul pe `/api/`; `AuthzService` din `/api/me`, `permissionGuard` pe rute, directiva `*fgCan`. Meniul arată doar paginile permise, iar editarea politicilor doar cu `policy:write`. Drop-ul pe Agent 01 fără `file:write` afișează un mesaj. Utilizatorul, rolul și logout-ul apar în sidebar, rolul și în bara de sus. SSE e citit cu `fetch` + header `Authorization` și reconectare cu token reînnoit. Fiecare cerere tus primește tokenul curent. Descărcarea și preview-ul din passport folosesc linkuri semnate.
- **Teste noi:** `RolePermissionsTest` (12), `ContentLinkSignerTest` (7), `AuthenticationIT` (4: tokenuri invalide → 401, `/me`, actuator deschis pe portul de management, matricea pe un document PII + link semnat valid/alterat); primele teste frontend (`parseSse`, config OIDC). `MetadataSchemaIT`: fără autentificare, actorul e `system`.

### Cum se verifică
- `cd backend && ./mvnw -B clean verify`: 102 teste au rulat, unul a picat (`MetadataSchemaIT`, actor așteptat `demo-user`) și a fost reparat. Testele noi și `MetadataSchemaIT` trec: 19 unit + 10 IT.
- `cd frontend && npx ng build && npx ng test --watch=false` → 4/4.
- `.\start.ps1` → toate serviciile `healthy`. `curl -k https://localhost/api/files` → 401 `application/problem+json`. Simulatorul trimite cu token (`/api/meters/stats`: `duplicatesWritten = 0`, 2000 citiri/min). O alertă trimisă în Alertmanager devine incidentul `ALERTMANAGER`.
- Login prin fluxul real code + PKCE (formularul Keycloak) cu toți cei 6 utilizatori demo: `/api/me` dă rolul corect și refresh token; doar `connections` și `admin` obțin linkul semnat la un document PII (ceilalți: 403); descărcarea prin link merge fără header; parola greșită e refuzată.

### Limitări cunoscute
- Fluxul UI (redirect la login, sidebar, SSE prin fetch, upload tus) n-a fost încă parcurs prin click-uri într-un browser real. Login-ul și API-ul au fost verificate cu un client HTTP care parcurge exact pașii browserului.
- Realm-ul se importă doar la prima pornire. Modificările ulterioare din `realm-filegrid.json` cer reimport (README, „Autentificare”) sau aplicare cu `kcadm`.

## Redesign UI — Village pe tot ecranul, încărcare doar pe Agent 01, sidebar (2026-11-15)

### Ce s-a făcut
- **Paletă nouă**: deep blue (fundal), alb pur (text, case, robot), roz bubblegum (accent: butoane, fir, stare activă). Verde/galben/roșu rămân doar pentru stări. Titlurile (h1–h4, etichete de secțiune) sunt italice; textul obișnuit rămâne drept. Token nou `--pink` în `styles.scss` (`--cyan` e acum alias).
- **Control Center = doar harta**: panoul de statistici (KPI), starea storage-urilor, „Fișiere în lucru”, activitatea live, teaserul Grid Intelligence și lista de incidente au fost scoase. Statisticile rămân în Grid Intelligence și Performance.
- **Village redesenat**: case reale (pereți albi, acoperiș roz, ușă, ferestre, coș, priză), alei de la piață la fiecare casă. Agent 01 merge efectiv până la ușa casei categoriei, apoi apare un fir animat de la casă la storage-ul ales, iar pachetul de date circulă pe fir. Rămâne doar firul permanent al citirilor de contor (agregat, cu rata/min). Animația e condusă exclusiv de evenimentele SSE reale; „Stocat și verificat” doar la `STORED_VERIFIED`.
- **Încărcare doar prin drag & drop pe Agent 01**: pagina `/upload`, formularul și dialogul de confirmare au fost șterse (`upload-page.ts`, `upload-form.ts`, `upload-tracker.ts`). Drop-ul e acceptat doar pe robot (zona robotului se testează la eliberare); în altă parte, agentul spune „Lasă fișierul chiar pe Agent 01.” Progresul tus apare în balonul robotului. Categoria e detectată automat de Agent 01.
- **Decizia Agentului 01** apare ca mesaj peste hartă după încărcare (fișier, stare reală, storage, explicație, „Criterii și alternative”, „Deschide pașaportul” după verificare). Pagina Decision Explorer rămâne în meniu.
- **File Explorer șters** (`/files`): click pe o casă deschide `/categories/:category` (căutare + filtre pliabile, fără buton de încărcare); căutarea globală rămâne Ctrl+K. Serverele nu mai sunt clicabile.
- **Sidebar ascuns**: meniul nu mai stă pe ecran; se deschide din ☰ și se închide la navigare, Esc sau click în afară. Comutatorul de animații și telefonul simulat s-au mutat în sidebar. **Politicile rămân editabile din UI** (în sidebar, secțiunea „Rutare & administrare”).
- `docs/experienta-utilizator.md` actualizat (§3, §4.1, §5, §7, §11.3, §12, §14), plus README, `frontend/README.md`, `docs/COMPLIANCE.md`, `docs/HANDOFF.md`.

### Cum se verifică
- `cd frontend && npx ng build` (fără erori).
- `docker compose up -d --build --no-deps frontend`, apoi https://localhost: trage un fișier pe Agent 01 → robotul merge la casă, firul se conectează la storage, apare mesajul cu decizia și, după verificare, „Deschide pașaportul”. Un fișier lăsat lângă robot nu se încarcă.
- Click pe o casă → lista fișierelor din categorie; ☰ → sidebar cu toate paginile, inclusiv Politici și Decision Explorer.

### Limitări cunoscute
- Frontend-ul nu are teste automate; fluxul a fost verificat manual în browser (drop pe robot și lângă robot, fișiere reale ajunse „Stocat și verificat” pe FTP și S3).
- Fără formular, metadatele opționale (zonă, stație, echipament, descriere) nu mai pot fi completate la încărcare din UI; rămân disponibile prin API (`Upload-Metadata`).
- Pauza/reluarea manuală a unui upload a dispărut odată cu pagina de încărcare; tus reîncearcă automat, iar după un eșec definitiv utilizatorul trage din nou fișierul pe robot și încărcarea continuă de la offset.
- Pe ecrane foarte înguste harta se micșorează proporțional; mesajul cu decizia poate acoperi o parte din hartă până este închis.

## Etapa 11 (partea Python) — autentificare în intelligence și simulator (2026-11-14)

Contractul: `docs/AUTH-DESIGN.md` (§3 matricea endpoint → permisiune, §4 „/api/intelligence”, „Assistant tools”, „Simulator”).

### Ce s-a făcut
- **intelligence — `auth.py`**: dependența FastAPI `require(permission)` citește `Authorization: Bearer`, întreabă `GET {FILEGRID_API_URL}/me` cu același token și păstrează răspunsul în cache pe `sha256(token)` până la `exp` (payload-ul JWT e decodat fără verificare doar pentru `exp`; verificarea o face api-ul). Fail-closed: fără token / token invalid → 401 (`WWW-Authenticate: Bearer`), fără permisiune → 403, api indisponibil sau neconfigurat → 503.
- Matricea aplicată: `findings`, `findings/{id}`, `summary`, `grid`, `evaluation`, `voice/calls` → `intelligence:read`; `findings/{id}/status`, `analyze` → `intelligence:write`; `assistant/chat` → `assistant:use`; `voice/call` → `voice:use`; `voice/field-report` → `voice:use` (+ `incident:create` când `create=true`). `/health` și `/api/intelligence/health` rămân publice, `/metrics` rămâne intern.
- **Asistent și voce acționează ca utilizatorul**: `FileGridApi.with_token(...)` creează un client per cerere cu tokenul celui care întreabă (aceeași conexiune partajată), deci instrumentele văd exact ce are voie să vadă utilizatorul. Constatările Agent 02 (citite direct din DB) sunt oferite asistentului doar dacă utilizatorul are `intelligence:read`. Incidentul din raportul vocal e creat în numele utilizatorului. Header-ul `X-Actor` a fost eliminat.
- **Agent 02 în fundal** (bucla și incidentele `GRID_ANOMALY`) folosește tokenul de serviciu `filegrid-intelligence` (client_credentials): în cache, reîmprospătat cu 30 s înainte de expirare, un singur retry după 401.
- **Simulator**: `ClientCredentialsToken` (asincron, `asyncio.Lock` ca loturile concurente să ceară un singur token), reîmprospătare cu 30 s înainte de expirare, retry o singură dată pe 401 cu token nou (aceeași `Idempotency-Key`, fără backoff). Dacă Keycloak nu răspunde, lotul se reia cu backoff ca la orice eroare tranzitorie (nicio citire pierdută). Fără `KC_SIMULATOR_SECRET` se trimite fără token (rulări locale).
- Tokenul nu ajunge niciodată în prompturi, în rezultatele instrumentelor, în loguri sau în `repr` (secretele sunt `repr=False`).
- Variabile noi în `.env.example`: `KC_TOKEN_URL`, `KC_SIMULATOR_CLIENT_ID`, `KC_SIMULATOR_SECRET`, `KC_INTELLIGENCE_CLIENT_ID`, `KC_INTELLIGENCE_SECRET`.

### Cum se verifică
- `cd intelligence && uv run pytest -q && uv run ruff check src tests` (67 de teste, dintre care 21 noi în `tests/test_auth.py`: allow/deny, cache, expirare, 503, 401, toate rutele protejate, tokenul transmis instrumentelor și absent din răspuns/loguri, refresh token de serviciu, retry pe 401).
- `cd simulator && uv run pytest -q && uv run ruff check src tests` (23 de teste, 7 noi în `tests/test_auth.py`).

### Limitări cunoscute
- Testele folosesc api și Keycloak simulate (`httpx.MockTransport`); integrarea reală (realm, `/api/me`, issuer pe backchannel) nu a fost verificată în stack.
- `/api/intelligence/docs` și `/api/intelligence/openapi.json` (generate de FastAPI) rămân publice; nu expun date, doar schema.
- O schimbare de roluri în Keycloak devine vizibilă în intelligence la următorul token (cache până la `exp`, maxim 10 minute).

## Etapele 15 și 16 — serviciul intelligence: Agent 02, AI Operations Assistant, voce simulată (2026-11-14)

### Ce s-a făcut
- Serviciu nou `intelligence/` (Python 3.12, uv, FastAPI, pandas), în compose (doar rețeaua `internal`, 384M) și în Traefik (`/api/intelligence`, prioritate 500).
- **Agent 02** determinist: caracteristici per citire calculate în SQL (store-db), detectori pe reguli + statistici robuste (mediană/MAD, normalizare la mediana populației pe interval), analiză la 60 s + la cerere, constatări în `grid_finding` (meta-db, creat de serviciu), texte în română fără afirmarea cauzelor, incidente `GRID_ANOMALY` pentru tensiune scăzută la post, evaluare precision/recall față de adevărul simulatorului.
- **AI Operations Assistant**: tools read-only peste api (fișiere, passport, decizia Agent 01, incidente, contoare, storage, categorii), Agent 02 și RAG BM25 peste `docs/` + `config/`; provider Anthropic (Claude, tool use) sau `SIMULATED` determinist; citări; refuz pentru date personale și scrieri.
- **Voce**: apeluri simulate (`voice_call`), `VoiceProvider` cu stub Twilio; raport vocal din teren → propunere de incident.

### Cum se verifică
- `docker build --target test intelligence` (36 de teste).
- `curl -sk https://localhost/api/intelligence/summary`, `/findings`, `/grid`, `/evaluation`; chat-ul din README.

### Limitări cunoscute
- Acces direct la store-db/meta-db cu userii existenți (nu view-uri analitice dedicate), rutare directă din Traefik (nu proxy prin api); fără SSE pentru progresul analizei; fără detectorii de frecvență a avariilor și corelări spațio-temporale; RAG fără pgvector (BM25).
- Fișierul de adevăr al simulatorului e complet doar de la ultima pornire a simulatorului: evaluarea începe de acolo.
- Prima analiză după pornire încarcă ~24 h de citiri (~45 s pentru 120k citiri); următoarele sunt incrementale (~2–4 s).

## Etapele 6, 7, 8 (alerte) și 9 (backend) — recuperare, criptare, incidente (2026-11-14)

Etapa 6 (recuperare cu verificare, File Passport, căutare, scrubber de integritate) și scheletul Etapei 9 au fost scrise de un coleg; în această sesiune au fost făcute să compileze, completate și testate.

### Ce s-a făcut
- **Security & Incident Center (backend):** `IncidentController` (`/api/incidents`: listă cu filtre, detaliu, creare, preview mesaje, preluare, responsabil, schimbare stare, notificări, confirmare „OK”, `/config`), flux live `/api/incidents/stream` (eveniment `incident-event`, `IncidentHub`).
- **WhatsApp simulat:** mod `NOTIFY_MODE=SIMULATED` (implicit), `PREPARED_ONLY` sau `REAL` (Twilio, doar cu `TWILIO_*` și numere în env). Mesajele vin din `config/templates/`, destinatarii și escaladarea din `config/notification-routing.yaml`. Escaladare automată la nivelul următor dacă nimeni nu confirmă (`NOTIFY_ESCALATION_OVERRIDE=2m` pentru demo); la rezolvare, actualizarea finală merge la toți cei alertați.
- **Incidente automate** (`IncidentAutomation`): storage căzut → `STORAGE_UNAVAILABLE` (rezolvat automat când revine), neconcordanță SHA-256 → `INTEGRITY_MISMATCH` cu fișierul legat, transfer eșuat → `TRANSFER_FAILED`. Deduplicare pe fingerprint.
- **Etapa 7 — criptare aplicativă** a documentelor de racordare: Tink Streaming AEAD `AES256_GCM_HKDF_1MB`, DEK per fișier împachetat cu `FILEGRID_KEK` în `file_key` (migrația `V7`), rotație KEK (`FILEGRID_KEK_ID`, `FILEGRID_KEK_PREVIOUS`). Read-back pe `sha256_stored` (ciphertext), descărcare decriptată și verificată pe `sha256_plain`; ciphertext alterat → `MISMATCH` + incident, nimic servit. Metrica `filegrid_pii_stored_unencrypted_total`. Detalii în `docs/ENCRYPTION.md`; demo `scripts/direct-access-demo.sh`.
- **Etapa 8 (parțial) — alerte de business:** `infra/prometheus/rules/business.yml` (StorageDown, IntegrityMismatch, MeterReadingsAtRisk, PersonalDataAtRisk); Alertmanager → `POST /api/incidents/alertmanager` (refuzat dacă vine prin gateway).
- `GET /api/storage/health`; drill `scripts/drills/storage-outage.sh` (S3 oprit → fallback/PENDING → incident → S3 pornit → incident rezolvat).

### Corecturi față de codul preluat
- `IncidentService` nu compila (referințe de metode ambigue, `IncidentHub` inexistent); toate textele `incident.*`, `notification.*`, `message.*` lipseau din bundle (orice incident ar fi dat eroare).
- Filtrul de date personale din mesaje trata codul incidentului (`INC-2026-000003`) și ora ca numere de telefon și le ascundea.

### Cum se verifică
- `cd backend && ./mvnw verify -Dit.test='EnvelopeEncryptionIT,IncidentCenterIT,RetrievalAndPassportIT,WorkerTransferIT,MultipartUploadIT'` → 23 de teste, toate trec.
- `curl -sk https://localhost/api/incidents/config`, `bash scripts/direct-access-demo.sh`, `bash scripts/drills/storage-outage.sh`.

### Limitări cunoscute
- Tracing OpenTelemetry, loguri JSON și dashboard-urile Grafana din Etapa 8 nu sunt făcute.
- Câmpurile personale din metadate (nume client, CNP) nu se colectează la upload; re-wrap în masă la rotația KEK nu este automatizat.
- Webhook-ul Alertmanager este protejat doar prin rețea (refuză cererile cu `X-Forwarded-For`), nu printr-un token.

## Etapa 5 — Simulatorul de contoare + ingestie idempotentă (2026-11-14)

### Ce s-a făcut
- **Backend, pachetul `ro.filegrid.meter`:**
  - `POST /api/meters/readings` (`MeterController`, `MeterIngestionService`): lot de cel mult 500 de citiri, antet `Idempotency-Key` obligatoriu, validare completă cu toate erorile în română (`ProblemDetail`, 400). Fiecare citire este un fișier JSON mic (≤ 64 KB, lotul ≤ 16 MB, conform regulii de buffere); `readingId = SHA-256(meterId|periodStart)`, SHA-256 calculat pe octeții exacți ai citirii.
  - Răspuns `202` **doar după ce RabbitMQ a confirmat fiecare citire** (`MeterReadingPublisher`: mesaje persistente pe `filegrid.jobs.meter`, tip `meter-reading`, publisher confirms așteptate împreună). Răspunsul listează `accepted` / `duplicate`. Același `Idempotency-Key` → același răspuns, fără republicare (`Idempotent-Replayed: true`); aceeași cheie cu alt conținut → 422; coadă indisponibilă → 503 + `Retry-After`.
  - `worker-meter` (`MeterQueueListener` + `MeterReadingWriter`): consum pe loturi (`filegrid.worker.meter-batch-size`, implicit 200), un singur `INSERT … SELECT unnest(…) ON CONFLICT DO NOTHING RETURNING` în store-db, read-back în aceeași tranzacție (`encode(sha256(content),'hex') = sha256`, altfel rollback), apoi `file_record` cu `idempotency_key = readingId` (+ `file_context`, `file_event VERIFIED`) în meta-db, tot `ON CONFLICT DO NOTHING`. Ack doar după commit; la eroare, lotul se reia. Mesajele alterate în tranzit (hash/id) merg în DLQ.
  - Migrații: store-db `V2__meter_reading_blob.sql` (PK `reading_id` + UNIQUE `(meter_id, period_start)`), meta-db `V4__meter_ingestion.sql` (`meter_batch` pentru idempotența loturilor, `meter_counter` pentru totaluri).
  - Reconciliere la 1 minut (`MeterReconciliation`, doar în `api`): gap-uri de `seq` per contor (ultimele 24 h), duplicate respinse, duplicate scrise numărate independent în store-db și meta-db → `filegrid_meter_gaps_total`, `filegrid_meter_duplicates_rejected_total`, `filegrid_meter_duplicates_written_total` (trebuie să fie 0), `filegrid_meter_readings_stored_total`, `filegrid_meter_meters_active`.
  - `GET /api/meters/stats`: citiri în ultimul minut, medie pe 5 minute, total, contoare active, gap-uri, duplicate respinse/scrise, `dataOrigin`.
- **Simulator** (`simulator/`, Python 3.12, uv, httpx async, prometheus-client):
  - `topology.yaml` (generat determinist de `tools/gen_topology.py`): 3 regiuni (Muntenia, Banat, Dobrogea), 12 stații, 60 de posturi de transformare cu coordonate schematice x/y, linii MV/HV; 2000 de contoare (`METERS`) atribuite determinist posturilor, ~10% prosumatori cu PV.
  - Profiluri: curbă zilnică (vârf dimineața/seara), weekend, efect de temperatură (încălzire sub 15 °C), producție solară pentru prosumatori; 15 eșantioane pe minut per citire (~2 KB).
  - Anomalii din `anomalies.yaml`: `ZERO_CONSUMPTION`, `SPIKE`, `MISSING_READINGS`, `REPEATED_VALUES`, `LOW_VOLTAGE` (per post), `NIGHT_EXPORT`, `IMPOSSIBLE_VALUES`, repetate zilnic pe alte contoare; ground truth în `output/injected_anomalies.json`.
  - Moduri `normal`, `peak` (x10), `storm` (normal + rafale x10); `METERS`, `INTERVAL_SECONDS`, `RATE_MULTIPLIER`, `TIME_SCALE`, `DURATION` din env/CLI.
  - Retrimite până la ack cu **același** `Idempotency-Key` și același corp; ~1% duplicate intenționate; stare persistată (`output/state.json`), deci la repornire continuă de la primul tick neconfirmat. Metrici proprii pe `:9105` (sent, acked, accepted, duplicates sent/reported, retried, rejected, latență).
  - Serviciu `simulator` în compose (mod normal implicit, volum `simulator-output`, 160 MB), scrape în Prometheus; `make sim-normal`, `make sim-peak`, `make sim-storm`, `make sim-stats`.
- Teste: `MeterIngestionIT` (6 teste, Testcontainers: meta-db, store-db, RabbitMQ; api + worker în același context), 16 teste pytest pentru simulator (generator, profiluri, anomalii, ground truth, client cu retry, buclă și reluare după repornire), rulate cu ruff în `docker build --target test simulator`.

### Cum se verifică
- `cd backend && ./mvnw verify` → testul „același lot trimis de 3 ori → exact o înregistrare per readingId” (`MeterIngestionIT`).
- `docker build --target test simulator` → ruff + 16 teste pytest.
- `.\start.ps1` → simulatorul pornește healthy; `curl -k https://localhost/api/meters/stats` de câteva ori: `readingsLastMinute` ≈ 2000, `readingsStored` crește continuu, `duplicatesWritten` = 0, `duplicatesRejected` > 0 (duplicatele intenționate), `dataOrigin` = `SIMULATED`.
- `make sim-peak` (PowerShell: `$env:SIM_MODE='peak'; docker compose up -d --no-deps --force-recreate simulator`) → după ~1 minut `readingsLastMinute` crește de ~10 ori.

### Decizii luate în afara promptului
- **Aceeași coadă pentru citiri și pentru fișierele de contor încărcate din UI** (`filegrid.jobs.meter`), deosebite prin tipul mesajului AMQP; listener-ul pe loturi tratează joburile de transfer cu `TransferWorker` ca înainte.
- **`readingId` cu `periodStart` în ISO-8601 UTC** (`Instant.toString()`), ca aceeași perioadă să dea mereu același id indiferent de formatul trimis.
- **Contorii totali în meta-db (`meter_counter`)**, scriși de api și de toți workerii, publicați ca metrici de un singur proces (api) — altfel valorile s-ar dubla sau s-ar pierde între replici. Metricile `*_total` cerute sunt `FunctionCounter`; gap-urile sunt valoarea curentă calculată (pot scădea dacă sosesc citiri întârziate).
- **Limită de lot 16 MB** (pe lângă 500 de citiri), pentru regula „buffere maxim 16 MB”.
- **Ceas simulat:** fiecare tick acoperă `INTERVAL_MINUTES` (15) de timp simulat; implicit un tick pe minut real, deci ziua simulată trece în 96 de minute. `TIME_SCALE` accelerează suplimentar.
- **Testele Python rulează în Docker** (stage `test` din Dockerfile), fiindcă pe mașina de dezvoltare nu există `uv`/Python 3.12; `uv.lock` a fost generat în container.

### Limitări cunoscute
- Citirile nu emit evenimente SSE individuale (ar fi ~2000/min); starea lor se vede în `/api/meters/stats` și în metrici.
- Gap-urile includ intenționat anomaliile `MISSING_READINGS` (sunt citiri care chiar lipsesc).
- Măsurătoarea de memorie „simulator pornit 5 minute” din Etapa 1 se face acum cu stack-ul complet (vezi mai jos la Etapa 4).

## Etapa 4 — Pipeline-ul de încărcare: tus, cozi per categorie, stări live (2026-11-14)

Începută de alt agent (upload tus/multipart, ingestie, cozi, SSE, teste), finalizată în această sesiune.

### Ce s-a făcut
- **Upload:** tus 1.0 la `/api/uploads` (chunk-uri în `/data/staging/tus`, mutate prin rename în `/data/staging/<fileId>` la finalizare, fără copiere), `POST /api/files` multipart pentru fișiere mici; metadate `category`, `zone`, `station`, `equipment`, `eventType`, `eventTime`, `description`, `dataOrigin`; limită `FILEGRID_UPLOAD_MAX_BYTES` (5 GB); curățarea upload-urilor abandonate > 24 h.
- **Ingestie:** `RECEIVED → ANALYZING (SHA-256 în flux) → CATEGORIZED → DESTINATION_SELECTED`, job publicat cu publisher confirms pe `filegrid.jobs.<categorie>` (durabile, persistente, DLQ per coadă); fiecare pas în `file_event` și pe exchange-ul fanout `filegrid.events`.
- **Worker** (pachetul `ro.filegrid.worker`, nou în această sesiune):
  - `WorkerListenerConfiguration`: un container RabbitMQ per categorie din `WORKER_CATEGORIES`, ack manual, concurență și prefetch configurabile per categorie (`WORKER_CONCURRENCY`, `WORKER_PREFETCH`, `filegrid.worker.per-category.*`).
  - `TransferWorker`: `TRANSFERRING` cu progres la ~5% sau 16 MB → put în flux din staging → read-back + SHA-256 → `STORED_VERIFIED` + `VERIFIED_INTACT` și ștergerea din staging. Neconcordanță → `MISMATCH` + `INTEGRITY_INCIDENT`, obiectul corupt șters, retry; dacă însăși copia din staging nu mai corespunde hash-ului original, `TRANSFER_FAILED` fără reîncercări inutile.
  - Erori de storage → retry cu backoff exponențial + jitter din politică (`Backoff`), apoi fallback pe lanțul politicii (eveniment `FALLBACK`, decizia de rutare actualizată), iar dacă toate cad → `PENDING`, fișierul rămâne în staging.
  - S3 multipart reluabil: `S3StorageAdapter.putResumable` + `MultipartCheckpoint`, persistat în `s3_multipart_transfer` (migrația `V3`); după un crash sau o pană S3, transferul continuă de la ultima parte confirmată (`TRANSFER_RESUMED`).
  - Idempotență: un job pentru un fișier `STORED_VERIFIED` este confirmat fără nicio scriere; un advisory lock PostgreSQL (pe conexiune dedicată, eliberat automat dacă workerul moare) garantează un singur worker per fișier, inclusiv între replici.
- **Reluare automată** (`RecoveryScheduler`, api): fișierele `PENDING` sunt re-rutate când `storage_health` raportează un storage al categoriei `UP`; ingestiile întrerupte de o repornire sunt reluate.
- **SSE** `GET /api/events/stream?fileId=&category=` cu payload-ul cerut; `bytesTransferred` este mereu numeric; numele storage-urilor afișate („Database”) vin din texte.

### Corecturi față de codul preluat
- Contextul `api` nu pornea deloc: `LiveEventRelay` își referea propria coadă din aceeași clasă (referință circulară). Topologia a fost mutată în `LiveEventTopology`.
- Publisher confirms nu erau activate în `application.yml`: orice publicare de job aștepta 10 s și eșua (fișierul ajungea `PENDING`). Acum `publisher-confirm-type: correlated`, `publisher-returns`, `template.mandatory`.
- Upload-ul tus era șters imediat după finalizare (HEAD dădea 404, contrar protocolului), iar fișierul de date era căutat într-o cale greșită (se copia tot fișierul). Acum se mută `uploads/<id>/data`, iar finalizarea se declanșează doar de PATCH-ul care completează upload-ul (sau POST-ul unui upload gol), o singură dată.
- `UploadCleanupScheduler` ștergea fișierele rămase în `RECEIVED`/`ANALYZING` după 24 h — contrar principiului A.3.7. Acum copia din staging a unui fișier neverificat nu se șterge niciodată.
- Evenimentele live se pot reordona între canale AMQP diferite (s-a văzut `ANALYZING` înaintea lui `RECEIVED`): `LiveEventPublisher` folosește acum o conexiune proprie cu un singur canal.
- Gateway-ul (Traefik v3) tăia orice corp de cerere citit mai mult de 60 s (`readTimeout` implicit): un PATCH tus mare se oprea la ~58 s cu 499. Entrypoint-ul HTTPS are acum `readTimeout: 1h`, `writeTimeout: 0s` (SSE), `idleTimeout: 180s`.
- Build-ul paralel al imaginii `filegrid/backend:local` din 5 servicii eșua la export: doar `api` are `build`, workerii refolosesc imaginea (`pull_policy: never`).
- Testele preluate aveau presupuneri greșite, corectate: HEAD tus poate fi 200 sau 204; DLX este `direct`; cozile nu se redeclară fără DLQ; coloanele `stored_blob`; cheia S3 în bucket; regex-ul mesajelor Agentului 01 aliniat la textele reale și generat din bundle; un singur `read()` nu umple un buffer; fișierele goale prin multipart sunt acceptate.
- Teste noi: `S3OutageRecoveryIT` (S3 oprit în mijlocul transferului → retry → fallback FTP → `PENDING` → S3 repornit → reluare multipart → `STORED_VERIFIED`), `LargeResumableUploadIT` (1,5 GB prin tus, întrerupt la 40%, reluat, S3 multipart, read-back, heap ≤ 256 MB), teste unitare pentru backoff, progres și listener; politică de test cu retry rapid (`src/test/resources/policy/fast-retry-policies.yaml`); `@DirtiesContext` pe testele de integrare, ca listener-ele să nu rămână active între clase.

### Cum se verifică
- `cd backend && ./mvnw verify`.
- `curl -N -k https://localhost/api/events/stream` + upload tus cu `curl` (comenzile din README) → `RECEIVED → … → STORED_VERIFIED`.
- `docker compose stop s3` în timpul transferului unui video → `FALLBACK` / `PENDING` → `docker compose start s3` → `TRANSFER_RESUMED` → `STORED_VERIFIED`.
- `bash scripts/mem-report.sh` în timpul upload-ului de 1,5 GB; `docker compose up -d --scale worker-video=3`.

### Limitări cunoscute
- Un fișier rămas în `DESTINATION_SELECTED` pentru că api-ul a căzut exact între salvarea deciziei și publicarea jobului nu este republicat automat (fereastră de milisecunde); restul stărilor intermediare se reiau singure.
- Reluarea FTP după un crash al workerului pornește de la începutul fișierului `.part` existent doar în cadrul aceluiași apel (adaptorul din Etapa 2); un worker nou reia de la offset-ul `.part` de pe server.
- Ordinea evenimentelor este garantată per proces; între api și worker se bazează pe cauzalitate (workerul pornește după publicarea jobului).


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

## Etapele 10, 12, 13, 14 (+ UI pentru 15/16) — Frontend Angular

### Ce s-a făcut
- `frontend/`: Angular 21 (standalone, signals, zoneless, strict), temă întunecată „control room” (Material + tokens CSS),
  rute lazy, meniu lateral, căutare globală Ctrl+K → File Passport, indicator live SSE, badge „DATE SIMULATE”,
  buton „Oprește animațiile” (+ prefers-reduced-motion), texte RO centralizate în `core/texts.ts`.
- Control Center: FileGrid Village SVG (4 case, 3 servere, cabluri, Agent 01 animat cu GSAP), mașina de stări condusă
  exclusiv de SSE `file-event` (succes doar la STORED_VERIFIED), flux agregat pentru contoare, storage căzut = cabluri
  întrerupte + „Indisponibil”, drop de fișiere pe sat, drop-down Decision Explorer, KPI-uri, jurnal live, hartă rețea.
- Upload tus reluabil (chunk 8 MB) cu cronologie de stări per fișier; File Explorer cu filtre în URL; pagini de categorie;
  File Passport (traseu 7 pași, integritate + verificare, descărcare/previzualizare după acțiunile primite, audit filtrabil,
  banner MISMATCH); Decision Explorer; politici (vizualizare/editare YAML, istoric).
- Incident Center: listă cu filtre live, detaliu (notificări per destinatar, mod SIMULAT/PREGĂTIT/TRIMIS, countdown
  escaladare, confirmare, ack/asignare/stare cu notă obligatorie la rezolvare), „Raportează incident” cu previzualizare
  WhatsApp live (incendiu: sursă + certitudine obligatorii), telefon simulat cu „Răspunde OK”, toast la CRITICAL.
- Grid Intelligence: hartă schematică (regiuni/stații/posturi), Agent 02, constatări cu observații, dovezi ECharts,
  încredere, verificare umană, confirmare/respingere, „Înregistrează ca incident”, evaluare detectori.
- AI Assistant (pagină + panou lateral cu context din passport/incident/constatare), pași agent pliabili, citări ca link-uri,
  dictare/redare vocală (Web Speech API, ro-RO). Comunicare teren: apel vocal simulat, raport vocal → propunere incident.
- Infra: `frontend/Dockerfile` (node 24 → nginx-unprivileged :8080), `frontend/nginx.conf`, serviciul `frontend` în compose
  (64M), ruta Traefik `/` (prioritate 1); `permissionsPolicy` permite `microphone=(self)` pentru dictare.

### Cum se verifică
- `cd frontend && npm run build`; `docker compose up -d --build --no-deps frontend`; https://localhost.
- Încarcă un fișier din `/upload` sau din Village → stările ajung la „Stocat și verificat” → „Deschide pașaportul”.

### Limitări cunoscute
- Fără teste unitare/Playwright automate pentru frontend încă (verificat manual cu Playwright MCP).
- `npm ci` în Docker refuză lock-file-ul generat pe Windows (dependențe wasm opționale) → Dockerfile folosește `npm install`.
- Fonturile/iconițele vin de la Google Fonts (fără internet, iconițele apar ca text).

## Fix — Agent 02 rezistent la citiri corupte (2026-11-14)

### Ce s-a făcut
- `intelligence/agent02.py`: o singură citire de contor cu conținut invalid (de exemplu după
  `scripts/corrupt-file.sh`, care inversează primul octet: `{` → `0x84`) bloca toată analiza
  (`invalid byte sequence for encoding "UTF8"`), deci `/grid` rămânea gol. Acum, la o eroare de date, rândul
  corupt este găsit prin bisecție pe pagină, exclus, logat (`reading … skipped`) și pagina se reîncarcă.
  `last_run.readingsSkippedCorrupt` arată câte citiri au fost excluse. Limită de siguranță: maxim 50 excluderi
  per pagină, altfel eroarea se propagă (o eroare sistemică nu trebuie să golească fereastra).
- Teste: `intelligence/tests/test_agent02_loading.py` (8 teste, store-db simulat).

### Cum se verifică
- `cd intelligence && uv run pytest -q` → 46 teste trec; `uv run ruff check src tests` curat.
- Pe date reale (fereastră 48 h care conține citirea coruptă): 299.951 citiri încărcate, 1 exclusă și logată.

### Limitări cunoscute
- Citirea exclusă nu apare ca constatare în `/grid`; integritatea ei e semnalată separat (incident INTEGRITY_MISMATCH).

## Etapa 19 — Kubernetes (kind): scalare per categorie cu KEDA (2026-11-14)

### Ce s-a făcut
- `infra/k8s/` cu Kustomize (`base/` + `overlays/kind/`): Deployments pentru api, worker-meter/-fault/-document/-video,
  intelligence, frontend, simulator; StatefulSets simple pentru meta-db, store-db, s3 (aceeași imagine Silo, fixată cu
  digest), ftp, rabbitmq; Job `s3-init` care rulează `infra/minio/init.sh`; Services; PVC-uri `staging-data` și
  `simulator-output`.
- Ingress ingress-nginx cu TLS (secret `filegrid-tls` din `infra/certs/`), aceleași rute ca Traefik; fără limită de body și
  fără buffering pentru tus/SSE.
- NetworkPolicies: storage-urile (store-db, s3, ftp) și meta-db/rabbitmq acceptă trafic doar de la api/workeri
  (+ intelligence la baze de date, ca în Compose; + s3-init la s3; + operatorul KEDA la RabbitMQ).
- KEDA 2.21.0: câte un ScaledObject (scaler `rabbitmq`, `QueueLength`) per categorie, fiecare pe coada lui
  `filegrid.jobs.<categorie>`; HPA pe CPU pentru api (metrics-server v0.9.1).
- `scripts/k8s/generate.sh`: Secrets + ConfigMaps din `.env` și din fișierele repo-ului, în
  `infra/k8s/overlays/kind/generated/` (ignorat de git). `scripts/k8s/up.sh`, `storm.sh`, `down.sh`; target-uri
  `make k8s-up`, `k8s-storm`, `k8s-status`, `k8s-down`. Versiuni fixate și verificate: kind node v1.36.4 (digest),
  ingress-nginx controller-v1.15.1, metrics-server v0.9.1, KEDA 2.21.0 (testat cu Kubernetes 1.34–1.36).
- `k8s-storm`: Job cu 6 pod-uri × 150 fișiere de 256 KiB (`category=FAULT_RECORD`, `dataOrigin=SIMULATED`) prin
  `POST /api/files`; afișează pod-urile workerilor și replicile înainte/după.
- `enableServiceLinks: false` pe toate pod-urile (altfel `RABBITMQ_PORT`/`FTP_PORT` injectate de Kubernetes ar strica
  configurarea Spring).
- Placeholder `TODO(keycloak)` în `infra/k8s/overlays/kind/kustomization.yaml`.

### Cum se verifică
- Offline: `kubectl kustomize infra/k8s/overlays/kind` (randează 40 de obiecte, fără erori).
- Pe cluster: `make k8s-up` → `kubectl --context kind-filegrid -n filegrid get pods` (toate Running) →
  `curl -k https://localhost:9443/api/actuator/health`.
- Criteriul de acceptare: într-un terminal `kubectl --context kind-filegrid -n filegrid get pods -w`, în altul
  `make k8s-storm`; doar `worker-fault` trebuie să crească peste 1 replică.

### Limitări cunoscute
- **Neverificat pe un cluster real**: pe mașina de dezvoltare nu sunt instalate `kind`, `helm` și `make`, iar stack-ul
  Compose rula; s-a validat doar randarea Kustomize, sintaxa scripturilor (`bash -n`), parsarea YAML și generarea
  Secrets/ConfigMaps cu un fișier env fictiv. Criteriul de acceptare (doar worker-fault scalează) rămâne de confirmat.
- Observabilitatea în k8s (kube-prometheus-stack, Loki, Tempo) nu este instalată; rămâne în Compose.
- Valorile de tuning din `.env` (JVM, pool-uri, SIM_*) nu sunt preluate în k8s; se folosesc valorile implicite din Compose.
- Volumul de staging este `ReadWriteOnce` pe un cluster cu un singur nod; multi-nod necesită `ReadWriteMany`.
- Pe Windows, `umask 077` nu restrânge permisiunile fișierelor generate pe NTFS; directorul rămâne ignorat de git.
- Keycloak, Toxiproxy și drill-urile de haos nu sunt portate în k8s.
