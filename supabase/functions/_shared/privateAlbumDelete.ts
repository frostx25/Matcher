import { extractPrivateAlbumBearer } from "./privateAlbumMedia.ts";

export type PrivateAlbumDeleteAction =
  | { kind: "item"; itemId: string }
  | { kind: "album" };

export type DeleteAuthorizationResult<T> =
  | { kind: "authorized"; value: T }
  | { kind: "unauthenticated" }
  | { kind: "forbidden" }
  | { kind: "not_found" }
  | { kind: "error" };

export type DeleteObjectResult = { kind: "ok" } | { kind: "error" };

export type AlbumFinalizationResult =
  | { kind: "finalized" }
  | { kind: "pending" }
  | { kind: "unauthenticated" }
  | { kind: "forbidden" }
  | { kind: "error" };

export type PrivateAlbumDeleteDependencies = {
  markItem: (
    accessToken: string,
    itemId: string,
  ) => Promise<DeleteAuthorizationResult<string>>;
  beginAlbum: (
    accessToken: string,
  ) => Promise<DeleteAuthorizationResult<string[]>>;
  removeObject: (objectPath: string) => Promise<DeleteObjectResult>;
  finalizeAlbum: (
    accessToken: string,
  ) => Promise<AlbumFinalizationResult>;
};

export type ParsedPrivateAlbumObjectPath = {
  ownerId: string;
  albumId: string;
  itemId: string;
  mimeType: "image/jpeg" | "image/png" | "image/webp";
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PATH_PATTERN =
  /^([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.(jpg|jpeg|png|webp)$/;
const MAX_REQUEST_BODY_LENGTH = 256;
const MAX_PRIVATE_ALBUM_ITEMS = 10;
const RESPONSE_HEADERS: Readonly<Record<string, string>> = {
  "cache-control": "private, no-store, max-age=0",
  "pragma": "no-cache",
  "x-content-type-options": "nosniff",
};

export function parsePrivateAlbumDeleteObjectPath(
  value: string,
): ParsedPrivateAlbumObjectPath | null {
  const match = PATH_PATTERN.exec(value);
  if (!match) return null;
  const mimeType = match[4] === "png"
    ? "image/png"
    : match[4] === "webp"
    ? "image/webp"
    : "image/jpeg";
  return {
    ownerId: match[1],
    albumId: match[2],
    itemId: match[3],
    mimeType,
  };
}

function deletedResponse(deleted: boolean, status: number): Response {
  const headers = new Headers(RESPONSE_HEADERS);
  headers.set("content-type", "application/json; charset=utf-8");
  return new Response(JSON.stringify({ deleted }), { status, headers });
}

async function readDeleteAction(
  request: Request,
): Promise<PrivateAlbumDeleteAction | null> {
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
  if (Object.keys(record).length !== 1) return null;
  if (
    typeof record.item_id === "string" && UUID_PATTERN.test(record.item_id)
  ) {
    return { kind: "item", itemId: record.item_id.toLowerCase() };
  }
  if (record.delete_album === true) return { kind: "album" };
  return null;
}

function authorizationFailure<T>(
  result: DeleteAuthorizationResult<T>,
): Response | null {
  if (result.kind === "unauthenticated") return deletedResponse(false, 401);
  if (result.kind === "forbidden") return deletedResponse(false, 403);
  if (result.kind === "error") return deletedResponse(false, 503);
  return null;
}

export function createPrivateAlbumDeleteHandler(
  dependencies: PrivateAlbumDeleteDependencies,
): (request: Request) => Promise<Response> {
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") {
      const response = deletedResponse(false, 405);
      response.headers.set("allow", "POST");
      return response;
    }

    const requestUrl = new URL(request.url);
    if (requestUrl.search || requestUrl.hash) {
      return deletedResponse(false, 400);
    }

    const accessToken = extractPrivateAlbumBearer(request);
    if (!accessToken) {
      const response = deletedResponse(false, 401);
      response.headers.set("www-authenticate", "Bearer");
      return response;
    }

    const action = await readDeleteAction(request);
    if (!action) return deletedResponse(false, 400);

    if (action.kind === "item") {
      let marked: DeleteAuthorizationResult<string>;
      try {
        marked = await dependencies.markItem(accessToken, action.itemId);
      } catch {
        return deletedResponse(false, 503);
      }
      if (marked.kind !== "authorized") {
        if (marked.kind === "not_found") {
          return deletedResponse(true, 200);
        }
        return authorizationFailure(marked) ?? deletedResponse(false, 503);
      }

      const parsed = parsePrivateAlbumDeleteObjectPath(marked.value);
      if (!parsed || parsed.itemId !== action.itemId) {
        return deletedResponse(false, 503);
      }

      try {
        const removal = await dependencies.removeObject(marked.value);
        return removal.kind === "ok"
          ? deletedResponse(true, 200)
          : deletedResponse(false, 503);
      } catch {
        return deletedResponse(false, 503);
      }
    }

    let begun: DeleteAuthorizationResult<string[]>;
    try {
      begun = await dependencies.beginAlbum(accessToken);
    } catch {
      return deletedResponse(false, 503);
    }
    if (begun.kind !== "authorized") {
      if (begun.kind === "not_found") {
        return deletedResponse(true, 200);
      }
      return authorizationFailure(begun) ?? deletedResponse(false, 503);
    }

    const parsedPaths = begun.value.map(parsePrivateAlbumDeleteObjectPath);
    const firstPath = parsedPaths[0];
    if (
      begun.value.length > MAX_PRIVATE_ALBUM_ITEMS ||
      new Set(begun.value).size !== begun.value.length ||
      parsedPaths.some((path) => path === null) ||
      (firstPath !== undefined && firstPath !== null &&
        parsedPaths.some((path) =>
          path === null || path.ownerId !== firstPath.ownerId ||
          path.albumId !== firstPath.albumId
        ))
    ) {
      return deletedResponse(false, 503);
    }

    let removalFailed = false;
    for (const path of begun.value) {
      try {
        const removal = await dependencies.removeObject(path);
        if (removal.kind !== "ok") removalFailed = true;
      } catch {
        removalFailed = true;
      }
    }
    if (removalFailed) return deletedResponse(false, 503);

    let finalized: AlbumFinalizationResult;
    try {
      finalized = await dependencies.finalizeAlbum(accessToken);
    } catch {
      return deletedResponse(false, 503);
    }
    if (finalized.kind === "unauthenticated") {
      return deletedResponse(false, 401);
    }
    if (finalized.kind === "forbidden") {
      return deletedResponse(false, 403);
    }
    return finalized.kind === "finalized"
      ? deletedResponse(true, 200)
      : deletedResponse(false, 503);
  };
}
