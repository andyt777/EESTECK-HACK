# ADR-0006 — Politica inițială de storage per categorie (ipoteză de validat prin benchmark)

- **Stare:** acceptat ca ipoteză; **de validat** prin benchmark-ul din Etapa 17 (în lucru)
- **Etapa:** 3 (politica), 17 (benchmark)

## Context

Prima întrebare din §5 a cerinței este „ce tip de storage se potrivește cel mai bine fiecărei categorii de date și de ce?”, iar §1 spune că astăzi nimeni nu poate răspunde „pe baza unor date”. Sistemul trebuie să pornească cu o politică rezonabilă înainte de a avea măsurători, iar §3 cere ca fiecare dintre cele trei storage-uri (FTP, S3, bază de date) să fie „folosit efectiv”.

## Decizie

Politica inițială din `config/policies.yaml` (identică cu `docs/ARCHITECTURE.md` §A.4):

| Categorie | Primar | Fallback | Argument |
| --- | --- | --- | --- |
| `METER_READING` | Database | S3 | fișiere mici și foarte multe; tranzacții + cheie unică (`readingId`) garantează zero dubluri; date interogabile direct de Agent 02 |
| `FAULT_RECORD` | FTP | S3 | 1–10 MB, ierarhie zonă/stație/dată, citire imediată pentru dispecerat; S3 preia vârfurile dacă FTP e saturat sau căzut |
| `CONNECTION_DOCUMENT` | S3 (criptat aplicativ, versioning) | Database (criptat) | protecție PII (ADR-0004), versiuni păstrate pentru audit |
| `DRONE_VIDEO` | S3 multipart | FTP (cu reluare) | 100 MB – 1 GB+, transfer reluabil pe părți (ADR-0002) |

Toate trei storage-urile au cel puțin un rol de primar. Fallback-ul este folosit automat după reîncercări cu backoff (secțiunea `retry`), iar dacă toate cad fișierul trece în `PENDING` și este reluat automat.

**Validarea** (planificată în `docs/PLAN.md`, Etapa 17): un benchmark controlat (`POST /api/admin/benchmarks`) scrie și citește, pentru fiecare categorie, un set reprezentativ în toate storage-urile tehnic eligibile, în namespace-ul de benchmark, și măsoară p50/p95/p99 la scriere/citire, throughput, erori și consumul de resurse. Raportul compară scorul măsurat cu politica activă. Dacă datele contrazic ipoteza, recomandarea este aplicată **doar de un om**, schimbând `config/policies.yaml` sau din `PUT /api/admin/policies` (fără cod, ADR-0005), iar acest ADR se actualizează cu cifrele măsurate și cu decizia finală.

## Alternative evaluate

| Alternativă | De ce nu (acum) |
| --- | --- |
| Totul în S3 | nu răspunde cerinței „fiecare storage folosit efectiv”; pierde garanțiile tranzacționale pentru contoare |
| Contoare pe S3 (un obiect per citire) | milioane de obiecte mici, fără unicitate tranzacțională; deduplicarea ar trebui făcută separat |
| Video pe Database | limită `bytea` (64 MB în politică), fără reluare pe părți |
| Avarii pe Database | posibil (sub 64 MB), dar FTP corespunde ierarhiei folosite de dispecerat; rămâne alternativă de evaluat în benchmark |

## Consecințe

- Până la benchmark, răspunsul la prima întrebare din §5 este argumentat calitativ, nu cu date măsurate — trebuie spus explicit la demo.
- Latența folosită la scorare vine deocamdată din probe-ul de sănătate (`HEALTH_PROBE`); după Etapa 8 va veni din p95 măsurat.

## Verificare

- `GET /api/policies` arată politica activă; `PolicyAndRoutingIT` verifică rutarea pentru fiecare categorie.
- Benchmark-ul și pagina Performance cu date măsurate: **în lucru** (Etapa 17).
