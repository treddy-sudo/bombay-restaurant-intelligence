# Bombay Restaurant OpenClaw Tools

OpenClaw tool plugin for Bombay Restaurant Intelligence through Batch 3.

The plugin deliberately has no database access. Spring Boot/PostgreSQL remains the accounting authority.

## Tools

- `restaurant_route_text` — classify approved text workflows with Ollama structured output.
- `restaurant_ingest_text` — extract a text candidate with Ollama and send it to signed Spring intake.
- `restaurant_ingest_image` — extract strict image candidates with Ollama vision, then send image + candidates to signed Spring validation/normalization.
- `restaurant_preview_spreadsheet` — send CSV/XLS/XLSX bytes directly to Spring for deterministic parsing, checksum dedupe, stored column mappings, and preview. This path does not use Ollama.
- `restaurant_confirm_spreadsheet` — confirm a previously previewed spreadsheet job so every row passes through `IntakeAgent -> NormalizationAgent`. This path does not use Ollama.
- `restaurant_get_sales` — request verified sales from Spring and use Ollama only to format the backend-provided value.

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

Use `../openclaw.batch3.example.json5` as the current model/plugin configuration reference. Production WhatsApp routing remains intentionally deferred until Batch 4/5.

See the repository-level `OPENCLAW.md` for trust boundaries, signed request details, and batch-specific architecture.
