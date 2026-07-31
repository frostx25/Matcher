import {
  ageAttemptReference,
  ageSubjectReference,
  canonicalDiditWebhookPayload,
  diditApiUrl,
  diditWebhookSignature,
  HttpInputError,
  isOpenProviderState,
  isPassingResult,
  isProviderSessionId,
  isTrustedDiditHostedUrl,
  normalizeProviderState,
  providerResultBelongsToAttempt,
  providerSessionCreationBelongsToAttempt,
  readJsonRequest,
  readJsonRequestWithRaw,
  verifyDiditWebhookSignature,
} from "./ageVerification.ts";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

async function hmacHex(secret: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value)),
  );
  return [...signature].map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

const expectedSession = {
  sessionId: "11111111-1111-4111-8111-111111111111",
  vendorData: "22222222-2222-4222-8222-222222222222",
  workflowId: "33333333-3333-4333-8333-333333333333",
  workflowVersion: 4,
  environment: "live" as const,
};

const approvedDecision = {
  session_id: expectedSession.sessionId,
  session_kind: "user",
  status: "Approved",
  environment: "live",
  workflow_id: expectedSession.workflowId,
  vendor_data: expectedSession.vendorData,
  features: ["ID_VERIFICATION", "LIVENESS", "FACE_MATCH"],
  id_verifications: [{ status: "Approved", age: 99 }],
  liveness_checks: [{ status: "Approved", method: "PASSIVE", score: 98.5 }],
  face_matches: [{ status: "Approved", score: 97.2 }],
};

Deno.test("Didit passes only when all three required workflow features approve", () => {
  assert(isPassingResult(approvedDecision), "complete 18+ workflow must pass");
  assert(
    !isPassingResult({
      ...approvedDecision,
      features: ["ID_VERIFICATION", "LIVENESS"],
    }),
    "missing face match must fail closed",
  );
  assert(
    !isPassingResult({
      ...approvedDecision,
      face_matches: [{ status: "Declined" }],
    }),
    "a declined required node must fail closed",
  );
  assert(
    !isPassingResult({ ...approvedDecision, liveness_checks: [] }),
    "empty required result arrays must fail closed",
  );
  assert(
    !isPassingResult({
      ...approvedDecision,
      liveness_checks: [{ status: "Approved", score: 98.5 }],
    }),
    "missing liveness method must fail closed",
  );
  assert(
    !isPassingResult({
      ...approvedDecision,
      liveness_checks: [{
        status: "Approved",
        method: "ACTIVE_3D",
        score: 98.5,
      }],
    }),
    "non-passive liveness must fail closed",
  );
  assert(
    !isPassingResult({
      ...approvedDecision,
      liveness_checks: [{ status: "Approved", method: "passive", score: 98.5 }],
    }),
    "liveness method must match the provider contract exactly",
  );
  assert(
    !isPassingResult({ ...approvedDecision, status: "In Review" }),
    "manual review must not grant the verified badge",
  );
});

Deno.test("decision binding rejects another session, user, workflow or environment", () => {
  assert(
    providerResultBelongsToAttempt(approvedDecision, expectedSession),
    "matching provider decision expected",
  );
  assert(
    !providerResultBelongsToAttempt(
      { ...approvedDecision, vendor_data: "other" },
      expectedSession,
    ),
    "vendor binding mismatch",
  );
  assert(
    !providerResultBelongsToAttempt(
      { ...approvedDecision, workflow_id: "other" },
      expectedSession,
    ),
    "workflow mismatch",
  );
  assert(
    !providerResultBelongsToAttempt(
      { ...approvedDecision, environment: "sandbox" },
      expectedSession,
    ),
    "environment mismatch",
  );
  assert(
    !providerResultBelongsToAttempt(
      { ...approvedDecision, session_kind: "business" },
      expectedSession,
    ),
    "KYB session cannot satisfy user age verification",
  );
  const { session_kind: _discarded, ...withoutKind } = approvedDecision;
  assert(
    !providerResultBelongsToAttempt(withoutKind, expectedSession),
    "missing KYC discriminator must fail closed",
  );
});

