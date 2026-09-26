# OpenClaw + Ollama Architecture

Bombay Restaurant Intelligence keeps Spring Boot/PostgreSQL as the accounting authority and uses OpenClaw/Ollama only for channel orchestration and understanding.

## Final trust boundary

```text
WhatsApp
  -> OpenClaw Gateway / WhatsApp channel
  -> deterministic group/sender admission
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

OpenClaw owns the linked WhatsApp session and reconnect loop. Spring's existing Meta webhook remains available as legacy/fallback code but is not part of the OpenClaw production path.

The DM configuration uses pairing, `session.dmScope="per-channel-peer"`, loopback Gateway binding with token authentication, restaurant-only tools, and no generic shell/database/browser/filesystem mutation authority.

Ordinary accepted DMs may require a visible channel response; keep any unavoidable ingestion acknowledgement minimal. Deterministic silent success behavior is implemented for the management group in Batch 5.

## Batch 5 — allowlisted management group

The production group policy is deterministic and evaluated before the agent:

- `groupPolicy="allowlist"`;
- exact management-group JID allowlist;
- exact manager sender allowlist;
- `requireMention=false` for the configured management group;
- per-channel/per-peer session isolation;
- successful VERIFIED data ingestion returns `NO_REPLY` in the management group;
- review/error/duplicate outcomes may return one concise notice;
- approved dashboard questions return only backend-backed values;
- unauthorized groups and senders never reach restaurant tools.

Real group JIDs and manager numbers are operator-local configuration and must not be committed to Git.

## Restaurant tool surface

- `restaurant_route_text`
- `restaurant_ingest_text`
- `restaurant_ingest_image`
- `restaurant_preview_spreadsheet`
- `restaurant_confirm_spreadsheet`
- `restaurant_get_sales`
- `restaurant_health`

No shell, direct database, browser, unrestricted filesystem, automation, or generic HTTP tool is required by the restaurant agent.

## Data paths

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

The agent policy in `openclaw/workspace/AGENTS.md` prohibits arithmetic from chat history, direct database writes, model-controlled verification, unsupported business conclusions, and instructions embedded in untrusted attachments.

## Signed Spring internal API

Spring exposes tightly scoped OpenClaw endpoints under `/api/internal/v1`. Requests require:

- `X-Restaurant-Timestamp`
- `X-Restaurant-Request-Id`
- `X-Restaurant-Signature`

The signature is HMAC-SHA256 over:

```text
timestamp\nrequestId\nHTTP_METHOD\npathAndQuery\nsha256(body)
```

Spring rejects invalid signatures, stale timestamps, replayed request IDs, and requests over the configured authenticated internal-API rate limit. `OPENCLAW_BACKEND_SHARED_SECRET` must be the same strong secret on the OpenClaw host and Spring service.

## Ollama model configuration

Logical models remain configuration-driven:

- `OLLAMA_ROUTER_MODEL` — initial `qwen3.5:9b`
- `OLLAMA_TEXT_MODEL` — initial `qwen3.5:9b`
- `OLLAMA_VISION_MODEL` — initial `qwen3.5:9b`
- `OLLAMA_VISION_FALLBACK` — initial `gemma4:12b`
- `OLLAMA_REASONING_MODEL` — preferred `qwen3.5:27b`
- `OLLAMA_RESPONSE_MODEL` — initial `qwen3.5:9b`

Use the native Ollama endpoint at `OLLAMA_BASE_URL`. Model readiness is checked through the local Ollama model-list endpoint; a configured model that is not installed makes `restaurant_health` report not ready.

## OpenClaw host setup

Keep all credentials outside Git and shell history. Read the Gateway token and backend shared secret interactively:

```bash
read -rsp "OpenClaw gateway token: " OPENCLAW_GATEWAY_TOKEN && export OPENCLAW_GATEWAY_TOKEN && echo
export OPENCLAW_BACKEND_BASE_URL='https://bombay-restaurant-intelligence.onrender.com'
read -rsp "OpenClaw backend shared secret: " OPENCLAW_BACKEND_SHARED_SECRET && export OPENCLAW_BACKEND_SHARED_SECRET && echo
export OLLAMA_BASE_URL='http://127.0.0.1:11434'
```

Install and validate the restaurant plugin:

```bash
cd openclaw/restaurant-tools
npm install
npm test
npm run plugin:check
openclaw plugins install --link .
openclaw plugins enable bombay-restaurant-tools
```

Link the dedicated WhatsApp account:

```bash
openclaw channels login --channel whatsapp
```

Scan the QR code with the dedicated account. Configure the real management-group JID and allowed manager numbers only on the OpenClaw host, using `openclaw/openclaw.batch6.example.json5` as the template.

With `dmPolicy="pairing"`, approve a manager DM explicitly before processing it:

```bash
openclaw pairing list whatsapp
openclaw pairing approve whatsapp <code>
```

## Batch 6 — production hardening

### Readiness and health

`restaurant_health` combines independent readiness signals instead of inferring health from chat behavior:

- Spring internal API reachable;
- PostgreSQL query succeeds;
- document storage is writable/reachable;
- analytics service can calculate a verified-only response;
- Ollama is reachable;
- every configured required Ollama model is installed.

Spring readiness is intentionally scoped to components the restaurant agent depends on. Do not expose credentials, local paths, prompts, attachment contents, or database connection strings in health responses.

### Bounded retries and deadlines

OpenClaw uses bounded infrastructure retries only:

- Spring read-only requests may retry according to `OPENCLAW_BACKEND_READ_RETRIES`;
- every retry receives a fresh signed request ID;
- accounting POST/write requests are never blindly retried;
- Spring requests use `OPENCLAW_BACKEND_TIMEOUT_MS`;
- Ollama requests use `OLLAMA_REQUEST_TIMEOUT_MS` and `OLLAMA_MAX_RETRIES`;
- the agent policy forbids conversational retry loops on top of plugin retries.

Idempotency still belongs to Spring through WhatsApp message IDs, checksums, source IDs/rows, and normalized fingerprints.

### Rate limiting

The HMAC-authenticated internal API applies a configurable per-minute request limit after signature validation. Configure `OPENCLAW_BACKEND_MAX_REQUESTS_PER_MINUTE` to suit the management group. Gateway authentication also uses its own attempt/window/lockout limits in the Batch 6 OpenClaw template.

### Structured logging

OpenClaw/plugin logs are structured and content-minimized. Permitted operational fields include:

- timestamp;
- request ID / source ID;
- group/sender identifiers when supplied for correlation;
- component/tool/model name;
- backend endpoint;
- processing/review status;
- retry attempt;
- error type;
- latency.

Logs must not include message bodies, attachment contents, HMAC signatures, shared secrets, model prompts, access tokens, database passwords, or raw private model responses.

### Backup and restore

Repository scripts:

```bash
scripts/backup-postgres.sh
scripts/restore-postgres.sh <backup-file>
```

Both scripts are shell-syntax checked by CI. Supply database credentials through the environment at execution time; never commit them. Store backups outside the application container with restricted access and retention appropriate for the restaurant.

Test restore procedures against a disposable/non-production database before relying on a backup. Do not overwrite production as a restore test. Provider-native database backup availability depends on the active Render database plan and should be verified in the Render dashboard before production launch.

### Failure behavior

- Ollama unavailable/model timeout: no accounting write is fabricated; return one concise operational error when a reply is appropriate.
- malformed/uncertain extraction: Spring review rules still decide `REVIEW_REQUIRED`.
- Spring/database/storage unavailable: stop the workflow rather than inventing success.
- duplicate: Spring rejects/reuses idempotency boundaries; group may receive one short duplicate notice.
- unsupported file: reject before accounting ingestion.
- unauthorized group/sender: channel policy blocks it before agent/tool execution.
- prompt injection in text/image/document/spreadsheet: treat it as untrusted business content, never tool authority.

## Automated coverage through Batch 6

CI verifies:

- frontend production build;
- all Spring integration/unit tests;
- Batch 1 text and verified-sales flows;
- Batch 2 image/review/dedupe/prompt-injection boundaries;
- Batch 3 deterministic CSV/XLS/XLSX flows;
- Batch 4 DM pairing/isolation/tool restrictions;
- Batch 5 group/sender allowlists, silent group behavior, and safe staged-media access;
- signed internal API rate limiting;
- database/storage/analytics readiness;
- bounded Spring read retries and no blind write retries;
- bounded Ollama retries and installed-model discovery;
- structured log data minimization;
- OpenClaw plugin tests and generated manifest validation;
- backup/restore shell syntax;
- committed-secret scan;
- production Docker image build.

## Final operator acceptance checks

CI cannot create the real WhatsApp session or know private production allowlists. Before declaring the WhatsApp assistant operational, perform these checks on the OpenClaw host:

1. Link the dedicated WhatsApp account by QR and verify reconnect after an OpenClaw restart.
2. Configure the exact management-group JID and allowed manager numbers; verify an unauthorized group and sender are ignored.
3. Run `restaurant_health` and confirm Spring/database/storage/analytics/Ollama/model readiness.
4. In the authorized group, send `Paid Salman 6500 vegetables`; confirm no success reply and verify the backend/dashboard record.
5. Send a receipt image; confirm VERIFIED data affects totals and REVIEW_REQUIRED data does not.
6. Send a CSV/XLS/XLSX file; confirm deterministic preview/confirm ingestion and checksum dedupe.
7. Ask `What were today's sales?`; confirm the reply matches the Spring dashboard.
8. Ask an unsupported judgment question; confirm only the bounded capability response is returned.
9. Send the same image/file again; confirm no duplicate accounting record is created.
10. Exercise a database backup and restore it into a disposable database.

The final source of business truth remains PostgreSQL via Spring Boot at every step.
