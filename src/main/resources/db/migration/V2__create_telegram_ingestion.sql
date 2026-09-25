CREATE TABLE telegram_source (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    telegram_id INTEGER UNIQUE,
    username TEXT COLLATE NOCASE,
    title TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (telegram_id IS NOT NULL OR username IS NOT NULL)
);

CREATE UNIQUE INDEX telegram_source_username_unique
    ON telegram_source (username)
    WHERE username IS NOT NULL;

CREATE TRIGGER telegram_source_set_updated_at
AFTER UPDATE ON telegram_source
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE telegram_source
    SET updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.id;
END;

CREATE TABLE telegram_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id INTEGER NOT NULL,
    telegram_message_id INTEGER NOT NULL,
    published_at TEXT NOT NULL,
    text TEXT,
    message_url TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT telegram_message_source_fk
        FOREIGN KEY (source_id) REFERENCES telegram_source (id) ON DELETE CASCADE,
    CONSTRAINT telegram_message_source_message_unique
        UNIQUE (source_id, telegram_message_id)
);

CREATE INDEX telegram_message_published_at_idx
    ON telegram_message (published_at DESC);

CREATE TRIGGER telegram_message_set_updated_at
AFTER UPDATE ON telegram_message
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE telegram_message
    SET updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.id;
END;

UPDATE app_metadata
SET "value" = '2'
WHERE "key" = 'schema.version';