Deno.test("session creation requires the pinned workflow version and trusted URL", () => {
  const creation = {
    ...approvedDecision,
    status: "Not Started",
    workflow_version: 4,
    callback:
      "https://example.supabase.co/functions/v1/age-verification-return",
    url: "https://verify.didit.me/pt-BR/session/3FaJ9wLqX2Mz",
  };
  assert(
    providerSessionCreationBelongsToAttempt(
      creation,
      expectedSession,
      creation.callback,
    ),
    "valid pinned creation response expected",
  );
  assert(
    !providerSessionCreationBelongsToAttempt(
      { ...creation, workflow_version: 5 },
      expectedSession,
      creation.callback,
    ),
    "workflow version drift must fail closed",
  );
  assert(
    !providerSessionCreationBelongsToAttempt(
      { ...creation, session_kind: "business" },
      expectedSession,
      creation.callback,
    ),
    "KYB creation response must fail closed",
  );
  const { session_kind: _discarded, ...creationWithoutKind } = creation;
  assert(
    providerSessionCreationBelongsToAttempt(
      creationWithoutKind,
      expectedSession,
      creation.callback,
    ),
    "live create responses may omit the discriminator; the decision still requires it",
  );
  assert(
    !providerSessionCreationBelongsToAttempt(
      {
        ...creation,
        url: "https://verify.didit.me.evil.test/session/token123456",
      },
      expectedSession,
      creation.callback,
    ),
    "lookalike host must be rejected",
  );
});

Deno.test("Didit lifecycle statuses map to the database contract", () => {
  assert(normalizeProviderState("Not Started") === "PENDING", "pending");
  assert(normalizeProviderState("In Progress") === "PROCESSING", "processing");
  assert(normalizeProviderState("In Review") === "PROCESSING", "review");
  assert(normalizeProviderState("Approved") === "COMPLETE", "complete");
  assert(normalizeProviderState("Declined") === "FAIL", "declined");
  assert(normalizeProviderState("Expired") === "EXPIRED", "expired");
  assert(normalizeProviderState("Kyc Expired") === "EXPIRED", "KYC expired");
  assert(normalizeProviderState("Abandoned") === "CANCELLED", "abandoned");
  assert(normalizeProviderState("APPROVED") === "ERROR", "case must be exact");
  assert(normalizeProviderState("new-state") === "ERROR", "unknown state");
  assert(isOpenProviderState("Not Started"), "not-started session is open");
  assert(isOpenProviderState("In Progress"), "in-progress session is open");
  assert(isOpenProviderState("Resubmitted"), "resubmitted session is open");
  assert(!isOpenProviderState("In Review"), "manual review is not user-open");
});

Deno.test("Didit canonical JSON sorts nested keys and preserves unicode and arrays", () => {
  const canonical = canonicalDiditWebhookPayload({
    z: { b: 2, a: "José" },
    array: [{ d: 4, c: 3 }, 1],
    a: true,
  });
  assert(
    canonical ===
      '{"a":true,"array":[{"c":3,"d":4},1],"z":{"a":"José","b":2}}',
    `unexpected canonical JSON: ${canonical}`,
  );
});

Deno.test("V2 webhook HMAC accepts valid payload and rejects tampering or replay", async () => {
  const now = 1_785_500_000;
  const secret = "destination-secret";
  const payload = {
    timestamp: now,
    session_id: expectedSession.sessionId,
    status: "Approved",
    webhook_type: "status.updated",
    person: { last_name: "Española", first_name: "Carmen" },
  };
  const raw = new TextEncoder().encode(JSON.stringify(payload));
  const headers = new Headers({
    "x-timestamp": String(now),
    "x-signature-v2": await diditWebhookSignature(payload, secret),
  });
  assert(
    await verifyDiditWebhookSignature(payload, raw, headers, secret, now),
    "valid V2 signature expected",
  );
  assert(
    !await verifyDiditWebhookSignature(
      { ...payload, status: "Declined" },
      raw,
      headers,
      secret,
      now,
    ),
    "modified signed field must fail",
  );
  assert(
    !await verifyDiditWebhookSignature(
      payload,
      raw,
      headers,
      secret,
      now + 301,
    ),
    "stale webhook must fail",
  );
  const mismatchedTimestampHeaders = new Headers(headers);
  mismatchedTimestampHeaders.set("x-timestamp", String(now + 1));
  assert(
    !await verifyDiditWebhookSignature(
      payload,
      raw,
      mismatchedTimestampHeaders,
      secret,
      now + 1,
    ),
    "body and header timestamps must match",
  );
});

Deno.test("raw and simple HMAC fallbacks are accepted for server-side refetch", async () => {
  const now = 1_785_500_000;
  const secret = "destination-secret";
  const payload = {
    webhook_type: "status.updated",
    status: "Approved",
    session_id: expectedSession.sessionId,
    timestamp: now,
  };
  const rawText = JSON.stringify(payload);
  const raw = new TextEncoder().encode(rawText);
  const rawHeaders = new Headers({
    "x-timestamp": String(now),
    "x-signature-v2": "0".repeat(64),
    "x-signature": await hmacHex(secret, rawText),
  });
  assert(
    await verifyDiditWebhookSignature(payload, raw, rawHeaders, secret, now),
    "valid raw-body signature expected",
  );

  const simple = [
    payload.timestamp,
    payload.session_id,
    payload.status,
    payload.webhook_type,
  ].join(":");
  const simpleHeaders = new Headers({
    "x-timestamp": String(now),
    "x-signature-simple": await hmacHex(secret, simple),
  });
  assert(
    await verifyDiditWebhookSignature(payload, raw, simpleHeaders, secret, now),
    "simple envelope signature is safe because the handler refetches",
  );
});

