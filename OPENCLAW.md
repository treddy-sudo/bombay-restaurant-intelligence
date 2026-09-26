# OpenClaw + Ollama Architecture

This repository keeps Spring Boot/PostgreSQL as the accounting authority and adds OpenClaw/Ollama as an orchestration and understanding layer.

## Trust boundary

```text
OpenClaw -> Ollama -> signed Spring internal API -> IntakeAgent/NormalizationAgent -> PostgreSQL
OpenClaw -> approved intent -> signed Spring analytics API -> VERIFIED rows -> Java BigDecimal -> Ollama formatting
OpenClaw -> CSV/XLS/XLSX bytes -> signed Spring upload API -> deterministic parser -> IntakeAgent/NormalizationAgent -> PostgreSQL
```

Ollama produces candidates and human-readable wording only. It never writes PostgreSQL, marks records verified, calculates official totals, or parses spreadsheets in Batch 3.

## Batch 1: text + approved dashboard question

Batch 1 intentionally does not modify production WhatsApp behavior. It proves two text-only flows:

1. `Paid Salman 6500 vegetables`
   - OpenClaw router classifies `DATA_TEXT`.
   - Ollama returns a strict structured candidate.
   - `restaurant_ingest_text` signs a request to Spring.
   - Spring builds an `IntermediateBusinessRecord` and calls `IntakeAgent`.
   - `NormalizationAgent` alone decides `VERIFIED` or `REVIEW_REQUIRED` and persists the record.

2. `What are today's sales?`
   - OpenClaw router classifies `DASHBOARD_QUESTION` + `TODAY_SALES`.
   - `restaurant_get_sales` calls the signed Spring analytics endpoint.
   - Spring reads only verified transactions and calculates the total in Java.
   - Ollama formats only the backend-provided value.

## Batch 2: strict image extraction

Image flow:

```text
image bytes
 -> restaurant_ingest_image
 -> Ollama native vision /api/chat with strict JSON schema
 -> optional one-time configured vision fallback
 -> signed Spring image-candidates endpoint
 -> server-side type/size/checksum validation + source storage + duplicate check
 -> IntakeAgent
 -> NormalizationAgent
 -> VERIFIED or REVIEW_REQUIRED
 -> PostgreSQL / verified-only analytics
```

The OpenClaw vision prompt treats pixels, OCR text, handwriting, QR text, screenshots, and visible commands as untrusted document content. Spring independently validates JPEG/PNG/WEBP type, size, checksum, filename extension, duplicate status, and source traceability. Model records contain candidate fields only and never an approval field.

## Batch 3: deterministic CSV/XLS/XLSX

Spreadsheet flow:

```text
CSV/XLS/XLSX bytes
 -> restaurant_preview_spreadsheet
 -> signed Spring spreadsheet preview endpoint
 -> extension/content-type/size validation
 -> existing UploadIngestionService
 -> Apache Commons CSV or Apache POI
 -> saved source-column mappings
 -> checksum dedupe + preview job
 -> restaurant_confirm_spreadsheet
 -> IntakeAgent
 -> NormalizationAgent
 -> VERIFIED or REVIEW_REQUIRED
 -> PostgreSQL / verified-only analytics
```

Batch 3 deliberately does **not** send spreadsheet contents to Ollama. Parsing, row identity, amounts, checksums, column mapping, deduplication, preview, confirmation, normalization, and persistence remain deterministic Java/Spring operations.

The same `UploadIngestionService` is used by the dashboard multipart upload path and the OpenClaw signed JSON path. This prevents a second spreadsheet ingestion implementation from drifting away from the existing application behavior.

The signed spreadsheet endpoint accepts only:

