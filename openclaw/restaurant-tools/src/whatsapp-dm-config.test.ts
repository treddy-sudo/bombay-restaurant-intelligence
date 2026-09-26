import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const configPath = fileURLToPath(new URL("../../openclaw.batch4.example.json5", import.meta.url));
const policyPath = fileURLToPath(new URL("../../workspace/AGENTS.md", import.meta.url));
const config = readFileSync(configPath, "utf8");
const policy = readFileSync(policyPath, "utf8");

const restaurantTools = [
  "restaurant_route_text",
  "restaurant_ingest_text",
  "restaurant_ingest_image",
  "restaurant_preview_spreadsheet",
  "restaurant_confirm_spreadsheet",
  "restaurant_get_sales",
];

describe("Batch 4 native WhatsApp DM configuration", () => {
  it("makes OpenClaw the WhatsApp owner with safe DM isolation and groups disabled", () => {
    expect(config).toContain("channels: {");
    expect(config).toContain("whatsapp: {");
    expect(config).toContain('dmPolicy: "pairing"');
    expect(config).toContain('groupPolicy: "disabled"');
    expect(config).toContain('dmScope: "per-channel-peer"');
    expect(config).toContain('bind: "loopback"');
    expect(config).toContain('token: "${OPENCLAW_GATEWAY_TOKEN}"');
  });

  it("exposes only the established restaurant tool surface and denies broad runtime authority", () => {
    for (const tool of restaurantTools) {
      expect(config).toContain(`"${tool}"`);
    }
    expect(config).toContain('"group:runtime"');
    expect(config).toContain('"group:fs"');
    expect(config).toContain("elevated: { enabled: false }");
    expect(config).toContain("agentToAgent: { enabled: false }");
  });

  it("does not reintroduce the reversed Spring-to-OpenClaw WhatsApp bridge", () => {
    expect(config).not.toContain("WHATSAPP_OPENCLAW_ENABLED");
    expect(config).not.toContain("OPENCLAW_GATEWAY_URL");
    expect(config).not.toContain("Meta WhatsApp Cloud API remains at Spring");
  });

  it("pins the workspace policy to backend accounting truth and deterministic spreadsheet parsing", () => {
    expect(policy).toContain("Never calculate official monetary totals yourself");
    expect(policy).toContain("Never answer an accounting question from memory");
    expect(policy).toContain("Never mark a candidate VERIFIED");
    expect(policy).toContain("IntakeAgent -> NormalizationAgent");
    expect(policy).toContain("restaurant_preview_spreadsheet");
    expect(policy).toContain("Do not send spreadsheet contents to a vision or general reasoning model");
    expect(policy).toContain("Ordinary accepted DMs may require a visible channel response");
    expect(policy).toContain("keep any unavoidable DM acknowledgement minimal");
  });
});
