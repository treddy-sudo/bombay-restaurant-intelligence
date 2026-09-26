import { describe, expect, it, vi } from "vitest";
import { SpringBackendClient } from "./backend.js";
import { OllamaClient } from "./ollama.js";
import type { StructuredLogEvent } from "./observability.js";

describe("Batch 6 backend resilience", () => {
  it("retries a retryable GET once with a fresh signed request", async () => {
    const fetchFn = vi.fn()
      .mockResolvedValueOnce(new Response("unavailable", { status: 503 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        intent: "TODAY_SALES",
        from: "2026-09-26",
        to: "2026-09-26",
        metric: "sales",
        value: 1234,
        currency: "INR",
      }), { status: 200, headers: { "Content-Type": "application/json" } }));

    const client = new SpringBackendClient(
      "http://spring.local",
      "shared-secret",
      fetchFn as unknown as typeof fetch,
      { readRetries: 1, timeoutMs: 1000, log: () => undefined },
    );

    const result = await client.queryTodaySales();
    expect(result.value).toBe(1234);
    expect(fetchFn).toHaveBeenCalledTimes(2);

    const firstHeaders = new Headers(fetchFn.mock.calls[0]?.[1]?.headers);
    const secondHeaders = new Headers(fetchFn.mock.calls[1]?.[1]?.headers);
    expect(firstHeaders.get("X-Restaurant-Request-Id")).toBeTruthy();
    expect(secondHeaders.get("X-Restaurant-Request-Id")).toBeTruthy();
    expect(secondHeaders.get("X-Restaurant-Request-Id")).not.toBe(firstHeaders.get("X-Restaurant-Request-Id"));
  });

  it("never blindly retries an accounting POST", async () => {
    const fetchFn = vi.fn().mockResolvedValue(new Response("unavailable", { status: 503 }));
    const client = new SpringBackendClient(
      "http://spring.local",
      "shared-secret",
      fetchFn as unknown as typeof fetch,
      { readRetries: 2, timeoutMs: 1000, log: () => undefined },
    );

    await expect(client.ingestTextCandidate({
      sourceId: "wamid.1",
      sourceType: "WHATSAPP_TEXT",
      rawText: "Paid Salman 6500 vegetables",
      transactionType: "VENDOR_PAYMENT",
      category: "VEGETABLES",
      vendor: "Salman",
      amount: "6500.00",
      confidence: 0.96,
    })).rejects.toThrow("503");

    expect(fetchFn).toHaveBeenCalledOnce();
  });

  it("aborts a backend read when its deadline is exceeded", async () => {
    const fetchFn = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => reject(init.signal?.reason ?? new Error("aborted")), { once: true });
    }));
    const client = new SpringBackendClient(
      "http://spring.local",
      "shared-secret",
      fetchFn as unknown as typeof fetch,
      { readRetries: 0, timeoutMs: 10, log: () => undefined },
    );

    await expect(client.health()).rejects.toThrow("timed out");
    expect(fetchFn).toHaveBeenCalledOnce();
  });

  it("structured backend logs omit request bodies and shared secrets", async () => {
    const events: StructuredLogEvent[] = [];
    const fetchFn = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      id: "tx-1",
      status: "VERIFIED",
      transactionType: "VENDOR_PAYMENT",
      amount: 6500,
      currency: "INR",
      businessDate: "2026-09-26",
      message: "Recorded successfully",
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    const client = new SpringBackendClient(
      "http://spring.local",
      "super-secret-value",
      fetchFn as unknown as typeof fetch,
      { log: (event) => events.push(event) },
    );

    await client.ingestTextCandidate({
      sourceId: "wamid.2",
      sourceType: "WHATSAPP_TEXT",
      rawText: "private restaurant payload",
      amount: "6500.00",
      confidence: 0.9,
    });

    const serialized = JSON.stringify(events);
    expect(serialized).not.toContain("private restaurant payload");
    expect(serialized).not.toContain("super-secret-value");
    expect(serialized).toContain("/api/internal/v1/intake/text-candidate");
  });
});

describe("Batch 6 Ollama resilience and model health", () => {
  it("retries model listing once and reports installed models", async () => {
    const fetchFn = vi.fn()
      .mockResolvedValueOnce(new Response("busy", { status: 503 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        models: [
          { name: "qwen3.5:9b", model: "qwen3.5:9b" },
          { name: "gemma4:12b", model: "gemma4:12b" },
        ],
      }), { status: 200, headers: { "Content-Type": "application/json" } }));
    const client = new OllamaClient(
      "http://ollama.local",
      fetchFn as unknown as typeof fetch,
      { maxRetries: 1, timeoutMs: 1000, log: () => undefined },
    );

    const models = await client.listModels();
    expect(models).toEqual(expect.arrayContaining(["qwen3.5:9b", "gemma4:12b"]));
    expect(fetchFn).toHaveBeenCalledTimes(2);
  });

  it("retries malformed structured output only within the configured bound", async () => {
    const fetchFn = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: { content: "not-json" } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: { content: JSON.stringify({ classification: "IGNORE" }) } }), { status: 200 }));
    const client = new OllamaClient(
      "http://ollama.local",
      fetchFn as unknown as typeof fetch,
      { maxRetries: 1, timeoutMs: 1000, log: () => undefined },
    );

    const result = await client.structured<{ classification: string }>(
      "router-model",
      "private system prompt",
      "private user prompt",
      { type: "object" },
    );

    expect(result.classification).toBe("IGNORE");
    expect(fetchFn).toHaveBeenCalledTimes(2);
  });

  it("Ollama structured logs contain model metadata but no prompts", async () => {
    const events: StructuredLogEvent[] = [];
    const fetchFn = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      message: { content: JSON.stringify({ reply: "ok" }) },
    }), { status: 200 }));
    const client = new OllamaClient(
      "http://ollama.local",
      fetchFn as unknown as typeof fetch,
      { maxRetries: 0, log: (event) => events.push(event) },
    );

    await client.structured("response-model", "secret system prompt", "sensitive user content", { type: "object" });
    const serialized = JSON.stringify(events);
    expect(serialized).toContain("response-model");
    expect(serialized).not.toContain("secret system prompt");
    expect(serialized).not.toContain("sensitive user content");
  });
});
