# infra/toxiproxy

Proxy-urile Toxiproxy pentru rețea degradată (Etapa 18), definite în `toxiproxy.json`.
Serverul le încarcă la pornire (`-config=/etc/toxiproxy/toxiproxy.json`); `bash scripts/chaos/setup.sh`
le creează/actualizează și fără restart (`POST /populate`, idempotent).

| Proxy | Ascultă (rețeaua `internal`) | Upstream | Cine îl folosește |
| --- | --- | --- | --- |
| `degraded-edge` | `toxiproxy:18080` | `api:8080` | clienții de test: k6 (`BASE_URL=http://toxiproxy:18080/api`), `scripts/upload-cli.py` |
| `s3` | `toxiproxy:19000` | `s3:9000` | workerii, **opt-in**: `WORKER_S3_ENDPOINT=http://toxiproxy:19000` |
| `store-db` | `toxiproxy:15432` | `store-db:5432` | workerii, **opt-in**: `WORKER_STORE_DB_URL=jdbc:postgresql://toxiproxy:15432/filegrid_store` |
| `ftp-control` | `toxiproxy:2121` | `ftp:21` | doar canalul de control FTP — vezi limitarea de mai jos |

Implicit workerii vorbesc direct cu storage-urile (comportament neschimbat). Toxicele puse pe `s3` /
`store-db` au efect asupra workerilor doar după opt-in: variabilele de mai sus în fișierul de mediu din
rădăcină, apoi `docker compose up -d` (recreează workerii). Fără opt-in, „storage căzut” se simulează cu
`docker compose stop <storage>` (drill-urile `storage-outage.sh` și `ftp-outage.sh`).

**Limitare FTP:** pure-ftpd lucrează în mod pasiv și anunță `PUBLICHOST=ftp`, deci conexiunile de date ocolesc
proxy-ul, iar pure-ftpd poate refuza o conexiune de date care vine de la alt IP decât cea de control. De aceea
workerii NU sunt legați de `ftp-control` (nu există opt-in pentru FTP).

Comenzi: `scripts/chaos/*.sh` (vezi `scripts/chaos/README.md`). Totul se curăță cu `bash scripts/chaos/reset.sh`.
