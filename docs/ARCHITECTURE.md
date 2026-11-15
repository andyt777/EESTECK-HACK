# FileGrid PPC — Arhitectura

> Copiată exact din secțiunea „Arhitectura” din docs/PLAN.md. NU se schimbă fără aprobarea arhitectului.

### A.1 Componente

| Componentă | Tehnologie | Rol |
| --- | --- | --- |
| gateway | Traefik v3 | singurul punct expus în exterior; TLS, rutare `/`, `/api`, `/auth`, `/grafana` |
| keycloak | Keycloak 26 (realm `filegrid`, baza `keycloak` în meta-db) | autentificare OIDC: login code + PKCE pentru UI, client credentials pentru simulator, intelligence și Alertmanager; 6 roluri (`docs/AUTH-DESIGN.md`, ADR-0007) |
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
| tunnel (opțional) | Cloudflare quick tunnel, profil `tunnel` | URL public temporar doar pentru webhook-ul Twilio `POST /api/whatsapp/inbound` (PPCastor pe WhatsApp, ADR-0008) |

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

WhatsApp (echipa de teren) ──Twilio──▶ tunnel ──▶ gateway :8088 (doar POST /api/whatsapp/inbound) ──▶ api
                                             │ semnătura Twilio verificată, expeditor ∈ NOTIFY_CONTACT_*, MessageSid o singură dată
                                             │ OK → confirmă alerta · întrebare → asistent (intelligence, token svc-whatsapp)
                                             │ raport text/vocal/poză → propunere → „DA” → incident (poza = fișier FileGrid REAL)
                                             ▼
                                   răspunsul lui PPCastor ──Twilio API──▶ WhatsApp
```
(ADR-0008, aprobat de arhitect pe 2026-11-15.)

### A.3 Principii care nu se negociază

1. **Agent 01 este determinist**, nu un LLM. El rulează un motor de politici cu scorare. Decizia (criterii, scoruri, alternative și motivele respingerii) se salvează înainte de transfer, deci Decision Explorer arată motivele reale.
2. **Politica este configurabilă fără cod**: `config/policies.yaml` montat în container, cu override-uri versionate în DB, editabile din UI-ul de admin. Se reîncarcă fără restart.
3. **Streaming peste tot**: niciun fișier nu se încarcă întreg în memorie. Asta acoperă cerința ca fișierele de peste 1 GB să nu crească resursele proporțional.
4. **„Stocat și verificat” apare doar după read-back și compararea hash-ului SHA-256.**
5. **Idempotență pentru contoare**: `readingId = SHA-256(meterId|periodStart)` + constrângere UNIQUE + ack trimis doar după commit. Nu se pierd și nu se dublează citiri.
6. **Bulkhead**: coadă și workeri separați pe fiecare categorie. O furtună de avarii nu încetinește contoarele.
7. **Staging + outbox**: un fișier primit nu se pierde niciodată. Dacă storage-ul e căzut, fișierul trece în PENDING și este reluat automat.
8. **Date personale**: documentele de racordare sunt criptate la nivel de aplicație (envelope encryption cu AES-256-GCM, Google Tink streaming AEAD) înainte de a ajunge în storage. Un acces direct la storage vede doar ciphertext.
9. **Storage-urile nu publică porturi**: stau doar în rețeaua internă, iar singura cale de acces este FileGrid. Tunelul opțional pentru WhatsApp expune doar webhook-ul Twilio, nimic altceva (ADR-0008).
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
