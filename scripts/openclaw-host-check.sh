#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_DIR="$ROOT_DIR/openclaw/restaurant-tools"
FAILURES=0
WARNINGS=0

ok() { printf 'OK   %s\n' "$*"; }
warn() { printf 'WARN %s\n' "$*"; WARNINGS=$((WARNINGS + 1)); }
fail() { printf 'FAIL %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

have() { command -v "$1" >/dev/null 2>&1; }

require_command() {
  if have "$1"; then
    ok "$1 available ($(command -v "$1"))"
  else
    fail "$1 is not installed or not on PATH"
  fi
}

require_env() {
  local name="$1"
  if [[ -n "${!name:-}" ]]; then
    ok "$name is set"
  else
    fail "$name is not set"
  fi
}

printf 'Bombay Restaurant Intelligence - OpenClaw host readiness\n'
printf 'Repository: %s\n\n' "$ROOT_DIR"

require_command curl
require_command node
require_command npm
require_command openclaw

if have node; then
  if node -e 'const [M,m]=process.versions.node.split(".").map(Number); process.exit((M>26 || (M===26&&m>=1) || (M===24&&m>=16)) ? 0 : 1)' >/dev/null 2>&1; then
    ok "Node $(node -v) meets OpenClaw requirement (24.16+ or 26.1+)"
  else
    fail "Node $(node -v) is too old for the pinned OpenClaw toolchain"
  fi
fi

if have openclaw; then
  OPENCLAW_VERSION="$(openclaw --version 2>/dev/null | head -n 1 || true)"
  if [[ -n "$OPENCLAW_VERSION" ]]; then
    ok "OpenClaw CLI responds: $OPENCLAW_VERSION"
  else
    fail "OpenClaw CLI is installed but did not return a version"
  fi
fi

require_env OPENCLAW_GATEWAY_TOKEN
require_env OPENCLAW_BACKEND_BASE_URL
require_env OPENCLAW_BACKEND_SHARED_SECRET
require_env OLLAMA_BASE_URL

BACKEND_URL="${OPENCLAW_BACKEND_BASE_URL:-}"
if [[ -n "$BACKEND_URL" ]]; then
  case "$BACKEND_URL" in
    https://*) ok "Backend base URL uses HTTPS" ;;
    http://127.0.0.1:*|http://localhost:*) warn "Backend base URL is local HTTP; acceptable only for local development" ;;
    *) fail "OPENCLAW_BACKEND_BASE_URL should use HTTPS outside localhost" ;;
  esac
fi

OLLAMA_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
OLLAMA_AUTH_ARGS=()
case "$OLLAMA_URL" in
  https://ollama.com|https://ollama.com/*)
    require_env OLLAMA_API_KEY
    require_env OPENCLAW_OLLAMA_PROVIDER_API_KEY
    if [[ -n "${OLLAMA_API_KEY:-}" ]]; then
      OLLAMA_AUTH_ARGS=(-H "Authorization: Bearer ${OLLAMA_API_KEY}")
    fi
    ;;
  http://127.0.0.1:*|http://localhost:*)
    ok "Ollama is configured for local inference"
    ;;
  https://*)
    if [[ -n "${OLLAMA_API_KEY:-}" ]]; then
      OLLAMA_AUTH_ARGS=(-H "Authorization: Bearer ${OLLAMA_API_KEY}")
    else
      warn "Remote Ollama endpoint has no OLLAMA_API_KEY; ensure that endpoint intentionally allows unauthenticated access"
    fi
    ;;
  *)
    warn "OLLAMA_BASE_URL uses an unusual scheme/location: $OLLAMA_URL"
    ;;
esac

OLLAMA_TAGS_JSON=""
if have curl; then
  if OLLAMA_TAGS_JSON="$(curl --fail --silent --show-error --max-time 10 "${OLLAMA_AUTH_ARGS[@]}" "$OLLAMA_URL/api/tags" 2>/dev/null)"; then
    ok "Ollama API is reachable at $OLLAMA_URL"
  else
    fail "Ollama API is not reachable/authenticated at $OLLAMA_URL"
  fi
