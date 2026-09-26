import { describe, expect, it, vi } from "vitest";
import { OllamaClient } from "./ollama.js";

describe("OllamaClient vision structured output", () => {
  it("uses native Ollama images plus the exact JSON schema", async () => {
    const image = Buffer.from("receipt-bytes").toString("base64");
    const schema = {
      type: "object",
      additionalProperties: false,
      required: ["documentType", "records"],
      properties: {
        documentType: { type: "string" },
        records: { type: "array" },
      },
    };
    const fetchFn = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body));
      expect(body.model).toBe("vision-model");
      expect(body.stream).toBe(false);
      expect(body.format).toEqual(schema);
      expect(body.options).toEqual({ temperature: 0 });
      expect(body.messages[0]).toEqual({ role: "system", content: "system rules" });
      expect(body.messages[1]).toEqual({
        role: "user",
        content: "extract receipt",
        images: [image],
      });
      return new Response(JSON.stringify({
        message: {
          content: JSON.stringify({ documentType: "PURCHASE_RECEIPT", records: [] }),
        },
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    }) as unknown as typeof fetch;

    const client = new OllamaClient("http://ollama.local", fetchFn);
    const result = await client.structuredVision<{ documentType: string; records: unknown[] }>(
      "vision-model",
      "system rules",
      "extract receipt",
      `data:image/jpeg;base64,${image}`,
      schema,
    );

    expect(result.documentType).toBe("PURCHASE_RECEIPT");
    expect(fetchFn).toHaveBeenCalledOnce();
  });

  it("sends bearer auth to Ollama cloud chat and model-list requests", async () => {
    const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers);
      expect(headers.get("Authorization")).toBe("Bearer test-cloud-key");
      const url = String(input);
      if (url.endsWith("/api/tags")) {
        return new Response(JSON.stringify({ models: [{ name: "gemma4:cloud", model: "gemma4:cloud" }] }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      expect(headers.get("Content-Type")).toBe("application/json");
      return new Response(JSON.stringify({ message: { content: JSON.stringify({ intent: "DATA_TEXT" }) } }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }) as unknown as typeof fetch;

    const client = new OllamaClient("https://ollama.com", fetchFn, { apiKey: "test-cloud-key" });
    const result = await client.structured<{ intent: string }>(
      "gemma4:cloud",
      "system",
      "user",
      { type: "object" },
    );
    const models = await client.listModels();

    expect(result.intent).toBe("DATA_TEXT");
    expect(models).toEqual(["gemma4:cloud"]);
    expect(fetchFn).toHaveBeenCalledTimes(2);
  });

  it("rejects malformed structured model output", async () => {
    const fetchFn = vi.fn(async () => new Response(JSON.stringify({
      message: { content: "not-json" },
    }), { status: 200, headers: { "Content-Type": "application/json" } })) as unknown as typeof fetch;
    const client = new OllamaClient("http://ollama.local", fetchFn);

    await expect(client.structuredVision(
      "vision-model",
      "system",
      "user",
      Buffer.from("image").toString("base64"),
      { type: "object" },
    )).rejects.toThrow("invalid structured output");
  });
});
