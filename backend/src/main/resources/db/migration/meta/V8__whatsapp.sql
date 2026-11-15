-- PPCastor on WhatsApp (docs/ADR/0008-whatsapp-inbound.md).
-- The message text is never stored (it may contain personal data): only what kind of message it was.

-- One row per Twilio MessageSid: a retried webhook is recognised and ignored.
CREATE TABLE whatsapp_inbound (
    message_sid  VARCHAR(64)  PRIMARY KEY,
    phone_masked VARCHAR(32)  NOT NULL,
    role         VARCHAR(64),
    kind         VARCHAR(16)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED', 'DONE', 'FAILED', 'IGNORED')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A field report waiting for the sender's "DA" (at most one per phone, 15 minutes).
-- The phone is kept only as a SHA-256 hash.
CREATE TABLE whatsapp_pending_report (
    phone_hash VARCHAR(64) PRIMARY KEY,
    role       VARCHAR(64) NOT NULL,
    proposal   JSONB       NOT NULL,
    file_id    UUID,
    expires_at TIMESTAMPTZ NOT NULL
);