Deno.test("request JSON reader preserves raw bytes and enforces limits", async () => {
  const validText = '{"name":"José"}';
  const valid = new Request("https://example.invalid", {
    method: "POST",
    headers: { "content-type": "application/json; charset=utf-8" },
    body: validText,
  });
  const parsed = await readJsonRequestWithRaw(valid, 64);
  assert(parsed.value.name === "José", "valid object expected");
  assert(
    new TextDecoder().decode(parsed.rawBody) === validText,
    "raw webhook bytes must be retained",
  );

  const invalid = new Request("https://example.invalid", {
    method: "POST",
    headers: { "content-type": "text/plain" },
    body: "{}",
  });
  let contentTypeError: unknown;
  try {
    await readJsonRequest(invalid, 16);
  } catch (error) {
    contentTypeError = error;
  }
  assert(
    contentTypeError instanceof HttpInputError &&
      contentTypeError.status === 415,
    "non-JSON content type must be rejected",
  );

  const oversized = new Request("https://example.invalid", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ value: "too large" }),
  });
  let sizeError: unknown;
  try {
    await readJsonRequest(oversized, 8);
  } catch (error) {
    sizeError = error;
  }
  assert(
    sizeError instanceof HttpInputError && sizeError.status === 413,
    "oversized body must be rejected",
  );
});

Deno.test("attempt reference is opaque, stable per minute and changes next minute", async () => {
  const now = 1_785_500_000_000;
  const first = await ageAttemptReference("user-id", "age-v2", now);
  const repeated = await ageAttemptReference("user-id", "age-v2", now + 5_000);
  const nextMinute = await ageAttemptReference(
    "user-id",
    "age-v2",
    now + 60_000,
  );
  assert(first === repeated, "same minute must be idempotent");
  assert(first !== nextMinute, "next minute must allow a new reference");
  assert(isProviderSessionId(first), "opaque reference must be UUID-shaped");
  assert(!first.includes("user-id"), "reference must not expose user id");
});

Deno.test("subject reference is stable, opaque and isolated from attempts", async () => {
  const first = await ageSubjectReference("user-id");
  const repeated = await ageSubjectReference("user-id");
  const anotherUser = await ageSubjectReference("another-user-id");
  const attempt = await ageAttemptReference(
    "user-id",
    "age-v2",
    1_785_500_000_000,
  );

  assert(first === repeated, "same user must keep a stable provider subject");
  assert(first !== anotherUser, "different users must have isolated subjects");
  assert(first !== attempt, "subject and attempt domains must not collide");
  assert(isProviderSessionId(first), "subject must be UUID-shaped");
  assert(first[14] === "8", "subject must use the UUIDv8 version nibble");
  assert(!first.includes("user-id"), "subject must not expose user id");
});

Deno.test("hosted and API URLs are constrained to exact Didit origins", () => {
  assert(
    isTrustedDiditHostedUrl(
      "https://verify.didit.me/pt-BR/session/3FaJ9wLqX2Mz",
    ),
    "valid Portuguese hosted URL expected",
  );
  assert(
    isTrustedDiditHostedUrl("https://verify.didit.me/session/3FaJ9wLqX2Mz"),
    "valid hosted URL without locale expected",
  );
  assert(
    !isTrustedDiditHostedUrl(
      "https://verify.didit.me.evil.test/session/3FaJ9wLqX2Mz",
    ),
    "lookalike domain must be rejected",
  );
  assert(
    !isTrustedDiditHostedUrl(
      "https://user@verify.didit.me/session/3FaJ9wLqX2Mz",
    ),
    "userinfo must be rejected",
  );
  assert(
    diditApiUrl(
      "https://verification.didit.me",
      `/v3/session/${expectedSession.sessionId}/decision/`,
    ) ===
      `https://verification.didit.me/v3/session/${expectedSession.sessionId}/decision/`,
    "unexpected provider API URL",
  );
  let rejected = false;
  try {
    diditApiUrl("https://verification.didit.me.evil.test", "/v3/session/");
  } catch {
    rejected = true;
  }
  assert(rejected, "provider API origin must be exact");
});
