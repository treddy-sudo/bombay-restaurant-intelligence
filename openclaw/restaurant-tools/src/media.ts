import { realpath, readFile, stat } from "node:fs/promises";
import { basename, isAbsolute, join, relative, resolve } from "node:path";

export type ScopedAttachment = {
  path: string;
  filename: string;
  bytes: Buffer;
  base64: string;
};

async function existingRealRoots(roots: readonly string[]): Promise<string[]> {
  const resolved: string[] = [];
  for (const root of roots) {
    try {
      resolved.push(await realpath(resolve(root)));
    } catch {
      // A configured root may not exist until OpenClaw stages its first attachment.
    }
  }
  return resolved;
}

function insideRoot(target: string, root: string): boolean {
  const rel = relative(root, target);
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

async function resolveAttachmentPath(requestedPath: string, roots: readonly string[]): Promise<{ path: string; roots: string[] }> {
  if (!requestedPath.trim()) throw new Error("Attachment path is empty");
  const realRoots = await existingRealRoots(roots);
  if (realRoots.length === 0) throw new Error("No configured inbound media root exists on this host");

  const candidates = isAbsolute(requestedPath)
    ? [requestedPath]
    : realRoots.map((root) => join(root, requestedPath));

  for (const candidate of candidates) {
    try {
      const target = await realpath(candidate);
      if (realRoots.some((root) => insideRoot(target, root))) {
        return { path: target, roots: realRoots };
      }
    } catch {
      // Try the next configured root for a relative staged path.
    }
  }
  throw new Error("Attachment path is outside configured OpenClaw inbound media roots");
}

export async function readScopedAttachment(
  requestedPath: string,
  allowedRoots: readonly string[],
  maxBytes = 10 * 1024 * 1024,
): Promise<ScopedAttachment> {
  const resolved = await resolveAttachmentPath(requestedPath, allowedRoots);
  const info = await stat(resolved.path);
  if (!info.isFile()) throw new Error("Attachment path is not a regular file");
  if (info.size <= 0) throw new Error("Attachment is empty");
  if (info.size > maxBytes) throw new Error(`Attachment exceeds ${maxBytes} byte limit`);

  const bytes = await readFile(resolved.path);
  return {
    path: resolved.path,
    filename: basename(resolved.path),
    bytes,
    base64: bytes.toString("base64"),
  };
}

export async function resolveAttachmentInput(params: {
  inlineBase64?: string;
  attachmentPath?: string;
  allowedRoots: readonly string[];
  maxBytes?: number;
}): Promise<{ base64: string; stagedFilename?: string }> {
  const inline = params.inlineBase64?.trim();
  const path = params.attachmentPath?.trim();
  if (Boolean(inline) === Boolean(path)) {
    throw new Error("Provide exactly one of inline Base64 or attachmentPath");
  }
  if (inline) {
    return { base64: inline };
  }
  const attachment = await readScopedAttachment(path!, params.allowedRoots, params.maxBytes);
  return { base64: attachment.base64, stagedFilename: attachment.filename };
}
