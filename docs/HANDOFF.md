# Predare — FileGrid PPC

## ⏩ STARE CURENTĂ (2026-11-15) — citește întâi asta

Nimic din ce urmează NU e commit-uit încă. Pe laptopul vechi: `git add -A && git commit` (mesajele propuse sunt mai jos), apoi push.

### A. Redesign UI — GATA și verificat în browser
Village pe tot ecranul, paletă deep blue/alb/roz bubblegum, titluri italice, case reale, robotul merge la casă și trage firul spre storage,
încărcare DOAR prin drag & drop pe Agent 01 (fără formular), mesaj „Decizia Agentului 01” după upload, File Explorer șters
(click pe casă → `/categories/:category`, Ctrl+K rămâne), sidebar ascuns (☰) cu Decision Explorer și Politici (editabile din UI).
Detalii: `docs/CHANGELOG-ETAPE.md` (prima secțiune), `docs/experienta-utilizator.md` actualizat.
Commit propus: `feat(frontend): full-screen village, drop-on-agent upload, hidden sidebar`.

### B. Etapa 11 — autentificare — GATA (backend, frontend, verificat cap-coadă prin gateway)
Detalii și cum se verifică: `docs/CHANGELOG-ETAPE.md` (prima secțiune), README „Autentificare” (utilizatori demo, reset realm).
- Suita backend completă: 102 teste au rulat, iar singurul eșec (`MetadataSchemaIT`) a fost reparat. Teste noi: `RolePermissionsTest`, `ContentLinkSignerTest`, `AuthenticationIT`. Frontend: build + 4 teste vitest.
- Live: 401 fără token, simulatorul cu token (`duplicatesWritten = 0`), alertă Alertmanager → incident, login code + PKCE cu toți cei 6 utilizatori demo, PII doar pentru connections/admin.
- Reparat pe parcurs: utilizatorii demo n-aveau email → Keycloak 26 cerea VERIFY_PROFILE la login (realm actualizat + aplicat live cu kcadm);
  cache-ul BuildKit era corupt după ce discul C: s-a umplut (binarul `uv` avea 0 octeți) → `docker builder prune -af`.

Rămas:
1. Parcurgere în browser real cu fiecare rol: redirect la login, sidebar (utilizator, rol, logout), meniul filtrat, SSE live (punctul verde),
   drop pe Agent 01 (connections/admin/field), preview + descărcare în passport.
2. Commit: `feat(auth): keycloak oidc, jwt resource server, role permissions, signed content links` și `feat(frontend): oidc login, authz service, sse over fetch`.
3. Apoi următorul punct din 0.2 (Etapa 17 — benchmark storage).

Capcane: fișierele Java/TS au CRLF — înlocuirile PowerShell cu `` `n `` nu se aplică, folosește editări exacte; dacă gateway-ul nu vede
rutele noi: `bash scripts/reload-config.sh`; `.env` existent primește variabilele noi automat la `.\start.ps1` (doar cele lipsă).

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
