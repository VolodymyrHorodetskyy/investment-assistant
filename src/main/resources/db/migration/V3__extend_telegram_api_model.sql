ALTER TABLE telegram_source
    ADD COLUMN source_type TEXT NOT NULL DEFAULT 'CHANNEL'
        CHECK (source_type IN ('CHANNEL', 'SUPERGROUP', 'GROUP'));

ALTER TABLE telegram_source
    ADD COLUMN last_ingestion_at TEXT;

ALTER TABLE telegram_message
    ADD COLUMN sender_telegram_id INTEGER;

ALTER TABLE telegram_message
    ADD COLUMN sender_display_name TEXT;

CREATE INDEX telegram_message_source_published_at_idx
    ON telegram_message (source_id, published_at DESC, telegram_message_id DESC);

UPDATE app_metadata
SET "value" = '3'
WHERE "key" = 'schema.version';
