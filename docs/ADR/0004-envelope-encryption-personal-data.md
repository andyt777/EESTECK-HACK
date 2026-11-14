# ADR-0004 — Criptare envelope a datelor personale la nivel de aplicație

- **Stare:** acceptat (arhitectură, `docs/ARCHITECTURE.md` §A.3.8); implementat în Etapa 7
- **Etapa:** 7

## Context

Documentele de racordare conțin date personale (cereri, acte de identitate). Cerința: „un acces neautorizat direct la storage nu expune datele personale din documentele de racordare”, iar datele trebuie să rămână „protejate și verificabile la audit”. Criptarea oferită de storage (SSE în S3, TDE în baza de date) nu ajută: cine are credențialele storage-ului citește datele în clar. Politica permite ca un document să ajungă pe S3 (primar) sau pe Database (fallback), deci protecția trebuie să fie independentă de storage.

## Decizie

1. **Envelope encryption în FileGrid, înainte de storage** (`backend/src/main/java/ro/filegrid/crypto/EnvelopeEncryption.java`):
   - fiecare fișier are propria cheie de date (DEK), un keyset Google Tink **Streaming AEAD `AES256_GCM_HKDF_1MB`**, cu AAD `filegrid:file:<fileId>`;
   - DEK-ul este împachetat cu cheia master `FILEGRID_KEK` (AES-256-GCM, AAD `filegrid:dek:<fileId>`) și salvat în `file_key` (meta-db, migrația `V7__file_key.sql`);
   - KEK-ul vine doar din mediu (`.env`, generat de `scripts/init-env.sh`), nu ajunge niciodată în repo, DB sau storage.
2. **Criptare în flux**, segmente de 1 MB (compatibil cu ADR-0002); ciphertext-ul se scrie o singură dată în staging, iar reîncercările și reluarea multipart folosesc același ciphertext.
3. **Două hash-uri**: `sha256_stored` (ciphertext, verificat la read-back după scriere) și `sha256_plain` (original, verificat la descărcare după decriptare). Un ciphertext alterat eșuează autentificarea GCM → `MISMATCH` + incident, nimic servit.
4. **Rotație KEK** prin `FILEGRID_KEK_ID` și `FILEGRID_KEK_PREVIOUS`; fiecare DEK reține `key_id`.
5. Categoriile criptate se aleg din politică (`needsEncryption: true` în `config/policies.yaml`). Metrica `filegrid_pii_stored_unencrypted_total` trebuie să rămână 0 (alerta `PersonalDataAtRisk` din `infra/prometheus/rules/business.yml`).
6. Cheile obiectelor sunt `<categorie>/<an>/<lună>/<zi>/<uuid>` — niciun nume de client în cale sau în metadatele storage-ului.

## Alternative evaluate

| Alternativă | De ce nu |
| --- | --- |
| SSE-S3 / SSE-KMS în storage | storage-ul decriptează pentru oricine are credențialele lui; nu acoperă fallback-ul pe Database |
| `pgcrypto` / TDE în baza de date | același motiv; în plus, nu acoperă S3 |
| O singură cheie pentru toate fișierele | o cheie compromisă expune tot; rotația cere re-criptarea întregului conținut |
| KMS extern (Vault, cloud KMS) | serviciu în plus de operat local; interfața actuală permite trecerea ulterioară (KEK-ul e un singur punct de schimbare) |

## Consecințe

- Pierderea `FILEGRID_KEK` face documentele ilizibile (asumat; documentat în `docs/HANDOFF.md` și `docs/ENCRYPTION.md`).
- Re-împachetarea în masă a DEK-urilor la rotație nu este automatizată.
- Autorizarea citirii (`pii:read`) se face prin `AccessPolicy`; până la Etapa 11 politica este permisivă (vezi ADR-0007).

## Verificare

- `EnvelopeEncryptionIT` (Testcontainers).
- Live: `bash scripts/direct-access-demo.sh` — în S3 se vede doar ciphertext, descărcarea prin FileGrid dă fișierul cu SHA-256 identic.
- Detalii complete: `docs/ENCRYPTION.md`.
