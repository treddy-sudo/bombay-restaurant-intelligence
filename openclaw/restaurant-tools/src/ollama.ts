import type { FetchLike } from "./backend.js";

export type JsonSchema = Record<string, unknown>;

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
    const response = await this.fetchFn(`${this.baseUrl}/api/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model,
        stream: false,
        messages: [
          { role: "system", content: systemPrompt },
          { role: "user", content: userPrompt },
        ],
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
