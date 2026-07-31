import { extractPrivateAlbumBearer } from "./privateAlbumMedia.ts";

export type CleanupBatchResult =
  | { kind: "ok"; objectPaths: string[] }
  | { kind: "unauthenticated" }
  | { kind: "forbidden" }
  | { kind: "error" };

export type CleanupDeleteResult =
  | { kind: "ok" }
  | { kind: "error" };

export type CleanupConfirmationResult =
  | { kind: "confirmed" }
  | { kind: "pending" }
  | { kind: "error" };

export type PrivateAlbumCleanupDependencies = {
  getBatch: (
    accessToken: string,
    batchSize: number,
  ) => Promise<CleanupBatchResult>;
  deleteObject: (objectPath: string) => Promise<CleanupDeleteResult>;
  confirmDeleted: (
    objectPath: string,
  ) => Promise<CleanupConfirmationResult>;
};

export type PrivateAlbumCleanupCounts = {
  processed: number;
  deleted: number;
  failed: number;
};

const UUID_SEGMENT =
  "[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
const PRIVATE_ALBUM_CLEANUP_PATH = new RegExp(
  `^${UUID_SEGMENT}/${UUID_SEGMENT}/${UUID_SEGMENT}\\.(?:jpg|jpeg|png|webp)$`,
);
const MAX_REQUEST_BODY_LENGTH = 256;
const EMPTY_COUNTS: Readonly<PrivateAlbumCleanupCounts> = {
  processed: 0,
  deleted: 0,
  failed: 0,
};
const RESPONSE_HEADERS: Readonly<Record<string, string>> = {
  "cache-control": "private, no-store, max-age=0",
  "pragma": "no-cache",
  "x-content-type-options": "nosniff",
};

export function isPrivateAlbumCleanupObjectPath(value: string): boolean {
  return PRIVATE_ALBUM_CLEANUP_PATH.test(value);
}

function countsResponse(
  counts: PrivateAlbumCleanupCounts,
  status: number,
  extraHeaders: Record<string, string> = {},
): Response {
  const headers = new Headers(RESPONSE_HEADERS);
  headers.set("content-type", "application/json; charset=utf-8");
  for (const [name, value] of Object.entries(extraHeaders)) {
    headers.set(name, value);
  }
  return new Response(JSON.stringify(counts), { status, headers });
}

async function readBatchSize(request: Request): Promise<number | null> {
  const contentType = request.headers.get("content-type")
    ?.split(";", 1)[0]
    .trim()
    .toLowerCase();
  if (contentType !== "application/json") return null;

  const declaredLength = Number(request.headers.get("content-length"));
  if (
    Number.isFinite(declaredLength) &&
    declaredLength > MAX_REQUEST_BODY_LENGTH
  ) {
    return null;
  }

  let rawBody: string;
  try {
    rawBody = await request.text();
  } catch {
    return null;
  }
  if (!rawBody || rawBody.length > MAX_REQUEST_BODY_LENGTH) return null;

  let body: unknown;
  try {
    body = JSON.parse(rawBody);
  } catch {
    return null;
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) return null;

  const record = body as Record<string, unknown>;
  if (
    Object.keys(record).length !== 1 ||
    !Object.hasOwn(record, "batch_size") ||
    !Number.isInteger(record.batch_size) ||
    (record.batch_size as number) < 1 ||
    (record.batch_size as number) > 100
  ) {
    return null;
  }
  return record.batch_size as number;
}

export function createPrivateAlbumCleanupHandler(
  dependencies: PrivateAlbumCleanupDependencies,
): (request: Request) => Promise<Response> {
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") {
      return countsResponse({ ...EMPTY_COUNTS }, 405, { "allow": "POST" });
    }

    const requestUrl = new URL(request.url);
    if (requestUrl.search || requestUrl.hash) {
      return countsResponse({ ...EMPTY_COUNTS }, 400);
    }

    const accessToken = extractPrivateAlbumBearer(request);
    if (!accessToken) {
      return countsResponse({ ...EMPTY_COUNTS }, 401, {
        "www-authenticate": "Bearer",
      });
    }

    const batchSize = await readBatchSize(request);
    if (batchSize === null) {
      return countsResponse({ ...EMPTY_COUNTS }, 400);
    }

    let batch: CleanupBatchResult;
    try {
      batch = await dependencies.getBatch(accessToken, batchSize);
    } catch {
      return countsResponse({ ...EMPTY_COUNTS }, 503);
    }
    if (batch.kind === "unauthenticated") {
      return countsResponse({ ...EMPTY_COUNTS }, 401, {
        "www-authenticate": "Bearer",
      });
    }
    if (batch.kind === "forbidden") {
      return countsResponse({ ...EMPTY_COUNTS }, 403);
    }
    if (batch.kind === "error") {
      return countsResponse({ ...EMPTY_COUNTS }, 503);
    }

    if (
      batch.objectPaths.length > batchSize ||
      new Set(batch.objectPaths).size !== batch.objectPaths.length
    ) {
      return countsResponse({ ...EMPTY_COUNTS }, 503);
    }

    const counts: PrivateAlbumCleanupCounts = {
      processed: batch.objectPaths.length,
      deleted: 0,
      failed: 0,
    };

    for (const objectPath of batch.objectPaths) {
      if (!isPrivateAlbumCleanupObjectPath(objectPath)) {
        counts.failed += 1;
        continue;
      }

      let deletion: CleanupDeleteResult;
      try {
        deletion = await dependencies.deleteObject(objectPath);
      } catch {
        counts.failed += 1;
        continue;
      }
      if (deletion.kind !== "ok") {
        counts.failed += 1;
        continue;
      }

      let confirmation: CleanupConfirmationResult;
      try {
        confirmation = await dependencies.confirmDeleted(objectPath);
      } catch {
        counts.failed += 1;
        continue;
      }
      if (confirmation.kind === "confirmed") {
        counts.deleted += 1;
      } else {
        counts.failed += 1;
      }
    }

    return countsResponse(counts, 200);
  };
}
