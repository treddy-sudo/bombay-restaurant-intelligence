import { describe, expect, it, vi } from "vitest";
import { RestaurantRouter, type BackendPort, type OllamaPort } from "./router.js";

const normalization = {
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
};

describe("RestaurantRouter approved dashboard routing", () => {
  it("routes restaurant data through Ollama extraction and Spring normalization", async () => {
    const structured = vi.fn()
      .mockResolvedValueOnce({ classification: "DATA_TEXT", intent: "UNSUPPORTED", confidence: 0.99 })
      .mockResolvedValueOnce({
        transactionType: "VENDOR_PAYMENT",
        businessDate: "2026-09-26",
        vendor: "Salman",
        employee: "",
        category: "VEGETABLES",
        amount: "6500.00",
        description: "Vegetable payment to Salman",
        context: "",
        confidence: 0.96,
      });
    const ingestTextCandidate = vi.fn().mockResolvedValue(normalization);
    const backend = {
      ingestTextCandidate,
      queryTodaySales: vi.fn(),
      queryAnalytics: vi.fn(),
    } as unknown as BackendPort;
    const ollama = { structured } as unknown as OllamaPort;
    const router = new RestaurantRouter(backend, ollama, { router: "router-model", text: "text-model", response: "response-model" });

    const result = await router.routeText("Paid Salman 6500 vegetables", "msg-1", "manager");

    expect(result).toMatchObject({ classification: "DATA_TEXT", silent: true, record: normalization });
    expect(ingestTextCandidate).toHaveBeenCalledWith(expect.objectContaining({
      sourceId: "msg-1",
      rawText: "Paid Salman 6500 vegetables",
      transactionType: "VENDOR_PAYMENT",
      category: "VEGETABLES",
      vendor: "Salman",
      amount: "6500.00",
      confidence: 0.96,
    }), undefined);
  });

  it("keeps today's sales compatibility path and formats only the Spring value", async () => {
    const structured = vi.fn()
      .mockResolvedValueOnce({ classification: "DASHBOARD_QUESTION", intent: "TODAY_SALES", period: "TODAY", confidence: 0.99 })
      .mockResolvedValueOnce({ reply: "Today's verified sales are ₹84,560." });
    const queryTodaySales = vi.fn().mockResolvedValue({
      intent: "TODAY_SALES",
      period: "TODAY",
      from: "2026-09-26",
      to: "2026-09-26",
      metric: "sales",
      value: 84560,
      currency: "INR",
    });
    const backend = {
      ingestTextCandidate: vi.fn(),
      queryTodaySales,
      queryAnalytics: vi.fn(),
    } as unknown as BackendPort;
    const ollama = { structured } as unknown as OllamaPort;
    const router = new RestaurantRouter(backend, ollama, { router: "router-model", text: "text-model", response: "response-model" });

    const result = await router.routeText("What are today's sales?", "msg-2", "manager");

    expect(queryTodaySales).toHaveBeenCalledOnce();
    expect(backend.queryAnalytics).not.toHaveBeenCalled();
    expect(result).toMatchObject({
      classification: "DASHBOARD_QUESTION",
      intent: "TODAY_SALES",
      silent: false,
      reply: "Today's verified sales are ₹84,560.",
      analytics: { value: 84560, currency: "INR" },
    });
    expect(structured.mock.calls[1]?.[2]).toContain("84560");
  });

  it("routes vendor spend with subject and period to the signed Spring analytics query", async () => {
    const structured = vi.fn()
      .mockResolvedValueOnce({
        classification: "DASHBOARD_QUESTION",
        intent: "VENDOR_SPEND",
        period: "THIS_MONTH",
        subject: "Salman",
        confidence: 0.99,
      })
      .mockResolvedValueOnce({ reply: "Verified spend with Salman this month is ₹12,345.67." });
    const queryAnalytics = vi.fn().mockResolvedValue({
      intent: "VENDOR_SPEND",
      period: "THIS_MONTH",
      from: "2026-09-01",
      to: "2026-09-26",
      metric: "vendorSpend",
      subject: "Salman",
      value: 12345.67,
      currency: "INR",
      previousValue: null,
      changePercent: null,
      message: null,
    });
    const backend = {
      ingestTextCandidate: vi.fn(),
      queryTodaySales: vi.fn(),
      queryAnalytics,
    } as unknown as BackendPort;
    const router = new RestaurantRouter(
      backend,
      { structured } as unknown as OllamaPort,
      { router: "router", text: "text", response: "response" },
    );

    const result = await router.routeText("How much did we spend with Salman this month?", "msg-vendor", "manager");

    expect(queryAnalytics).toHaveBeenCalledWith(expect.objectContaining({
      intent: "VENDOR_SPEND",
      period: "THIS_MONTH",
      subject: "Salman",
    }), undefined);
    expect(result).toMatchObject({
      classification: "DASHBOARD_QUESTION",
      intent: "VENDOR_SPEND",
      silent: false,
      analytics: { value: 12345.67, subject: "Salman" },
      reply: "Verified spend with Salman this month is ₹12,345.67.",
    });
    expect(structured.mock.calls[1]?.[2]).toContain("12345.67");
  });

  it("does not call Spring when an explicit date-range intent lacks both dates", async () => {
    const structured = vi.fn().mockResolvedValue({
      classification: "DASHBOARD_QUESTION",
      intent: "DATE_RANGE_EXPENSES",
      period: "DATE_RANGE",
      from: "2026-09-01",
      to: "",
      confidence: 0.93,
    });
    const backend = {
      ingestTextCandidate: vi.fn(),
      queryTodaySales: vi.fn(),
      queryAnalytics: vi.fn(),
    } as unknown as BackendPort;
    const router = new RestaurantRouter(
      backend,
      { structured } as unknown as OllamaPort,
      { router: "router", text: "text", response: "response" },
    );

    const result = await router.routeText("What were expenses from September 1?", "msg-range", "manager");

    expect(backend.queryAnalytics).not.toHaveBeenCalled();
    expect(result).toMatchObject({ classification: "DASHBOARD_QUESTION", intent: "UNSUPPORTED", silent: false });
  });

  it("does not invent answers for unsupported business advice", async () => {
    const structured = vi.fn().mockResolvedValue({ classification: "DASHBOARD_QUESTION", intent: "UNSUPPORTED", confidence: 0.98 });
    const backend = {
      ingestTextCandidate: vi.fn(),
      queryTodaySales: vi.fn(),
      queryAnalytics: vi.fn(),
    } as unknown as BackendPort;
    const router = new RestaurantRouter(
      backend,
      { structured } as unknown as OllamaPort,
      { router: "router", text: "text", response: "response" },
    );

    const result = await router.routeText("Do you think Salman charges too much?", "msg-3", "manager");

    expect(backend.queryTodaySales).not.toHaveBeenCalled();
    expect(backend.queryAnalytics).not.toHaveBeenCalled();
    expect(result).toMatchObject({ intent: "UNSUPPORTED", silent: false });
  });
});
