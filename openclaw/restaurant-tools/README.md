# Bombay Restaurant OpenClaw Tools

OpenClaw tool plugin for Bombay Restaurant Intelligence.

The plugin deliberately has no database access. Spring Boot/PostgreSQL remains the accounting authority.

## Tools

- `restaurant_route_text` — classify restaurant text with Ollama structured output, then either ingest a candidate through Spring or route an approved dashboard question to Spring analytics.
- `restaurant_ingest_text` — extract a text candidate with Ollama and send it to signed Spring intake.
- `restaurant_ingest_image` — extract strict image candidates with Ollama vision, then send image + candidates to signed Spring validation/normalization.
- `restaurant_preview_spreadsheet` — send CSV/XLS/XLSX bytes directly to Spring for deterministic parsing, checksum dedupe, stored column mappings, and preview. This path does not use Ollama.
- `restaurant_confirm_spreadsheet` — confirm a previously previewed spreadsheet job so every row passes through `IntakeAgent -> NormalizationAgent`. This path does not use Ollama.
- `restaurant_get_sales` — compatibility tool for today's verified sales. Natural dashboard questions should normally use `restaurant_route_text`.
- `restaurant_health` — check signed Spring readiness and required Ollama model availability without invoking an accounting model.

Approved dashboard routing is intentionally bounded. It supports verified sales/expenses for approved periods, today's net operating result, vendor/category spend, salary totals and employee salary, cash/UPI/Zomato/Swiggy sales, sales/expense comparisons, and pending review count. Ollama selects only the approved intent/period/subject; Spring reads VERIFIED transactions and calculates every authoritative financial value with Java `BigDecimal`.

OpenClaw and Ollama never mark transactions verified, calculate official totals, or write directly to PostgreSQL.

## Required environment

```bash
export OPENCLAW_BACKEND_BASE_URL=http://127.0.0.1:8080
read -rsp "OpenClaw backend shared secret: " OPENCLAW_BACKEND_SHARED_SECRET && export OPENCLAW_BACKEND_SHARED_SECRET && echo
export OLLAMA_BASE_URL=http://127.0.0.1:11434
export OLLAMA_ROUTER_MODEL=qwen3.5:9b
export OLLAMA_TEXT_MODEL=qwen3.5:9b
export OLLAMA_VISION_MODEL=qwen3.5:9b
export OLLAMA_VISION_FALLBACK=gemma4:12b
export OLLAMA_RESPONSE_MODEL=qwen3.5:9b
```

The Spring service must use the same `OPENCLAW_BACKEND_SHARED_SECRET`. Keep that value outside Git and documentation.

Spreadsheet preview/confirm requires only Spring plus the shared secret; Ollama is not involved in spreadsheet parsing.

## Build and validate

OpenClaw's current tool-plugin SDK requires Node 24.16+ or Node 26.1+.

```bash
npm install
npm test
npm run plugin:check
```

## Install into OpenClaw

```bash
openclaw plugins install --link .
openclaw plugins enable bombay-restaurant-tools
openclaw plugins inspect bombay-restaurant-tools --runtime
```

Use `../openclaw.batch5.example.json5` as the management-group/plugin configuration reference and `../../OPENCLAW_HOST_SETUP.md` for the persistent-host activation procedure.

See the repository-level `OPENCLAW.md` for trust boundaries, signed request details, WhatsApp policy, and end-to-end acceptance checks.
