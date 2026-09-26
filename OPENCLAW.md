# OpenClaw + Ollama Architecture

This repository keeps Spring Boot/PostgreSQL as the accounting authority and adds OpenClaw/Ollama as an orchestration and understanding layer.

## Trust boundary

```text
Meta WhatsApp Cloud API -> signed Meta webhook -> Spring gateway -> OpenClaw restaurant tool -> signed Spring internal API -> IntakeAgent/NormalizationAgent -> PostgreSQL
OpenClaw -> Ollama -> candidate extraction/routing only
OpenClaw -> approved intent -> signed Spring analytics API -> VERIFIED rows -> Java BigDecimal -> Ollama wording
CSV/XLS/XLSX -> deterministic Spring parser -> IntakeAgent/NormalizationAgent -> PostgreSQL
```

Ollama produces candidates, routing decisions, vision extraction, and human-readable wording only. It never writes PostgreSQL, marks records verified, calculates official totals, or parses spreadsheets.

## Batch 1: text + approved dashboard question

`restaurant_route_text` classifies restaurant text. Data candidates go back through signed Spring intake and `NormalizationAgent`; approved dashboard questions read only verified Java-calculated values from Spring.

## Batch 2: strict image extraction

`restaurant_ingest_image` uses Ollama native vision with a strict JSON schema and one configured fallback. Spring independently validates image type, size, checksum, extension, duplicate status, source traceability, and final normalization status. The model has no approval field.

## Batch 3: deterministic CSV/XLS/XLSX

`restaurant_preview_spreadsheet` and `restaurant_confirm_spreadsheet` call the same Spring `UploadIngestionService` used by the dashboard. Apache POI / Commons CSV, saved source-column mappings, checksum dedupe, preview, confirmation, and normalization stay deterministic. Spreadsheet contents are not sent to Ollama.

## Batch 4: Meta WhatsApp DM -> OpenClaw

Batch 4 keeps the required **official WhatsApp Business Cloud API** integration at Spring. It does not switch the application to OpenClaw's separate WhatsApp Web/Baileys channel.

Inbound DM flow:

```text
Meta webhook
 -> Spring /api/whatsapp/webhook
 -> X-Hub-Signature-256 verification
 -> WhatsApp message-id dedupe
 -> Meta media download when needed
 -> OpenClaw Gateway /tools/invoke
 -> allowlisted Bombay restaurant tool
 -> Ollama only where that tool requires understanding
 -> HMAC-signed Spring internal API
 -> IntakeAgent -> NormalizationAgent -> PostgreSQL
 -> deterministic acknowledgement via Meta Cloud API
```

### Text DMs

Spring invokes `restaurant_route_text` with:

- the original Meta message id as `sourceId`;
- sender number for source traceability;
- `sourceType=WHATSAPP_TEXT`;
- a stable OpenClaw idempotency key derived from the Meta message id and tool name.

For a data message such as `Paid Salman 4200 vegetables`, OpenClaw returns the Spring normalization result and Spring formats the WhatsApp acknowledgement. For an approved dashboard question, Spring sends the tool's verified-data reply. `IGNORE` produces no accounting write.

### Image DMs

Spring downloads image bytes with the Meta access token and invokes `restaurant_ingest_image` with `sourceType=WHATSAPP_IMAGE`. The image then follows the Batch 2 vision boundary and signed Spring intake.

OpenClaw `/tools/invoke` has a default 2 MB request-body limit. Because Base64 and JSON add overhead, `OPENCLAW_WHATSAPP_MAX_MEDIA_BYTES` defaults to `1250000` bytes and may not exceed `1500000` in this application. Larger/unsupported images stay on the existing Spring ingestion fallback instead of being dropped.

### Spreadsheet DMs

CSV/XLS/XLSX documents under the bridge size limit use the Batch 3 tools:

1. Spring downloads the Meta document.
2. Spring invokes `restaurant_preview_spreadsheet`.
3. WhatsApp receives `Previewed N rows ... Reply CONFIRM <jobId> to import.`
4. A later `CONFIRM <jobId>` DM invokes `restaurant_confirm_spreadsheet`.
5. Every confirmed row still passes through `IntakeAgent -> NormalizationAgent`.

Other documents, or files too large for the OpenClaw HTTP bridge, retain the existing Spring document ingestion path in Batch 4.

### Why Spring remains the Meta edge

The existing Spring webhook already owns Meta webhook verification, `X-Hub-Signature-256` validation, message-id dedupe, official Cloud API media download, and outbound Cloud API replies. Keeping those controls avoids duplicating Meta security code inside OpenClaw and preserves the project's Cloud API requirement.

