# Investment Intelligence Assistant

A personal, modular Spring Boot application intended to collect investment information, analyze it, and produce private reports and alerts. This repository currently contains **Stage 1 only**: the application foundation and local persistence. It has no external integrations and needs no API keys.

## Stage 1 scope

- Spring Boot monolith on Java 21
- SQLite persistence through Spring JDBC
- automatic Flyway schema migrations
- typed configuration for the database and disabled future integrations
- database-backed `GET /api/status` endpoint
- Maven and Docker builds
- integration tests for startup, migrations, SQLite, and application status

The code is organized by domain under `com.investmentassistant`: `config`, `persistence`, `telegram`, `portfolio`, `market`, `ai`, and `reporting`. The future-domain packages are deliberately empty except for package documentation. Shared configuration and persistence stay small and direct; Stage 1 does not introduce service boundaries or abstractions before they are needed.

## Technology stack

- Java 21
- Spring Boot 4.1
- Maven Wrapper
- Spring JDBC (`JdbcTemplate`)
- SQLite with the Xerial JDBC driver
- Flyway
- JUnit 5 and AssertJ
- Docker and Docker Compose

## Build and test

Java 21 is required when building on the host.

```bash
./mvnw clean verify
```

## Run locally

```bash
./mvnw spring-boot:run
```

Then inspect the status endpoint:

```bash
curl http://localhost:8080/api/status
```

The default SQLite file is `./data/investment-assistant.db`. Flyway applies migrations from `src/main/resources/db/migration` at startup and records them in `flyway_schema_history`.

## Run with Docker Compose

```bash
mkdir -p data
docker compose up --build
```

Compose exposes port `8080` and bind-mounts the host's `./data` directory to `/app/data`. The container runs as UID/GID `1000:1000` by default, matching the normal first Linux user; `APP_UID` and `APP_GID` can override those IDs when needed. The database therefore survives container replacement and image rebuilds at the visible host path `./data/investment-assistant.db`. Stop the service with:

```bash
docker compose down
```

The database remains in `./data` after `docker compose down`.

## Configuration

Spring reads defaults from `src/main/resources/application.yml`. Environment variables can override them:

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `APP_DATABASE_PATH` | `./data/investment-assistant.db` | SQLite database file |
| `APP_UID` | `1000` | Container process UID used by Docker Compose |
| `APP_GID` | `1000` | Container process GID used by Docker Compose |
| `APP_TELEGRAM_ENABLED` | `false` | Reserved for Telegram integration |
| `APP_OPENAI_ENABLED` | `false` | Reserved for OpenAI integration |
| `APP_IBKR_ENABLED` | `false` | Reserved for IBKR integration |
| `APP_MARKET_DATA_ENABLED` | `false` | Reserved for market-data integration |

`.env.example` documents the available settings. Copy it to `.env` if desired; `.env`, SQLite files, and the whole local `data` directory are ignored by Git. The reserved integration flags do not activate any integration in Stage 1.

## Planned stages

Future stages can add Telegram collection and delivery, OpenAI-based analysis, IBKR portfolio access, market events, earnings and SEC filing collection, and daily/weekly reporting. Those capabilities are outside this stage and are not stubbed with speculative implementation code.
