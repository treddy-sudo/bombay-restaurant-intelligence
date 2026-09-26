export type RestaurantPluginConfig = {
  backendBaseUrl?: string;
  ollamaBaseUrl?: string;
  routerModel?: string;
  textModel?: string;
  visionModel?: string;
  visionFallback?: string;
  responseModel?: string;
};

export type RuntimeConfig = {
  backendBaseUrl: string;
  sharedSecret: string;
  ollamaBaseUrl: string;
  routerModel: string;
  textModel: string;
  visionModel: string;
  visionFallback: string;
  responseModel: string;
};

export function resolveRuntimeConfig(config: RestaurantPluginConfig): RuntimeConfig {
  const sharedSecret = (process.env.OPENCLAW_BACKEND_SHARED_SECRET ?? "").trim();
  if (!sharedSecret) {
    throw new Error("OPENCLAW_BACKEND_SHARED_SECRET is required");
  }

  return {
    backendBaseUrl: (process.env.OPENCLAW_BACKEND_BASE_URL ?? config.backendBaseUrl ?? "http://127.0.0.1:8080").replace(/\/+$/, ""),
    sharedSecret,
    ollamaBaseUrl: (process.env.OLLAMA_BASE_URL ?? config.ollamaBaseUrl ?? "http://127.0.0.1:11434").replace(/\/+$/, ""),
    routerModel: process.env.OLLAMA_ROUTER_MODEL ?? config.routerModel ?? "qwen3.5:9b",
    textModel: process.env.OLLAMA_TEXT_MODEL ?? config.textModel ?? "qwen3.5:9b",
    visionModel: process.env.OLLAMA_VISION_MODEL ?? config.visionModel ?? "qwen3.5:9b",
    visionFallback: process.env.OLLAMA_VISION_FALLBACK ?? config.visionFallback ?? "gemma4:12b",
    responseModel: process.env.OLLAMA_RESPONSE_MODEL ?? config.responseModel ?? "qwen3.5:9b",
  };
}
