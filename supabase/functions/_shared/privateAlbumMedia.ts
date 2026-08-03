import {
  PRIVATE_ALBUM_MAX_IMAGE_BYTES,
  PRIVATE_ALBUM_MIN_IMAGE_BYTES,
  validatePrivateAlbumImage,
} from "./privateAlbumImage.ts";

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

export type AlbumIdentityVerification =
  | "verified"
  | "unauthenticated"
  | "error";

export type PrivateAlbumCallerAuthorizationDependencies = {
  verifyIdentity: (
    accessToken: string,
  ) => Promise<AlbumIdentityVerification>;
  authorizeItem: (
    accessToken: string,
    itemId: string,
  ) => Promise<AlbumAuthorizationResult>;
};

export type PrivateAlbumSupabaseConfig = {
  url: string;
  publishableKey: string;
  secretKey: string;
};

export type PrivateAlbumFetch = (
  input: Request | string | URL,
  init?: RequestInit,
) => Promise<Response>;

export type PrivateAlbumSupabaseClientOptions = {
  auth: {
    persistSession: false;
    autoRefreshToken: false;
  };
  global?: {
    headers?: Record<string, string>;
    fetch?: PrivateAlbumFetch;
  };
};

export type PrivateAlbumSupabaseClient = {
  auth: {
    getUser: (accessToken: string) => Promise<{
      data: { user?: unknown } | null;
      error: unknown;
    }>;
  };
  rpc: (
    functionName: string,
    parameters: Record<string, unknown>,
  ) => Promise<{ data: unknown; error: unknown }>;
  storage: {
    from: (bucket: string) => {
      download: (
        objectPath: string,
      ) => Promise<{ data: Blob | null; error: unknown }>;
    };
  };
};

export type PrivateAlbumSupabaseClientFactory = (
  url: string,
  key: string,
  options: PrivateAlbumSupabaseClientOptions,
) => PrivateAlbumSupabaseClient;

export type PrivateAlbumSupabaseAdapter = {
  authorize: (
    accessToken: string,
    itemId: string,
  ) => Promise<AlbumAuthorizationResult>;
  download: (objectPath: string) => Promise<AlbumDownloadResult>;
};

export type PrivateAlbumMediaDependencies = {
  authorize: (
    accessToken: string,
    itemId: string,
  ) => Promise<AlbumAuthorizationResult>;
  download: (objectPath: string) => Promise<AlbumDownloadResult>;
  allowedOrigins?: ReadonlySet<string>;
};

