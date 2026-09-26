import { createHash, createHmac } from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import { SpringBackendClient } from "./backend.js";

describe("SpringBackendClient signing", () => {
  it("signs the canonical method, path, request id, timestamp, and body hash", async () => {
    const secret = "test-shared-secret";
    const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      const headers = new Headers(init?.headers);
      const timestamp = headers.get("X-Restaurant-Timestamp")!;
      const requestId = headers.get("X-Restaurant-Request-Id")!;
      const signature = headers.get("X-Restaurant-Signature")!;
      const body = typeof init?.body === "string" ? init.body : "";
      const bodyHash = createHash("sha256").update(body).digest("hex");
      const canonical = [timestamp, requestId, init?.method ?? "GET", `${url.pathname}${url.search}`, bodyHash].join("\n");
      const expected = `sha256=${createHmac("sha256", secret).update(canonical).digest("hex")}`;
      expect(signature).toBe(expected);
      return new Response(JSON.stringify({
        id: "tx-1",
        status: "VERIFIED",
        transactionType: "VENDOR_PAYMENT",
        category: "VEGETABLES",
        vendor: "Salman",
        employee: null,
        amount: 6500,
        currency: "INR",
        businessDate: "2026-09-26",
        message: "Recorded successfully",
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    }) as unknown as typeof fetch;

    const client = new SpringBackendClient("http://spring.local", secret, fetchFn);
    const result = await client.ingestTextCandidate({
      sourceId: "msg-1",
      sender: "manager",
      sourceType: "MANUAL_TEXT",
      rawText: "Paid Salman 6500 vegetables",
      transactionType: "VENDOR_PAYMENT",
      category: "VEGETABLES",
      vendor: "Salman",
      amount: "6500.00",
      confidence: 0.96,
    });

    expect(result.status).toBe("VERIFIED");
    expect(fetchFn).toHaveBeenCalledOnce();
  });
});
