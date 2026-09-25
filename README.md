# Investment Intelligence Assistant

A personal modular Spring Boot application for collecting investment information and, in later stages, producing private reports and alerts. Stage 2 adds reliable ingestion of selected Telegram channels through a normal Telegram user account. It does not include OpenAI, summaries, IBKR, market-data APIs, or a Telegram output bot.

## Stage 2 architecture

```text
Selected Telegram channels
        |
TDLight Java / TDLib user client
        |
Telegram ingestion service
        |
Spring JDBC + SQLite
```

The code remains a modular monolith under `com.investmentassistant`. Telegram-specific configuration, client integration, normalization, ingestion, source administration, and persistence live in the `telegram` package. Shared database configuration remains in `config` and `persistence`.

The Telegram client uses [TDLight Java](https://github.com/tdlight-team/tdlight-java), a maintained Java wrapper around Telegram's TDLib with packaged Linux native libraries. TDLib handles MTProto, ordered updates, reconnection, local session storage, history, and user-account authentication. The selected release supports Java 17 through Java 21+ and Linux amd64. This avoids implementing MTProto or compiling TDLib/JNI during every Docker build.

## Technology stack

- Java 21
- Spring Boot 4.1
- Maven Wrapper
- Spring JDBC and SQLite
- Flyway
- TDLight Java / TDLib
- JUnit 5 and AssertJ
- Docker and Docker Compose

## Build and test

Java 21 or newer is required on the host.

```bash
./mvnw clean verify
```

Telegram is disabled by default, so builds and automated tests never connect to Telegram or require credentials.

## Configuration

Copy the placeholder file and fill it locally:

```bash
cp .env.example .env
```

The required Telegram variables are:

| Environment variable | Purpose |
| --- | --- |
| `TELEGRAM_API_ID` | Telegram application ID |
| `TELEGRAM_API_HASH` | Telegram application hash |
| `TELEGRAM_PHONE` | User account phone number in international format |

Runtime settings are available as environment variables:

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `APP_DATABASE_PATH` | `./data/investment-assistant.db` | SQLite database file |
| `APP_TELEGRAM_ENABLED` | `false` | Starts Telegram ingestion when credentials are complete |
| `APP_TELEGRAM_SESSION_PATH` | `./data/telegram` | Persistent TDLib session directory |
| `APP_TELEGRAM_HISTORY_LIMIT` | `100` | Recent messages imported for each enabled source, capped at 100 |
| `APP_TELEGRAM_AUTH_MODE` | `RUNTIME` | `RUNTIME` is non-interactive; `CONSOLE` is only for first login |

If Telegram is enabled while any required credential is missing, the integration stays `DISABLED` and the application still starts. Credentials are never written to `application.yml`, source code, Docker images, or the database.

## Run locally

Without Telegram:

```bash
./mvnw spring-boot:run
```

The default SQLite file is `./data/investment-assistant.db`. Flyway creates and tracks the schema automatically.

```bash
curl http://localhost:8080/api/status
```

Example response before Telegram is enabled:

```json
{"status":"UP","database":"UP","telegram":"DISABLED","application":"investment-assistant"}
```

Telegram can report `DISABLED`, `AUTH_REQUIRED`, `CONNECTING`, `CONNECTED`, or `ERROR`. Telegram authentication does not make the whole application unavailable.

## Run with Docker Compose

```bash
mkdir -p data/telegram
docker compose up --build
```

Compose runs as UID/GID `1000:1000` by default, which matches the Oracle `opc` user. On another Linux host, pass the owner of the local `data` directory when it differs:

```bash
APP_UID="$(id -u)" APP_GID="$(id -g)" docker compose up --build
```

Compose exposes port `8080` and mounts `./data` to `/app/data`. Both SQLite and the TDLib session therefore survive application restarts, container recreation, image rebuilds, and redeployment:

```text
./data/investment-assistant.db -> /app/data/investment-assistant.db
./data/telegram                -> /app/data/telegram
```

The runtime JVM is limited by percentage settings to remain conservative on the small DEV VM. TDLight adds a packaged native TDLib/OpenSSL 3 dependency to the single application image; it does not add another container.

## First Telegram authentication

Perform this once in a trusted local terminal. Do not expose verification codes or the 2FA password through HTTP.

1. Put the API ID, API hash, and phone number in the ignored local `.env` file.
2. Build the image and stop any regular application container so only one process uses the TDLib session:

   ```bash
   docker compose build
   docker compose stop investment-assistant
   ```

3. Start the one-time interactive login against the same persistent `./data` mount:

   ```bash
   docker compose run --rm -it \
     -e APP_TELEGRAM_ENABLED=true \
     -e APP_TELEGRAM_AUTH_MODE=CONSOLE \
     -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
     investment-assistant
   ```

4. Enter the Telegram verification code and, if requested, the Telegram 2FA password directly in that terminal. Neither value is logged or stored by this application.
5. Wait for the `Telegram authenticated` log entry, then stop the interactive process with `Ctrl+C`.
6. Start normal non-interactive ingestion:

   ```bash
   APP_TELEGRAM_ENABLED=true docker compose up -d
   ```

TDLib stores its authenticated database under `./data/telegram`. Subsequent starts reuse it and normally do not request a code again. In normal `RUNTIME` mode the application never prompts on standard input; if Telegram needs authentication again, `/api/status` reports `AUTH_REQUIRED`.

## Configure Telegram sources

Sources can be managed through the development REST API. Telegram must report `CONNECTED` before discovery, adding a source, or re-enabling one. Metadata supplied by Telegram is authoritative; `POST` accepts only a Telegram chat ID.

```bash
# Discover channels and groups in the authenticated account
curl http://localhost:8080/api/telegram/sources/available

# Monitor one source and import up to 100 recent messages
curl -X POST http://localhost:8080/api/telegram/sources \
  -H 'Content-Type: application/json' \
  -d '{"telegramId":-1001234567890}'

# List monitored sources and inspect recent messages
curl 'http://localhost:8080/api/telegram/sources?enabled=true'
curl 'http://localhost:8080/api/telegram/sources/1/messages?limit=20'

# Disable while preserving the source and all collected messages
curl -X PATCH http://localhost:8080/api/telegram/sources/1 \
  -H 'Content-Type: application/json' \
  -d '{"enabled":false}'
```

The API also provides `GET /api/telegram/sources/{id}`, `GET /api/telegram/messages?telegramId=...`, and `GET /api/telegram/stats`. Message limits default to 20 and are capped at 200. Historical imports default to `APP_TELEGRAM_HISTORY_LIMIT` and accept a per-request `historyLimit` from 1 to 1000. Errors use a stable `{ "code", "message" }` response without stack traces.

There is intentionally no REST `DELETE` endpoint. Disabling a source stops new ingestion while preserving its configuration and history.

The original local command-line administration remains available for recovery and offline use.

Add a public channel username:

```bash
docker compose run --rm \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  investment-assistant \
  --app.telegram.admin.command=add \
  --app.telegram.admin.source=@channel_username
```

A numeric TDLib chat ID can be used for a channel without a public username. List configured sources:

```bash
docker compose run --rm \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  investment-assistant --app.telegram.admin.command=list
```

Disable or enable a source by its local database ID:

```bash
docker compose run --rm -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  investment-assistant --app.telegram.admin.command=disable --app.telegram.admin.source-id=1

docker compose run --rm -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  investment-assistant --app.telegram.admin.command=enable --app.telegram.admin.source-id=1
```

Restart the normal application after source changes. On connection it resolves every enabled source and imports up to the configured history limit. The database uniqueness constraint on `(source_id, telegram_message_id)` makes repeated imports idempotent.

## Verify ingestion

Check connectivity:

```bash
curl http://localhost:8080/api/status
docker compose logs --tail=100 investment-assistant
```

Check the persisted message count without exposing message text:

```bash
docker compose run --rm \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  investment-assistant --app.telegram.admin.command=message-count
```

The `telegram_source` table stores channel/group configuration and ingestion timestamps. The `telegram_message` table stores the source, TDLib message ID, publication timestamp, sender metadata when available, text or media caption, and a message link when TDLib can provide one. New messages and message-content edits are upserted. Photos, videos, and documents are not downloaded in Stage 2.

## Security notes

- `.env`, `data/`, SQLite files, private keys, PEM files, and common TDLib session directories are ignored by Git.
- Telegram verification codes and 2FA passwords are accepted only by the interactive console flow and are not persisted in the application database. The 2FA password uses hidden terminal input.
- The development REST API currently has no authentication. Because port 8080 may be publicly reachable, its `POST` and `PATCH` endpoints must not remain exposed without authentication beyond DEV/testing. The controller and service boundary is ready for a later Spring Security layer.
- The REST API never returns Telegram credentials, phone numbers, verification codes, 2FA passwords, or TDLib session data.
- `/api/status` exposes only connection states, never credentials, phone numbers, session details, or environment values.

## Database migrations

- `V1` creates `app_metadata`.
- `V2` creates `telegram_source` and `telegram_message`, their constraints and indexes, and advances `schema.version` to `2`.
- `V3` adds source type, last-ingestion time, sender metadata, and the source/message ordering index.

SQLite foreign keys are enabled for every connection. Deleting a source cascades to its stored messages. Message duplicates are prevented by a database constraint and handled with an upsert so edited text/captions replace the previously stored content.

## Future stages

Future work may add OpenAI analysis, summaries, IBKR portfolio access, financial and market-data APIs, and Telegram report delivery. Those integrations are outside Stage 2.
