# braseth-back-end

Backend API for the Braseth printing shop e-commerce site: catalogue, customer
accounts, quotes, orders, design uploads and the staff admin area.

Built with Java 21, Spring Boot 3.5 (WebFlux, reactive R2DBC), PostgreSQL,
Liquibase, MinIO (S3-compatible file storage) and JWT authentication.

## Requirements

- JDK 21
- Docker (for PostgreSQL, MinIO and the integration tests)

Maven is not needed; use the bundled wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Getting started

**1. Start PostgreSQL and MinIO**

```sh
docker compose up -d                  # PostgreSQL on localhost:5445
docker compose -f minio.yaml up -d    # MinIO API on :9000, console on :9001
```

The MinIO setup also creates the `product-media` bucket.

**2. Create the local database** (once)

```sh
docker exec braseth_ecommerce-postgres psql -U braseth_ecommerce -c "CREATE DATABASE braseth_local"
```

**3. Run the API with the `local` profile**

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile:

- uses the `braseth_local` database and runs the Liquibase migrations,
  including dev sign-in accounts and sample catalogue data;
- writes verification and reset e-mail links to the log instead of sending mail;
- ships a local-only JWT secret, so no environment variables are required;
- points e-mail links at the Angular dev server (`http://localhost:4200`).

The API is then available at `http://localhost:8080`:

| URL | What |
| --- | --- |
| `/swagger-ui.html` | Interactive API docs |
| `/actuator/health` | Health check |
| `/api/v1/...` | API (`auth`, `products`, `me`, `admin`, ...) |

## Configuration

Settings come from environment variables. `.env.example` lists them all with
their local defaults; copy it to `.env` (never commit `.env`) or set the values
in your IDE run configuration or on the server.

| Variable | Purpose |
| --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection |
| `JWT_SECRET` | Token signing key. **Required** outside the `local` profile. Generate with `openssl rand -hex 32` |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | Gmail account and app password for account e-mails |
| `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` | File storage |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | Optional. Posts each new quote request to the staff Telegram chat (see below) |

In production, set a fresh `JWT_SECRET` and replace the default database and
MinIO credentials.

### Telegram alerts for quote requests

Every quote request is saved to the database. With the two Telegram variables
set, the API also posts it to a staff chat: who asked, how to reach them, the
products, notes and a link to the request in the staff area.

1. In Telegram, message [@BotFather](https://t.me/BotFather), send `/newbot`
   and copy the token it gives you into `TELEGRAM_BOT_TOKEN`.
2. Send your new bot any message (or add it to the staff group and post there).
3. Open `https://api.telegram.org/bot<token>/getUpdates` and copy
   `result[].message.chat.id` into `TELEGRAM_CHAT_ID`. A group's id is negative.
4. Restart the API. The `local` profile also reads both values from `.env`.

If Telegram is down or the values are wrong, the request is still saved and the
customer still sees it as sent; the API logs `Telegram alert not sent`.

## Tests

```sh
./mvnw test      # unit tests
./mvnw verify    # unit tests + *IT integration tests
```

Integration tests start a real PostgreSQL with Testcontainers, so Docker must be
running. GitHub Actions runs `./mvnw verify` on every push to `main` and on every
pull request (`.github/workflows/ci.yml`).

## Deployment

Every push to `main` that passes CI publishes a Docker image to
`ghcr.io/reaksarom/braseth-back-end`, tagged `latest` and with the commit SHA.
The image runs with the `prod` profile, which applies the Liquibase migrations
on startup (without the `local` dev accounts and sample data).

On a server with Docker:

```sh
# Copy docker-compose.prod.yml and .env.prod.example to the server, then:
cp .env.prod.example .env            # fill in every value
docker compose -f docker-compose.prod.yml up -d
```

To update, pull the new image and restart:

```sh
docker compose -f docker-compose.prod.yml pull api
docker compose -f docker-compose.prod.yml up -d
```

To roll back, set `API_TAG` in `.env` to an earlier commit SHA and run `up -d`.

Notes:

- Put a reverse proxy with HTTPS (Caddy, Nginx, Traefik) in front of port
  8080 (API) and port 9000 (MinIO). `MINIO_PUBLIC_URL` must be the address
  browsers use for MinIO, because download links are signed for that host.
- If the image is private on GitHub, run `docker login ghcr.io` on the server
  with a token that has `read:packages`.
- The `V002` seed migration creates staff accounts with the password
  `Admin@1234`. Change those passwords right after the first deploy.
- Swagger UI is disabled in production.

## Project layout

```
src/main/java/com/braseth/ecommerce/
  common/          shared entities, errors, utilities
  config/          application configuration
  infrastructure/  mail and MinIO storage
  modules/         features: auth, account, catalogue, content, design,
                   order, staff, submission, user
  security/        JWT filter and Spring Security setup
src/main/resources/
  db/changelog/    Liquibase migrations
```
