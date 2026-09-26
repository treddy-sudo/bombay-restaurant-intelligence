import { defaultStructuredLogSink, errorType, type StructuredLogEvent, type StructuredLogSink } from "./observability.js";

export type ToolTraceMetadata = {
  whatsappMessageId?: string;
  groupId?: string;
  senderId?: string;
  messageType?: string;
  agentTool: string;
  ollamaModel?: string;
};

export async function observeTool<T>(
  metadata: ToolTraceMetadata,
  action: () => Promise<T>,
  summarize?: (result: T) => Partial<StructuredLogEvent>,
  log: StructuredLogSink = defaultStructuredLogSink,
): Promise<T> {
  const started = Date.now();
  try {
    const result = await action();
    log({
      component: "openclaw",
      ...metadata,
      processingStatus: "SUCCESS",
      latencyMs: Date.now() - started,
      ...(summarize?.(result) ?? {}),
    });
    return result;
  } catch (error) {
    log({
      component: "openclaw",
      ...metadata,
      processingStatus: "ERROR",
      errorType: errorType(error),
      latencyMs: Date.now() - started,
    });
    throw error;
  }
}

export function reviewStatusFromResults(results: Array<{ status?: string }> | undefined): string | undefined {
  if (!results?.length) return undefined;
  if (results.some((result) => result.status === "REVIEW_REQUIRED")) return "REVIEW_REQUIRED";
  if (results.every((result) => result.status === "VERIFIED")) return "VERIFIED";
  return results.map((result) => result.status).filter(Boolean).join(",") || undefined;
}