fi

ROUTER_MODEL="${OLLAMA_ROUTER_MODEL:-qwen3.5:9b}"
TEXT_MODEL="${OLLAMA_TEXT_MODEL:-qwen3.5:9b}"
VISION_MODEL="${OLLAMA_VISION_MODEL:-qwen3.5:9b}"
VISION_FALLBACK="${OLLAMA_VISION_FALLBACK:-gemma4:12b}"
REASONING_MODEL="${OLLAMA_REASONING_MODEL:-qwen3.5:27b}"
RESPONSE_MODEL="${OLLAMA_RESPONSE_MODEL:-qwen3.5:9b}"

if [[ -n "$OLLAMA_TAGS_JSON" ]] && have node; then
  AVAILABLE_MODELS="$(printf '%s' "$OLLAMA_TAGS_JSON" | node -e '
    let body="";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", chunk => body += chunk);
    process.stdin.on("end", () => {
      try {
        const parsed = JSON.parse(body);
        const names = new Set();
        for (const model of parsed.models ?? []) {
          if (model?.name) names.add(model.name);
          if (model?.model) names.add(model.model);
        }
        process.stdout.write([...names].join("\n"));
      } catch {
        process.exit(1);
      }
    });
  ' 2>/dev/null || true)"

  for model in "$ROUTER_MODEL" "$TEXT_MODEL" "$VISION_MODEL" "$VISION_FALLBACK" "$REASONING_MODEL" "$RESPONSE_MODEL"; do
    if printf '%s\n' "$AVAILABLE_MODELS" | grep -Fxq "$model"; then
      ok "Ollama model available: $model"
    else
      fail "Required Ollama model unavailable: $model"
    fi
  done
fi

if [[ -d "$PLUGIN_DIR" ]]; then
  ok "Restaurant OpenClaw plugin directory exists"
  if have npm; then
    if [[ ! -d "$PLUGIN_DIR/node_modules" ]]; then
      warn "Plugin dependencies are not installed; run: (cd '$PLUGIN_DIR' && npm install --no-audit --no-fund)"
    else
      if (cd "$PLUGIN_DIR" && npm test >/dev/null 2>&1); then
        ok "Restaurant plugin tests pass"
      else
        fail "Restaurant plugin tests failed"
      fi
      if (cd "$PLUGIN_DIR" && npm run plugin:check >/dev/null 2>&1); then
        ok "Restaurant plugin manifest/build validation passes"
      else
        fail "Restaurant plugin validation failed"
      fi
    fi
  fi
else
  fail "Restaurant plugin directory is missing: $PLUGIN_DIR"
fi

if have openclaw; then
  if openclaw gateway status >/dev/null 2>&1; then
    ok "OpenClaw Gateway responds"
  else
    warn "OpenClaw Gateway is not running yet"
  fi

  if openclaw channels status --probe >/dev/null 2>&1; then
    ok "OpenClaw channel probe completed"
  else
    warn "WhatsApp/channel probe is not healthy yet; QR linkage may still be required"
  fi
fi

printf '\nOperator-only steps (not automated by this script):\n'
printf '  1. Copy openclaw/openclaw.batch6.example.json5 to your OpenClaw config and replace only the local group JID / manager allowlist.\n'
printf '  2. Install the official WhatsApp plugin if needed: openclaw plugins install @openclaw/whatsapp\n'
printf '  3. Link the dedicated account: openclaw channels login --channel whatsapp\n'
printf '  4. Approve intended DM pairing requests: openclaw pairing list whatsapp / openclaw pairing approve whatsapp <code>\n'
printf '  5. Start/restart the Gateway and run restaurant_health.\n'
printf '  6. Execute the acceptance checks in OPENCLAW.md.\n\n'

if (( FAILURES > 0 )); then
  printf 'Readiness FAILED: %d failure(s), %d warning(s).\n' "$FAILURES" "$WARNINGS"
  exit 1
fi

printf 'Readiness PASSED with %d warning(s).\n' "$WARNINGS"
