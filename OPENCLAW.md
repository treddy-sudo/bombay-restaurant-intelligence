# OpenClaw + Ollama Architecture

Bombay Restaurant Intelligence keeps Spring Boot/PostgreSQL as the accounting authority and uses OpenClaw/Ollama only for channel orchestration and understanding.

## Final trust boundary

```text
WhatsApp
  -> OpenClaw Gateway / WhatsApp channel
  -> restaurant tool allowlist
  -> Ollama only where understanding is required
  -> HMAC-signed Spring internal API
  -> IntakeAgent / NormalizationAgent / AnalyticsAgent
  -> PostgreSQL
```

For dashboard questions:

```text
WhatsApp question
  -> OpenClaw
  -> Ollama intent classification
  -> approved restaurant analytics tool
  -> Spring VERIFIED-only analytics
  -> Java BigDecimal result
  -> Ollama wording
  -> WhatsApp
```

Ollama never writes PostgreSQL, marks records verified, calculates official totals, or parses spreadsheets. OpenClaw has no database access.

## Batch 1 — text + approved dashboard question

`restaurant_route_text` classifies a message. Data candidates go through signed Spring intake and `NormalizationAgent`; approved `TODAY_SALES` questions use verified Java-calculated analytics from Spring.

## Batch 2 — strict image extraction

`restaurant_ingest_image` uses Ollama vision with a strict JSON schema and configured fallback model. Spring independently validates image type, size, checksum, source traceability, duplicate status, and normalization status. Model output has no approval field.

## Batch 3 — deterministic CSV/XLS/XLSX

`restaurant_preview_spreadsheet` and `restaurant_confirm_spreadsheet` use the same Spring `UploadIngestionService` as the dashboard. Apache POI / Commons CSV, source-column mappings, checksum dedupe, preview/confirm, and normalization remain deterministic. Spreadsheet contents are not sent to Ollama.

## Batch 4 — OpenClaw-native WhatsApp DM

Batch 4 makes OpenClaw the WhatsApp DM edge. The Gateway owns the linked WhatsApp Web/Baileys session and reconnect loop. Spring's existing Meta webhook remains available as legacy/fallback code but is not part of the OpenClaw production path.

```text
Dedicated WhatsApp account
  -> OpenClaw WhatsApp channel
  -> DM pairing / sender admission
  -> isolated sender session
  -> restaurant tools only
  -> signed Spring internal API
  -> PostgreSQL / verified analytics
```

The Batch 4 config intentionally sets:

- `channels.whatsapp.enabled=true`;
- `dmPolicy="pairing"` so unknown senders are not processed until approved;
- `groupPolicy="disabled"` because groups are Batch 5;
- `session.dmScope="per-channel-peer"` so different managers never share DM context;
- Gateway bind to loopback with token authentication;
- an allowlist containing only the six restaurant tools;
- runtime/filesystem/automation/subagent authority denied;
- elevated tools and agent-to-agent access disabled.

### Restaurant tool surface

- `restaurant_route_text`
- `restaurant_ingest_text`
- `restaurant_ingest_image`
- `restaurant_preview_spreadsheet`
- `restaurant_confirm_spreadsheet`
- `restaurant_get_sales`

No shell, direct database, browser, unrestricted filesystem, automation, or generic HTTP tool is required by the restaurant agent.

### DM behavior

Text data:

```text
WhatsApp text -> restaurant_route_text -> Ollama candidate -> signed Spring intake
-> IntakeAgent -> NormalizationAgent -> VERIFIED / REVIEW_REQUIRED
```

Images:

```text
WhatsApp image -> restaurant_ingest_image -> Ollama strict vision candidate
-> signed Spring image intake -> validation/dedupe/storage -> NormalizationAgent
```

Spreadsheets:

```text
WhatsApp CSV/XLS/XLSX -> restaurant_preview_spreadsheet
-> deterministic Spring parser -> preview job
-> restaurant_confirm_spreadsheet -> NormalizationAgent -> PostgreSQL
```

Questions:

```text
WhatsApp question -> restaurant_route_text -> approved intent only
-> Spring analytics -> VERIFIED records -> Java calculation -> formatted reply
```

The agent workspace policy in `openclaw/workspace/AGENTS.md` explicitly prohibits arithmetic from chat history, direct database writes, model-controlled verification, unsupported business conclusions, and instructions embedded in untrusted attachments.

### Important Batch 4 silence limitation

