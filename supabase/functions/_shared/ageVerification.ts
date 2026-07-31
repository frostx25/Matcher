export const AGE_POLICY_VERSION = "age-v2-didit-document";
export const MINIMUM_AGE = 18;
// Local resume window. The provider owns the actual workflow expiration.
export const PROVIDER_SESSION_TTL_SECONDS = 60 * 60;

export const MAX_CLIENT_BODY_BYTES = 1_024;
export const MAX_NOTIFICATION_BODY_BYTES = 1024 * 1024;
export const MAX_PROVIDER_BODY_BYTES = 1024 * 1024;
export const WEBHOOK_TOLERANCE_SECONDS = 5 * 60;

export type JsonObject = Record<string, unknown>;

export type DiditFeatureResult = {
  status?: unknown;
  method?: unknown;
};

export type DiditDecision = {
  session_id?: unknown;
  session_kind?: unknown;
  session_url?: unknown;
  url?: unknown;
  status?: unknown;
  environment?: unknown;
  workflow_id?: unknown;
  workflow_version?: unknown;
  vendor_data?: unknown;
  callback?: unknown;
  features?: unknown;
  id_verifications?: unknown;
  liveness_checks?: unknown;
  face_matches?: unknown;
};

export type ExpectedDiditSession = {
  sessionId: string;
  vendorData: string;
  workflowId: string;
  workflowVersion: number;
  environment: "live" | "sandbox";
};

export class HttpInputError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
  ) {
    super(code);
    this.name = "HttpInputError";
  }
}

const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function isJsonObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function parseContentLength(headers: Headers): number | null {
  const raw = headers.get("content-length");
  if (raw === null) return null;
  if (!/^\d+$/.test(raw)) {
    throw new HttpInputError("INVALID_CONTENT_LENGTH", 400);
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value)) {
    throw new HttpInputError("INVALID_CONTENT_LENGTH", 400);
  }
  return value;
}

function requireJsonContentType(headers: Headers): void {
  const contentType = headers.get("content-type")?.split(";", 1)[0].trim()
    .toLowerCase();
  if (contentType !== "application/json") {
    throw new HttpInputError("JSON_REQUIRED", 415);
  }
}

async function readLimitedBody(
  body: ReadableStream<Uint8Array> | null,
  maxBytes: number,
): Promise<Uint8Array> {
  if (!body) return new Uint8Array();
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        throw new HttpInputError("BODY_TOO_LARGE", 413);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

function decodeJsonObject(bytes: Uint8Array, allowEmpty: boolean): JsonObject {
  if (bytes.byteLength === 0 && allowEmpty) return {};
  if (bytes.byteLength === 0) throw new HttpInputError("INVALID_JSON", 400);

  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new HttpInputError("INVALID_JSON", 400);
  }

  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new HttpInputError("INVALID_JSON", 400);
  }
  if (!isJsonObject(value)) {
    throw new HttpInputError("JSON_OBJECT_REQUIRED", 400);
  }
  return value;
}

async function readJsonBody(
  body: ReadableStream<Uint8Array> | null,
  headers: Headers,
  maxBytes: number,
  allowEmpty: boolean,
): Promise<{ value: JsonObject; bytes: Uint8Array }> {
  const declaredLength = parseContentLength(headers);
  if (declaredLength !== null && declaredLength > maxBytes) {
    throw new HttpInputError("BODY_TOO_LARGE", 413);
  }
  if (!body && allowEmpty) return { value: {}, bytes: new Uint8Array() };
  requireJsonContentType(headers);
  const bytes = await readLimitedBody(body, maxBytes);
  return { value: decodeJsonObject(bytes, allowEmpty), bytes };
}