export async function authorizePrivateAlbumCaller(
  accessToken: string,
  itemId: string,
  dependencies: PrivateAlbumCallerAuthorizationDependencies,
): Promise<AlbumAuthorizationResult> {
  const identity = await dependencies.verifyIdentity(accessToken);
  if (identity === "unauthenticated") return { kind: "unauthenticated" };
  if (identity === "error") return { kind: "error" };

  // The exact verified user token, rather than the privileged credential used
  // for identity verification, must reach the RPC so auth.uid() and its
  // authorization checks remain authoritative.
  return await dependencies.authorizeItem(accessToken, itemId);
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const JWT_PATTERN = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const INVALID_IDENTITY_CODES = new Set([
  "bad_jwt",
  "invalid_credentials",
  "invalid_jwt",
  "jwt_expired",
  "session_expired",
  "session_not_found",
  "user_banned",
  "user_not_found",
]);
const INVALID_IDENTITY_ERROR_NAMES = new Set([
  "authinvalidtokenresponseerror",
  "authsessionmissingerror",
]);

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

type AuthorizationRow = {
  object_path?: unknown;
  mime_type?: unknown;
};

function objectValue(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object"
    ? value as Record<string, unknown>
    : null;
}

function stringField(value: unknown, field: string): string | null {
  const record = objectValue(value);
  const candidate = record?.[field];
  return typeof candidate === "string" ? candidate : null;
}

function numericField(value: unknown, field: string): number | null {
  const record = objectValue(value);
  const candidate = record?.[field];
  if (typeof candidate === "number" && Number.isFinite(candidate)) {
    return candidate;
  }
  if (typeof candidate === "string" && /^\d{3}$/.test(candidate)) {
    return Number(candidate);
  }
  return null;
}

function isJwtKey(value: string): boolean {
  return JWT_PATTERN.test(value);
}

function isSafeKey(value: string): boolean {
  return value.length > 0 && value.length <= 4096 && !/\s/.test(value);
}

function isPublishableKey(value: string): boolean {
  return isSafeKey(value) &&
    (value.startsWith("sb_publishable_") || isJwtKey(value));
}

function isSecretKey(value: string): boolean {
  return isSafeKey(value) &&
    (value.startsWith("sb_secret_") || isJwtKey(value));
}

function namedDefaultKey(
  rawValue: string,
  validator: (value: string) => boolean,
): string | null {
  try {
    const parsed = JSON.parse(rawValue);
    const record = objectValue(parsed);
    if (!record || Array.isArray(parsed)) return null;
    const value = record.default;
    return typeof value === "string" && validator(value) ? value : null;
  } catch {
    return null;
  }
}

function resolveKey(
  readEnv: (name: string) => string | undefined,
  pluralName: string,
  singularName: string,
  legacyName: string,
  validator: (value: string) => boolean,
): string | null {
  const plural = readEnv(pluralName)?.trim();
  if (plural) return namedDefaultKey(plural, validator);

  const singular = readEnv(singularName)?.trim();
  if (singular) return validator(singular) ? singular : null;

  const legacy = readEnv(legacyName)?.trim();
  if (legacy) return validator(legacy) ? legacy : null;
  return null;
}

function normalizeSupabaseUrl(value: string | undefined): string | null {
  if (!value) return null;
  try {
    const parsed = new URL(value.trim());
    if (
      (parsed.protocol !== "https:" && parsed.protocol !== "http:") ||
      parsed.username || parsed.password || parsed.pathname !== "/" ||
      parsed.search || parsed.hash
    ) {
      return null;
    }
    return parsed.origin;
  } catch {
    return null;
  }
}

export function resolvePrivateAlbumSupabaseConfig(
  readEnv: (name: string) => string | undefined,
): PrivateAlbumSupabaseConfig | null {
  const url = normalizeSupabaseUrl(readEnv("SUPABASE_URL"));
  const publishableKey = resolveKey(
    readEnv,
    "SUPABASE_PUBLISHABLE_KEYS",
    "SUPABASE_PUBLISHABLE_KEY",
    "SUPABASE_ANON_KEY",
    isPublishableKey,
  );
  const secretKey = resolveKey(
    readEnv,
    "SUPABASE_SECRET_KEYS",
    "SUPABASE_SECRET_KEY",
    "SUPABASE_SERVICE_ROLE_KEY",
    isSecretKey,
  );
  return url && publishableKey && secretKey
    ? { url, publishableKey, secretKey }
    : null;
}

export function classifyPrivateAlbumIdentityResult(
  user: unknown,
  error: unknown,
): AlbumIdentityVerification {
  if (!error) {
    return objectValue(user) ? "verified" : "error";
  }

  const status = numericField(error, "status");
  const code = (
    stringField(error, "code") ?? stringField(error, "error_code") ?? ""
  ).toLowerCase();
  const name = (stringField(error, "name") ?? "").toLowerCase();
  if (
    status === 401 || INVALID_IDENTITY_CODES.has(code) ||
    INVALID_IDENTITY_ERROR_NAMES.has(name)
  ) {
    return "unauthenticated";
  }
  return "error";
}

export function createPrivateAlbumServiceFetch(
  secretKey: string,
  fetchImplementation: PrivateAlbumFetch,
): PrivateAlbumFetch {
  return (input, init) => {
    const headers = new Headers(input instanceof Request ? input.headers : {});
    const suppliedHeaders = new Headers(init?.headers);
    suppliedHeaders.forEach((value, name) => headers.set(name, value));
    headers.set("apikey", secretKey);

    const authorization = headers.get("authorization") ?? "";
    if (/^Bearer sb_secret_[^\s]+$/.test(authorization)) {
      headers.delete("authorization");
    } else if (!authorization && isJwtKey(secretKey)) {
      // Legacy service_role keys are JWTs and still require both headers.
      headers.set("authorization", `Bearer ${secretKey}`);
    }

    return fetchImplementation(input, { ...init, headers });
  };
}

function oneAuthorizationRow(value: unknown): AuthorizationRow | null {
  if (Array.isArray(value)) {
    if (value.length !== 1) return null;
    return oneAuthorizationRow(value[0]);
  }
  return objectValue(value) as AuthorizationRow | null;
}

function mapAuthorizationError(error: unknown): AlbumAuthorizationResult {
  const marker = (
    stringField(error, "message") ?? stringField(error, "code") ?? ""
  ).toUpperCase();
  if (marker.includes("AUTH_REQUIRED")) return { kind: "unauthenticated" };
  if (
    marker.includes("PRIVATE_ALBUM_FORBIDDEN") ||
    marker.includes("ALBUM_ACCESS_DENIED")
  ) {
    return { kind: "forbidden" };
  }
  if (
    marker.includes("PRIVATE_ALBUM_ITEM_NOT_FOUND") ||
    marker.includes("ALBUM_ITEM_NOT_FOUND")
  ) {
    return { kind: "not_found" };
  }
  return { kind: "error" };
}

function storageErrorStatus(error: unknown): number | null {
  return numericField(error, "statusCode") ?? numericField(error, "status");
}

export function createPrivateAlbumSupabaseAdapter(
  config: PrivateAlbumSupabaseConfig,
  createClient: PrivateAlbumSupabaseClientFactory,
  fetchImplementation: PrivateAlbumFetch = fetch,
): PrivateAlbumSupabaseAdapter {
  const service = createClient(config.url, config.secretKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: {
      fetch: createPrivateAlbumServiceFetch(
        config.secretKey,
        fetchImplementation,
      ),
    },
  });

  return {
    authorize: async (accessToken, itemId) => {
      try {
        return await authorizePrivateAlbumCaller(accessToken, itemId, {
          verifyIdentity: async (token) => {
            try {
              const result = await service.auth.getUser(token);
              return classifyPrivateAlbumIdentityResult(
                result.data?.user,
                result.error,
              );
            } catch {
              return "error";
            }
          },
          authorizeItem: async (token, authorizedItemId) => {
            try {
              const caller = createClient(config.url, config.publishableKey, {
                auth: { persistSession: false, autoRefreshToken: false },
                global: {
                  headers: { "authorization": `Bearer ${token}` },
                },
              });
              const { data, error } = await caller.rpc(
                "authorize_private_album_item",
                { album_item_id: authorizedItemId },
              );
              if (error) return mapAuthorizationError(error);

              const row = oneAuthorizationRow(data);
              if (!row) return { kind: "not_found" };
              if (
                typeof row.object_path !== "string" ||
                typeof row.mime_type !== "string"
              ) {
                return { kind: "error" };
              }
              return {
                kind: "authorized",
                objectPath: row.object_path,
                mimeType: row.mime_type,
              };
            } catch {
              return { kind: "error" };
            }
          },
        });
      } catch {
        return { kind: "error" };
      }
    },
    download: async (objectPath) => {
      try {
        const { data, error } = await service.storage.from("private-albums")
          .download(objectPath);
        if (error || !data) {
          return storageErrorStatus(error) === 404
            ? { kind: "not_found" }
            : { kind: "error" };
        }
        if (
          data.size < PRIVATE_ALBUM_MIN_IMAGE_BYTES ||
          data.size > PRIVATE_ALBUM_MAX_IMAGE_BYTES
        ) {
          return { kind: "invalid" };
        }
        return {
          kind: "ok",
          bytes: new Uint8Array(await data.arrayBuffer()),
          mimeType: data.type,
        };
      } catch {
        return { kind: "error" };
      }
    },
  };
}
