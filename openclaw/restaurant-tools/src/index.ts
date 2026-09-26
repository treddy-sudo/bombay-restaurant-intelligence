import { Type } from "typebox";
import { defineToolPlugin } from "openclaw/plugin-sdk/tool-plugin";
import { SpringBackendClient } from "./backend.js";
import { resolveRuntimeConfig, type RestaurantPluginConfig } from "./config.js";
import { OllamaClient } from "./ollama.js";
import { RestaurantRouter } from "./router.js";

const configSchema = Type.Object({
  backendBaseUrl: Type.Optional(Type.String()),
  ollamaBaseUrl: Type.Optional(Type.String()),
  routerModel: Type.Optional(Type.String()),
  textModel: Type.Optional(Type.String()),
  visionModel: Type.Optional(Type.String()),
  visionFallback: Type.Optional(Type.String()),
  responseModel: Type.Optional(Type.String()),
});

function backendFor(config: RestaurantPluginConfig): SpringBackendClient {
  const runtime = resolveRuntimeConfig(config);
  return new SpringBackendClient(runtime.backendBaseUrl, runtime.sharedSecret);
}

function routerFor(config: RestaurantPluginConfig): RestaurantRouter {
  const runtime = resolveRuntimeConfig(config);
  return new RestaurantRouter(
    backendFor(config),
    new OllamaClient(runtime.ollamaBaseUrl),
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
      description: "Extract strict candidate records from a receipt, handwritten sheet, sales summary, salary image, screenshot, or other restaurant image with Ollama vision, then send the image and candidates to Spring for validation, dedupe, storage, normalization, review, and accounting persistence.",
      parameters: Type.Object({
        imageBase64: Type.String({ minLength: 4 }),
        contentType: imageContentType,
        filename: Type.String({ minLength: 1, maxLength: 255 }),
        sourceId: Type.String({ minLength: 1 }),
        sender: Type.Optional(Type.String()),
        sourceType: imageSourceType,
      }, { additionalProperties: false }),
      async execute({ imageBase64, contentType, filename, sourceId, sender, sourceType }, config, context) {
        const result = await routerFor(config).ingestImage(
          imageBase64,
          contentType,
          filename,
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
      description: "Send a CSV/XLS/XLSX file directly to the signed Spring upload pipeline for deterministic parsing, checksum dedupe, stored column mappings, and preview. This tool does not send spreadsheet contents to Ollama.",
      parameters: Type.Object({
        fileBase64: Type.String({ minLength: 4 }),
        contentType: spreadsheetContentType,
        filename: Type.String({ minLength: 1, maxLength: 255 }),
      }, { additionalProperties: false }),
      async execute({ fileBase64, contentType, filename }, config, context) {
        const preview = await backendFor(config).previewSpreadsheet(
          { fileBase64, contentType, filename },
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
  ],
});
