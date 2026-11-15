# Predare — FileGrid PPC

## ⏩ STARE CURENTĂ (2026-11-15, dimineața) — citește întâi asta

Totul este commit-uit și împins pe `main`. Pe alt laptop: `git pull`, apoi pașii de la „Pornire pe laptopul nou” de mai jos.

### Pornire pe laptopul nou (5 pași)
1. `git pull` și `.\start.ps1` (sau `./start.sh`). La prima rulare creează `.env` cu secrete noi; `.env` NU e în git.
2. Completează în `.env`: `LLM_PROVIDER=gemini`, `LLM_API_KEY=<cheia Gemini>` (https://aistudio.google.com/apikey).
   `LLM_MODEL=gemini-3.5-flash-lite` e deja implicit. Apoi: `docker compose -f infra/docker-compose.yml --env-file .env up -d --force-recreate --no-deps intelligence`.
   Fără cheie, asistentul merge în „mod de rezervă, fără AI” (nu e eroare).
3. Deschide **https://localhost** (scrie `https://`; certificat self-signed). Te trimite la login Keycloak.
4. Utilizatori demo: `field.demo`, `dispatcher.demo`, `connections.demo`, `analyst.demo`, `incident-manager.demo`, `admin.demo`.
   Parolele sunt în `.env`: `KC_DEMO_*_PASSWORD` (se generează pe fiecare laptop, deci diferă). Grafana: `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`.
5. Verificare rapidă: `curl -k https://localhost/api/files` → 401; în aplicație punctul „Live” e verde; trage un fișier pe PPCastor.

### Ce e gata
- **Etapa 11 — autentificare Keycloak OIDC** (backend, frontend, intelligence, simulator, Alertmanager). Detalii: README „Autentificare”,
  `docs/AUTH-DESIGN.md`, `docs/CHANGELOG-ETAPE.md`. Doar `connections` și `admin` văd date personale.
- **Asistentul AI pe Gemini**: eticheta răspunsului separă „Răspuns: Gemini / mod de rezervă” de „Date: simulate / reale”
  (`dataOrigin` se deduce din datele citite); reîncercări la 429/503; răspunsurile randate ca Markdown fără innerHTML.
- **Redesign (2026-11-15)**: mascota **PPCastor** (castor cu cască și vestă reflectorizantă) a înlocuit robotul și cară fișierul ca pe un buștean
  la casa categoriei; Village e un oraș cu energie verde (dealuri, turbine eoliene, copaci, pârâu, barajul castorului ca intrare, panouri solare);
  paleta întregii aplicații: baby blue + alb, text bleumarin, rozul bubblegum păstrat (`--pink` pentru suprafețe, `--pink-ink` pentru text pe alb).
  Fișiere: `frontend/src/app/pages/control/village.*`, tokenii din `frontend/src/styles.scss`.
- **Accesibilitate**: contur de focus la tastatură, butoane cu iconiță etichetate, meniul lateral preia focusul.
- **PPCastor peste tot (2026-11-15, după-amiaza)**: asistentul AI e un PPCastor separat (căști + tabletă), Grid Intelligence are PPCastor cu lupă,
  „Agent 01/02” nu mai apar în UI; statisticile din Grid au titluri clare.
- **WhatsApp real (ADR-0008)**: `POST /api/whatsapp/inbound`, client Keycloak `filegrid-whatsapp`, profil compose `tunnel`.
  Pe un laptop cu realm-ul deja importat: `bash scripts/keycloak-whatsapp-client.sh` (o singură dată). Pașii Twilio: README „WhatsApp real”.
  **Testat cu telefon real pe cont Twilio trial**: mesajele primite merg; răspunsurile sunt refuzate de Twilio (eroare 21654,
  trial = doar template-uri). La demo se arată telefon → FileGrid real + răspunsul pe telefonul simulat. Detalii în CHANGELOG.

### Design: skill-ul impeccable
- `PRODUCT.md` (rădăcină) = adevărul despre produs și angajamentele de brand (PPCastor, orașul verde, paleta).
- `.impeccable/surfaces/…village-html.md` = direcția pentru Village; `.impeccable/audit/` = raportul de audit.
- Hook-ul de design e activat (`.impeccable/config.json`); pe alt laptop rulează o dată `/impeccable hooks on` (consimțământul e local).
- **Nefăcut:** recenzia finală de design și `DESIGN.md` (rulează `/impeccable document` după ce designul e aprobat).

### Rămas de făcut (în ordine)
1. Decizii de design deschise: fontul (Inter e semnalat ca generic), poziția mesajului „Decizia Agentului 01” (acoperă barajul castorului),
   verificare pe ecran de telefon; pagini nevăzute încă în tema nouă: Performance, Politici, Decision Explorer, Comunicare teren; panoul asistentului are acum PPCastor, încă neverificat în browser.
2. Parcurgere în browser cu fiecare rol (nu doar `admin.demo`): meniul filtrat, drop refuzat pentru rolurile fără `file:write`.
3. Etapa 17 — benchmark storage + pagina /performance cu date măsurate; apoi integrarea finală din secțiunea 0.2 de mai jos.

### Capcane
- **Disc C: plin** strică build-urile Docker: dacă `intelligence`/`simulator` pică la `COPY --from=base /app/.venv`, cache-ul BuildKit e corupt → `docker builder prune -af`.
- **Gemini 429** = limita pe minut a cheii gratuite. `gemini-3.5-flash-lite` are limite mai mari decât `gemini-3.5-flash`.
- **Realm-ul Keycloak se importă doar la prima pornire**; după modificări în `realm-filegrid.json` vezi README „Autentificare” (reset) sau aplică cu `kcadm`.
- Fișierele Java/TS au CRLF: înlocuirile cu `\n` din scripturi nu se potrivesc; folosește editări exacte.
- Dacă gateway-ul nu vede rute noi: `bash scripts/reload-config.sh`. După schimbări în frontend: `docker compose … up -d --build --no-deps frontend`.
- `uv` nu e instalat local: testele Python se rulează cu `docker build --target test intelligence`
  (sau `intelligence/.venv/Scripts/python -m pytest -q`, dacă `.venv` există).
- Git Bash pe Windows: `docker compose exec … /opt/...` are nevoie de `MSYS_NO_PATHCONV=1`, altfel calea devine o cale Windows (exit 127).
- Heredoc-urile din Git Bash au înjumătățit backslash-urile în fișierele Java: scrie codul cu editorul, nu cu `cat <<EOF`.

---

# Predare anterioară (2026-11-14, noaptea)

## 0. Prompt de lipit în Claude Code pe laptopul nou

```
Continuă proiectul FileGrid PPC (hackathon). Răspunde-mi DOAR în română; cod, identificatori și commit-uri în engleză.
Citește întâi: CLAUDE.md, docs/HANDOFF.md (secțiunea 0 e starea curentă), docs/AUTH-DESIGN.md, docs/PLAN.md,
docs/CHANGELOG-ETAPE.md, docs/COMPLIANCE.md.
Decizii luate (nu le mai întreba): autentificare cu Keycloak OIDC (design în docs/AUTH-DESIGN.md), cele 6 roluri
(field, dispatcher, connections, analyst, incident-manager, admin; doar connections + admin au pii:read),
commit direct pe main la finalul fiecărei etape, push doar când îți cer. Ordinea rămasă e în secțiunea 0.2.
Suntem la hackathon: lucrează rapid, în paralel cu subagenți pe fișiere separate, dar raportează doar ce ai testat efectiv.
Pasul 1: git pull, bash scripts/init-env.sh (completez eu cheia Gemini în fișierul de mediu), .\start.ps1, apoi rulează
`cd backend && ./mvnw -B clean verify` și `cd intelligence && uv run pytest -q` și spune-mi rezultatele.
Pasul 2: continuă cu primul punct neterminat din secțiunea 0.2 a HANDOFF.md.
```

## 0.1 Ce e gata (commit-uit pe `main`)
- Agent 02 nu mai cade la citiri corupte (`7ad2f26`); cele 4 teste backend care picau sunt reparate (`cc2199d`).
- Designul autentificării: `docs/AUTH-DESIGN.md` (`6c19d69`).
- Etapa 20 (parțial): CI GitHub Actions `.github/workflows/ci.yml`, gitleaks (`.gitleaks.toml`, `.pre-commit-config.yaml`;
  scanarea istoricului: fără secrete), ADR-uri 0002–0007, `docs/COMPLIANCE.md`, diagramă Mermaid în README (`5ddbba6`).
  Workflow-ul NU a rulat încă pe GitHub.

## 0.2 Rămas de făcut (în ordine; actualizat la fiecare integrare)
Dacă un punct e „în lucru” și sesiunea s-a oprit, munca neintegrată e pe branch-uri locale `worktree-agent-*`
(doar pe laptopul vechi, NU pe GitHub dacă nu s-a făcut push).
1. Etapa 11 — autentificare: backend + Keycloak; frontend Angular (+ primele teste frontend); intelligence + simulator — în lucru.
2. Etapa 8 — observabilitate (loguri JSON, OpenTelemetry → Tempo, dashboard-uri Grafana, docs/OBSERVABILITY.md) — în lucru.
3. Etapa 18 — Toxiproxy, drill-uri `scripts/drills/`, k6 `load-tests/`, `docs/DEMO.md` — în lucru.
4. Etapa 19 — Kubernetes (`infra/k8s/`, KEDA, `make k8s-up|k8s-storm|k8s-down`) — în lucru.
5. Etapa 17 — benchmark storage + pagina /performance cu date măsurate — neînceput (după Etapa 11).
6. Integrare finală: suita completă backend, pytest, build frontend, `.\start.ps1`, login cap-coadă în browser cu fiecare rol,
   actualizare COMPLIANCE.md și CHANGELOG.

## 0.3 Capcane noi
- Pe laptopul vechi portul 80 e ocupat de IIS: aplicația e doar pe **https://localhost** (scrie explicit `https://`).
- După `git pull` cu rute Traefik noi: `bash scripts/reload-config.sh` (gateway-ul nu vede singur rutele noi).
- Dacă Maven dă „cannot find symbol” pe clase care există: `./mvnw clean verify` (clase vechi în `target/`).
- Worktree-urile agenților stau în `.claude/worktrees/` (ignorat de git).

---

# Istoric: predarea anterioară (2026-11-14, seara)

Document de continuare pe alt laptop. Citește-l împreună cu `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/PLAN.md` și `docs/CHANGELOG-ETAPE.md`.

## 1. Pornire pe laptopul nou

1. `git clone` / `git pull` (după ce modificările de mai jos au fost commit-uite și împinse).
2. `bash scripts/init-env.sh` → generează `.env` cu secrete noi. **`.env` nu este în git**: completează din nou manual:
   - `LLM_PROVIDER=gemini`, `LLM_API_KEY=<cheia Gemini>`, `LLM_MODEL=gemini-3.5-flash-lite`
   - opțional `NOTIFY_ESCALATION_OVERRIDE=2m` pentru demo-ul de escaladare.
   - Atenție: `FILEGRID_KEK` nou ⇒ documentele criptate de pe laptopul vechi nu se pot deschide (datele sunt oricum în volumele Docker locale, nu se mută).
3. `./start.sh` (sau `.\start.ps1`) → toate serviciile `healthy`. Dacă Traefik nu vede rutele: `bash scripts/reload-config.sh`.
4. Deschide https://localhost (certificat self-signed).

## 2. Ce funcționează (verificat live prin gateway)

| Cerință demo | Stare | Cum se arată |
| --- | --- | --- |
| 1. Upload + clasificare | ✅ | drag & drop pe Agent 01 în Control Center (tus reluabil, fără formular) |
| 2. Rutare automată (Agent 01, determinist) | ✅ | Decision Explorer în passport, `/decisions`, `/policies` |
| 3. Stocare + verificare SHA-256 | ✅ | timeline live până la „Stocat și verificat”; „Verifică integritatea” în passport |
| 4. File Passport | ✅ | `/files/:id` |
| 5. Cădere storage + fallback | ✅ | `bash scripts/drills/storage-outage.sh` → PASS (S3 oprit → document pe Database criptat → incident → S3 repornit → incident rezolvat) |
| 6. Detecție anomalii rețea (Agent 02) | ✅ | `/grid`: 18 constatări pe datele simulatorului, precizie/recall 1.0 pe fereastra cu ground truth |
| 7. AI conversațional pe datele aplicației (Agent 03) | ✅ cu Gemini (`gemini-3.5-flash`), fallback automat SIMULATED | `/assistant` sau panoul global |
| 8. Incidente + comunicare operator | ✅ | `/incidents`: WhatsApp simulat, confirmare „OK”, escaladare, rezolvare cu actualizare finală; `/field`: apel vocal simulat, raport dictat din teren |

Extra verificate: criptare documente de racordare (`bash scripts/direct-access-demo.sh` — în S3 doar ciphertext, descărcarea prin FileGrid dă PDF-ul cu SHA-256 identic), alerte Prometheus → Alertmanager → incidente, idempotență contoare (`duplicatesWritten = 0`).

## 3. Ce s-a făcut în această sesiune (necommit-uit la momentul scrierii)

- **Backend (Java):** API-ul Security & Incident Center (`incident/`: controller, hub SSE, automatizare incidente, webhook Alertmanager), texte RO lipsă, fix filtru telefon în mesaje, `GET /api/storage/health`, criptare envelope Tink (`crypto/`, migrația `V7`), integrare în worker și în recuperare. Detalii: `docs/CHANGELOG-ETAPE.md`, `docs/ENCRYPTION.md`.
- **intelligence/ (Python, nou):** Agent 02, asistentul (Anthropic / Gemini / Ollama / SIMULATED), voce simulată. Rețea Docker nouă `egress` doar pentru acest serviciu (acces LLM).
- **frontend/ (Angular, nou):** Control Center (Village SVG + GSAP, KPI, hartă rețea), Upload, Files, Passport + Decision Explorer, Incidents + telefon simulat, Grid Intelligence, Assistant (+ voce în browser), Field, Performance.
- **infra:** servicii `intelligence` și `frontend` în compose, rute Traefik, `business.yml`, receiver Alertmanager, `microphone=(self)` în headerele de securitate (necesar pentru dictare — de aprobat de arhitect).
- **scripts:** `direct-access-demo.sh`, `drills/storage-outage.sh`.

## 4. Teste

- Backend (rulate): `./mvnw verify -Dit.test='EnvelopeEncryptionIT,IncidentCenterIT,RetrievalAndPassportIT,WorkerTransferIT,MultipartUploadIT'` → 23/23 trec. **Suita completă `./mvnw verify` nu a fost rulată după modificări** — de făcut înainte de demo.
- intelligence: `docker build --target test intelligence` → ruff + 38 teste trec.
- frontend: `npm run build` trece; **nu există încă teste automate** (CLAUDE.md le cere).

## 5. Rămas de făcut (în ordinea priorității)

1. Rulează suita completă backend și repară regresiile.
2. Commit + push (mesaj propus mai jos), ca să poți continua pe laptopul nou.
3. Repetiție demo cap-coadă în browser, în ordinea din §2; închide incidentele de test (`INC-2026-000001…000005`) sau pornește cu volume curate (`docker compose down -v` — șterge TOATE datele).
4. Teste frontend minime (câteva unit teste + un smoke Playwright).
5. Etapa 8 rămasă: tracing OpenTelemetry, loguri JSON, dashboard-uri Grafana, `docs/OBSERVABILITY.md`.
6. Etapa 17 (benchmark storage, Performance Overview cu date măsurate), Etapa 18 (Toxiproxy, k6), Etapa 19 (Kubernetes), Etapa 11 (autentificare — se discută).
7. Agent 02: detectorii lipsă (frecvență avarii, corelație spațio-temporală); pgvector pentru RAG (acum BM25).

## 6. Capcane cunoscute

- **Windows:** `python3` e stub-ul Microsoft Store (se blochează) — scripturile folosesc `node` pentru JSON; curl-ul Windows nu scrie în `/tmp` din Git Bash (folosește `cygpath -w`). Fișierele Java au CRLF: editările cu `perl`/`sed` multi-linie pot să nu se aplice.
- **Gemini:** `gemini-2.5-flash` nu mai e disponibil pentru conturi noi (404); `gemini-3.8-flash` dădea 503 (cerere mare). `gemini-3.5-flash` merge, dar pe o cheie gratuită dă 429 după 2–3 întrebări pe minut; `gemini-3.5-flash-lite` a trecut 8/8 cereri la rând (2026-11-15) și e implicit. Dacă LLM-ul cade, asistentul trece automat pe SIMULATED (cu notă în răspuns).
- **Build frontend:** dacă `docker compose up -d --build frontend` eșuează când api-ul repornește, folosește `--no-deps`.
- **Prima analiză Agent 02** după pornire: 45–70 s (încarcă 24 h de citiri).
- `.playwright-mcp/` (capturi de test) este acum în `.gitignore`.

## 7. Mesaj de commit propus

```
feat: incident center API, envelope encryption, intelligence service and Angular control center

- incidents: REST API, SSE, simulated WhatsApp, escalation, automatic incidents, Alertmanager webhook
- stage 7: Tink streaming AEAD for connection documents, per-file DEK wrapped with FILEGRID_KEK
- intelligence: Agent 02 grid anomalies, AI operations assistant (Anthropic/Gemini/Ollama/SIMULATED), simulated field voice
- frontend: control center, upload, passport/decision explorer, incidents, grid, assistant, field
- infra: intelligence/frontend services, egress network, business alert rules, demo drills
```
