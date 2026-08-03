import { validatePrivateAlbumImage } from "./privateAlbumImage.ts";

export const PRIVATE_ALBUM_ALLOWED_MIME_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
]);

export type AlbumAuthorizationResult =
  | { kind: "authorized"; objectPath: string; mimeType: string }
  | { kind: "unauthenticated" }
  | { kind: "forbidden" }
  | { kind: "not_found" }
  | { kind: "error" };

export type AlbumDownloadResult =
  | { kind: "ok"; bytes: Uint8Array; mimeType: string }
  | { kind: "not_found" }
  | { kind: "invalid" }
  | { kind: "error" };

export type PrivateAlbumMediaDependencies = {
  authorize: (
    accessToken: string,
    itemId: string,
  ) => Promise<AlbumAuthorizationResult>;
  download: (objectPath: string) => Promise<AlbumDownloadResult>;
  allowedOrigins?: ReadonlySet<string>;
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const JWT_PATTERN = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;

const PRIVATE_RESPONSE_HEADERS: Readonly<Record<string, string>> = {
  "cache-control": "private, no-store, max-age=0",
  "pragma": "no-cache",
  "x-content-type-options": "nosniff",
};

function normalizeMimeType(value: string): string {
  return value.split(";", 1)[0].trim().toLowerCase();
}

function isSafeObjectPath(value: string): boolean {
  if (
    value.length < 1 || value.length > 512 || value.includes("\\") ||
    value.startsWith("/") || value.endsWith("/") ||
    [...value].some((character) => {
      const code = character.codePointAt(0) ?? 0;
      return code < 32 || code === 127;
    })
  ) {
    return false;
  }
  return value.split("/").every((segment) =>
    segment.length > 0 && segment !== "." && segment !== ".."
  );
}

export function extractPrivateAlbumBearer(request: Request): string | null {
  const header = request.headers.get("authorization");
  if (!header || header.length > 4103) return null;
  const match = /^Bearer ([^\s]+)$/.exec(header);
  if (!match || !JWT_PATTERN.test(match[1])) return null;
  return match[1];
}

export function parseAllowedOrigins(value: string | undefined): Set<string> {
  const result = new Set<string>();
  for (const candidate of (value ?? "").split(",")) {
    const trimmed = candidate.trim();
    if (!trimmed) continue;
    try {
      const parsed = new URL(trimmed);
      const loopback = parsed.hostname === "localhost" ||
        parsed.hostname === "127.0.0.1" || parsed.hostname === "[::1]";
      if (
        parsed.username || parsed.password || parsed.pathname !== "/" ||
        parsed.search || parsed.hash ||
        (parsed.protocol !== "https:" &&
          !(loopback && parsed.protocol === "http:"))
      ) {
        continue;
      }
      result.add(parsed.origin);
    } catch {
      // Invalid origins are ignored instead of broadening CORS.
    }
  }
  return result;
}

function corsHeaders(
  request: Request,
  allowedOrigins: ReadonlySet<string>,
): { allowed: boolean; headers: Headers } {
  const headers = new Headers({ "vary": "Origin" });
  const origin = request.headers.get("origin");
  if (!origin) return { allowed: true, headers };
  if (!allowedOrigins.has(origin)) return { allowed: false, headers };
  headers.set("access-control-allow-origin", origin);
  headers.set("access-control-allow-methods", "GET, HEAD, OPTIONS");
  headers.set(
    "access-control-allow-headers",
    "authorization, apikey, x-client-info",
  );
  headers.set("access-control-max-age", "600");
  return { allowed: true, headers };
}

function jsonError(
  code: string,
  status: number,
  cors: Headers,
  extraHeaders: Record<string, string> = {},
  omitBody = false,
): Response {
  const headers = new Headers(cors);
  headers.set("content-type", "application/json; charset=utf-8");
  for (const [name, value] of Object.entries(PRIVATE_RESPONSE_HEADERS)) {
    headers.set(name, value);
  }
  for (const [name, value] of Object.entries(extraHeaders)) {
    headers.set(name, value);
  }
  return new Response(omitBody ? null : JSON.stringify({ code }), {
    status,
    headers,
  });
}

export function createPrivateAlbumMediaHandler(
  dependencies: PrivateAlbumMediaDependencies,
): (request: Request) => Promise<Response> {
  const allowedOrigins = dependencies.allowedOrigins ?? new Set<string>();

  return async (request: Request): Promise<Response> => {
    const cors = corsHeaders(request, allowedOrigins);
    const fail = (
      code: string,
      status: number,
      extraHeaders: Record<string, string> = {},
    ) =>
      jsonError(
        code,
        status,
        cors.headers,
        extraHeaders,
        request.method === "HEAD",
      );
    if (!cors.allowed) return fail("ACCESS_DENIED", 403);

    if (request.method === "OPTIONS") {
      const requestedMethod = request.headers.get(
        "access-control-request-method",
      );
      if (requestedMethod !== "GET" && requestedMethod !== "HEAD") {
        return fail("METHOD_NOT_ALLOWED", 405, {
          "allow": "GET, HEAD, OPTIONS",
        });
      }
      return new Response(null, { status: 204, headers: cors.headers });
    }

    if (request.method !== "GET" && request.method !== "HEAD") {
      return fail("METHOD_NOT_ALLOWED", 405, {
        "allow": "GET, HEAD, OPTIONS",
      });
    }

    const requestUrl = new URL(request.url);
    if (
      requestUrl.searchParams.size !== 1 ||
      !requestUrl.searchParams.has("item_id")
    ) {
      return fail("INVALID_REQUEST", 400);
    }
    const itemId = requestUrl.searchParams.get("item_id") ?? "";
    if (!UUID_PATTERN.test(itemId)) {
      return fail("INVALID_REQUEST", 400);
    }

    const accessToken = extractPrivateAlbumBearer(request);
    if (!accessToken) {
      return fail("AUTH_REQUIRED", 401, {
        "www-authenticate": "Bearer",
      });
    }

    let authorization: AlbumAuthorizationResult;
    try {
      authorization = await dependencies.authorize(accessToken, itemId);
    } catch {
      return fail("BACKEND_UNAVAILABLE", 503);
    }
    if (authorization.kind === "unauthenticated") {
      return fail("AUTH_REQUIRED", 401, {
        "www-authenticate": "Bearer",
      });
    }
    if (authorization.kind === "forbidden") {
      return fail("ACCESS_DENIED", 403);
    }
    if (authorization.kind === "not_found") {
      return fail("NOT_FOUND", 404);
    }
    if (authorization.kind === "error") {
      return fail("BACKEND_UNAVAILABLE", 503);
    }

    const authorizedMimeType = normalizeMimeType(authorization.mimeType);
    if (
      !PRIVATE_ALBUM_ALLOWED_MIME_TYPES.has(authorizedMimeType) ||
      !isSafeObjectPath(authorization.objectPath)
    ) {
      return fail("BACKEND_UNAVAILABLE", 503);
    }

    let download: AlbumDownloadResult;
    try {
      download = await dependencies.download(authorization.objectPath);
    } catch {
      return fail("BACKEND_UNAVAILABLE", 503);
    }
    if (download.kind === "not_found") {
      return fail("NOT_FOUND", 404);
    }
    if (download.kind === "invalid") {
      return fail("UNSUPPORTED_MEDIA_TYPE", 415);
    }
    if (download.kind === "error") {
      return fail("BACKEND_UNAVAILABLE", 503);
    }

    const storedMimeType = normalizeMimeType(download.mimeType);
    if (
      !PRIVATE_ALBUM_ALLOWED_MIME_TYPES.has(storedMimeType) ||
      storedMimeType !== authorizedMimeType
    ) {
      return fail("UNSUPPORTED_MEDIA_TYPE", 415);
    }

    const image = validatePrivateAlbumImage(
      download.bytes,
      authorizedMimeType,
    );
    if (!image.valid) {
      return fail("UNSUPPORTED_MEDIA_TYPE", 415);
    }

    const headers = new Headers(cors.headers);
    for (const [name, value] of Object.entries(PRIVATE_RESPONSE_HEADERS)) {
      headers.set(name, value);
    }
    headers.set("content-type", authorizedMimeType);
    headers.set("content-length", download.bytes.byteLength.toString());

    return new Response(
      request.method === "HEAD" ? null : download.bytes.slice(),
      { status: 200, headers },
    );
  };
}
