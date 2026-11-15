# PPCastor on real WhatsApp — design

## Intent
The simulated field phone becomes a real WhatsApp conversation with PPCastor (Twilio). Field teams and dispatch
receive real incident alerts, confirm them, ask PPCastor questions and send field reports (text, voice note, photo)
from their own phones. The simulated phone stays for demos without Twilio (`NOTIFY_MODE=SIMULATED`).

## Scope (user: a + b + c, 2026-11-15)
- **a. Alerts + confirmation**: outbound alerts already exist (`NotificationChannel.TwilioWhatsAppChannel`,
  `NOTIFY_MODE=REAL`). New: a reply `OK` / `OK INC-0007` confirms the newest open alert for that phone
  (`IncidentService.awaitingNotification` + `confirm`).
- **b. Questions**: any other text goes to the PPCastor assistant; the answer is sent back on WhatsApp.
- **c. Field reports**: a message starting with `raport` (text), a voice note (transcribed) or a photo becomes an
  incident **proposal**; PPCastor summarises it and creates the incident only after the sender answers `DA`
  (human approval for a consequential action). Photos are stored as FileGrid files (REAL), linked to the incident.

## Non-goals / anti-goals
- No WhatsApp "extension": WhatsApp has none; this is a bot on a Twilio WhatsApp sender.
- No personal data on WhatsApp, whoever asks: the WhatsApp channel acts with fixed least-privilege permissions.
- No action from unknown numbers. No free LLM action: the assistant stays read-only.
- The router (Agent 01) and the anomaly engine stay deterministic; the LLM is used only by the assistant and for
  voice-note transcription.

## Constraints (verified in Twilio docs)
- Twilio signs every webhook: `X-Twilio-Signature = Base64(HMAC-SHA1(authToken, fullUrl + sorted(name+value)))`.
- WhatsApp: free-form messages only inside the 24-hour window after the user's last message; outside it Twilio
  needs an approved template. Alerts to a phone that has been silent >24 h can fail (recorded as FAILED; the UI
  still shows the alert). For the demo the team sends any message first.
- WhatsApp inbound carries at most one media item (`MediaUrl0`, `MediaContentType0`).
- Sandbox: the profile name/picture are Twilio's; PPCastor appears as a signature on every message
  (`🦫 *PPCastor*`) and as an image in the first reply. A branded profile needs a Meta-approved business sender.
- Twilio must reach the app: `localhost` is not public, so a tunnel (compose profile `tunnel`, cloudflared quick
  tunnel) exposes the gateway; the public URL is set in the Twilio sandbox console and in `WHATSAPP_WEBHOOK_URL`.

## Approaches considered
1. **Chosen — webhook in `api` (Java)**, which already owns alerts, contacts (`NOTIFY_CONTACT_*`), confirmation,
   incidents and file ingestion. For questions, transcription and report parsing it calls `intelligence` through
   the existing public endpoints with a Keycloak **client-credentials token of a new client `filegrid-whatsapp`**
   holding only the `field` realm role, so the existing permission checks apply unchanged.
2. Webhook in `intelligence` (Python) — rejected: it would have to call back into `api` for confirmation, incidents
   and file storage, and would duplicate the contact list.
3. Shared secret between `api` and an internal `intelligence` endpoint — rejected: bypasses the existing
   token-based authorization (CLAUDE.md: no code that makes security harder).

## Design
- `POST /api/whatsapp/inbound` (form-encoded). Spring Security: permitted without a bearer token, but the controller
  rejects (403) any request whose Twilio signature does not validate against `WHATSAPP_WEBHOOK_URL`; 404 unless
  `NOTIFY_MODE=REAL` and `TWILIO_*` are set. Body size limited; always answers 200 with empty TwiML quickly.
- Idempotency: Flyway table `whatsapp_inbound(message_sid UNIQUE, phone_masked, role, kind, status, created_at)`;
  a retried `MessageSid` is ignored. Message text is not stored (may contain personal data); only the kind.
- Sender → role: the E.164 number must equal one of `NOTIFY_CONTACT_<ROLE>`; otherwise one fixed reply, no action.
- Processing on a bounded executor (LLM answers may exceed Twilio's 15 s webhook timeout); replies sent with the
  existing `TwilioWhatsAppChannel.send`, every reply prefixed with the PPCastor signature.
- Intents (deterministic, in this order): `OK[ INC-x]` → confirm; `DA`/`NU` with a pending report → create/discard;
  media audio → transcribe (`intelligence` `POST /api/intelligence/voice/transcribe`, Gemini; without an LLM key
  → "trimite raportul ca text") → report flow; media image → store as FileGrid file (streamed, max 16 MB buffer)
  + report flow with the caption; text starting with `raport` → report flow; anything else → assistant
  (`/api/intelligence/assistant/chat`).
- Pending report: table `whatsapp_pending_report(phone, proposal jsonb, file_id, expires_at)`, 15 min TTL.
- Data origin: everything received on WhatsApp is `dataOrigin=REAL`.
- New env: `WHATSAPP_WEBHOOK_URL`, `WHATSAPP_CLIENT_ID`, `WHATSAPP_CLIENT_SECRET` (+ `.env.example`, `init-env`).
  Keycloak: client `filegrid-whatsapp` (service account, role `field`) in `realm-filegrid.json`, applied with kcadm.
- Docs: ADR-0008 (WhatsApp inbound), ARCHITECTURE.md A.1/A.2 line for the webhook (needs architect approval),
  README "WhatsApp real" setup steps (Twilio sandbox, tunnel, env, join code).

## Verification
Unit: signature validator (Twilio doc example vector), intent parser, contact matching. Integration
(Testcontainers): inbound → confirm, inbound → pending report → DA → incident, duplicate MessageSid ignored,
bad signature 403. Python: transcribe endpoint (LLM mocked). Manual: real phone via sandbox + tunnel.
Security audit (read-only) before calling it done.

## Left to the builder
Exact reply wording (Romanian, PPCastor voice), executor sizing, photo category mapping via the existing classifier.
