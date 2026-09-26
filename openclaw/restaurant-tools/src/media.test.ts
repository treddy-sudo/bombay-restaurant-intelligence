import { mkdtemp, mkdir, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { readScopedAttachment, resolveAttachmentInput } from "./media.js";

const cleanup: string[] = [];

async function tempDir(prefix: string): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), prefix));
  cleanup.push(dir);
  return dir;
}

afterEach(async () => {
  await Promise.all(cleanup.splice(0).map((dir) => rm(dir, { recursive: true, force: true })));
});

describe("scoped OpenClaw attachment reads", () => {
  it("reads an absolute staged attachment only when it is inside an allowed root", async () => {
    const root = await tempDir("restaurant-media-root-");
    const nested = join(root, "inbound");
    await mkdir(nested);
    const file = join(nested, "receipt.jpg");
    await writeFile(file, Buffer.from("receipt-bytes"));

    const result = await readScopedAttachment(file, [root]);

    expect(result.filename).toBe("receipt.jpg");
    expect(result.bytes.toString()).toBe("receipt-bytes");
    expect(Buffer.from(result.base64, "base64").toString()).toBe("receipt-bytes");
  });

  it("resolves a sandbox-relative staged path against configured roots", async () => {
    const root = await tempDir("restaurant-workspace-");
    const nested = join(root, "media", "inbound");
    await mkdir(nested, { recursive: true });
    await writeFile(join(nested, "sales.csv"), "date,amount\n2026-09-26,100\n");

    const result = await readScopedAttachment("media/inbound/sales.csv", [root]);

    expect(result.filename).toBe("sales.csv");
    expect(result.bytes.toString()).toContain("2026-09-26");
  });

  it("rejects files outside configured roots", async () => {
    const root = await tempDir("restaurant-allowed-");
    const outside = await tempDir("restaurant-outside-");
    const file = join(outside, "secret.txt");
    await writeFile(file, "do not read");

    await expect(readScopedAttachment(file, [root])).rejects.toThrow("outside configured OpenClaw inbound media roots");
  });

  it("rejects symlinks that escape an allowed root after realpath resolution", async () => {
    const root = await tempDir("restaurant-symlink-root-");
    const outside = await tempDir("restaurant-symlink-outside-");
    const outsideFile = join(outside, "outside.csv");
    const link = join(root, "linked.csv");
    await writeFile(outsideFile, "amount\n999\n");
    await symlink(outsideFile, link);

    await expect(readScopedAttachment(link, [root])).rejects.toThrow("outside configured OpenClaw inbound media roots");
  });

  it("enforces file size limits before reading", async () => {
    const root = await tempDir("restaurant-size-root-");
    const file = join(root, "large.csv");
    await writeFile(file, Buffer.alloc(32, 1));

    await expect(readScopedAttachment(file, [root], 16)).rejects.toThrow("exceeds 16 byte limit");
  });

  it("requires exactly one inline or staged attachment source", async () => {
    const root = await tempDir("restaurant-input-root-");
    const file = join(root, "receipt.jpg");
    await writeFile(file, "image");

    await expect(resolveAttachmentInput({ allowedRoots: [root] })).rejects.toThrow("exactly one");
    await expect(resolveAttachmentInput({ inlineBase64: "aW1hZ2U=", attachmentPath: file, allowedRoots: [root] }))
      .rejects.toThrow("exactly one");
    await expect(resolveAttachmentInput({ attachmentPath: file, allowedRoots: [root] }))
      .resolves.toMatchObject({ stagedFilename: "receipt.jpg" });
  });
});
