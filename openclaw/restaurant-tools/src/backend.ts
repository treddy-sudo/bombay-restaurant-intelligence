import { createHash, createHmac, randomUUID } from "node:crypto";
import { RETRYABLE_HTTP_STATUSES, retryDelay, withTimeout } from "./http.js";
import { defaultStructuredLogSink, errorType, type StructuredLogSink } from "./observability.js";

export type TextSourceType = "MANUAL_TEXT" | "WHATSAPP_TEXT";
export type ImageSourceType = "IMAGE" | "WHATSAPP_IMAGE";
export type SourceType = TextSourceType | ImageSourceType;

export type DashboardIntent =
  | "TODAY_SALES"
  | "YESTERDAY_SALES"
  | "DATE_RANGE_SALES"
  | "TODAY_EXPENSES"
  | "YESTERDAY_EXPENSES"
  | "DATE_RANGE_EXPENSES"
  | "TODAY_PROFIT"
  | "THIS_WEEK_SALES"
  | "THIS_MONTH_SALES"
  | "THIS_WEEK_EXPENSES"
  | "THIS_MONTH_EXPENSES"
  | "VENDOR_SPEND"
  | "CATEGORY_SPEND"
  | "SALARY_TOTAL"
  | "EMPLOYEE_SALARY"
  | "CASH_SALES"
  | "UPI_SALES"
  | "ZOMATO_SALES"
  | "SWIGGY_SALES"
  | "SALES_COMPARISON"
  | "EXPENSE_COMPARISON"
  | "PENDING_REVIEW_COUNT";

export type AnalyticsPeriod = "NONE" | "TODAY" | "YESTERDAY" | "THIS_WEEK" | "THIS_MONTH" | "DATE_RANGE";

export type AnalyticsQuery = {
  intent: DashboardIntent;
  period?: AnalyticsPeriod;
  from?: string;
  to?: string;
  subject?: string;
};

export type TextCandidate = {
  sourceId: string;
  sender?: string;
  sourceType: TextSourceType;
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

export type ImageCandidateRecord = {
  businessDate?: string;
  rawText?: string;
  transactionType?: string;
  category?: string;
  vendor?: string;
  employee?: string;
  amount?: string;
  description?: string;
  context?: string;
  confidence: number;
};

export type ImageCandidateRequest = {
  sourceId: string;
  sender?: string;
  sourceType: ImageSourceType;
  filename: string;
  contentType: "image/jpeg" | "image/png" | "image/webp";
  imageBase64: string;
  fileChecksum: string;
  documentType: string;
  records: ImageCandidateRecord[];
};

export type SpreadsheetContentType =
  | "text/csv"
  | "application/csv"
  | "application/vnd.ms-excel"
  | "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export type SpreadsheetPreviewRequest = {
  filename: string;
  contentType: SpreadsheetContentType;
  fileBase64: string;
};

export type SpreadsheetPreviewRecord = {
  sourceType: "EXCEL" | "CSV";
  sourceId: string;
  businessDate: string;
  sender?: string | null;
  fields: Record<string, string>;
  confidence: number;
  rawText?: string | null;
  sourceFilename?: string | null;
  originalFileLocation?: string | null;
  fileChecksum?: string | null;
};

