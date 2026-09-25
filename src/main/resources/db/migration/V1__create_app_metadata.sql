CREATE TABLE app_metadata (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    "key" TEXT NOT NULL UNIQUE,
    "value" TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER app_metadata_set_updated_at
AFTER UPDATE ON app_metadata
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
    UPDATE app_metadata
    SET updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.id;
END;

INSERT INTO app_metadata ("key", "value")
VALUES ('schema.version', '1');
