const PRIVATE_WORKER_HEADERS = {
  "content-type": "application/json",
  "cache-control": "private, no-store, max-age=0",
  "pragma": "no-cache",
  "x-content-type-options": "nosniff",
};

export async function hasWorkerAuthorization(
  request: Request,
  expectedSecret: string,
): Promise<boolean> {
  const header = request.headers.get("authorization") ?? "";
  const provided = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!provided || !expectedSecret) return false;
  const encoder = new TextEncoder();
  const [providedHash, expectedHash] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(provided)),
    crypto.subtle.digest("SHA-256", encoder.encode(expectedSecret)),
  ]);
  const left = new Uint8Array(providedHash);
  const right = new Uint8Array(expectedHash);
  let difference = left.length ^ right.length;
  for (let index = 0; index < Math.min(left.length, right.length); index++) {
    difference |= left[index] ^ right[index];
  }
  return difference === 0;
}

export async function readWorkerBatchSize(
  request: Request,
  maximum: number,
): Promise<number | null> {
  if (request.headers.get("content-type")?.split(";", 1)[0].trim() !== "application/json") {
    return null;
  }
  const declared = Number(request.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > 128) return null;
  let body: unknown;
  try {
    const raw = await request.text();
    if (!raw || raw.length > 128) return null;
    body = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) return null;
  const record = body as Record<string, unknown>;
  if (Object.keys(record).length !== 1 || !Number.isInteger(record.batch_size)) return null;
  const size = record.batch_size as number;
  return size >= 1 && size <= maximum ? size : null;
}

export function workerJson(value: Record<string, number>, status: number): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: PRIVATE_WORKER_HEADERS,
  });
}