OpenClaw's `/tools/invoke` bearer credential is a trusted operator credential for the Gateway. Treat `OPENCLAW_GATEWAY_TOKEN` as a high-value secret, keep the Gateway behind loopback plus a controlled HTTPS tunnel/reverse proxy or other private ingress, and never commit the token. The Batch 4 example keeps `gateway.bind=loopback` and uses environment substitution for the token.

## Restaurant tool surface through Batch 4

- `restaurant_route_text`
- `restaurant_ingest_text`
- `restaurant_ingest_image`
- `restaurant_preview_spreadsheet`
- `restaurant_confirm_spreadsheet`
- `restaurant_get_sales`

The OpenClaw configuration allowlists only these tools for the restaurant Gateway. No shell, filesystem mutation, database, browser, or arbitrary HTTP tool is required by this application.

## Signed Spring internal API

Spring exposes:

- `POST /api/internal/v1/intake/text-candidate`
- `POST /api/internal/v1/intake/image-candidates`
- `POST /api/internal/v1/intake/spreadsheets/preview`
- `POST /api/internal/v1/intake/spreadsheets/{jobId}/confirm`
- `GET /api/internal/v1/analytics/query?intent=TODAY_SALES`

Requests require `X-Restaurant-Timestamp`, `X-Restaurant-Request-Id`, and `X-Restaurant-Signature`. The signature is HMAC-SHA256 over:

```text
timestamp\nrequestId\nHTTP_METHOD\npathAndQuery\nsha256(body)
```

Spring rejects invalid signatures, stale timestamps, and replayed request ids. `OPENCLAW_BACKEND_SHARED_SECRET` must be the same strong secret on Spring and OpenClaw. It is separate from `OPENCLAW_GATEWAY_TOKEN`.

## Batch 4 configuration

### OpenClaw host

Use `openclaw/openclaw.batch4.example.json5` and set trusted environment variables:

```bash
export OPENCLAW_GATEWAY_TOKEN='<long-random-gateway-token>'
export OPENCLAW_BACKEND_BASE_URL='https://<spring-service-host>'
export OPENCLAW_BACKEND_SHARED_SECRET='<separate-long-random-backend-secret>'
export OLLAMA_BASE_URL='http://127.0.0.1:11434'
```

Then validate/install as before:

```bash
cd openclaw/restaurant-tools
npm install
npm test
npm run plugin:check
openclaw plugins install --link .
openclaw plugins enable bombay-restaurant-tools
openclaw config validate
```

Expose the loopback Gateway to Spring only through a controlled HTTPS/private ingress and set that resulting URL on Spring. Do not expose an unauthenticated Gateway to the public internet.

### Spring / Render

Keep Batch 4 off until the Gateway is reachable and verified:

```text
WHATSAPP_MODE=meta
WHATSAPP_OPENCLAW_ENABLED=true
OPENCLAW_GATEWAY_URL=https://<controlled-openclaw-gateway-host>
OPENCLAW_GATEWAY_TOKEN=<same gateway token>
OPENCLAW_WHATSAPP_MAX_MEDIA_BYTES=1250000
OPENCLAW_BACKEND_SHARED_SECRET=<same Spring/OpenClaw backend secret>
```

Meta credentials remain the existing `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_BUSINESS_ACCOUNT_ID`, `WHATSAPP_VERIFY_TOKEN`, and `WHATSAPP_APP_SECRET` values.

`WHATSAPP_OPENCLAW_ENABLED` defaults to `false`, so deploying Batch 4 code alone does not change the currently live WhatsApp path.

## Automated coverage through Batch 4

Batch 4 adds tests that prove:

- legacy WhatsApp routing still works while the bridge is disabled;
- duplicate Meta message ids are rejected before OpenClaw;
- text data DMs invoke `restaurant_route_text` with `WHATSAPP_TEXT`;
- dashboard questions return the OpenClaw tool reply;
- Meta image bytes invoke `restaurant_ingest_image` and use Spring normalization for acknowledgement;
- spreadsheet DMs preview without importing;
- `CONFIRM <jobId>` invokes the confirm tool;
- Gateway requests use bearer auth, WhatsApp channel context, sender target context, and a stable idempotency key;
- the media bridge limit protects OpenClaw's HTTP request-body boundary;
- enabling the bridge without Gateway URL/token fails fast.

## Future batches

- Batch 5: allowlisted always-listening management group with silent ingestion and per-group isolation.
- Batch 6: monitoring, retry/fallback policy, review notifications, backups, model/storage health, and production hardening.
