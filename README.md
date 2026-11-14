# FileGrid PPC

Punctul unic de intrare pentru fișierele operaționale ale unui operator de distribuție a energiei electrice: citiri de contoare, înregistrări de avarie, documente de racordare și video-uri de inspecție cu drona. FileGrid decide, pe baza unei politici per categorie, în ce storage (FTP, S3 sau Database) ajunge fiecare fișier, îi verifică integritatea și măsoară tot ce se întâmplă.

Arhitectura este descrisă în [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), iar progresul pe etape în [docs/CHANGELOG-ETAPE.md](docs/CHANGELOG-ETAPE.md).

> **Stadiu:** Etapa 1 — infrastructura Docker. Serviciile aplicative (API, workeri, frontend, simulator) vin în etapele următoare.

## Cerințe preliminare

| Unealtă | Obligatoriu | Note |
| --- | --- | --- |
| Docker Desktop (sau Docker Engine + Compose v2) | da | minimum 6 GB RAM alocați (mod lite), 8 GB recomandat (mod full); vezi „Resurse” |
| Git | da | pe Windows include și Git Bash |
| mkcert | nu | dacă există, certificatul local e de încredere în browser (`mkcert -install` o singură dată); altfel se generează unul self-signed |
| make | nu | doar pentru scurtăturile din `Makefile` |

## Pornire cu o singură comandă

```bash
./start.sh          # Linux, macOS, Git Bash, WSL
```

```powershell
.\start.ps1         # Windows PowerShell (nu are nevoie de bash)
```

Scriptul face, în ordine:

1. `scripts/init-env.sh` — creează `.env` din `.env.example`, cu parole aleatorii puternice. Un `.env` existent nu este suprascris niciodată; se adaugă doar variabilele care lipsesc.
2. `scripts/gen-certs.sh` — generează certificatul TLS local în `infra/certs/` (mkcert dacă există, altfel openssl self-signed).
3. `docker compose up -d --build`
4. așteaptă până când toate serviciile sunt `healthy`, apoi afișează URL-urile.

Gateway-ul publică HTTPS pe 443 și HTTP pe 8080 (HTTP doar redirecționează către HTTPS; 8080 este implicit pentru că portul 80 e adesea ocupat pe Windows). Dacă un port este deja folosit pe mașină, schimbă `GATEWAY_HTTP_PORT` / `GATEWAY_HTTPS_PORT` în `.env` și rulează din nou scriptul.

### Mod full și mod lite

| Mod | Comandă | Workeri |
| --- | --- | --- |
| full (implicit) | `./start.sh`, `.\start.ps1`, `make up` | câte un worker per categorie (necesar pentru demo-ul de bulkhead) |
| lite | `./start.sh --lite`, `.\start.ps1 --lite`, `make up-lite` | un singur `worker-all`, pentru toate categoriile |

Modul implicit este dat de `COMPOSE_PROFILES` din `.env`. Workerii apar din Etapa 2; până atunci cele două moduri pornesc aceleași servicii.

## Resurse

| Mod | Memorie alocată lui Docker |
| --- | --- |
| lite | minimum 6 GB |
| full + demo | 8 GB recomandat |

Fiecare serviciu are o limită de memorie în `infra/docker-compose.yml` (`deploy.resources.limits.memory`). Consumul real față de limită, plus verificarea că niciun container nu a fost oprit din lipsă de memorie (OOMKilled):

```bash
bash scripts/mem-report.sh      # sau: make mem
```

Postgres (`meta-db`, `store-db`) pornește cu `shared_buffers=64MB`, `max_connections=50`, `work_mem=4MB`; valorile se schimbă din `.env` (`PG_SHARED_BUFFERS`, `PG_MAX_CONNECTIONS`, `PG_WORK_MEM`).

**Cum mărești memoria pe Windows (Docker Desktop cu WSL2):** creează sau editează fișierul `C:\Users\<utilizator>\.wslconfig`:

```ini
[wsl2]
memory=8GB
processors=4
```

apoi rulează `wsl --shutdown` într-un terminal și repornește Docker Desktop. Verificare: `docker info --format "{{.MemTotal}}"`.

## URL-uri

| Ce | Adresă | Autentificare |
| --- | --- | --- |
| Grafana | https://localhost/grafana | `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` din `.env` |

Gateway-ul (Traefik) este singurul serviciu care publică porturi. HTTP este redirecționat către HTTPS, iar TLS minim este 1.2.

## Comenzi utile

Toate se rulează din rădăcina repository-ului.

| Comandă | Echivalent `make` | Ce face |
| --- | --- | --- |
| `docker compose ps -a` | `make ps` | starea și sănătatea serviciilor |
| `docker compose logs -f gateway` | `make logs s=gateway` | loguri |
| `docker compose down` | `make down` | oprește serviciile, păstrează datele |
| `bash scripts/mem-report.sh` | `make mem` | memorie folosită per serviciu față de limită |
| `docker compose down -v` | `make reset` | oprește și **șterge toate datele** (`make reset` cere confirmare) |

## Servicii (Etapa 1)

| Serviciu | Rol | Rețea |
| --- | --- | --- |
| `gateway` | Traefik v3 — TLS, rutare, headere de securitate | `edge` + `internal`, porturi publicate |
| `meta-db` | PostgreSQL 16 + pgvector — metadate | `internal` |
| `store-db` | PostgreSQL 16 separat — storage-ul „Database” | `internal` |
| `s3` (+ `minio-init`) | Silo (fork comunitar MinIO, vezi [ADR-0001](docs/ADR/0001-s3-storage-silo-fork.md)) — storage-ul „S3”; bucket-uri versionate și user de serviciu dedicat | `internal` |
| `ftp` | pure-ftpd — storage-ul „FTP”, mod pasiv | `internal` |
| `rabbitmq` | cozi de mesaje | `internal` |
| `prometheus`, `alertmanager`, `grafana`, `loki`, `tempo`, `alloy` | metrici, alerte, dashboard-uri, loguri, trace-uri | `internal` |
| `toxiproxy` | simularea unei rețele degradate (configurat în Etapa 18) | `internal` |

Rețeaua `internal` este izolată (`internal: true`): storage-urile nu publică niciun port și nu pot fi accesate din afara Docker. Singura cale de acces la ele este FileGrid.

## Secrete

Niciun secret nu se află în repository. `.env` și `infra/certs/` sunt ignorate de git; `.env.example` conține doar placeholdere.

## Structura repository-ului

```
├── compose.yaml           # punct de intrare; include infra/docker-compose.yml
├── start.sh / start.ps1 / Makefile
├── .env.example
├── config/                # politici și template-uri (montate în containere)
├── backend/               # Spring Boot (api + worker)
├── frontend/              # Angular
├── intelligence/          # FastAPI: Agent 02 + AI Assistant
├── simulator/             # contoare simulate
├── infra/                 # docker-compose.yml + configurația fiecărui serviciu
├── load-tests/            # k6
├── scripts/               # init-env, gen-certs, wait-healthy, ...
└── docs/                  # cerința, experiența, arhitectura, planul
```
