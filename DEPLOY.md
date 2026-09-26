# Deployment — Render + Supabase + OpenClaw WhatsApp

## Production topology

Production uses:

- **Render Singapore** for the Spring Boot backend and bundled React dashboard.
- **Supabase Singapore (`ap-southeast-1`)** for PostgreSQL and private document storage.
- A separate persistent **OpenClaw + Ollama host** for the dedicated WhatsApp session and local Ollama models.

Health check: `/actuator/health`.

Spring Boot remains the only application component allowed to write authoritative accounting data. OpenClaw and Ollama call signed Spring APIs and never connect directly to PostgreSQL or Supabase Storage.

## Supabase database

Flyway remains the authority for the restaurant business schema. On the first connection to an empty Supabase database, Spring applies the repository migrations in `src/main/resources/db/migration`.

Business tables in the exposed `public` schema have RLS enabled, and the Supabase `anon` and `authenticated` roles have table privileges revoked. Do not add permissive Data API policies for these accounting tables. The dashboard uses Spring APIs rather than querying Supabase directly.

### Render database connection

Supabase direct PostgreSQL endpoints are IPv6 by default. For the persistent Render Spring backend, use the Supabase **shared pooler in session mode**.

In Supabase, open **Connect → Session pooler** and configure these Render environment variables from that connection information:

- `DATABASE_HOST` — Supavisor session-pooler host
- `DATABASE_PORT=5432`
- `DATABASE_NAME=postgres`
- `DATABASE_USERNAME` — full Supavisor username, normally `postgres.<project-ref>`
- `DATABASE_PASSWORD` — Supabase database password

Do not use transaction-pooler port `6543` for the long-running Spring/JPA service unless it is deliberately reconfigured and tested for transaction pooling. Never commit the database password or connection string.

## Supabase private document storage

Production source documents live in the private bucket:

```text
restaurant-documents
```

The bucket is private, has a 20 MB per-object limit, and is restricted to application-supported image, PDF, CSV, Excel and generic binary MIME types.

Spring uses Supabase Storage's S3-compatible server-side endpoint through the existing `S3CompatibleDocumentStorageService`. Generate a dedicated S3 access-key pair in **Supabase → Storage → S3 Configuration** and configure only the Render server with:

- `STORAGE_MODE=s3`
- `S3_ENDPOINT=https://<project-ref>.storage.supabase.co/storage/v1/s3`
- `S3_REGION=ap-southeast-1`
- `S3_BUCKET=restaurant-documents`
- `S3_ACCESS_KEY`
- `S3_SECRET_KEY`

Supabase S3 access keys bypass Storage RLS and must never be exposed to React, OpenClaw, Ollama, WhatsApp, or source control. The database stores object locations/checksums and transaction references; file bytes remain in private Storage.

## Render service

The Render web service deploys from `main` in Singapore. Keep the existing Render PostgreSQL database available as a rollback source until the Supabase cutover and data verification are complete.

Move the Render web service off the free validation plan before relying on it for production availability. Do not delete the old Render database until data migration, application smoke tests, and rollback verification have passed.

## Required environment variables

### Core

- `DATABASE_HOST`
- `DATABASE_PORT`
- `DATABASE_NAME`
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`
- `APP_OWNER_USERNAME`
- `APP_OWNER_PASSWORD`
- `NORMALIZATION_AUTO_POST_THRESHOLD` (default `0.85`)
- `OPENCLAW_BACKEND_SHARED_SECRET`

### Document storage

Local development:

- `STORAGE_MODE=local`
- `STORAGE_LOCAL_ROOT=./storage`

Supabase production:

- `STORAGE_MODE=s3`
- `S3_ENDPOINT`
- `S3_REGION=ap-southeast-1`
- `S3_BUCKET=restaurant-documents`
- `S3_ACCESS_KEY`
- `S3_SECRET_KEY`

### OpenClaw / Ollama

The persistent OpenClaw host uses:

- `OPENCLAW_GATEWAY_TOKEN`
- `OPENCLAW_BACKEND_BASE_URL=https://bombay-restaurant-intelligence.onrender.com`
- `OPENCLAW_BACKEND_SHARED_SECRET` — same shared secret as Spring
- `OLLAMA_BASE_URL=http://127.0.0.1:11434`

See `OPENCLAW_HOST_SETUP.md` for model installation, plugin setup, WhatsApp QR login, allowlists, readiness checks, and final acceptance tests.

### Legacy Meta webhook adapter

The repository still contains the Meta WhatsApp webhook adapter for compatibility/testing. The intended production group workflow uses the dedicated OpenClaw WhatsApp channel instead of making the Meta webhook adapter the accounting boundary.

## Render build/start

When using the included Dockerfile, Render builds and starts the container directly. Runtime command:

```text
java -Dserver.port=${PORT} -jar app.jar
```

Native-equivalent build:

```text
cd frontend && npm install --no-audit --no-fund && npm run build && cd .. && rm -rf src/main/resources/static && mkdir -p src/main/resources/static && cp -R frontend/dist/. src/main/resources/static/ && mvn -B clean test package
```

## Controlled Supabase cutover

Do not switch live production credentials until the Supabase branch has passed CI.

1. Keep the existing Render database untouched as rollback.
2. Confirm the Supabase Singapore project and private `restaurant-documents` bucket are healthy.
3. Obtain the Supabase session-pooler connection information and server-side S3 credentials directly from Supabase.
4. Export/copy any existing records that must be retained from the old Render database using a controlled migration path.
5. Point a controlled Spring deployment at Supabase so Flyway applies and validates the schema.
6. Verify table counts and critical records in Supabase before changing the live service.
7. Configure the live Render service with the Supabase session-pooler variables and S3 variables.
8. Verify `/actuator/health` and signed `restaurant_health`.
9. Test text ingestion, image storage, CSV/XLS/XLSX, duplicate handling, review-required behavior, analytics, and receipt/file retrieval.
10. Keep the old Render database through the rollback window; only then retire it.

## Historical backfill

Do not block launch on historical data. TXT/ZIP WhatsApp exports, historical Excel reports, and Zomato/Swiggy settlements must feed the same `IntermediateBusinessRecord -> NormalizationAgent -> transactions` pipeline and preserve original business dates.
