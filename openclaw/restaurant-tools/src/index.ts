import { Type } from "typebox";
import { defineToolPlugin } from "openclaw/plugin-sdk/tool-plugin";
import { SpringBackendClient } from "./backend.js";
import { resolveRuntimeConfig, type RestaurantPluginConfig, type RuntimeConfig } from "./config.js";
import { resolveAttachmentInput } from "./media.js";
import { OllamaClient } from "./ollama.js";
import { RestaurantRouter } from "./router.js";

const configSchema = Type.Object({
  backendBaseUrl: Type.Optional(Type.String()),
  ollamaBaseUrl: Type.Optional(Type.String()),
  routerModel: Type.Optional(Type.String()),
  textModel: Type.Optional(Type.String()),
  visionModel: Type.Optional(Type.String()),
  visionFallback: Type.Optional(Type.String()),
  reasoningModel: Type.Optional(Type.String()),
  responseModel: Type.Optional(Type.String()),
  inboundMediaRoots: Type.Optional(Type.Array(Type.String({ minLength: 1 }), { minItems: 1 })),
  backendTimeoutMs: Type.Optional(Type.Integer({ minimum: 1000, maximum: 300000 })),
  backendReadRetries: Type.Optional(Type.Integer({ minimum: 0, maximum: 2 })),
  ollamaTimeoutMs: Type.Optional(Type.Integer({ minimum: 1000, maximum: 300000 })),
  ollamaMaxRetries: Type.Optional(Type.Integer({ minimum: 0, maximum: 2 })),
});

function backendFromRuntime(runtime: RuntimeConfig): SpringBackendClient {
  return new SpringBackendClient(runtime.backendBaseUrl, runtime.sharedSecret, fetch, {
    timeoutMs: runtime.backendTimeoutMs,
    readRetries: runtime.backendReadRetries,
  });
}

function ollamaFromRuntime(runtime: RuntimeConfig): OllamaClient {
  return new OllamaClient(runtime.ollamaBaseUrl, fetch, {
    timeoutMs: runtime.ollamaTimeoutMs,
    maxRetries: runtime.ollamaMaxRetries,
  });
}

function backendFor(config: RestaurantPluginConfig): SpringBackendClient {
  return backendFromRuntime(resolveRuntimeConfig(config));
}

function routerFor(config: RestaurantPluginConfig): RestaurantRouter {
  const runtime = resolveRuntimeConfig(config);
  return new RestaurantRouter(
    backendFromRuntime(runtime),
    ollamaFromRuntime(runtime),
    {
      router: runtime.routerModel,
      text: runtime.textModel,
      vision: runtime.visionModel,
      visionFallback: runtime.visionFallback,
      response: runtime.responseModel,
    },
  );
}

const textSourceType = Type.Optional(Type.Union([
  Type.Literal("MANUAL_TEXT"),
  Type.Literal("WHATSAPP_TEXT"),
]));

const imageSourceType = Type.Optional(Type.Union([
  Type.Literal("IMAGE"),
  Type.Literal("WHATSAPP_IMAGE"),
]));

const imageContentType = Type.Union([
  Type.Literal("image/jpeg"),
  Type.Literal("image/png"),
  Type.Literal("image/webp"),
]);

