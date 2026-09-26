# Bombay Restaurant Intelligence

Private, single-owner restaurant intelligence application for one restaurant business in India. It starts collecting clean data immediately and keeps the canonical accounting record in PostgreSQL. There is no public signup, SaaS tenancy, subscription system, Kafka, Redis, vector database, or microservice layer.

## Architecture

Exactly three logical agents are used:

1. **Intake Agent** — WhatsApp text/images/documents, XLS/XLSX, CSV, dashboard uploads, and manual text become `IntermediateBusinessRecord` candidates.
2. **Normalization Agent** — validates amount/date, resolves category/vendor/employee aliases, learns owner corrections, blocks duplicates, auto-posts high-confidence records, and sends uncertain records to review.
3. **Analytics Agent** — reads only `VERIFIED` transactions. Java performs every total and KPI using `BigDecimal`; optional AI may only explain numbers already calculated by Java.

```text
WhatsApp / Image / Excel / CSV / Manual Text
        -> Intake Agent
        -> Normalization Agent
        -> PostgreSQL (source of truth)
        -> Analytics Agent
        -> Owner Dashboard
```

## First vertical slice

`Paid Salman 6500 for vegetables`

becomes a `VENDOR_PAYMENT`, category `VEGETABLES`, vendor `Salman`, amount `₹6,500.00`, status `VERIFIED`, and immediately appears in dashboard totals. The dashboard input uses the exact same intake/normalization path as WhatsApp and files.

## Data integrity rules

- Money is always Java `BigDecimal` / PostgreSQL `NUMERIC(19,2)`.
- Only `VERIFIED` transactions affect official totals.
- WhatsApp IDs, file SHA-256 checksums, source-row IDs, and normalized fingerprints enforce idempotency.
- Source metadata remains attached to transactions and is visible from the dashboard.
- Manual review corrections create audit-log entries.
- Correction mappings such as `sabji -> VEGETABLES` are persisted and reused without model training.
- Spreadsheet source-column mappings can be saved through `/api/source-column-mappings` for recurring report layouts.

## Technology

- Java 21, Spring Boot 3, Maven
- Spring Web, WebClient, Data JPA, Security, Bean Validation, Actuator
- PostgreSQL + Flyway
- Apache POI + Apache Commons CSV
- React 18, Vite, TypeScript, Recharts
- Local document storage by default; S3-compatible implementation is included for later production storage
- GitHub Actions CI
- Render deployment (one web service + PostgreSQL)

## Local setup

Requirements: Java 21, Maven 3.9+, Node 22+, npm, Docker (for local PostgreSQL).

```bash
cp .env.example .env
docker compose up -d postgres
cd frontend && npm install && npm run build && cd ..
rm -rf src/main/resources/static && mkdir -p src/main/resources/static
cp -R frontend/dist/. src/main/resources/static/
mvn clean test package
set -a && source .env && set +a
java -jar target/restaurant-intelligence-0.1.0.jar
```

Open `http://localhost:8080`. Default values in `.env.example` are development-only; change the owner password before any reachable deployment.

For frontend hot reload, run `npm run dev` inside `frontend/`; Vite proxies `/api` and `/actuator` to Spring Boot on port 8080.

## Sample requests

```bash
curl -u owner:YOUR_PASSWORD -H 'Content-Type: application/json' -d '{"text":"Paid Salman 6500 for vegetables"}' http://localhost:8080/api/intake/manual-text
curl -u owner:YOUR_PASSWORD -F file=@purchase-report.xlsx http://localhost:8080/api/intake/uploads/preview
curl -u owner:YOUR_PASSWORD -X POST http://localhost:8080/api/intake/uploads/JOB_UUID/confirm
```

## WhatsApp

The default `WHATSAPP_MODE=mock` requires no Meta credentials. Set `WHATSAPP_MODE=meta` for production. The webhook supports Meta verification, inbound message IDs/senders/timestamps, text, image, documents, media download, duplicate prevention, acknowledgements, and optional `X-Hub-Signature-256` verification when `WHATSAPP_APP_SECRET` is configured.

Primary production usage is direct messages to the connected business number. Group support is deliberately not a dependency; if the Meta account later qualifies for Groups API, group events can feed the same `WhatsAppWebhookService -> IntakeAgent` pipeline.

## AI extraction contract

`AI_EXTRACTION_MODE=mock` is credential-free. `AI_EXTRACTION_MODE=http` calls the configured `AI_EXTRACTION_URL` with a bearer `AI_API_KEY` and expects strict JSON matching `List<IntermediateBusinessRecord>`. Free-form model text never writes accounting data directly; the Normalization Agent still validates every candidate.

## Analytics semantics

Sales are `SALE` transaction rows. Cash outflows (`EXPENSE`, `VENDOR_PAYMENT`, `SALARY`, `EMPLOYEE_ADVANCE`, `ADVERTISING`) are included in dashboard expense totals; `SETTLEMENT` and `REFUND` are excluded from that expense aggregate. Comparisons return `Not enough data for comparison` when the prior period has no verified data.

The UI displays `Data available from <first verified transaction date>` and never invents historical comparison values.

## Tests and CI

GitHub Actions runs the React build, backend tests, Maven package, and a secret-pattern check on every push/PR. The automated suite covers manual parsing, rupee/comma amounts, vendor detection, vegetable and `sabji` normalization, employee advance, Zomato advertising context, duplicate sources/files, low-confidence review, unknown categories, approval/rejection audit behavior, deterministic/date/category/vendor analytics, WhatsApp webhook verification/signatures, WhatsApp text processing, and duplicate message idempotency.

See [DEPLOY.md](DEPLOY.md) for Render and Meta setup.
