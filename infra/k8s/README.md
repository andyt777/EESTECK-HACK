# infra/k8s — FileGrid pe Kubernetes (kind)

Manifeste Kubernetes / Kustomize (Etapa 19). Scopul: **demonstrarea scalării per categorie**.
Docker Compose rămâne calea principală „o singură comandă”.

## Structură

```
infra/k8s/
├── kind/cluster.yaml            # cluster kind cu UN nod (kindest/node v1.36.4, pinned cu digest), porturi host 9080/9443
├── base/                        # manifestele comune
│   ├── kustomization.yaml
│   ├── namespace.yaml           # namespace filegrid
│   ├── volumes.yaml             # PVC staging-data + simulator-output (vezi „Volumul de staging”)
│   ├── storage/                 # StatefulSets + Services: meta-db, store-db, s3 (+ Job s3-init), ftp, rabbitmq
│   ├── app/                     # Deployments: api, worker-meter/-fault/-document/-video, intelligence, frontend, simulator
│   ├── ingress.yaml             # ingress-nginx + TLS (secret filegrid-tls)
│   ├── network-policies.yaml    # storage-urile acceptă trafic doar de la api/workeri
│   └── autoscaling/             # KEDA ScaledObjects (RabbitMQ) + HPA CPU pentru api
├── overlays/kind/               # overlay-ul folosit de make k8s-up (+ TODO Keycloak)
│   └── generated/               # Secrets + ConfigMaps generate din .env (IGNORAT de git)
└── jobs/fault-storm.yaml        # Job-ul de furtună (make k8s-storm), aplicat doar la cerere
```

Scripturi: `scripts/k8s/up.sh`, `storm.sh`, `down.sh`, `generate.sh`.

## Versiuni fixate (verificate pe paginile de release, 2026-11)

| Componentă | Versiune | Notă |
| --- | --- | --- |
| kind | v0.33.0 (recomandat) | imaginea nodului `kindest/node:v1.36.4@sha256:099e…faed` din release notes v0.33.0 |
| ingress-nginx | `controller-v1.15.1` | manifestul `deploy/static/provider/kind/deploy.yaml` |
| metrics-server | v0.9.1 | necesar pentru HPA pe CPU; patch `--kubelet-insecure-tls` (kind) |
| KEDA | 2.21.0 | testat oficial cu Kubernetes 1.34–1.36 → de aceea nodul e 1.36, nu 1.37 |

Imaginile aplicației (`filegrid/*:local`) se construiesc cu `docker compose build` și se copiază în nod cu
`kind load docker-image`; overlay-ul kind pune `imagePullPolicy: Never`. Imaginile terțe (postgres, pgvector, Silo,
pure-ftpd, rabbitmq, busybox) sunt **aceleași** ca în `infra/docker-compose.yml`.

## Secrets și ConfigMaps (niciun secret în git)

`scripts/k8s/generate.sh` citește `.env` (linie cu linie, fără `source`) și scrie în
`infra/k8s/overlays/kind/generated/` (ignorat de git):

- `secrets.yaml`: `backend-secrets`, `api-notify-secrets`, `intelligence-secrets`, `meta-db-secret`, `store-db-secret`,
  `s3-secret`, `ftp-secret`, `rabbitmq-secret`, `keda-rabbitmq` (connection string AMQP pentru KEDA) și `filegrid-tls`
  (din `infra/certs/`, creat de `scripts/gen-certs.sh` dacă lipsește).
- `configmaps.yaml`: `filegrid-config` (policies.yaml, notification-routing.yaml), `filegrid-templates`, `filegrid-docs`
  (`docs/*.md`, fără PDF – limita de 1 MiB), `simulator-topology`, `rabbitmq-config`, `s3-init` (din `infra/minio/`),
  `intelligence-llm` (provider/model LLM din `.env`).

Sunt generate din aceleași fișiere pe care le montează Compose, deci există o singură sursă de adevăr. Nu sunt în
kustomization pentru că Kustomize nu poate citi fișiere din afara directorului său (load restrictor) și pentru ca
`.env` să nu ajungă niciodată într-un manifest randat. Fiecare pod primește doar secretele de care are nevoie
(ex.: doar `s3` și `s3-init` văd credențialele root ale S3).

## Volumul de staging (kind cu un nod)

API-ul scrie fișierele în `/data/staging`, iar workerii le citesc de acolo (A.2). În Compose e un volum Docker
partajat; aici e PVC-ul `staging-data`, `ReadWriteOnce`, din StorageClass-ul implicit `standard` al kind
(local-path-provisioner = un director pe nod).

- `ReadWriteOnce` înseamnă „montat de pe **un singur nod**”, nu „de un singur pod”: toate pod-urile de pe același nod îl
  pot monta simultan. De aceea clusterul kind are **exact un nod**.
- Un init container al api-ului (`busybox`, root, doar `chown 10001` + `chmod 770`) dă directorul utilizatorului
  backend-ului (uid 10001 din `backend/Dockerfile`).
