import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const configPath = fileURLToPath(new URL("../../openclaw.batch5.example.json5", import.meta.url));
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

describe("Batch 5 WhatsApp management-group configuration", () => {
  it("admits only an explicit management group and explicit management senders", () => {
    expect(config).toContain('groupPolicy: "allowlist"');
    expect(config).toContain("groupAllowFrom: [");
    expect(config).toContain('"1234567890-1234567890@g.us"');
    expect(config).toContain("requireMention: false");
    expect(config).not.toMatch(/groups:\s*\{\s*"\*"\s*:/s);
    expect(config).not.toMatch(/groupAllowFrom:\s*\[\s*"\*"/s);
  });

  it("preserves Batch 4 DM pairing and per-sender isolation", () => {
    expect(config).toContain('dmPolicy: "pairing"');
    expect(config).toContain('dmScope: "per-channel-peer"');
  });

  it("allows selective group silence without enabling silent DMs", () => {
    expect(config).toContain("silentReply: {");
    expect(config).toContain('group: "allow"');
    expect(config).not.toMatch(/silentReply:\s*\{[^}]*dm\s*:\s*"allow"/s);
  });

  it("keeps the agent restricted to the established restaurant tools", () => {
    for (const tool of restaurantTools) {
      expect(config).toContain(`"${tool}"`);
    }
    expect(config).toContain('"group:runtime"');
    expect(config).toContain('"group:fs"');
    expect(config).toContain('"group:automation"');
    expect(config).toContain("elevated: { enabled: false }");
    expect(config).toContain("agentToAgent: { enabled: false }");
  });

  it("requires successful verified data ingestion to be silent and questions/errors to remain visible", () => {
    expect(policy).toContain("If the result is `DATA_TEXT` and the Spring record status is `VERIFIED`, return exactly `NO_REPLY`");
    expect(policy).toContain("If every Spring result is `VERIFIED`, return exactly `NO_REPLY`");
    expect(policy).toContain("If all confirmed rows are `VERIFIED`, return exactly `NO_REPLY`");
    expect(policy).toContain("If any result is `REVIEW_REQUIRED`, send one concise notice");
    expect(policy).toContain("If the result is `DASHBOARD_QUESTION` with an approved intent, send only the backend-backed reply");
  });

  it("fails closed conceptually across group context and preserves accounting authority", () => {
    expect(policy).toContain("Never reinterpret an unauthorized group or sender as authorized");
    expect(policy).toContain("group history may help understand language but is never an accounting data source");
    expect(policy).toContain("Never calculate official monetary totals yourself");
    expect(policy).toContain("Never answer an accounting question from memory");
    expect(policy).toContain("Never carry values from one WhatsApp group, DM, sender, or future restaurant location into another conversation's answer");
  });

  it("keeps staged attachment reads scoped to configured media roots", () => {
    expect(config).toContain("inboundMediaRoots: [");
    expect(policy).toContain("Staged attachments may be read only by the restaurant tools through their configured inbound-media roots");
    expect(policy).toContain("do not read the file with any other tool");
  });
});