function uuidFromBytes(bytes: Uint8Array): string {
  bytes[6] = (bytes[6] & 0x0f) | 0x80;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, "0"))
    .join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${
    hex.slice(16, 20)
  }-${hex.slice(20)}`;
}

export function isProviderSessionId(value: unknown): value is string {
  return typeof value === "string" && uuidPattern.test(value);
}

export function parseWorkflowVersion(value: unknown): number | null {
  const parsed = typeof value === "string" && /^\d+$/.test(value)
    ? Number(value)
    : value;
  return typeof parsed === "number" && Number.isSafeInteger(parsed) &&
      parsed > 0
    ? parsed
    : null;
}

export function parseDiditEnvironment(
  value: unknown,
): "live" | "sandbox" | null {
  return value === "live" || value === "sandbox" ? value : null;
}

export async function ageSubjectReference(userId: string): Promise<string> {
  // Stable pseudonym for Didit's cross-session user grouping. Keep its domain
  // separate from per-attempt references so neither identifier can collide.
  const input = new TextEncoder().encode(`matcher-age-subject-v1\0${userId}`);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", input));
  return uuidFromBytes(digest.slice(0, 16));
}

export async function ageAttemptReference(
  userId: string,
  policyVersion: string,
  nowMs = Date.now(),
): Promise<string> {
  // A minute-scoped opaque reference closes the usual double-tap race while
  // remaining distinct from the stable provider subject pseudonym.
  const minuteBucket = Math.floor(nowMs / 60_000);
  const input = new TextEncoder().encode(
    `matcher-age-attempt-v2\0${userId}\0${policyVersion}\0${minuteBucket}`,
  );
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", input));
  return uuidFromBytes(digest.slice(0, 16));
}

export function normalizeProviderState(value: unknown): string {
  switch (value) {
    case "Not Started":
      return "PENDING";
    case "In Progress":
    case "Awaiting User":
    case "Resubmitted":
    case "In Review":
      return "PROCESSING";
    case "Approved":
      return "COMPLETE";
    case "Declined":
      return "FAIL";
    case "Expired":
    case "Kyc Expired":
      return "EXPIRED";
    case "Abandoned":
      return "CANCELLED";
    default:
      return "ERROR";
  }
}

export function isOpenProviderState(value: unknown): boolean {
  return value === "Not Started" || value === "In Progress" ||
    value === "Awaiting User" || value === "Resubmitted";
}

function hasRequiredFeatures(value: unknown): boolean {
  if (!Array.isArray(value)) return false;
  const features = new Set(value.filter((item) => typeof item === "string"));
  return features.has("ID_VERIFICATION") && features.has("LIVENESS") &&
    features.has("FACE_MATCH");
}

function allApproved(value: unknown): boolean {
  return Array.isArray(value) && value.length > 0 &&
    value.every((item) => isJsonObject(item) && item.status === "Approved");
}

function allApprovedPassiveLiveness(value: unknown): boolean {
  return Array.isArray(value) && value.length > 0 &&
    value.every((item) =>
      isJsonObject(item) && item.status === "Approved" &&
      item.method === "PASSIVE"
    );
}

export function providerResultBelongsToAttempt(
  result: DiditDecision,
  expected: ExpectedDiditSession,
): boolean {
  return result.session_id === expected.sessionId &&
    result.session_kind === "user" &&
    result.vendor_data === expected.vendorData &&
    result.workflow_id === expected.workflowId &&
    (result.workflow_version === undefined ||
      parseWorkflowVersion(result.workflow_version) ===
        expected.workflowVersion) &&
    result.environment === expected.environment;
}

export function providerSessionCreationBelongsToAttempt(
  result: DiditDecision,
  expected: ExpectedDiditSession,
  callback: string,
): boolean {
  // The live v3 API currently omits environment and session_kind from the
  // create response. If a future response includes session_kind it must still
  // be KYC. The authoritative decision is always required to contain `user`
  // before the optional verified badge can be granted.
  return result.session_id === expected.sessionId &&
    (result.session_kind === undefined || result.session_kind === "user") &&
    result.vendor_data === expected.vendorData &&
    result.workflow_id === expected.workflowId &&
    parseWorkflowVersion(result.workflow_version) ===
      expected.workflowVersion &&
    result.callback === callback && isOpenProviderState(result.status) &&
    isTrustedDiditHostedUrl(result.url);
}

export function isPassingResult(result: DiditDecision): boolean {
  return normalizeProviderState(result.status) === "COMPLETE" &&
    hasRequiredFeatures(result.features) &&
    allApproved(result.id_verifications) &&
    allApprovedPassiveLiveness(result.liveness_checks) &&
    allApproved(result.face_matches);
}

function sortJsonRecursively(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortJsonRecursively);
  if (isJsonObject(value)) {
    const sorted: Record<string, unknown> = Object.create(null);
    for (const key of Object.keys(value).sort()) {
      sorted[key] = sortJsonRecursively(value[key]);
    }
    return sorted;
  }
  // JSON.parse already normalizes whole-valued floats such as 1.0 to 1.
  return value;
}

export function canonicalDiditWebhookPayload(payload: JsonObject): string {
  return JSON.stringify(sortJsonRecursively(payload));
}

async function hmacSha256Hex(
  secret: string,
  message: Uint8Array,
): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const messageBytes = new Uint8Array(message.byteLength);
  messageBytes.set(message);
  const signature = new Uint8Array(
    await crypto.subtle.sign("HMAC", key, messageBytes.buffer),
  );
  return [...signature].map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function constantTimeHexEqual(
  expected: string,
  supplied: string | null,
): boolean {
  if (!supplied || !/^[0-9a-f]{64}$/i.test(supplied)) return false;
  const normalized = supplied.toLowerCase();
  let different = expected.length ^ normalized.length;
  for (let index = 0; index < expected.length; index += 1) {
    different |= expected.charCodeAt(index) ^
      (normalized.charCodeAt(index) || 0);
  }
  return different === 0;
}

export async function diditWebhookSignature(
  payload: JsonObject,
  secret: string,
): Promise<string> {
  return await hmacSha256Hex(
    secret,
    new TextEncoder().encode(canonicalDiditWebhookPayload(payload)),
  );
}

export async function verifyDiditWebhookSignature(
  payload: JsonObject,
  rawBody: Uint8Array,
  headers: Headers,
  secret: string,
  nowSeconds = Math.floor(Date.now() / 1_000),
): Promise<boolean> {
  const timestampHeader = headers.get("x-timestamp");
  if (!timestampHeader || !/^\d{1,12}$/.test(timestampHeader)) return false;
  const timestamp = Number(timestampHeader);
  if (
    !Number.isSafeInteger(timestamp) ||
    Math.abs(nowSeconds - timestamp) > WEBHOOK_TOLERANCE_SECONDS ||
    payload.timestamp !== timestamp
  ) {
    return false;
  }
  if (!secret || secret.length > 4_096) return false;

  const signatureV2 = headers.get("x-signature-v2");
  const expectedV2 = await diditWebhookSignature(payload, secret);
  if (constantTimeHexEqual(expectedV2, signatureV2)) return true;

  const rawSignature = headers.get("x-signature");
  const expectedRaw = await hmacSha256Hex(secret, rawBody);
  if (constantTimeHexEqual(expectedRaw, rawSignature)) return true;

  // Safe here because callers use only the signed envelope as a trigger and
  // always retrieve the complete decision again with the server API key.
  const simpleSignature = headers.get("x-signature-simple");
  const simplePayload = [
    payload.timestamp ?? "",
    payload.session_id ?? "",
    payload.status ?? "",
    payload.webhook_type ?? "",
  ].join(":");
  const expectedSimple = await hmacSha256Hex(
    secret,
    new TextEncoder().encode(simplePayload),
  );
  return constantTimeHexEqual(expectedSimple, simpleSignature);
}

export async function readJsonRequest(
  request: Request,
  maxBytes: number,
  allowEmpty = false,
): Promise<JsonObject> {
  return (await readJsonBody(
    request.body,
    request.headers,
    maxBytes,
    allowEmpty,
  )).value;
}

export async function readJsonRequestWithRaw(
  request: Request,
  maxBytes: number,
): Promise<{ value: JsonObject; rawBody: Uint8Array }> {
  const result = await readJsonBody(
    request.body,
    request.headers,
    maxBytes,
    false,
  );
  return { value: result.value, rawBody: result.bytes };
}

export async function readJsonResponse(
  response: Response,
  maxBytes: number,
): Promise<JsonObject> {
  return (await readJsonBody(
    response.body,
    response.headers,
    maxBytes,
    false,
  )).value;
}

export function extractBearerToken(request: Request): string | null {
  const authorization = request.headers.get("authorization");
  if (!authorization || authorization.length > 8_200) return null;
  const match = authorization.match(/^Bearer ([A-Za-z0-9._~-]+)$/i);
  return match?.[1] ?? null;
}

export function diditApiUrl(baseUrl: string, path: string): string {
  const base = new URL(baseUrl);
  const isLoopback = base.hostname === "localhost" ||
    base.hostname === "127.0.0.1" ||
    base.hostname === "host.docker.internal";
  const isProductionOrigin = base.protocol === "https:" &&
    base.hostname === "verification.didit.me" &&
    (base.port === "" || base.port === "443");
  const isLocalTestOrigin = isLoopback && base.protocol === "http:";
  if (
    (!isProductionOrigin && !isLocalTestOrigin) || base.username ||
    base.password || base.pathname !== "/" || base.search || base.hash
  ) {
    throw new Error("invalid Didit API base URL");
  }
  return new URL(path, `${base.origin}/`).toString();
}

export function isTrustedDiditHostedUrl(value: unknown): value is string {
  if (typeof value !== "string" || value.length > 2_048) return false;
  try {
    const url = new URL(value);
    const pathPattern =
      /^\/(?:[a-z]{2}(?:-[A-Z]{2})?\/)?session\/[A-Za-z0-9_-]{12}\/?$/;
    return url.protocol === "https:" && url.hostname === "verify.didit.me" &&
      (url.port === "" || url.port === "443") && !url.username &&
      !url.password && !url.search && !url.hash &&
      pathPattern.test(url.pathname);
  } catch {
    return false;
  }
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}
