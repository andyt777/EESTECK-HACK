# FileGrid PPC

Punctul unic de intrare pentru fișierele operaționale ale unui operator de distribuție a energiei electrice: citiri de contoare, înregistrări de avarie, documente de racordare și video-uri de inspecție cu drona. FileGrid decide, pe baza unei politici per categorie, în ce storage (FTP, S3 sau Database) ajunge fiecare fișier, îi verifică integritatea și măsoară tot ce se întâmplă.

Arhitectura este descrisă în [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), iar progresul pe etape în [docs/CHANGELOG-ETAPE.md](docs/CHANGELOG-ETAPE.md).

> **Stadiu:** Etapa 2 — infrastructura Docker plus backend-ul (API și workeri) cu modelul de date și cele trei adaptoare de storage. Upload-ul, rutarea, frontend-ul și simulatorul vin în etapele următoare.

## Cerințe preliminare

| Unealtă | Obligatoriu | Note |
| --- | --- | --- |
| Docker Desktop (sau Docker Engine + Compose v2) | da | **Docker Engine 29 sau mai nou**; minimum 6 GB RAM alocați (mod lite), 8 GB recomandat (mod full); vezi „Resurse” |
| Git | da | pe Windows include și Git Bash |
| mkcert | nu | dacă există, certificatul local e de încredere în browser (`mkcert -install` o singură dată); altfel se generează unul self-signed |
| make | nu | doar pentru scurtăturile din `Makefile` |
| JDK 21 | doar pentru teste | necesar pentru `./mvnw verify`; pornirea sistemului nu are nevoie de el (backend-ul se compilează în Docker) |

Testele de integrare folosesc Testcontainers, fixat la versiunea 1.21.4 — prima care poate vorbi cu Docker Engine 29 (care acceptă doar API 1.44+). De aici cerința de versiune pentru Docker.

### Windows: unde ții proiectul

Recomandat: clonează repository-ul în sistemul de fișiere WSL (de exemplu `~/filegrid`, dintr-un terminal Ubuntu) și deschide-l cu VS Code prin extensia **Remote - WSL**. Docker Desktop rulează containerele în WSL, iar un proiect ținut pe un disc Windows (`C:\`, `D:\`) ajunge în containere printr-un strat de traducere între cele două sisteme de fișiere. Consecințe pe discul Windows:

- notificările de modificare a fișierelor nu ajung în containere, deci serviciile nu observă singure o configurație schimbată (de aceea există `scripts/reload-config.sh`);
- accesul la fișiere este mult mai lent (build-uri, `node_modules`);
- terminațiile de linie și drepturile de execuție ale scripturilor se pot strica.

Proiectul funcționează și de pe un disc Windows; în acest caz, evită folderele sincronizate (OneDrive).

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

Scriptul de pornire oprește workerii celuilalt mod, deci poți trece oricând dintr-unul în altul rulându-l din nou. Comenzile `docker compose` date de mână folosesc modul din `COMPOSE_PROFILES` (fișierul de mediu), implicit `full`.

## Resurse

| Mod | Memorie alocată lui Docker |
| --- | --- |
| lite | minimum 6 GB |
| full + demo | 8 GB recomandat |

Fiecare serviciu are o limită de memorie în `infra/docker-compose.yml` (`deploy.resources.limits.memory`). Pentru backend, limita și dimensiunile JVM sunt explicite și se schimbă din fișierul de mediu: `API_MEMORY_LIMIT` / `API_JAVA_OPTS`, `WORKER_MEMORY_LIMIT` / `WORKER_JAVA_OPTS`, `WORKER_VIDEO_MEMORY_LIMIT` / `WORKER_VIDEO_JAVA_OPTS`. Numărul de transferuri simultane per worker se schimbă cu `WORKER_CONCURRENCY` și `WORKER_VIDEO_CONCURRENCY` (implicit 2 pentru video). Consumul real față de limită, plus verificarea că niciun container nu a fost oprit din lipsă de memorie (OOMKilled):

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
| Starea API-ului | https://localhost/api/actuator/health | — (răspunde `{"status":"UP"}`) |

Gateway-ul (Traefik) este singurul serviciu care publică porturi. HTTP este redirecționat către HTTPS, iar TLS minim este 1.2. Din actuator este expusă în exterior doar starea; metricile (`/actuator/prometheus`) rulează pe un port separat, accesibil numai din rețeaua internă.

După o modificare în `infra/traefik/` sau `infra/prometheus/` cu sistemul pornit, rulează `bash scripts/reload-config.sh` (sau `make reload-config`): Prometheus își reîncarcă pe loc configurația și regulile, iar gateway-ul este repornit. Comanda nu depinde de notificările sistemului de fișiere, deci merge la fel pe orice platformă.

## Teste

```bash
cd backend
./mvnw verify
```

Rulează testele unitare și testele de integrare (Testcontainers: PostgreSQL, S3, FTP reale, pornite automat în Docker). Testele rulează cu heap de 256 MB, iar adaptoarele S3 și FTP fac round-trip cu un fișier de 300 MB: așa se demonstrează că fișierele sunt procesate în flux, nu încărcate în memorie.

## Comenzi utile

Toate se rulează din rădăcina repository-ului.

| Comandă | Echivalent `make` | Ce face |
| --- | --- | --- |
| `docker compose ps -a` | `make ps` | starea și sănătatea serviciilor |
| `docker compose logs -f gateway` | `make logs s=gateway` | loguri |
| `docker compose down` | `make down` | oprește serviciile, păstrează datele |
| `bash scripts/mem-report.sh` | `make mem` | memorie folosită per serviciu față de limită |
| `bash scripts/reload-config.sh` | `make reload-config` | aplică modificările din `infra/` (Prometheus, gateway) |
| `docker compose down -v` | `make reset` | oprește și **șterge toate datele** (`make reset` cere confirmare) |

## Servicii

| Serviciu | Rol | Rețea |
| --- | --- | --- |
| `gateway` | Traefik v3 — TLS, rutare, headere de securitate | `edge` + `internal`, porturi publicate |
| `api` | Spring Boot (profil `api`) — API-ul FileGrid, verificarea periodică a storage-urilor | `edge` + `internal` |
| `worker-meter`, `worker-fault`, `worker-document`, `worker-video` | aceeași imagine, profil `worker`, câte unul per categorie (mod full); `worker-all` în mod lite | `internal` |
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
