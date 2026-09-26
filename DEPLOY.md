# Deployment — Render + Meta WhatsApp

## Render topology

Production uses one web service plus one Render PostgreSQL database in **Singapore**. The repository includes a multi-stage `Dockerfile` that:

1. builds React with Node 22,
2. copies `frontend/dist` into Spring Boot static resources,
3. runs Maven tests/package with Java 21,
4. runs one Java 21 service.

Health check: `/actuator/health`.

The current `render.yaml` is intentionally configured for a **free validation web service** and references the existing Render database named `bombay-restaurant-intelligence-db`. It does not define a second database, so applying the Blueprint reuses the existing database and obtains host/port/database/user/password through Render-managed `fromDatabase` references. No database password belongs in GitHub or in this document.

For production WhatsApp webhook reliability, upgrade the web service to an always-on paid plan after validation. The existing free Render Postgres instance is also suitable only for validation; select an appropriate persistent paid Postgres plan before relying on it for production records. Do not change plans until the account owner has explicitly approved the associated charges.

## Initial Blueprint deployment

1. In the Render Dashboard choose **New → Blueprint**.
2. Select `treddy-sudo/bombay-restaurant-intelligence` and the `main` branch.
3. Render will read the repository-root `render.yaml`.
4. Confirm the existing database reference `bombay-restaurant-intelligence-db`.
5. Enter a strong value for `APP_OWNER_PASSWORD` when Render prompts for the `sync: false` variable. The initial username is `owner` and can be changed later in the service environment settings.
6. Apply the Blueprint.
7. Wait for the Docker build and deploy to complete, then verify `/actuator/health` returns `UP`.
8. Sign in to the dashboard with the owner credentials and perform the manual text smoke test `Paid Salman 6500 for vegetables`.

The GitHub CI workflow independently builds the React production bundle, runs all Maven tests/package, scans for committed secrets, and builds the same multi-stage Docker image used by Render.

## Required environment variables

### Core

- `DATABASE_HOST` — supplied by Render from the existing database
- `DATABASE_PORT` — supplied by Render from the existing database
- `DATABASE_NAME` — supplied by Render from the existing database
- `DATABASE_USERNAME` — supplied by Render from the existing database
- `DATABASE_PASSWORD` — supplied by Render from the existing database
- `APP_OWNER_USERNAME`
- `APP_OWNER_PASSWORD`
- `NORMALIZATION_AUTO_POST_THRESHOLD` (default `0.85`)

### WhatsApp

- `WHATSAPP_MODE=mock|meta`
- `WHATSAPP_ACCESS_TOKEN`
- `WHATSAPP_PHONE_NUMBER_ID`
- `WHATSAPP_BUSINESS_ACCOUNT_ID`
- `WHATSAPP_VERIFY_TOKEN`
- `WHATSAPP_APP_SECRET` (recommended; enables webhook signature validation)
- optional `WHATSAPP_GRAPH_BASE_URL` to update Meta Graph API version without code changes

### AI extraction

- `AI_EXTRACTION_MODE=mock|http`
- `AI_EXTRACTION_URL`
- `AI_API_KEY`

### Document storage

Default local storage:

- `STORAGE_MODE=local`
- `STORAGE_LOCAL_ROOT=./storage`

S3-compatible mode:

- `STORAGE_MODE=s3`
- `S3_ENDPOINT`
- `S3_REGION`
- `S3_BUCKET`
- `S3_ACCESS_KEY`
- `S3_SECRET_KEY`

Local Render filesystem storage should be treated as temporary. Configure S3-compatible storage before relying on uploaded source documents as durable production evidence.

Never commit any real value from the secret variables above.

## Render build/start

When using the included Dockerfile, Render builds and starts the container directly. Runtime command:

```text
java -Dserver.port=${PORT} -jar app.jar
```

Native-equivalent build:

```text
cd frontend && npm install --no-audit --no-fund && npm run build && cd .. && rm -rf src/main/resources/static && mkdir -p src/main/resources/static && cp -R frontend/dist/. src/main/resources/static/ && mvn -B clean test package
```

Start command:

```text
java -Dserver.port=$PORT -jar target/restaurant-intelligence-0.1.0.jar
```

## Meta account steps after app deployment

1. Create/select the Meta app and WhatsApp Business account.
2. Attach the production business phone number.
3. Generate/configure a production access token and put it only in Render environment variables.
4. Set callback URL to `https://YOUR_RENDER_HOST/api/whatsapp/webhook`.
5. Set Meta's verify token to the exact `WHATSAPP_VERIFY_TOKEN` stored in Render.
6. Subscribe the WhatsApp webhook to `messages` events.
7. Configure `WHATSAPP_APP_SECRET` so inbound webhook signatures are validated.
8. Switch `WHATSAPP_MODE` from `mock` to `meta` and deploy.
9. Send `Paid Salman 4200 vegetables` and verify the acknowledgement plus dashboard update.
10. If the Meta account later receives Groups API access, add group-event parsing only at the WhatsApp adapter; no downstream agent/schema changes are required.

## Historical backfill

Do not block launch on history. Live data begins immediately after deployment. Future TXT/ZIP WhatsApp exports, historical Excel reports, and Zomato/Swiggy settlements must feed the same `IntermediateBusinessRecord -> NormalizationAgent -> transactions` pipeline and preserve original business dates.
