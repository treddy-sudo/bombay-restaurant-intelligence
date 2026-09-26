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

  it("signs the exact approved analytics query string and preserves scoped parameters", async () => {
    const secret = "analytics-secret";
    const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      expect(url.pathname).toBe("/api/internal/v1/analytics/query");
      expect(url.searchParams.get("intent")).toBe("VENDOR_SPEND");
      expect(url.searchParams.get("period")).toBe("THIS_MONTH");
      expect(url.searchParams.get("subject")).toBe("Salman & Sons");
      expect(url.searchParams.has("from")).toBe(false);
      expect(url.searchParams.has("to")).toBe(false);

      const headers = new Headers(init?.headers);
      const timestamp = headers.get("X-Restaurant-Timestamp")!;
      const requestId = headers.get("X-Restaurant-Request-Id")!;
      const bodyHash = createHash("sha256").update("").digest("hex");
      const canonicalPath = `${url.pathname}${url.search}`;
      const canonical = [timestamp, requestId, "GET", canonicalPath, bodyHash].join("\n");
      const expected = `sha256=${createHmac("sha256", secret).update(canonical).digest("hex")}`;
      expect(headers.get("X-Restaurant-Signature")).toBe(expected);

      return new Response(JSON.stringify({
        intent: "VENDOR_SPEND",
        period: "THIS_MONTH",
        from: "2026-09-01",
        to: "2026-09-26",
        metric: "vendorSpend",
        subject: "Salman & Sons",
        value: 12345.67,
        currency: "INR",
        previousValue: null,
        changePercent: null,
        message: null,
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    }) as unknown as typeof fetch;

    const client = new SpringBackendClient("http://spring.local", secret, fetchFn);
    const answer = await client.queryAnalytics({
      intent: "VENDOR_SPEND",
      period: "THIS_MONTH",
      subject: "Salman & Sons",
    });

    expect(answer.value).toBe(12345.67);
    expect(answer.subject).toBe("Salman & Sons");
    expect(fetchFn).toHaveBeenCalledOnce();
  });
});