- Pe un cluster cu mai multe noduri trebuie un volum `ReadWriteMany` (NFS, CephFS, EFS, Azure Files…) – în rest
  manifestele rămân la fel. Același lucru pentru `simulator-output` (scris de simulator, citit de intelligence).

## Rețea

- **Ingress** (ingress-nginx, TLS): `/` → frontend, `/api` → api, `/api/intelligence` → intelligence,
  `/api/actuator/health` → api:8081 (singurul endpoint actuator expus, ca în Traefik). Upload fără limită de body,
  fără buffering (tus + SSE). `/grafana` nu există (observabilitatea nu e instalată în k8s).
- **NetworkPolicies**: `store-db`, `s3`, `ftp`, `meta-db` și `rabbitmq` acceptă conexiuni doar de la pod-urile cu
  `filegrid.io/backend=true` (api + workeri). Excepții, la fel ca în Compose: `intelligence` citește `meta-db` și
  `store-db` (Agent 02 analizează citirile contoarelor), Job-ul `s3-init` ajunge la `s3`, iar operatorul KEDA
  (namespace `keda`) ajunge la RabbitMQ pentru adâncimea cozilor. kind aplică NetworkPolicy nativ din v0.24.
- `enableServiceLinks: false` pe toate pod-urile: altfel Kubernetes injectează `RABBITMQ_PORT=tcp://…` și
  `FTP_PORT=tcp://…`, pe care Spring le-ar citi drept porturi.
- FTP pasiv: `PUBLICHOST` = IP-ul pod-ului, Service headless, porturile 30000–30099 permise de NetworkPolicy.

## Scalare

- **KEDA**: câte un `ScaledObject` per worker, scaler `rabbitmq` (AMQP, `mode: QueueLength`) pe coada proprie
  `filegrid.jobs.<categorie>`. `worker-fault`: 1→4 replici, țintă 5 mesaje în așteptare per replică,
  `pollingInterval: 5`, `cooldownPeriod: 60`. Ceilalți workeri au praguri mai mari / max mai mic.
- **HPA pe CPU** pentru api (1→3, țintă 85%, scale-up amortizat 120 s) – pragul e intenționat sus, ca furtuna de avarii
  să nu scaleze api-ul în demo.

## Comenzi

Cu `make` (Git Bash/WSL cu make instalat):

```bash
make k8s-up       # cluster + ingress-nginx + metrics-server + KEDA, build/load imagini, apply
make k8s-storm    # furtuna de avarii; în alt terminal: kubectl -n filegrid get pods -w
make k8s-status
make k8s-down
```

Fără `make` (Windows): aceleași scripturi, din **Git Bash**:

```bash
bash scripts/k8s/up.sh
bash scripts/k8s/storm.sh
bash scripts/k8s/down.sh
```

Din **PowerShell** (Git Bash trebuie să fie instalat; `bash` din PATH să fie cel din Git, nu WSL):

```powershell
& "C:\Program Files\Git\bin\bash.exe" scripts/k8s/up.sh
& "C:\Program Files\Git\bin\bash.exe" scripts/k8s/storm.sh
kubectl --context kind-filegrid -n filegrid get pods -w
& "C:\Program Files\Git\bin\bash.exe" scripts/k8s/down.sh
```

Variabile utile: `K8S_SKIP_BUILD=1` (nu reconstruiește imaginile), `STORM_WATCH_SECONDS=300`, `K8S_CLUSTER=alt-nume`.
Unelte: kubectl vine cu Docker Desktop; kind se instalează după https://kind.sigs.k8s.io/docs/user/quick-start/#installation
(binar `kind.exe` pus în PATH). Helm nu este necesar (toate componentele se instalează din manifeste fixate).

## Memorie

Limitele însumate ale aplicației sunt ~4,4 GiB (aceleași ca în Compose), plus planul de control kind, ingress-nginx și
KEDA (~1 GiB), plus până la 3 replici suplimentare de worker-fault (448 MiB fiecare) în timpul furtunii. **Oprește
stack-ul Compose (`docker compose down`, volumele rămân) înainte de `make k8s-up`** dacă Docker Desktop are sub ~10 GiB.

## Limitări

- **Neverificat pe un cluster real** la momentul scrierii (validat doar offline cu `kubectl kustomize`); vezi
  docs/CHANGELOG-ETAPE.md, Etapa 19.
- Observabilitatea (kube-prometheus-stack, Loki, Tempo) **nu** este instalată în k8s; metricile/logurile/trace-urile
  rămân în Compose.
- Setările de tuning din `.env` (JVM, pool-uri, SIM_*) nu sunt preluate: manifestele folosesc valorile implicite din
  Compose. Doar secretele și alegerea LLM vin din `.env`.
- Keycloak: TODO în `overlays/kind/kustomization.yaml`, se adaugă după ce configurația din Compose este finală.
- Toxiproxy și drill-urile de haos nu sunt portate în k8s.
