# ADR-0003 — Cozi și workeri separați pe categorie (bulkhead)

- **Stare:** acceptat (arhitectură, `docs/ARCHITECTURE.md` §A.1 și §A.3.6)
- **Etapa:** 2 (roluri api/worker), 4 (cozi și consumatori), 5 (contoare)

## Context

Cerința de scalabilitate: „un vârf de 10 ori peste volumul normal într-o singură categorie (de exemplu, avariile într-o zi cu furtună) nu degradează celelalte categorii” și „capacitatea crește prin adăugarea de instanțe, fără modificări de cod”. Profilurile de încărcare diferă radical: citirile de contor sunt mii de mesaje mici pe minut, video-urile sunt puține transferuri de ordinul GB care țin un worker ocupat minute întregi. O coadă comună ar pune un vârf de video-uri sau de avarii în fața citirilor de contor (head-of-line blocking).

## Decizie

1. **O coadă durabilă per categorie** în RabbitMQ: `filegrid.jobs.meter|fault|document|video` (`messaging/Queues.java`, `MessagingConfiguration`), fiecare cu DLQ proprie. Joburile se publică cu publisher confirms (`JobPublisher`, `MeterReadingPublisher`); `202` pentru citiri se dă doar după confirmare.
2. **Câte un serviciu worker per categorie** din aceeași imagine (`worker-meter`, `worker-fault`, `worker-document`, `worker-video` în `infra/docker-compose.yml`, profil Compose `full`), fiecare cu limită proprie de memorie, concurență și prefetch (`WORKER_CONCURRENCY`, `WORKER_PREFETCH`, `WORKER_VIDEO_CONCURRENCY`). Categoriile consumate se aleg cu `WORKER_CATEGORIES` (`worker/WorkerListenerConfiguration.java`).
3. **Scalare orizontală fără cod**: `docker compose up -d --scale worker-video=3`. Un advisory lock PostgreSQL (`worker/TransferLock.java`) garantează un singur worker per fișier între replici, iar un job pentru un fișier deja `STORED_VERIFIED` este confirmat fără nicio scriere.
4. Pentru mașini cu resurse mici există modul `lite` (`worker-all`, toate categoriile într-un proces), explicit mai slab ca izolare și nefolosit pentru demo-ul de bulkhead.

## Alternative evaluate

| Alternativă | De ce nu |
| --- | --- |
| O singură coadă cu priorități | prioritățile nu opresc un worker deja ocupat cu un video de 1 GB; izolarea resurselor (memorie, CPU) lipsește |
| Thread pool-uri separate în același proces | izolează doar firele; un OOM sau un GC lung afectează toate categoriile; nu se pot scala independent |
| Kafka | durabilitate și throughput mai mari decât e nevoie, cost operațional și de memorie mai mare pentru o mașină de dezvoltare |

## Consecințe

- Mai multe containere (4 workeri în modul `full`) și mai multă memorie în total; bugetele sunt documentate în README și `docs/CHANGELOG-ETAPE.md`.
- Ordinea evenimentelor este garantată per proces, nu global (limitare documentată la Etapa 4).
- Bulkhead-ul pe resursele de storage partajate (de exemplu, același S3 pentru documente și video) nu este acoperit de cozi; benchmark-ul (Etapa 17) și drill-urile (Etapa 18) măsoară efectul.

## Verificare

- `IngestionAndQueuingIT`, `JobIdempotencyIT`, `MeterIngestionIT`, `WorkerPropertiesTest`.
- Live: `make sim-storm` / `make sim-peak` și `docker compose up -d --scale worker-video=3`. Demonstrarea cantitativă „vârf x10 într-o categorie nu degradează celelalte” (k6 + metrici p95 per categorie) este **în lucru** (Etapele 8 și 18).
