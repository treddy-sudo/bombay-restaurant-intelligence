# OpenClaw + Ollama Host Setup

This guide completes the operator-controlled part of Bombay Restaurant Intelligence. Spring Boot/PostgreSQL are already deployed separately; this host runs OpenClaw, Ollama, and the dedicated WhatsApp Web session.

## 1. Host requirements

OpenClaw currently requires Node 24.16+ or 26.1+. Use a persistent macOS/Linux/WSL2 host with enough RAM/VRAM for the configured Ollama models. The WhatsApp account should be a dedicated restaurant assistant number rather than a personal account.

Install OpenClaw using the official installer or your normal package-management workflow. Do not bake Gateway tokens, backend HMAC secrets, WhatsApp session files, or restaurant allowlists into the repository.

## 2. Clone and configure

```bash
git clone https://github.com/treddy-sudo/bombay-restaurant-intelligence.git
cd bombay-restaurant-intelligence
```

Set the required secrets in the host environment without committing them:

```bash
read -rsp "OpenClaw gateway token: " OPENCLAW_GATEWAY_TOKEN && export OPENCLAW_GATEWAY_TOKEN && echo
export OPENCLAW_BACKEND_BASE_URL='https://bombay-restaurant-intelligence.onrender.com'
read -rsp "Spring/OpenClaw shared secret: " OPENCLAW_BACKEND_SHARED_SECRET && export OPENCLAW_BACKEND_SHARED_SECRET && echo
export OLLAMA_BASE_URL='http://127.0.0.1:11434'
```

Optional tuning variables are documented in `.env.example`.

## 3. Ollama models

Run Ollama locally and install the configured model aliases before starting the Gateway:

```bash
ollama pull qwen3.5:9b
ollama pull gemma4:12b
ollama pull qwen3.5:27b
```

The defaults use `qwen3.5:9b` for routing/text/vision/response, `gemma4:12b` as the vision fallback, and `qwen3.5:27b` as the reasoning model. Change aliases through environment/config rather than application business logic.

## 4. Restaurant plugin

```bash
cd openclaw/restaurant-tools
npm install --no-audit --no-fund
npm test
npm run plugin:check
openclaw plugins install --link .
openclaw plugins enable bombay-restaurant-tools
cd ../..
```

## 5. Production OpenClaw config

Copy `openclaw/openclaw.batch6.example.json5` into your normal OpenClaw config location. Replace only operator-local values such as:

- management-group JID;
- allowed manager WhatsApp numbers;
- local media roots if your host uses different paths.

Keep the restaurant tool allowlist and deny rules intact unless you deliberately review the security impact.

Do not commit the resulting production config if it contains real group or sender identifiers.

## 6. WhatsApp plugin and QR link

Install the official WhatsApp channel plugin if the login flow has not already installed it:

```bash
openclaw plugins install @openclaw/whatsapp
```

Link the dedicated WhatsApp account:

```bash
openclaw channels login --channel whatsapp
```

Scan the QR from the dedicated account. On a headless server, make sure you can see the live QR directly; delayed screenshots may expire.

For intended DM access requests:

```bash
openclaw pairing list whatsapp
openclaw pairing approve whatsapp <code>
```

## 7. Start and diagnose

```bash
openclaw gateway
```

Useful checks:

```bash
openclaw gateway status
openclaw channels status --probe
openclaw doctor
openclaw logs --follow
```

Then run the repository checker:

```bash
bash scripts/openclaw-host-check.sh
```

The checker never prints secret values. It verifies the local runtime, environment presence, Ollama reachability/models, plugin tests/manifest, and Gateway/channel status where available.

## 8. Final acceptance

Run the operator acceptance list in `OPENCLAW.md`. At minimum verify:

1. unauthorized groups and senders are ignored;
2. `Paid Salman 6500 vegetables` processes silently in the management group;
3. receipt images flow through Ollama candidates and Spring normalization/review;
4. CSV/XLS/XLSX files use deterministic Spring preview/confirm parsing;
5. `What were today's sales?` matches the Spring dashboard;
6. unsupported judgment questions return only the capability boundary;
7. duplicate image/file retries never create duplicate accounting records;
8. `restaurant_health` reports Spring/database/storage/analytics/Ollama/model readiness;
9. Gateway reconnect works after restart;
10. a PostgreSQL backup can be restored into a disposable database.

## Production account items still outside code

Before relying on the system operationally, make the source repository private if that is your policy, and move Render web/database resources off free/expiring plans if you require always-on availability and durable production storage.
