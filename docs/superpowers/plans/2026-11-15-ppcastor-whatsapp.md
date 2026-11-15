# PPCastor WhatsApp Implementation Plan

> Executed inline (superpowers:executing-plans). Spec: `docs/superpowers/specs/2026-11-15-ppcastor-whatsapp-design.md` (approved by the user, 2026-11-15, including the ARCHITECTURE.md change and the new Keycloak client).

**Goal:** real WhatsApp conversation with PPCastor: confirm alerts (`OK`), ask questions, send field reports (text, voice, photo) that become incidents after `DA`.

## Global constraints
- Streaming only, buffers ≤ 16 MB; media downloads capped at `filegrid.whatsapp.media-max-bytes` (16 MB).
- No secrets in the repo; every new variable in `.env.example`. Message text is never persisted or logged.
- Unknown senders: one fixed reply, no action. Every inbound request is signature-checked before anything else.
- Everything from WhatsApp is `dataOrigin=REAL`. Replies are prefixed `🦫 *PPCastor*:`.
- Java/TS files use CRLF: exact edits only.

## Review focus
- Signature check uses the configured public URL; a tunnel URL change without updating `WHATSAPP_WEBHOOK_URL` → every request 403 (logged once, clear message).
- Twilio retries the same `MessageSid` → processed once (UNIQUE + insert-first).
- `DA` without a pending report, or after 15 min → explains, creates nothing.
- Voice note with no LLM key → asks for text, creates nothing.
- Photo larger than the cap → refused, staging tmp file deleted.

## Tasks
1. **intelligence** `POST /api/intelligence/voice/transcribe` (multipart `audio`, permission `voice:use`, ≤ 16 MB) → `{transcript}`; Gemini native `generateContent` with `inline_data`; 503 without LLM key. Tests with a mocked transport.
2. **api** `whatsapp/TwilioSignature.valid(token, url, params, header)` — unit test with the Twilio docs vector (`L/OH5YylLD5NRKLltdqwSvS0BnU=`).
3. **api** `whatsapp/WhatsAppIntents.parse(body, mediaType, hasPending)` → `Intent(kind, incidentCode)`; `whatsapp/WhatsAppContacts.roleOf(phone)` from `NOTIFY_CONTACT_*`. Unit tests.
4. **api** `upload/FileIntakeService.accept(...)` extracted from `MultipartFileController` (behaviour unchanged; existing upload ITs stay green).
5. **api** `V8__whatsapp.sql` (`whatsapp_inbound`, `whatsapp_pending_report`), `WhatsAppProperties`, `ServiceToken` (client credentials), `IntelligenceClient`, `TwilioMedia`, `WhatsAppService` (async), `WhatsAppInboundController` (`POST /api/whatsapp/inbound`, permitAll + signature). IT: confirm via OK, report → DA → incident, duplicate SID, bad signature 403, unknown sender.
6. **infra**: Keycloak client `filegrid-whatsapp` + realm role `svc-whatsapp` (`file:read, incident:read, assistant:use, voice:use, storage:read`), `RolePermissions`, compose env, `.env.example`, `scripts/keycloak-whatsapp-client.sh` (kcadm), compose profile `tunnel` (cloudflared).
7. **docs**: ADR-0008, ARCHITECTURE.md A.1/A.2, README „WhatsApp real”, CHANGELOG, HANDOFF.
8. Full test runs, `docker compose up`, security audit (read-only agent), fix Critical/Important.