export type SpreadsheetPreviewResponse = {
  jobId: string;
  filename: string;
  checksum: string;
  recordCount: number;
  records: SpreadsheetPreviewRecord[];
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

export type ImageIngestionResponse = {
  checksum: string;
  documentType?: string | null;
  processed: number;
  results: NormalizationResult[];
};

export type SpreadsheetConfirmResponse = {
  jobId: string;
  processed: number;
  results: NormalizationResult[];
};

export type AnalyticsAnswer = {
  intent: DashboardIntent;
  period: AnalyticsPeriod;
  from?: string | null;
  to?: string | null;
  metric: string;
  subject?: string | null;
  value: number;
  currency?: "INR" | null;
  previousValue?: number | null;
  changePercent?: number | null;
  message?: string | null;
};

export type SpringReadiness = {
  status: "UP" | "DOWN";
  timestamp: string;
  components: Record<string, { status: "UP" | "DOWN"; errorType?: string | null }>;
};

export type FetchLike = typeof fetch;

export type BackendClientOptions = {
  timeoutMs?: number;
  readRetries?: number;
  log?: StructuredLogSink;
};

export class SpringBackendClient {
  private readonly timeoutMs: number;
  private readonly readRetries: number;
  private readonly log: StructuredLogSink;

  constructor(
    private readonly baseUrl: string,
    private readonly sharedSecret: string,
    private readonly fetchFn: FetchLike = fetch,
    options: BackendClientOptions = {},
  ) {
    this.timeoutMs = options.timeoutMs ?? 15_000;
    this.readRetries = options.readRetries ?? 1;
    this.log = options.log ?? defaultStructuredLogSink;
  }

  ingestTextCandidate(candidate: TextCandidate, signal?: AbortSignal): Promise<NormalizationResult> {
    return this.request("POST", "/api/internal/v1/intake/text-candidate", candidate, signal);
  }

  ingestImageCandidates(candidate: ImageCandidateRequest, signal?: AbortSignal): Promise<ImageIngestionResponse> {
    return this.request("POST", "/api/internal/v1/intake/image-candidates", candidate, signal);
  }

  previewSpreadsheet(request: SpreadsheetPreviewRequest, signal?: AbortSignal): Promise<SpreadsheetPreviewResponse> {
    return this.request("POST", "/api/internal/v1/intake/spreadsheets/preview", request, signal);
  }

  confirmSpreadsheet(jobId: string, signal?: AbortSignal): Promise<SpreadsheetConfirmResponse> {
    return this.request("POST", `/api/internal/v1/intake/spreadsheets/${encodeURIComponent(jobId)}/confirm`, undefined, signal);
  }

  queryAnalytics(query: AnalyticsQuery, signal?: AbortSignal): Promise<AnalyticsAnswer> {
    const params = new URLSearchParams({ intent: query.intent });
    if (query.period) params.set("period", query.period);
    if (query.from) params.set("from", query.from);
    if (query.to) params.set("to", query.to);
    if (query.subject) params.set("subject", query.subject);
    return this.request("GET", `/api/internal/v1/analytics/query?${params.toString()}`, undefined, signal);
  }

  queryTodaySales(signal?: AbortSignal): Promise<AnalyticsAnswer> {
    return this.queryAnalytics({ intent: "TODAY_SALES" }, signal);
  }

  health(signal?: AbortSignal): Promise<SpringReadiness> {
    return this.request("GET", "/api/internal/v1/health", undefined, signal);
  }

  private async request<T>(method: string, path: string, body: unknown, callerSignal?: AbortSignal): Promise<T> {
    if (!this.sharedSecret) throw new Error("Backend shared secret is not configured");

    const maxAttempts = method === "GET" ? this.readRetries + 1 : 1;
    let lastError: unknown;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      const url = new URL(path, this.baseUrl);
      const bodyText = body === undefined ? "" : JSON.stringify(body);
      const timestamp = Math.floor(Date.now() / 1000).toString();
      const requestId = randomUUID();
      const bodyHash = createHash("sha256").update(bodyText).digest("hex");
      const canonicalPath = `${url.pathname}${url.search}`;
      const canonical = [timestamp, requestId, method, canonicalPath, bodyHash].join("\n");
      const signature = `sha256=${createHmac("sha256", this.sharedSecret).update(canonical).digest("hex")}`;
      const started = Date.now();

      const headers: Record<string, string> = {
        "X-Restaurant-Timestamp": timestamp,
        "X-Restaurant-Request-Id": requestId,
        "X-Restaurant-Signature": signature,
      };
      if (body !== undefined) headers["Content-Type"] = "application/json";

      try {
        const response = await withTimeout(this.timeoutMs, callerSignal, (signal) => this.fetchFn(url, {
          method,
          headers,
          body: body === undefined ? undefined : bodyText,
          signal,
        }));
        const text = await response.text();

        this.log({
          component: "spring-backend",
          requestId,
          backendEndpoint: canonicalPath,
          processingStatus: response.ok ? "SUCCESS" : "HTTP_ERROR",
          errorType: response.ok ? undefined : `HTTP_${response.status}`,
          latencyMs: Date.now() - started,
          attempt,
        });

        if (!response.ok) {
          const error = new Error(`Spring backend request failed (${response.status}): ${text.slice(0, 300)}`);
          if (method === "GET" && RETRYABLE_HTTP_STATUSES.has(response.status) && attempt < maxAttempts) {
            lastError = error;
            await retryDelay(attempt);
            continue;
          }
          throw error;
        }
        return JSON.parse(text) as T;
      } catch (error) {
        lastError = error;
        this.log({
          component: "spring-backend",
          requestId,
          backendEndpoint: canonicalPath,
          processingStatus: "ERROR",
          errorType: errorType(error),
          latencyMs: Date.now() - started,
          attempt,
        });
        if (callerSignal?.aborted || method !== "GET" || attempt >= maxAttempts) throw error;
        await retryDelay(attempt);
      }
    }

    throw lastError instanceof Error ? lastError : new Error("Spring backend request failed");
  }
}
