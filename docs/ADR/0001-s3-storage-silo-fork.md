# ADR-0001 — Storage-ul „S3”: Silo (fork comunitar Pigsty al MinIO)

- **Stare:** acceptat (decizie de arhitect, 2026-11-14)
- **Etapa:** 1 — infrastructură

## Context

Arhitectura cere ca storage-ul „S3” să fie emulat local cu MinIO. La implementarea Etapei 1 am constatat că:

- imaginile oficiale `minio/minio` și `minio/mc` nu mai pot fi descărcate: Docker Hub răspunde „repository does not exist”, iar Quay refuză descărcările anonime (401);
- proiectul upstream a fost arhivat în 2026, deci nu mai primește corecții;
- ultima versiune comunitară oficială rămâne astfel cu vulnerabilități (CVE-uri) necorectate.

Un sistem care trebuie să pornească „pe o mașină curată, cu o singură comandă” nu poate depinde de o imagine care nu mai există în registru.

## Decizie

Folosim **Silo**, fork-ul comunitar întreținut de Pigsty (`pgsty/silo`), licențiat AGPLv3 și compatibil cu MinIO la nivel de API S3, format de date pe disc, variabile `MINIO_*` și client `mc`.

- Imagine: `pgsty/silo:RELEASE.2026-11-12T00-00-00Z`
- Digest: `sha256:c20d112390b9a9ce8c11b8af95ad35bcaa62f4759663c09c936cd8d2b511f329`
- Imaginea este fixată pe tag **și** pe digest în `infra/docker-compose.yml` (ancora `x-silo-image`).
- Imaginea serverului conține și clientul (`mcli`, cu alias `mc`), așa că aceeași imagine este folosită și de `minio-init`. Avem o singură imagine terță pentru S3.

Dintre imaginile Pigsty, `pgsty/silo` este cea activă: ultimul release este din 2026-11-12, în timp ce `pgsty/minio` s-a oprit la 2026-08-04 (proiectul a fost redenumit Silo).

Nu s-a schimbat nimic altceva: variabilele `MINIO_*`, porturile, bucket-urile, versioning-ul și userul de serviciu sunt identice.

## Alternative evaluate

| Alternativă | De ce nu |
| --- | --- |
| RustFS | implementare nouă; nu oferă garanția de compatibilitate cu `mc` și cu notificările/auditul de bucket de care avem nevoie |
| Garage | fără versioning de obiecte și fără notificări de bucket |
| SeaweedFS | model diferit de administrare; fără `mc admin` pentru useri, politici și audit |

Criteriul comun: etapele următoare au nevoie de **versioning** (documente de racordare, audit), de **notificări/audit de bucket** (detectarea accesului direct la storage, Etapa 9) și de **`mc`** (scripturile de corupere și de acces direct din Etapele 6 și 7).

## Consecințe

- Depindem de o imagine terță. Riscul este limitat prin fixarea pe digest: conținutul imaginii nu se poate schimba fără o modificare vizibilă în repository.
- Actualizarea versiunii este o schimbare explicită (tag + digest), verificată prin rularea criteriilor de acceptare.
- Backend-ul va folosi doar AWS SDK v2 pentru S3 (Etapa 2), deci furnizorul S3 poate fi schimbat fără modificări de cod, doar din configurație.
- Datele scrise anterior cu `pgsty/minio` au fost citite fără migrare de Silo (verificat la trecerea pe noua imagine).

## Verificare

Criteriile de acceptare ale Etapei 1 au fost rulate din nou cu Silo: toate serviciile sunt `healthy`, `minio-init` creează cele 5 bucket-uri versionate și userul de serviciu, Prometheus colectează metricile `s3`, iar portul 9000 nu este publicat.