- `.csv` with `text/csv` or `application/csv`;
- `.xls` with `application/vnd.ms-excel`;
- `.xlsx` with `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.

Decoded files are limited to 10 MB. Preview checksum duplicates are rejected before import. Confirmation reuses the existing preview job and sends every parsed row through `IntakeAgent -> NormalizationAgent`.

Semantic header interpretation by Ollama is not enabled in Batch 3. Unknown headers continue to use deterministic parser behavior and saved `source_column_mappings`; a future semantic suggestion layer may propose mappings, but it must never bypass preview/confirmation or deterministic normalization.

## OpenClaw tools through Batch 3

- `restaurant_route_text`
- `restaurant_ingest_text`
- `restaurant_ingest_image`
- `restaurant_preview_spreadsheet`
- `restaurant_confirm_spreadsheet`
- `restaurant_get_sales`

The example configuration restricts the agent tool catalog to these tools. No shell, filesystem mutation, database, browser, or arbitrary HTTP tool is required for the restaurant agent.

## Signed internal API

Spring exposes:

- `POST /api/internal/v1/intake/text-candidate`
- `POST /api/internal/v1/intake/image-candidates`
- `POST /api/internal/v1/intake/spreadsheets/preview`
- `POST /api/internal/v1/intake/spreadsheets/{jobId}/confirm`
- `GET /api/internal/v1/analytics/query?intent=TODAY_SALES`

Requests require:

- `X-Restaurant-Timestamp`
- `X-Restaurant-Request-Id`
- `X-Restaurant-Signature`

The signature is HMAC-SHA256 over:

```text
timestamp\nrequestId\nHTTP_METHOD\npathAndQuery\nsha256(body)
```

Spring rejects missing/invalid signatures, timestamps outside a five-minute window, and replayed request IDs. `OPENCLAW_BACKEND_SHARED_SECRET` must be present on both the OpenClaw host and Spring service.

## Ollama configuration

All logical model choices are environment driven:

- `OLLAMA_ROUTER_MODEL` (default `qwen3.5:9b`)
- `OLLAMA_TEXT_MODEL` (default `qwen3.5:9b`)
- `OLLAMA_VISION_MODEL` (default `qwen3.5:9b`)
- `OLLAMA_VISION_FALLBACK` (default `gemma4:12b`)
- `OLLAMA_REASONING_MODEL` (preferred `qwen3.5:27b`, reserved for later approved workflows)
- `OLLAMA_RESPONSE_MODEL` (default `qwen3.5:9b`)

Use Ollama's native endpoint (`OLLAMA_BASE_URL`, normally `http://127.0.0.1:11434`), not `/v1`.

## Local setup through Batch 3

1. Start Spring/PostgreSQL as documented in the root README.
2. Set the same strong `OPENCLAW_BACKEND_SHARED_SECRET` for Spring and OpenClaw.
3. Start Ollama for text/image workflows. Spreadsheet preview/confirm itself does not require Ollama.
4. Install/validate the plugin:

```bash
cd openclaw/restaurant-tools
npm install
npm test
npm run plugin:check
openclaw plugins install --link .
openclaw plugins enable bombay-restaurant-tools
```

5. Adapt `openclaw/openclaw.batch3.example.json5` into the local OpenClaw configuration.

WhatsApp channel configuration is deliberately deferred until Batch 4/5.

## Automated coverage through Batch 3

In addition to the Batch 1/2 text and vision coverage, Batch 3 tests:

- signed XLSX preview;
- signed XLSX confirmation through `IntakeAgent/NormalizationAgent`;
- signed CSV preview and confirmation;
- verified spreadsheet transactions affecting dashboard totals;
- checksum duplicate rejection;
- content-type / extension mismatch rejection;
- OpenClaw signed preview requests carrying raw file Base64 directly to Spring;
- OpenClaw signed confirm requests with an empty body;
- plugin manifest exposure for the two spreadsheet tools.

## Future batches

- Batch 4: dedicated WhatsApp DM connection through OpenClaw.
- Batch 5: allowlisted always-listening management group with silent ingestion and per-group isolation.
- Batch 6: monitoring, retry/fallback policy, review notifications, backups, model/storage health, and production hardening.
