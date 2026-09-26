import { createHash, createHmac, randomUUID } from "node:crypto";

export type SourceType = "MANUAL_TEXT" | "WHATSAPP_TEXT";

export type TextCandidate = {
  sourceId: string;
  sender?: string;
  sourceType: SourceType;
  businessDate?: string;
  rawText: string;
  transactionType?: string;
  category?: string;
  vendor?: string;
  employee?: string;
  amount: string;
  description?: string;
  context?: string;
  confidence: number;
};

export type NormalizationResult = {
  id: string;
  status: string;
  transactionType: string;
  category?: string | null;
  vendor?: string | null;
  employee?: string | null;
  amount: number;
  currency: string;
  businessDate: string;
  message: string;
};

export type AnalyticsAnswer = {
  intent: "TODAY_SALES";
  from: string;
  to: string;
  metric: "sales";
  value: number;
  currency: "INR";
};

export type FetchLike = typeof fetch;

export class SpringBackendClient {
  constructor(
    private readonly baseUrl: string,
    private readonly sharedSecret: string,
    private readonly fetchFn: FetchLike = fetch,
  ) {}

  ingestTextCandidate(candidate: TextCandidate, signal?: AbortSignal): Promise<NormalizationResult> {
    return this.request("POST", "/api/internal/v1/intake/text-candidate", candidate, signal);
  }

  queryTodaySales(signal?: AbortSignal): Promise<AnalyticsAnswer> {
    return this.request("GET", "/api/internal/v1/analytics/query?intent=TODAY_SALES", undefined, signal);
  }

  private async request<T>(method: string, path: string, body: unknown, signal?: AbortSignal): Promise<T> {
    if (!this.sharedSecret) throw new Error("Backend shared secret is not configured");

    const url = new URL(path, this.baseUrl);
    const bodyText = body === undefined ? "" : JSON.stringify(body);
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const requestId = randomUUID();
    const bodyHash = createHash("sha256").update(bodyText).digest("hex");
    const canonicalPath = `${url.pathname}${url.search}`;
    const canonical = [timestamp, requestId, method, canonicalPath, bodyHash].join("\n");
    const signature = `sha256=${createHmac("sha256", this.sharedSecret).update(canonical).digest("hex")}`;

    const headers: Record<string, string> = {
      "X-Restaurant-Timestamp": timestamp,
      "X-Restaurant-Request-Id": requestId,
      "X-Restaurant-Signature": signature,
    };
    if (body !== undefined) headers["Content-Type"] = "application/json";

    const response = await this.fetchFn(url, {
      method,
      headers,
      body: body === undefined ? undefined : bodyText,
      signal,
    });

    const text = await response.text();
    if (!response.ok) {
      throw new Error(`Spring backend request failed (${response.status}): ${text.slice(0, 300)}`);
    }
    return JSON.parse(text) as T;
  }
}
