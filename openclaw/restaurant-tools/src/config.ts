import { homedir } from "node:os";
import { delimiter, isAbsolute, resolve } from "node:path";

export type RestaurantPluginConfig = {
  backendBaseUrl?: string;
  ollamaBaseUrl?: string;
  routerModel?: string;
  textModel?: string;
  visionModel?: string;
  visionFallback?: string;
  responseModel?: string;
  inboundMediaRoots?: string[];
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
  inboundMediaRoots: string[];
};

function expandRoot(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) return "";
  if (trimmed === "~") return homedir();
  if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
    return resolve(homedir(), trimmed.slice(2));
  }
  return isAbsolute(trimmed) ? resolve(trimmed) : resolve(process.cwd(), trimmed);
}

function resolveInboundMediaRoots(config: RestaurantPluginConfig): string[] {
  const envRoots = (process.env.OPENCLAW_INBOUND_MEDIA_ROOTS ?? "")
    .split(delimiter)
    .map((value) => value.trim())
    .filter(Boolean);
  const configured = envRoots.length > 0
    ? envRoots
    : (config.inboundMediaRoots?.length
      ? config.inboundMediaRoots
      : ["~/.openclaw/media", "~/.openclaw/workspace"]);
  return [...new Set(configured.map(expandRoot).filter(Boolean))];
}

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
    inboundMediaRoots: resolveInboundMediaRoots(config),
  };
}
