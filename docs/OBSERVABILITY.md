# Observabilitate — cum se demonstrează live

Etapa 8. Pentru fiecare cerință de observabilitate din cerință (§4) și pentru tabelul de business (§2.1): ce dashboard,
ce query și ce trace o arată. Totul rulează local în Docker Compose; Grafana e la **https://localhost/grafana**
(`GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` din `.env`), folderul **FileGrid**.

## Cum circulă datele

```
api, worker-*  ── metrici Micrometer (/actuator/prometheus :8081) ──▶ Prometheus ──▶ reguli business.yml ──▶ Alertmanager ──▶ api (incidente)
api, worker-*  ── trace-uri (agent OpenTelemetry Java, OTLP) ──┐
simulator      ── trace-uri (SDK OpenTelemetry Python, OTLP) ──┴──▶ Alloy ──▶ Tempo
toate          ── loguri pe stdout (backend: JSON) ──▶ Alloy ──▶ Loki
Grafana: Loki ⇄ Tempo (traceId în log → trace; span → logurile aceluiași trace)
```

- **Metrici** (`backend/.../observability/FileGridMetrics.java`, `QueueMetrics.java`), tag-uri `category`, `storage`, `outcome`:
  - `filegrid_store_duration_seconds` — scriere + read-back + verificare SHA-256 (histogramă → p50/p95/p99);
  - `filegrid_retrieve_duration_seconds` — citirea unui fișier stocat;
  - `filegrid_operations_total{operation=store|retrieve, outcome=verified|mismatch|error|ineligible|success}`;
  - `filegrid_bytes_total`, `filegrid_fallback_total{storage→to}`;
  - `filegrid_queue_depth`, `filegrid_queue_consumers`, `filegrid_pending_files` per categorie;
  - existente: `filegrid_storage_up`, `filegrid_integrity_mismatch_total`, `filegrid_meter_*`, `filegrid_pii_stored_unencrypted_total`,
    `filegrid_access_denied_total{permission}`.
- **Trace-uri**: agentul OpenTelemetry Java 2.33.1 (în imaginea backend, verificat cu SHA-256) instrumentează HTTP, RabbitMQ
  (antetul `traceparent` trece prin coadă: api → worker), JDBC și AWS SDK (S3). Span-uri manuale ale Agentului 01:
  `classify`, `route`, `encrypt`, `storage.put`, `storage.verify`. Simulatorul trimite un span `simulator.send_batch` per lot,
  cu `traceparent` în cerere, deci trace-ul începe la contor.
- **Loguri**: backend-ul scrie JSON (format Logstash) cu `traceId`, `spanId`, `fileId`, `category`, `storage`.
  `traceId`-ul din File Passport e chiar trace-ul OpenTelemetry al upload-ului.

## Cerințe și dovezi

| Cerință | Unde se vede | Query / pași |
| --- | --- | --- |
| Latență p50/p95/p99 per categorie × storage | dashboard **Latență, throughput și erori** (`/grafana/d/filegrid-latency`) | `histogram_quantile(0.95, sum by (le, category, storage) (rate(filegrid_store_duration_seconds_bucket{outcome="verified"}[5m])))` |
| Throughput și rată de erori per categorie × storage | același dashboard: „Operații / s”, „Octeți / s”, „Rata erorilor” | `sum by (category, storage) (rate(filegrid_operations_total{outcome!="verified"}[5m])) / sum by (category, storage) (rate(filegrid_operations_total[5m]))` |
| O operație urmărită de la cerere până la storage | Grafana → Explore → **Tempo** | TraceQL `{ name = "storage.put" }` sau `{ resource.service.name = "simulator" }`; trace-ul trece prin `api` (POST/PATCH) → `filegrid.jobs publish` → `worker-*` (`filegrid.jobs.<categorie> process`) → `storage.put` / `storage.verify` (S3: span-urile AWS SDK `PutObject`/`GetObject`) |
| Din trace la loguri și invers | Tempo: butonul „Logs for this span”; Loki: link-ul „Deschide trace-ul în Tempo” lângă `traceId` | Loki: `{service="worker-document"} \|= "<traceId>"` |
| Izolare între categorii (bulkhead) | dashboard **Cozi și workeri per categorie** (`/grafana/d/filegrid-queues`) | coada și p95 per categorie; o furtună pe `FAULT_RECORD` nu mută p95 pentru `METER_READING` |
| Citiri contoare: fără pierderi, fără dubluri | dashboard **Citiri contoare** (`/grafana/d/filegrid-meters`) | `filegrid_meter_duplicates_written_total` (trebuie 0), `increase(filegrid_meter_gaps_total[10m])` |
| Starea storage-urilor | dashboard **Starea storage-urilor** (`/grafana/d/filegrid-storage`) | `filegrid_storage_up`, erori de scriere și fallback-uri per storage |
| Date personale protejate | dashboard **Securitate și PII** (`/grafana/d/filegrid-security`) | `filegrid_pii_stored_unencrypted_total` (trebuie 0), refuzuri `pii:read`, jurnal Loki |
| Echipa anunțată automat când o cerință de business e în pericol | Alertmanager → `POST /api/incidents/alertmanager` → incident în `/incidents` + notificare | reguli în `infra/prometheus/rules/business.yml` (mai jos) |

