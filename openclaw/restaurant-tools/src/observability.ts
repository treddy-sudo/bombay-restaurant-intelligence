export type StructuredLogEvent = {
  timestamp?: string;
  requestId?: string;
  whatsappMessageId?: string;
  groupId?: string;
  senderId?: string;
  messageType?: string;
  classification?: string;
  agentTool?: string;
  ollamaModel?: string;
  backendEndpoint?: string;
  processingStatus?: string;
  reviewStatus?: string;
  errorType?: string;
  latencyMs?: number;
  attempt?: number;
  component?: "openclaw" | "spring-backend" | "ollama";
};

export type StructuredLogSink = (event: StructuredLogEvent) => void;

export const defaultStructuredLogSink: StructuredLogSink = (event) => {
  const safe: StructuredLogEvent = {
    ...event,
    timestamp: event.timestamp ?? new Date().toISOString(),
  };
  console.info(JSON.stringify(safe));
};

export function errorType(error: unknown): string {
  if (error instanceof Error && error.name) return error.name;
  return "UnknownError";
}
