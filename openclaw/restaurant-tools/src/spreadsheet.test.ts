import { createHash, createHmac } from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import { SpringBackendClient } from "./backend.js";

const SECRET = "spreadsheet-test-secret";

function verifySignature(method: string, url: URL, headers: Headers, bodyText: string) {
  const timestamp = headers.get("X-Restaurant-Timestamp");
  const requestId = headers.get("X-Restaurant-Request-Id");
  const signature = headers.get("X-Restaurant-Signature");
  expect(timestamp).toBeTruthy();
  expect(requestId).toBeTruthy();
  const bodyHash = createHash("sha256").update(bodyText).digest("hex");
  const canonical = [timestamp, requestId, method, `${url.pathname}${url.search}`, bodyHash].join("\n");
  const expected = `sha256=${createHmac("sha256", SECRET).update(canonical).digest("hex")}`;
  expect(signature).toBe(expected);
}

describe("SpringBackendClient Batch 3 spreadsheet flow", () => {
  it("sends spreadsheet bytes directly to the signed Spring preview endpoint", async () => {
    const fileBase64 = Buffer.from("Vendor,Amount\nSalman,100\n").toString("base64");
    const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      expect(url.pathname).toBe("/api/internal/v1/intake/spreadsheets/preview");
      expect(init?.method).toBe("POST");
      const bodyText = String(init?.body ?? "");
      const body = JSON.parse(bodyText);
      expect(body).toEqual({
        filename: "purchases.csv",
        contentType: "text/csv",
        fileBase64,
      });
      verifySignature("POST", url, new Headers(init?.headers), bodyText);
      return new Response(JSON.stringify({
        jobId: "2aabed92-82c0-43dd-b6f0-b8c801199a92",
        filename: "purchases.csv",
        checksum: "abc123",
        recordCount: 1,
        records: [{
          sourceType: "CSV",
          sourceId: "abc123:2",
          businessDate: "2026-09-26",
          fields: { vendor: "Salman", amount: "100" },
          confidence: 0.92,
        }],
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    }) as unknown as typeof fetch;

    const backend = new SpringBackendClient("https://spring.example", SECRET, fetchFn);
    const preview = await backend.previewSpreadsheet({
      filename: "purchases.csv",
      contentType: "text/csv",
      fileBase64,
    });

    expect(preview.recordCount).toBe(1);
    expect(preview.records[0]?.sourceType).toBe("CSV");
    expect(fetchFn).toHaveBeenCalledOnce();
  });

  it("confirms a preview job with a signed empty-body request", async () => {
    const jobId = "2aabed92-82c0-43dd-b6f0-b8c801199a92";
    const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      expect(url.pathname).toBe(`/api/internal/v1/intake/spreadsheets/${jobId}/confirm`);
      expect(init?.method).toBe("POST");
      expect(init?.body).toBeUndefined();
      verifySignature("POST", url, new Headers(init?.headers), "");
      return new Response(JSON.stringify({
        jobId,
        processed: 1,
        results: [{
          id: "tx-1",
          status: "VERIFIED",
          transactionType: "VENDOR_PAYMENT",
          category: "VEGETABLES",
          vendor: "Salman",
          employee: null,
          amount: 100,
          currency: "INR",
          businessDate: "2026-09-26",
          message: "Recorded successfully",
        }],
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    }) as unknown as typeof fetch;

    const backend = new SpringBackendClient("https://spring.example", SECRET, fetchFn);
    const confirmation = await backend.confirmSpreadsheet(jobId);

    expect(confirmation.processed).toBe(1);
    expect(confirmation.results[0]?.status).toBe("VERIFIED");
    expect(fetchFn).toHaveBeenCalledOnce();
  });
});
