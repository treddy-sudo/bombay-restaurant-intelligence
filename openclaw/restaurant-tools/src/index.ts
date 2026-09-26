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
  responseModel: Type.Optional(Type.String()),
});

function routerFor(config: RestaurantPluginConfig): RestaurantRouter {
  const runtime = resolveRuntimeConfig(config);
  return new RestaurantRouter(
    new SpringBackendClient(runtime.backendBaseUrl, runtime.sharedSecret),
    new OllamaClient(runtime.ollamaBaseUrl),
    { router: runtime.routerModel, text: runtime.textModel, response: runtime.responseModel },
  );
}

const sourceType = Type.Optional(Type.Union([Type.Literal("MANUAL_TEXT"), Type.Literal("WHATSAPP_TEXT")]));

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
        sourceType,
      }, { additionalProperties: false }),
      async execute({ text, sourceId, sender, sourceType }, config, context) {
        const result = await routerFor(config).routeText(text, sourceId, sender, sourceType ?? "MANUAL_TEXT", context.signal);
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
        sourceType,
      }, { additionalProperties: false }),
      async execute({ text, sourceId, sender, sourceType }, config, context) {
        const record = await routerFor(config).ingestText(text, sourceId, sender, sourceType ?? "MANUAL_TEXT", context.signal);
        return { ok: true, record };
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
