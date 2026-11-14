# Criptarea datelor personale (Etapa 7)

Documentele de racordare (și orice categorie cu `needsEncryption: true` în `config/policies.yaml`) sunt criptate de FileGrid **înainte** de a ajunge în storage. Un acces direct la S3 / FTP / store-db vede doar ciphertext (`scripts/direct-access-demo.sh`).

## Schema (envelope encryption)

| Element | Unde stă | Detalii |
| --- | --- | --- |
| KEK (key-encryption key) | variabila `FILEGRID_KEK` (256 bit, base64), generată de `scripts/init-env.sh` | nu ajunge niciodată în repo, în DB sau în storage |
| DEK (cheia de date) | `file_key.wrapped_dek` în meta-db, împachetată cu KEK (AES-256-GCM, AAD `filegrid:dek:<fileId>`) | una per fișier, keyset Tink |
| Ciphertext | storage-ul ales de Agent 01 | Tink Streaming AEAD `AES256_GCM_HKDF_1MB`, AAD `filegrid:file:<fileId>` |

- Criptarea este în flux (segmente de 1 MB): fișierul nu este încărcat în memorie. Workerul scrie `<fileId>.enc` în staging o singură dată; reîncercările și reluarea S3 multipart folosesc același ciphertext.
- `sha256_plain` = hash-ul originalului (calculat la primire); `sha256_stored` = hash-ul ciphertext-ului. Read-back-ul de după scriere compară `sha256_stored`; descărcarea și verificarea decriptează și compară `sha256_plain`.
- Un ciphertext alterat în storage nu trece autentificarea GCM → fișierul este marcat `MISMATCH`, se deschide un incident de integritate și conținutul nu este servit.
- Cheile obiectelor sunt `<categorie>/<an>/<lună>/<zi>/<uuid>`: niciun nume de client în cale sau în metadatele storage-ului.
- `file_record.sensitivity = PERSONAL_DATA`, `encrypted = true`. Descărcarea cere permisiunea `pii:read` (prin `AccessPolicy`).
- Metrica `filegrid_pii_stored_unencrypted_total` trebuie să fie mereu 0 (alerta `PersonalDataAtRisk`).

## Rotația KEK

1. Generați un KEK nou; mutați-l pe cel vechi în `FILEGRID_KEK_PREVIOUS=kek-1:<base64-vechi>`.
2. Setați `FILEGRID_KEK=<base64-nou>` și `FILEGRID_KEK_ID=kek-2`, apoi reporniți api-ul și workerii.
3. Fișierele noi folosesc `kek-2`; cele vechi se deschid cu cheia indicată de `file_key.key_id`.

KEK greșit → eroare clară („Data key … cannot be unwrapped … (wrong FILEGRID_KEK?)”), fără conținut parțial servit.

## Limitări
- Re-împachetarea DEK-urilor vechi cu KEK-ul nou (re-wrap în masă) nu este automatizată.
- Câmpurile personale din metadate (nume client, CNP) nu sunt colectate la upload în această versiune; textele de notificare mascheză automat CNP, IBAN, telefon și e-mail.
