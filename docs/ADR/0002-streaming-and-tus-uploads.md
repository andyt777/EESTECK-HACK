# ADR-0002 — Streaming peste tot și upload reluabil cu tus 1.0

- **Stare:** acceptat (arhitectură, `docs/ARCHITECTURE.md` §A.2 și §A.3.3)
- **Etapa:** 2 (adaptoare în flux), 4 (tus, pipeline), 7 (criptare în flux)

## Context

Cerința cere ca video-urile de dronă (100 MB – 1 GB+) să fie transferate „pe conexiuni lente sau instabile” și ca fișierele de peste 1 GB să fie procesate „fără ca resursele serviciului să crească proporțional cu dimensiunea fișierului”. Containerele backend au limite mici de memorie (api 640M, worker-video 512M, heap 224–320 MB), deci un fișier ținut întreg în memorie (`byte[]`) ar opri serviciul. Un upload HTTP clasic dintr-o singură cerere trebuie reluat de la zero după o întrerupere.

## Decizie

1. **Upload reluabil cu protocolul tus 1.0** la `/api/uploads` (`TusUploadController`, `TusUploadConfiguration`), plus `POST /api/files` multipart doar pentru fișiere mici (`MultipartFileController`). Frontend-ul folosește `tus-js-client` cu chunk-uri de 8 MB (`frontend/src/app/core/upload.service.ts`). După o întrerupere, `HEAD` întoarce `Upload-Offset`, iar clientul continuă de acolo.
2. **Streaming pe tot drumul**: chunk-urile se scriu în `/data/staging` (volum partajat), fișierul complet se mută prin rename (fără copiere), SHA-256 se calculează în flux (`storage/Sha256.java`), iar workerul trimite conținutul din staging către storage tot în flux.
3. **Adaptoare de storage în flux** (`storage/StorageAdapter.java`): S3 multipart cu părți de 16 MB și checkpoint persistat (`MultipartCheckpoint`, tabelul `s3_multipart_transfer`, migrația `V3`), FTP cu scriere în `.part` + rename și reluare prin offset REST, Database cu `bytea` scris în flux și citit în felii de 4 MB, limitat la 64 MB.
4. **Regula de buffere**: niciun buffer peste 16 MB (CLAUDE.md); testele rulează cu heap de 256 MB tocmai ca să prindă o încărcare completă în memorie.
5. Gateway-ul (Traefik) are `readTimeout: 1h` pe entrypoint-ul HTTPS, ca un PATCH tus mare să nu fie tăiat.

## Alternative evaluate

| Alternativă | De ce nu |
| --- | --- |
| Upload multipart HTTP clasic pentru tot | o întrerupere la 90% dintr-un video de 1 GB reia totul; nu răspunde cerinței de conexiuni instabile |
| Upload direct în S3 cu URL-uri presemnate | ocolește FileGrid (fără clasificare, hash și decizie a Agentului 01 înainte de storage) și expune storage-ul în afara rețelei interne, contrar §A.3.9 |
| Protocol propriu de chunk-uri | reinventează tus; tus are clienți maturi (browser, `curl`) și o specificație clară |

## Consecințe

- Memoria serviciilor nu depinde de mărimea fișierului; prețul este un volum de staging pe disc (fișierul stă acolo până la `STORED_VERIFIED`).
- Staging-ul devine și outbox (§A.3.7): un fișier primit nu se pierde dacă storage-ul cade.
- Database nu primește fișiere mari (limita de 64 MB din `config/policies.yaml`), ceea ce influențează politica (ADR-0006).
- Criptarea documentelor de racordare trebuie să fie și ea în flux (ADR-0004).

## Verificare

- `backend/src/test/java/.../LargeResumableUploadIT.java`: 1,5 GB prin tus, întrerupt la 40% și reluat, S3 multipart, read-back, cu heap de 256 MB.
- `TusUploadIT`, `S3MultipartResumptionIT`, `S3OutageRecoveryIT`, `FtpStorageAdapterIT` (upload întrerupt la 50% și reluat), `S3StorageAdapterIT` (round-trip 300 MB).
- Live: `bash scripts/mem-report.sh` în timpul unui upload mare; comenzile `curl` tus din README.
