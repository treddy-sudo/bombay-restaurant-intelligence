import type { FetchLike } from "./backend.js";
import { RETRYABLE_HTTP_STATUSES, retryDelay, withTimeout } from "./http.js";
import { defaultStructuredLogSink, errorType, type StructuredLogSink } from "./observability.js";

export type JsonSchema = Record<string, unknown>;

type OllamaMessage = {
  role: "system" | "user";
  content: string;
  images?: string[];
};

export type OllamaClientOptions = {
  timeoutMs?: number;
  maxRetries?: number;
  apiKey?: string;
  log?: StructuredLogSink;
};

export class OllamaClient {
  private readonly timeoutMs: number;
  private readonly maxRetries: number;
  private readonly apiKey?: string;
  private readonly log: StructuredLogSink;

  constructor(
    private readonly baseUrl: string,
    private readonly fetchFn: FetchLike = fetch,
    options: OllamaClientOptions = {},
  ) {
    this.timeoutMs = options.timeoutMs ?? 120_000;
    this.maxRetries = options.maxRetries ?? 1;
    this.apiKey = options.apiKey?.trim() || undefined;
    this.log = options.log ?? defaultStructuredLogSink;
  }

  async structured<T>(
    model: string,
    systemPrompt: string,
    userPrompt: string,
    schema: JsonSchema,
    signal?: AbortSignal,
  ): Promise<T> {
    return this.chatStructured<T>(
      model,
      [
        { role: "system", content: systemPrompt },
        { role: "user", content: userPrompt },
      ],
      schema,
      signal,
    );
  }

  async structuredVision<T>(
    model: string,
    systemPrompt: string,
    userPrompt: string,
    imageBase64: string,
    schema: JsonSchema,
    signal?: AbortSignal,
  ): Promise<T> {
    const normalizedImage = stripDataUrlPrefix(imageBase64);
    if (!normalizedImage) throw new Error("Image payload is empty");

    return this.chatStructured<T>(
      model,
      [
        { role: "system", content: systemPrompt },
        { role: "user", content: userPrompt, images: [normalizedImage] },
      ],
      schema,
      signal,
    );
  }

  async listModels(signal?: AbortSignal): Promise<string[]> {
    const maxAttempts = this.maxRetries + 1;
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      const started = Date.now();
      try {
        const response = await withTimeout(this.timeoutMs, signal, (requestSignal) => this.fetchFn(`${this.baseUrl}/api/tags`, {
          method: "GET",
          headers: this.requestHeaders(),
          signal: requestSignal,
        }));
        const raw = await response.text();
        this.log({
          component: "ollama",
          backendEndpoint: "/api/tags",
          processingStatus: response.ok ? "SUCCESS" : "HTTP_ERROR",
          errorType: response.ok ? undefined : `HTTP_${response.status}`,
          latencyMs: Date.now() - started,
          attempt,
        });
        if (!response.ok) {
          if (RETRYABLE_HTTP_STATUSES.has(response.status) && attempt < maxAttempts) {
            await retryDelay(attempt);
            continue;
          }
          throw new Error(`Ollama model-list request failed (${response.status}): ${raw.slice(0, 300)}`);
        }
        const envelope = JSON.parse(raw) as { models?: Array<{ name?: string; model?: string }> };
        return [...new Set((envelope.models ?? []).flatMap((entry) => [entry.name, entry.model]).filter((value): value is string => Boolean(value)))];
      } catch (error) {
        this.log({
          component: "ollama",
          backendEndpoint: "/api/tags",
          processingStatus: "ERROR",
          errorType: errorType(error),
          latencyMs: Date.now() - started,
          attempt,
        });
        if (signal?.aborted || attempt >= maxAttempts) throw error;
        await retryDelay(attempt);
      }
    }
    return [];
  }

  private requestHeaders(includeJson = false): Record<string, string> {
    const headers: Record<string, string> = {};
    if (includeJson) headers["Content-Type"] = "application/json";
    if (this.apiKey) headers.Authorization = `Bearer ${this.apiKey}`;
    return headers;
  }

  private async chatStructured<T>(
    model: string,
    messages: OllamaMessage[],
    schema: JsonSchema,
    callerSignal?: AbortSignal,
  ): Promise<T> {
    const maxAttempts = this.maxRetries + 1;
    let lastError: unknown;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      const started = Date.now();
      try {
        const response = await withTimeout(this.timeoutMs, callerSignal, (signal) => this.fetchFn(`${this.baseUrl}/api/chat`, {
          method: "POST",
          headers: this.requestHeaders(true),
          body: JSON.stringify({
            model,
            stream: false,
            messages,
            format: schema,
            options: { temperature: 0 },
          }),
          signal,
        }));

        const raw = await response.text();
        if (!response.ok) {
          const error = new Error(`Ollama request failed (${response.status}): ${raw.slice(0, 300)}`);
          this.log({
            component: "ollama",
            ollamaModel: model,
            backendEndpoint: "/api/chat",
            processingStatus: "HTTP_ERROR",
            errorType: `HTTP_${response.status}`,
            latencyMs: Date.now() - started,
            attempt,
          });
          if (RETRYABLE_HTTP_STATUSES.has(response.status) && attempt < maxAttempts) {
            lastError = error;
            await retryDelay(attempt);
            continue;
          }
          throw error;
        }

        let envelope: { message?: { content?: string } };
        try {
          envelope = JSON.parse(raw) as { message?: { content?: string } };
        } catch (error) {
          throw new Error(`Ollama returned invalid response JSON: ${String(error)}`);
        }

        const content = envelope.message?.content;
        if (!content) throw new Error("Ollama response did not contain message.content");

        try {
          const result = JSON.parse(content) as T;
          this.log({
            component: "ollama",
            ollamaModel: model,
            backendEndpoint: "/api/chat",
            processingStatus: "SUCCESS",
            latencyMs: Date.now() - started,
            attempt,
          });
          return result;
        } catch (error) {
          throw new Error(`Ollama returned invalid structured output: ${String(error)}`);
        }
      } catch (error) {
        lastError = error;
        this.log({
          component: "ollama",
          ollamaModel: model,
          backendEndpoint: "/api/chat",
          processingStatus: "ERROR",
          errorType: errorType(error),
          latencyMs: Date.now() - started,
          attempt,
        });
        if (callerSignal?.aborted || attempt >= maxAttempts) throw error;
        await retryDelay(attempt);
      }
    }

    throw lastError instanceof Error ? lastError : new Error("Ollama request failed");
  }
}

export function stripDataUrlPrefix(value: string): string {
  const trimmed = value.trim();
  const match = /^data:[^;]+;base64,(.*)$/s.exec(trimmed);
  return (match?.[1] ?? trimmed).replace(/\s+/g, "");
}
