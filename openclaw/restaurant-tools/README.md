# Bombay Restaurant OpenClaw Tools

Batch 1 OpenClaw tool plugin for Bombay Restaurant Intelligence.

The plugin deliberately has no database access. It can only:

- classify restaurant text with Ollama,
- extract structured text candidates with Ollama,
- send candidates to the signed Spring internal API,
- request approved verified analytics from Spring,
- format backend-provided values with Ollama.

## Required environment

```bash
export OPENCLAW_BACKEND_BASE_URL=http://127.0.0.1:8080
export OPENCLAW_BACKEND_SHARED_SECRET='replace-with-a-long-random-secret'
export OLLAMA_BASE_URL=http://127.0.0.1:11434
export OLLAMA_ROUTER_MODEL=qwen3.5:9b
export OLLAMA_TEXT_MODEL=qwen3.5:9b
export OLLAMA_RESPONSE_MODEL=qwen3.5:9b
```

The Spring service must use the same `OPENCLAW_BACKEND_SHARED_SECRET`.

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

Use `../openclaw.batch1.example.json5` as the Batch 1 model/plugin configuration reference. WhatsApp is intentionally not configured in Batch 1.
