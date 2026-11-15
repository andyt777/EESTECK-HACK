# ADR-0008 — PPCastor pe WhatsApp real (Twilio): mesaje primite din teren

- **Stare:** acceptat (decizie a arhitectului, 2026-11-15); implementat
- **Design detaliat:** `docs/superpowers/specs/2026-11-15-ppcastor-whatsapp-design.md`

## Context

Până acum, telefonul de teren era simulat: FileGrid putea **trimite** alerte reale pe WhatsApp prin Twilio (`NOTIFY_MODE=REAL`), dar nu putea **primi** nimic. Echipele de teren trebuiau să confirme alertele din aplicație și să raporteze avariile prin alt canal. Mascota produsului, PPCastor, devine interlocutorul de pe WhatsApp.

## Decizie

1. **Webhook în `api`:** `POST /api/whatsapp/inbound`, singurul endpoint al API-ului fără token Bearer. Nimic nu se execută înainte de verificarea semnăturii Twilio (`X-Twilio-Signature`, HMAC-SHA1 cu `TWILIO_AUTH_TOKEN` peste URL-ul public configurat în `WHATSAPP_WEBHOOK_URL` + parametrii POST sortați). Endpoint-ul răspunde 404 dacă nu sunt îndeplinite toate condițiile: `NOTIFY_MODE=REAL`, `TWILIO_*` și URL-ul public.
2. **Doar contactele configurate:** expeditorul trebuie să fie unul dintre numerele `NOTIFY_CONTACT_*`. Orice alt număr primește un singur răspuns fix și nu declanșează nicio acțiune.
3. **Intenții deterministe:** `OK` / `OK INC-…` confirmă alerta; `raport …`, un mesaj vocal sau o poză devin o *propunere* de incident; `DA` / `NU` aprobă sau resping propunerea (15 minute); orice altceva este o întrebare pentru asistent. **Incidentul se deschide doar după „DA”**: aprobare umană pentru o acțiune cu consecințe.
4. **Identitate proprie, minimă:** clientul Keycloak `filegrid-whatsapp` (client credentials), cu rolul `svc-whatsapp` = `file:read`, `incident:read`, `assistant:use`, `voice:use`, `storage:read`. Cu el, `api` cheamă asistentul, transcrierea și propunerea de raport din `intelligence`, prin endpoint-urile existente. Verificările de permisiuni existente se aplică neschimbate; pe WhatsApp nu ajung niciodată date personale (`pii:read` lipsește).
5. **Mesajele vocale** sunt transcrise de Gemini (`POST /api/intelligence/voice/transcribe`, audio în corpul cererii, maximum 16 MB). Fără cheie Gemini, PPCastor cere raportul în scris. Clasificarea raportului rămâne deterministă (`parse_field_report`).
6. **Pozele** devin fișiere FileGrid (`dataOrigin=REAL`) prin același flux ca upload-ul: `FileIntakeService`, extras din `MultipartFileController`, cu streaming, SHA-256 calculat în flux și rutare de către Agent 01. După „DA”, poza este atașată incidentului.
7. **Idempotență și confidențialitate:** tabela `whatsapp_inbound` are cheia `MessageSid`, deci un retry Twilio este ignorat. **Textul mesajelor nu se stochează și nu se loghează**, ci doar tipul mesajului și numărul mascat. Propunerea în așteptare e indexată după hash-ul SHA-256 al numărului.
8. **Răspuns asincron:** webhook-ul răspunde imediat cu TwiML gol; răspunsul lui PPCastor (cu semnătura „🦫 *PPCastor*:”) pleacă prin API-ul Twilio dintr-un pool mic de workeri, pentru că un răspuns LLM poate depăși cele 15 secunde cât așteaptă Twilio.
9. **Expunere minimă:** tunelul Cloudflare (profilul compose `tunnel`, opțional) ajunge doar la entrypoint-ul intern `whatsapp` al gateway-ului (port 8088, nepublicat), al cărui unic router este `POST /api/whatsapp/inbound`, cu rate limit. UI-ul, login-ul și restul API-ului nu devin publice.

## Consecințe

- Pozitiv: echipa de teren lucrează din WhatsApp-ul propriu: confirmă alertele, întreabă PPCastor și trimite rapoarte vocale sau foto care ajung ca date REAL în FileGrid.
- Limită: în sandbox-ul Twilio, profilul (nume, poză) este al Twilio; PPCastor apare ca semnătură în fiecare mesaj. Un profil de business cu numele și poza lui PPCastor cere un expeditor WhatsApp aprobat de Meta.
- Limită: regula WhatsApp de 24 de ore. O alertă către un număr care nu a scris nimic în ultimele 24 de ore poate fi refuzată de WhatsApp (status FAILED în FileGrid; alerta rămâne vizibilă în UI). În afara sandbox-ului ar fi nevoie de template-uri aprobate.
- Limită: URL-ul unui quick tunnel se schimbă la fiecare pornire. Trebuie actualizat `WHATSAPP_WEBHOOK_URL` (cu restart `api`) și în consola Twilio.
- Date trimise în afara platformei: textul întrebărilor și al rapoartelor, transcrierile și audio-ul mesajelor vocale ajung la furnizorul LLM (Gemini), iar conversația trece prin Twilio și Meta. Răspunsurile asistentului pleacă pe WhatsApp **fără link-uri** (sunt eliminate, ca să nu permită exfiltrarea prin URL sau prin previzualizarea link-ului). Confirmarea „OK” verifică numărul complet al destinatarului alertei, nu doar masca.
- Rămas pentru producție (audit 2026-11-15, Low): HMAC cu cheie în loc de SHA-256 simplu pentru numărul de telefon din propunerile în așteptare; job de retenție pentru `whatsapp_inbound` / `whatsapp_pending_report`; allowlist pentru host-urile de stocare Twilio la redirect și un termen total la descărcarea media; limită de mesaje per expeditor în coada de lucru.
- Risc acceptat: endpoint public fără autentificare OIDC. Este mitigat prin semnătura Twilio, lista de contacte, rate limit la gateway și faptul că webhook-ul este singura rută expusă prin tunel.