const spreadsheetContentType = Type.Union([
  Type.Literal("text/csv"),
  Type.Literal("application/csv"),
  Type.Literal("application/vnd.ms-excel"),
  Type.Literal("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
]);

export default defineToolPlugin({
  id: "bombay-restaurant-tools",
  name: "Bombay Restaurant Tools",
  description: "OpenClaw tools for verified restaurant ingestion and dashboard analytics.",
  configSchema,
  tools: (tool) => [
    tool({
      name: "restaurant_route_text",
      label: "Route Restaurant Text",
      description: "Classify one restaurant text message with Ollama, then either ingest it through Spring or answer an approved verified dashboard question.",
      parameters: Type.Object({
        text: Type.String(),
        sourceId: Type.String(),
        sender: Type.Optional(Type.String()),
        sourceType: textSourceType,
      }, { additionalProperties: false }),
      async execute({ text, sourceId, sender, sourceType }, config, context) {
        const result = await routerFor(config).routeText(
          text,
          sourceId,
          sender,
          sourceType ?? "MANUAL_TEXT",
          context.signal,
        );
        return { ok: true, result };
      },
    }),
    tool({
      name: "restaurant_ingest_text",
      label: "Ingest Restaurant Text",
      description: "Use Ollama only to extract a structured candidate, then send it to Spring IntakeAgent/NormalizationAgent. Never writes directly to PostgreSQL.",
      parameters: Type.Object({
        text: Type.String(),
        sourceId: Type.String(),
        sender: Type.Optional(Type.String()),
        sourceType: textSourceType,
      }, { additionalProperties: false }),
      async execute({ text, sourceId, sender, sourceType }, config, context) {
        const record = await routerFor(config).ingestText(
          text,
          sourceId,
          sender,
          sourceType ?? "MANUAL_TEXT",
          context.signal,
        );
        return { ok: true, record };
      },
    }),
    tool({
      name: "restaurant_ingest_image",
      label: "Ingest Restaurant Image",
      description: "Ingest a WhatsApp/OpenClaw staged image by exact AttachmentPath, or inline Base64 for tests/manual calls. The plugin reads only configured inbound-media roots, uses Ollama vision for strict candidates, then Spring validates, deduplicates, stores, normalizes, reviews, and persists them.",
      parameters: Type.Object({
        imageBase64: Type.Optional(Type.String({ minLength: 4 })),
        attachmentPath: Type.Optional(Type.String({ minLength: 1 })),
        contentType: imageContentType,
        filename: Type.Optional(Type.String({ minLength: 1, maxLength: 255 })),
        sourceId: Type.String({ minLength: 1 }),
        sender: Type.Optional(Type.String()),
        sourceType: imageSourceType,
      }, { additionalProperties: false }),
      async execute({ imageBase64, attachmentPath, contentType, filename, sourceId, sender, sourceType }, config, context) {
        const runtime = resolveRuntimeConfig(config);
        const input = await resolveAttachmentInput({
          inlineBase64: imageBase64,
          attachmentPath,
          allowedRoots: runtime.inboundMediaRoots,
          maxBytes: 10 * 1024 * 1024,
        });
        const resolvedFilename = filename?.trim() || input.stagedFilename;
        if (!resolvedFilename) throw new Error("filename is required for inline image Base64");
        const result = await routerFor(config).ingestImage(
          input.base64,
          contentType,
          resolvedFilename,
          sourceId,
          sender,
          sourceType ?? "IMAGE",
          context.signal,
        );
        return { ok: true, result };
      },
    }),
    tool({
      name: "restaurant_preview_spreadsheet",
      label: "Preview Restaurant Spreadsheet",
      description: "Preview a WhatsApp/OpenClaw staged CSV/XLS/XLSX by exact AttachmentPath, or inline Base64 for tests/manual calls. The plugin reads only configured inbound-media roots and sends bytes directly to Spring's deterministic parser; spreadsheet contents are never sent to Ollama.",
      parameters: Type.Object({
        fileBase64: Type.Optional(Type.String({ minLength: 4 })),
        attachmentPath: Type.Optional(Type.String({ minLength: 1 })),
        contentType: spreadsheetContentType,
        filename: Type.Optional(Type.String({ minLength: 1, maxLength: 255 })),
      }, { additionalProperties: false }),
      async execute({ fileBase64, attachmentPath, contentType, filename }, config, context) {
        const runtime = resolveRuntimeConfig(config);
        const input = await resolveAttachmentInput({
          inlineBase64: fileBase64,
          attachmentPath,
          allowedRoots: runtime.inboundMediaRoots,
          maxBytes: 10 * 1024 * 1024,
        });
        const resolvedFilename = filename?.trim() || input.stagedFilename;
        if (!resolvedFilename) throw new Error("filename is required for inline spreadsheet Base64");
        const preview = await backendFromRuntime(runtime).previewSpreadsheet(
          { fileBase64: input.base64, contentType, filename: resolvedFilename },
          context.signal,
        );
        return { ok: true, preview };
      },
    }),
    tool({
      name: "restaurant_confirm_spreadsheet",
      label: "Confirm Restaurant Spreadsheet",
      description: "Confirm a previously previewed spreadsheet job in Spring so every parsed row passes through IntakeAgent and NormalizationAgent before persistence. This tool does not use Ollama.",
      parameters: Type.Object({
        jobId: Type.String({ minLength: 1 }),
      }, { additionalProperties: false }),
      async execute({ jobId }, config, context) {
        const confirmation = await backendFor(config).confirmSpreadsheet(jobId, context.signal);
        return { ok: true, confirmation };
      },
    }),
    tool({
      name: "restaurant_get_sales",
      label: "Get Verified Sales",
      description: "Get today's verified sales from the Spring analytics API and have Ollama format only the backend-provided value.",
      parameters: Type.Object({}, { additionalProperties: false }),
      async execute(_params, config, context) {
        const result = await routerFor(config).getTodaySales(context.signal);
        return { ok: true, result };
      },
    }),
    tool({
      name: "restaurant_health",
      label: "Restaurant Intelligence Health",
      description: "Check signed Spring database/storage/analytics readiness and verify that the configured Ollama models are installed. This diagnostic never invokes a model or reads accounting data from chat history.",
      parameters: Type.Object({}, { additionalProperties: false }),
      async execute(_params, config, context) {
        const runtime = resolveRuntimeConfig(config);
        const backend = backendFromRuntime(runtime);
        const ollama = ollamaFromRuntime(runtime);
        const requiredModels = [...new Set([
          runtime.routerModel,
          runtime.textModel,
          runtime.visionModel,
          runtime.visionFallback,
          runtime.reasoningModel,
          runtime.responseModel,
        ].filter(Boolean))];

        const [springResult, modelResult] = await Promise.allSettled([
          backend.health(context.signal),
          ollama.listModels(context.signal),
        ]);
        const spring = springResult.status === "fulfilled"
          ? springResult.value
          : { status: "DOWN" as const, errorType: springResult.reason instanceof Error ? springResult.reason.name : "UnknownError" };
        const availableModels = modelResult.status === "fulfilled" ? modelResult.value : [];
        const installed = new Set(availableModels);
        const missingModels = requiredModels.filter((model) => !installed.has(model));
        const ollamaStatus = modelResult.status === "fulfilled" ? (missingModels.length === 0 ? "UP" : "DEGRADED") : "DOWN";
        const status = spring.status === "UP" && ollamaStatus === "UP" ? "UP" : (spring.status === "DOWN" || ollamaStatus === "DOWN" ? "DOWN" : "DEGRADED");

        return {
          ok: status === "UP",
          health: {
            status,
            spring,
            ollama: {
              status: ollamaStatus,
              requiredModels,
              missingModels,
              availableModelCount: availableModels.length,
            },
          },
        };
      },
    }),
  ],
});
