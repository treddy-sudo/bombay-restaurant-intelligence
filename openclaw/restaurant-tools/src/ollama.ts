import type { FetchLike } from "./backend.js";

export type JsonSchema = Record<string, unknown>;

type OllamaMessage = {
  role: "system" | "user";
  content: string;
  images?: string[];
};

export class OllamaClient {
  constructor(
    private readonly baseUrl: string,
    private readonly fetchFn: FetchLike = fetch,
  ) {}

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

  private async chatStructured<T>(
    model: string,
    messages: OllamaMessage[],
    schema: JsonSchema,
    signal?: AbortSignal,
  ): Promise<T> {
    const response = await this.fetchFn(`${this.baseUrl}/api/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model,
        stream: false,
        messages,
        format: schema,
        options: { temperature: 0 },
      }),
      signal,
    });

    const raw = await response.text();
    if (!response.ok) {
      throw new Error(`Ollama request failed (${response.status}): ${raw.slice(0, 300)}`);
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
      return JSON.parse(content) as T;
    } catch (error) {
      throw new Error(`Ollama returned invalid structured output: ${String(error)}`);
    }
  }
}

export function stripDataUrlPrefix(value: string): string {
  const trimmed = value.trim();
  const match = /^data:[^;]+;base64,(.*)$/s.exec(trimmed);
  return (match?.[1] ?? trimmed).replace(/\s+/g, "");
}
