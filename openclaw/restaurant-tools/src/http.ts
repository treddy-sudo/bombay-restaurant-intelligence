export const RETRYABLE_HTTP_STATUSES = new Set([429, 502, 503, 504]);

export async function withTimeout<T>(
  timeoutMs: number,
  callerSignal: AbortSignal | undefined,
  task: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
  const controller = new AbortController();
  const abortFromCaller = () => controller.abort(callerSignal?.reason ?? new Error("Request cancelled"));

  if (callerSignal?.aborted) abortFromCaller();
  else callerSignal?.addEventListener("abort", abortFromCaller, { once: true });

  const timer = setTimeout(() => controller.abort(new Error(`Request timed out after ${timeoutMs} ms`)), timeoutMs);
  try {
    return await task(controller.signal);
  } finally {
    clearTimeout(timer);
    callerSignal?.removeEventListener("abort", abortFromCaller);
  }
}

export async function retryDelay(attempt: number): Promise<void> {
  const delayMs = Math.min(500, 100 * Math.max(1, attempt));
  await new Promise((resolve) => setTimeout(resolve, delayMs));
}