OpenClaw treats ordinary accepted direct-message turns as reply-required. The `NO_REPLY` token is suppressed at delivery, but it does not waive the host's required-reply obligation for an admitted DM. Therefore Batch 4 does not claim guaranteed silent DM ingestion. Keep unavoidable ingestion acknowledgements minimal.

The required `DATA IN -> PROCESS SILENTLY` behavior is completed in Batch 5 using the management-group/ambient channel behavior where selective silence can be configured deterministically.

## Signed Spring internal API

Spring currently exposes:

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

Spring rejects invalid signatures, stale timestamps, and replayed request IDs. `OPENCLAW_BACKEND_SHARED_SECRET` must be the same strong secret on the OpenClaw host and Spring service.

## Ollama model configuration

Logical models remain configuration-driven:

- `OLLAMA_ROUTER_MODEL` — initial `qwen3.5:9b`
- `OLLAMA_TEXT_MODEL` — initial `qwen3.5:9b`
- `OLLAMA_VISION_MODEL` — initial `qwen3.5:9b`
- `OLLAMA_VISION_FALLBACK` — initial `gemma4:12b`
- `OLLAMA_REASONING_MODEL` — preferred `qwen3.5:27b`, reserved for approved later workflows
- `OLLAMA_RESPONSE_MODEL` — initial `qwen3.5:9b`

Use the native Ollama endpoint at `OLLAMA_BASE_URL`.

## Batch 4 setup on the OpenClaw host

Set trusted environment values outside Git:

```bash
export OPENCLAW_GATEWAY_TOKEN='<long-random-gateway-token>'
export OPENCLAW_BACKEND_BASE_URL='https://bombay-restaurant-intelligence.onrender.com'
export OPENCLAW_BACKEND_SHARED_SECRET='<same-random-secret-configured-on-spring>'
export OLLAMA_BASE_URL='http://127.0.0.1:11434'
```

Install/validate the restaurant plugin:

```bash
cd openclaw/restaurant-tools
npm install
npm test
npm run plugin:check
openclaw plugins install --link .
openclaw plugins enable bombay-restaurant-tools
```

Install/link the official WhatsApp channel on the dedicated account:

```bash
openclaw channels login --channel whatsapp
```

Scan the QR code using the dedicated WhatsApp account. The linked session belongs to OpenClaw, not Spring.

Use `openclaw/openclaw.batch4.example.json5` as the configuration reference and validate the effective config before starting the Gateway.

### Pair the manager DM

With `dmPolicy="pairing"`, an unknown manager receives a one-time pairing code and their business message is not processed until approved.

On the OpenClaw host:

```bash
openclaw pairing list whatsapp
openclaw pairing approve whatsapp <code>
```

After approval, test these DM flows:

1. `Paid Salman 6500 vegetables` — candidate goes through Spring normalization.
2. Send a receipt image — Ollama vision extracts a candidate; Spring decides VERIFIED/review.
3. Send CSV/XLS/XLSX — Spring deterministic preview/confirm path is used.
4. `What are today's sales?` — answer comes only from Spring verified analytics.
5. Ask an unsupported judgment question — receive the bounded capability response, not invented business advice.

A real QR-linked WhatsApp session cannot be created in GitHub CI because the session credentials and physical account are operator-controlled. Batch 4 CI therefore proves configuration policy, tool restrictions, existing ingestion/analytics contracts, plugin validity, and Docker/runtime compatibility; the final linked-account smoke test is performed on the OpenClaw host.

## Automated coverage through Batch 4

CI verifies:

- frontend build;
- all Spring integration/unit tests;
- Batch 1 text and verified-sales flows;
- Batch 2 strict image/review/dedupe flows;
- Batch 3 deterministic CSV/XLS/XLSX preview/confirm flows;
- OpenClaw plugin unit tests and generated manifest;
- Batch 4 WhatsApp config uses pairing, DM isolation, and groups disabled;
- only the established restaurant tool surface is allowed;
- broad runtime/filesystem/elevated/agent-to-agent authority is denied;
- the workspace policy prohibits chat-memory accounting and model-controlled verification;
- committed-secret scan;
- production Docker image build.

## Remaining batches

### Batch 5 — WhatsApp management group

Add the dedicated account to a test management group, configure exact group allowlists and sender allowlists, enable always-listening ambient handling, enforce per-group isolation, and implement deterministic silent data ingestion with replies only for approved dashboard questions/errors/review notifications.

### Batch 6 — production hardening

Complete structured request logging, model/backend/storage/WhatsApp health checks, bounded retries, review notifications, backups, rate limits, operational monitoring, and final failover procedures.