## Alerte de business (`infra/prometheus/rules/business.yml`)

Fiecare alertă are `severity`, `category`, `requirement` (secțiunea din cerință) și `incident_type`; adnotările sunt în română.

| Alertă | Cerință | Declanșare |
| --- | --- | --- |
| `StorageDown` | §2.1 disponibilitate | `filegrid_storage_up == 0` timp de 30 s |
| `IntegrityMismatch` | §2.1 integritate | o neconcordanță SHA-256 în ultimele 5 min |
| `MeterReadingsAtRisk` | §2.1 citiri contoare | dubluri scrise > 0 sau > 50 goluri noi în 10 min; separat: coada meter > 5000 timp de 5 min |
| `FaultRecordsNotImmediatelyAvailable` | §2.1 înregistrări de avarie | p95 stocare avarii > 5 s sau avarii PENDING, timp de 2 min |
| `DroneTransfersFailing` | §2.1 video-uri dronă | > 5% încercări video eșuate în 15 min sau > 5 fallback-uri video |
| `PersonalDataAtRisk` | §4 securitate | document PII stocat necriptat; separat: > 10 refuzuri `pii:read` în 10 min |

Pragurile pentru avarii (5 s, 2 min) copiază `sla` din `config/policies.yaml` (`FAULT_RECORD`): Prometheus nu citește
politica, deci la o schimbare de SLA se actualizează ambele.

## Demonstrații verificate (2026-11-15)

1. **Trace cap-coadă**: simulator → `POST /api/meters/readings` (api) → `filegrid.jobs publish` (RabbitMQ) →
   `filegrid.jobs.meter process` (worker-meter), într-un singur trace (Tempo, `rootServiceName=simulator`).
2. **Alertă de storage**: `docker compose stop ftp` → `StorageDown{storage="FTP"}` activă în Alertmanager după **79 s** →
   incident `STORAGE_UNAVAILABLE` deschis automat → `docker compose start ftp` → incidentul se rezolvă.
3. **Latență**: p95 de stocare pentru `METER_READING × DATABASE` disponibil în Prometheus și în dashboard.
4. **Loguri**: liniile din Loki sunt JSON și conțin `traceId`/`spanId`.

Pentru un trace prin **S3**: încarcă un document de racordare (casa *Documente de racordare*) și caută în Tempo
`{ resource.service.name = "worker-document" && name = "storage.put" }`.

## Costuri și reglaje

- Agentul OpenTelemetry adaugă ~60 MiB per JVM (în principal metaspace). De aceea `MaxMetaspaceSize=192m` și limitele
  `API_MEMORY_LIMIT=704M`, `WORKER_MEMORY_LIMIT=576M`, `WORKER_VIDEO_MEMORY_LIMIT=640M` (`.env.example`). Cu 128m, api-ul dădea
  `OutOfMemoryError: Metaspace` deși containerul raporta „healthy”.
- Oprire: `OTEL_JAVAAGENT_ENABLED=false` (backend), `OTEL_SDK_DISABLED=true` (simulator). În Kubernetes (kind) agentul e oprit
  (`infra/k8s/base/app/backend-env.yaml`): acolo nu există Alloy/Tempo.
- Eșantionare: `OTEL_TRACES_SAMPLER` (implicit toate trace-urile). Un lot de contoare produce un trace cu ~750 de span-uri
  (un mesaj RabbitMQ per citire); la volume mari se poate trece pe `parentbased_traceidratio`.

## Limitări cunoscute

- `filegrid_fallback_total` și `filegrid_retrieve_duration_seconds` apar în Prometheus abia după primul fallback, respectiv
  prima citire a unui fișier; până atunci panourile lor arată „No data”.
- Intelligence (Python) nu e încă instrumentat cu OpenTelemetry; logurile lui ajung în Loki, dar nu ca JSON.
- Observabilitatea nu e instalată în Kubernetes (doar în Compose).
