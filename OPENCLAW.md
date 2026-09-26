# OpenClaw + Ollama Architecture

This repository keeps Spring Boot/PostgreSQL as the accounting authority and adds OpenClaw/Ollama as an orchestration and understanding layer.

## Trust boundary

```text
OpenClaw -> Ollama -> signed Spring internal API -> IntakeAgent/NormalizationAgent -> PostgreSQL
OpenClaw -> approved intent -> signed Spring analytics API -> VERIFIED rows -> Java BigDecimal -> Ollama formatting
```

Ollama produces candidates and human-readable wording only. It never writes PostgreSQL, marks records verified, or calculates official totals.

## Batch 1 scope

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

The pending `batch-image-extractor` branch remains reserved for Batch 2.

## OpenClaw tools in Batch 1

- `restaurant_route_text`
- `restaurant_ingest_text`
- `restaurant_get_sales`

The OpenClaw example configuration restricts the agent tool catalog to these tools. No shell, filesystem mutation, database, browser, or arbitrary HTTP tool is required for the restaurant agent.

## Signed internal API

Spring exposes:

- `POST /api/internal/v1/intake/text-candidate`
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

Batch 1 uses environment-driven logical models:

- `OLLAMA_ROUTER_MODEL` (default `qwen3.5:9b`)
- `OLLAMA_TEXT_MODEL` (default `qwen3.5:9b`)
- `OLLAMA_RESPONSE_MODEL` (default `qwen3.5:9b`)

Reserved for later batches:

- `OLLAMA_VISION_MODEL` (preferred `qwen3.5:9b`)
- `OLLAMA_VISION_FALLBACK` (preferred `gemma4:12b`)
- `OLLAMA_REASONING_MODEL` (preferred `qwen3.5:27b`)

Use Ollama's native endpoint (`OLLAMA_BASE_URL`, normally `http://127.0.0.1:11434`), not `/v1`.

## Local Batch 1 setup

1. Start Spring/PostgreSQL as documented in the root README.
2. Set the same strong `OPENCLAW_BACKEND_SHARED_SECRET` for Spring and OpenClaw.
3. Start Ollama and ensure the selected models are available.
4. Install/validate the plugin:

```bash
cd openclaw/restaurant-tools
npm install
npm test
npm run plugin:check
openclaw plugins install --link .
openclaw plugins enable bombay-restaurant-tools
```

5. Adapt `openclaw/openclaw.batch1.example.json5` into the local OpenClaw configuration.

WhatsApp channel configuration is deliberately deferred until Batch 4/5.

## Future batches

- Batch 2: finish/rebase the existing image extractor work, Ollama vision schemas, receipt/handwriting/sales/salary image tests.
- Batch 3: deterministic Excel/CSV routing through existing parsers plus semantic mapping only when needed.
- Batch 4: dedicated WhatsApp DM connection.
- Batch 5: allowlisted always-listening management group with silent ingestion and per-group isolation.
- Batch 6: monitoring, retry/fallback policy, review notifications, backups, model/storage health, and production hardening.
