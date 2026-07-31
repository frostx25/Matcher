import { createClient } from "npm:@supabase/supabase-js@2.49.8";
import {
  AGE_POLICY_VERSION,
  ageAttemptReference,
  ageSubjectReference,
  diditApiUrl,
  type DiditDecision,
  type ExpectedDiditSession,
  extractBearerToken,
  HttpInputError,
  isOpenProviderState,
  isPassingResult,
  isProviderSessionId,
  isTrustedDiditHostedUrl,
  jsonResponse,
  MAX_CLIENT_BODY_BYTES,
  MAX_PROVIDER_BODY_BYTES,
  MINIMUM_AGE,
  normalizeProviderState,
  parseDiditEnvironment,
  parseWorkflowVersion,
  PROVIDER_SESSION_TTL_SECONDS,
  providerResultBelongsToAttempt,
  providerSessionCreationBelongsToAttempt,
  readJsonRequest,
  readJsonResponse,
} from "../_shared/ageVerification.ts";

type Attempt = {
  id: string;
  provider_subject_reference: string;
  provider_reference: string;
  provider_session_id: string | null;
  provider_workflow_id: string;
  provider_workflow_version: number;
  status: string;
  expires_at: string | null;
  created_at: string;
  updated_at: string;
};

type Account = {
  status: string;
  birth_year: number | null;
  terms_accepted_at: string | null;
  age_verification_status: string;
};

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const diditApiKey = Deno.env.get("DIDIT_API_KEY") ?? "";
const diditWorkflowId = Deno.env.get("DIDIT_WORKFLOW_ID") ?? "";
const diditWorkflowVersion = parseWorkflowVersion(
  Deno.env.get("DIDIT_WORKFLOW_VERSION"),
);
const diditEnvironment = parseDiditEnvironment(
  Deno.env.get("DIDIT_ENVIRONMENT"),
);
const diditApiBaseUrl = Deno.env.get("DIDIT_API_BASE_URL") ??
  "https://verification.didit.me";
const policyVersion = Deno.env.get("AGE_VERIFICATION_POLICY_VERSION") ??
  AGE_POLICY_VERSION;

function asAttempt(value: unknown): Attempt | null {
  if (!value || typeof value !== "object") return null;
  const attempt = value as Partial<Attempt>;
  return typeof attempt.id === "string" &&
      typeof attempt.provider_subject_reference === "string" &&
      typeof attempt.provider_reference === "string" &&
      typeof attempt.provider_workflow_id === "string" &&
      typeof attempt.provider_workflow_version === "number" &&
      typeof attempt.status === "string" &&
      typeof attempt.created_at === "string" &&
      typeof attempt.updated_at === "string"
    ? {
      id: attempt.id,
      provider_subject_reference: attempt.provider_subject_reference,
      provider_reference: attempt.provider_reference,
      provider_session_id: typeof attempt.provider_session_id === "string"
        ? attempt.provider_session_id
        : null,
      provider_workflow_id: attempt.provider_workflow_id,
      provider_workflow_version: attempt.provider_workflow_version,
      status: attempt.status,
      expires_at: typeof attempt.expires_at === "string"
        ? attempt.expires_at
        : null,
      created_at: attempt.created_at,
      updated_at: attempt.updated_at,
    }
    : null;
}

function asAccount(value: unknown): Account | null {
  if (!value || typeof value !== "object") return null;
  const account = value as Partial<Account>;
  return typeof account.status === "string" &&
      typeof account.age_verification_status === "string"
    ? {
      status: account.status,
      birth_year: typeof account.birth_year === "number"
        ? account.birth_year
        : null,
      terms_accepted_at: typeof account.terms_accepted_at === "string"
        ? account.terms_accepted_at
        : null,
      age_verification_status: account.age_verification_status,
    }
    : null;
}

function isRecentCreation(attempt: Attempt): boolean {
  const createdAt = Date.parse(attempt.created_at);
  return Number.isFinite(createdAt) && createdAt > Date.now() - 2 * 60_000;
}

function isWithinResumeCooldown(attempt: Attempt): boolean {
  const updatedAt = Date.parse(attempt.updated_at);
  return Number.isFinite(updatedAt) && updatedAt > Date.now() - 10_000;
}

function resumeUntil(): string {
  return new Date(
    Date.now() + PROVIDER_SESSION_TTL_SECONDS * 1_000,
  ).toISOString();
}

function attemptSelection(): string {
  return "id, provider_subject_reference, provider_reference, " +
    "provider_session_id, provider_workflow_id, provider_workflow_version, " +
    "status, expires_at, created_at, updated_at";
}

function expectedSession(
  attempt: Attempt,
  sessionId: string,
  environment: "live" | "sandbox",
): ExpectedDiditSession {
  return {
    sessionId,
    vendorData: attempt.provider_subject_reference,
    workflowId: attempt.provider_workflow_id,
    workflowVersion: attempt.provider_workflow_version,
    environment,
  };
}

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return jsonResponse({ code: "METHOD_NOT_ALLOWED" }, 405);
  }

  try {
    const body = await readJsonRequest(request, MAX_CLIENT_BODY_BYTES, true);
    // Workflow, callback and provider references are always server-owned.
    if (Object.keys(body).length !== 0) {
      return jsonResponse({ code: "UNSUPPORTED_INPUT" }, 400);
    }
  } catch (error) {
    if (error instanceof HttpInputError) {
      return jsonResponse({ code: error.code }, error.status);
    }
    return jsonResponse({ code: "INVALID_REQUEST" }, 400);
  }

  if (!supabaseUrl || !serviceRoleKey) {
    return jsonResponse({ code: "BACKEND_NOT_CONFIGURED" }, 503);
  }

  const accessToken = extractBearerToken(request);
  if (!accessToken) return jsonResponse({ code: "AUTH_REQUIRED" }, 401);

  const service = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: userError } = await service.auth.getUser(
    accessToken,
  );
  if (userError || !userData.user) {
    return jsonResponse({ code: "AUTH_REQUIRED" }, 401);
  }

  if (
    !diditApiKey || !isProviderSessionId(diditWorkflowId) ||
    diditWorkflowVersion === null || diditEnvironment === null
  ) {
    return jsonResponse({ code: "AGE_PROVIDER_NOT_CONFIGURED" }, 503);
  }

  let createSessionUrl: string;
  try {
    createSessionUrl = diditApiUrl(diditApiBaseUrl, "/v3/session/");
  } catch {
    return jsonResponse({ code: "AGE_PROVIDER_NOT_CONFIGURED" }, 503);
  }

  const userId = userData.user.id;
  const { data: accountData, error: accountError } = await service.from(
    "accounts",
  )
    .select("status, birth_year, terms_accepted_at, age_verification_status")
    .eq("id", userId)
    .maybeSingle();
  if (accountError) return jsonResponse({ code: "ACCOUNT_LOOKUP_FAILED" }, 503);

  const account = asAccount(accountData);
  if (!account || account.status === "deleted") {
    return jsonResponse({ code: "ACCOUNT_DELETED" }, 410);
  }
  if (account.status === "suspended") {
    return jsonResponse({ code: "ACCOUNT_SUSPENDED" }, 403);
  }
  if (account.age_verification_status === "verified") {
    return jsonResponse({ code: "ALREADY_VERIFIED" }, 409);
  }
  if (account.age_verification_status === "manual_review") {
    return jsonResponse({ code: "AGE_REVIEW_PENDING" }, 409);
  }
  if (account.status !== "active") {
    return jsonResponse({ code: "ACCOUNT_UNAVAILABLE" }, 403);
  }

  const { data: profileData, error: profileError } = await service.from(
    "profiles",
  )
    .select("id")
    .eq("id", userId)
    .maybeSingle();
  if (profileError) return jsonResponse({ code: "ACCOUNT_LOOKUP_FAILED" }, 503);
  if (!profileData || !account.birth_year || !account.terms_accepted_at) {
    return jsonResponse({ code: "ONBOARDING_REQUIRED" }, 409);
  }

  const { data: currentData, error: currentError } = await service.from(
    "age_verification_attempts",
  )
    .select(attemptSelection())
    .eq("user_id", userId)
    .in("status", ["creating", "pending", "processing"])
    .order("created_at", { ascending: false })
    .limit(1)
    .maybeSingle();
  if (currentError) {
    return jsonResponse({ code: "AGE_SESSION_LOOKUP_FAILED" }, 503);
  }

  const currentAttempt = asAttempt(currentData);
  if (
    currentAttempt?.provider_session_id &&
    isProviderSessionId(currentAttempt.provider_session_id)
  ) {
    // Coalesce rapid resume taps before spending the provider-wide API quota.
    if (isWithinResumeCooldown(currentAttempt)) {
      return jsonResponse({ code: "AGE_SESSION_IN_PROGRESS" }, 409);
    }

    let decisionUrl: string;
    try {
      decisionUrl = diditApiUrl(
        diditApiBaseUrl,
        `/v3/session/${currentAttempt.provider_session_id}/decision/`,
      );
    } catch {
      return jsonResponse({ code: "AGE_PROVIDER_NOT_CONFIGURED" }, 503);
    }

    let decisionResponse: Response;
    try {
      decisionResponse = await fetch(decisionUrl, {
        headers: { "x-api-key": diditApiKey, "accept": "application/json" },
        signal: AbortSignal.timeout(4_000),
      });
    } catch {
      return jsonResponse({ code: "AGE_PROVIDER_UNAVAILABLE" }, 503);
    }
    if (!decisionResponse.ok) {
      if (decisionResponse.status !== 404) {
        return jsonResponse({ code: "AGE_PROVIDER_UNAVAILABLE" }, 503);
      }
      const { error } = await service.from("age_verification_attempts")
        .update({ status: "error" })
        .eq("id", currentAttempt.id)
        .in("status", ["creating", "pending", "processing"]);
      if (error) {
        return jsonResponse({ code: "AGE_SESSION_CREATE_FAILED" }, 503);
      }
    } else {
      let decisionObject: Record<string, unknown>;
      try {
        decisionObject = await readJsonResponse(
          decisionResponse,
          MAX_PROVIDER_BODY_BYTES,
        );
      } catch {
        return jsonResponse({ code: "AGE_PROVIDER_INVALID_RESPONSE" }, 502);
      }
      const decision = decisionObject as DiditDecision;
      if (
        !providerResultBelongsToAttempt(
          decision,
          expectedSession(
            currentAttempt,
            currentAttempt.provider_session_id,
            diditEnvironment,
          ),
        )
      ) {
        return jsonResponse({ code: "AGE_PROVIDER_INVALID_RESPONSE" }, 502);
      }

      if (decision.status === "In Review") {
        const { error: reviewStateError } = await service.rpc(
          "finalize_age_verification",
          {
            provider_session_id: currentAttempt.provider_session_id,
            provider_state: "PROCESSING",
            provider_method: null,
            provider_check_type: null,
          },
        );
        if (reviewStateError) {
          return jsonResponse({ code: "AGE_SESSION_CREATE_FAILED" }, 503);
        }
        return jsonResponse({ code: "AGE_REVIEW_PENDING" }, 409);
      }

      let state = normalizeProviderState(decision.status);
      const passed = state === "COMPLETE" && isPassingResult(decision);
      if (state === "COMPLETE" && !passed) state = "ERROR";

      const { data: freshAccountData, error: freshAccountError } = await service
        .from("accounts")
        .select(
          "status, birth_year, terms_accepted_at, age_verification_status",
        )
        .eq("id", userId)
        .maybeSingle();
      if (freshAccountError) {
        return jsonResponse({ code: "ACCOUNT_LOOKUP_FAILED" }, 503);
      }
      const freshAccount = asAccount(freshAccountData);
      if (!freshAccount || freshAccount.status === "deleted") {
        return jsonResponse({ code: "ACCOUNT_DELETED" }, 410);
      }
      if (freshAccount.status === "suspended") {
        return jsonResponse({ code: "ACCOUNT_SUSPENDED" }, 403);
      }
      if (freshAccount.age_verification_status === "verified") {
        return jsonResponse({ code: "ALREADY_VERIFIED" }, 409);
      }
      if (freshAccount.age_verification_status === "manual_review") {
        return jsonResponse({ code: "AGE_REVIEW_PENDING" }, 409);
      }
      if (freshAccount.status !== "active") {
        return jsonResponse({ code: "ACCOUNT_UNAVAILABLE" }, 403);
      }

      const { error: finalizeError } = await service.rpc(
        "finalize_age_verification",
        {
          provider_session_id: currentAttempt.provider_session_id,
          provider_state: state,
          provider_method: passed ? "DOCUMENT" : null,
          provider_check_type: passed ? "PASSIVE" : null,
        },
      );
      if (finalizeError) {
        return jsonResponse({ code: "AGE_SESSION_CREATE_FAILED" }, 503);
      }

      if (passed) return jsonResponse({ code: "ALREADY_VERIFIED" }, 409);
      if (isOpenProviderState(decision.status)) {
        if (!isTrustedDiditHostedUrl(decision.session_url)) {
          return jsonResponse({ code: "AGE_PROVIDER_INVALID_RESPONSE" }, 502);
        }
        const expiresAt = resumeUntil();
        await service.from("age_verification_attempts")
          .update({ expires_at: expiresAt })
          .eq("id", currentAttempt.id);
        return jsonResponse({
          verification_url: decision.session_url,
          expires_at: expiresAt,
        });
      }
      // Terminal failures are closed by the RPC and may start a fresh attempt.
    }
  } else if (
    currentAttempt?.status === "creating" && isRecentCreation(currentAttempt)
  ) {
    return jsonResponse({ code: "AGE_SESSION_IN_PROGRESS" }, 409);
  } else if (currentAttempt) {
    const { error } = await service.from("age_verification_attempts")
      .update({ status: "error" })
      .eq("id", currentAttempt.id)
      .in("status", ["creating", "pending", "processing"]);
    if (error) return jsonResponse({ code: "AGE_SESSION_CREATE_FAILED" }, 503);
  }

  const since = new Date(Date.now() - 24 * 60 * 60 * 1_000).toISOString();
  const { count, error: countError } = await service.from(
    "age_verification_attempts",
  )
    .select("id", { count: "exact", head: true })
    .eq("user_id", userId)
    .gte("created_at", since);
  if (countError) {
    return jsonResponse({ code: "AGE_SESSION_LOOKUP_FAILED" }, 503);
  }
  if ((count ?? 0) >= 5) {
    return jsonResponse({ code: "AGE_SESSION_RATE_LIMITED" }, 429);
  }

  const [providerReference, providerSubjectReference] = await Promise.all([
    ageAttemptReference(userId, policyVersion),
    ageSubjectReference(userId),
  ]);
  const { data: insertedData, error: insertError } = await service.from(
    "age_verification_attempts",
  )
    .insert({
      user_id: userId,
      provider_subject_reference: providerSubjectReference,
      provider_reference: providerReference,
      provider_workflow_id: diditWorkflowId,
      provider_workflow_version: diditWorkflowVersion,
      status: "creating",
      policy_version: policyVersion,
      minimum_age: MINIMUM_AGE,
      challenge_age: MINIMUM_AGE,
    })
    .select(attemptSelection())
    .maybeSingle();

  const attempt = asAttempt(insertedData);
  if (insertError || !attempt) {
    // Concurrent double taps share the minute-scoped provider reference. The
    // first request owns provider creation; later taps retry after it finishes.
    const { data: duplicateData, error: duplicateError } = await service.from(
      "age_verification_attempts",
    )
      .select(attemptSelection())
      .eq("user_id", userId)
      .eq("provider_reference", providerReference)
      .maybeSingle();
    if (duplicateError || !asAttempt(duplicateData)) {
      return jsonResponse({ code: "AGE_SESSION_CREATE_FAILED" }, 503);
    }
    return jsonResponse({ code: "AGE_SESSION_IN_PROGRESS" }, 409);
  }

  const functionBase = `${supabaseUrl.replace(/\/+$/, "")}/functions/v1`;
  const callback = `${functionBase}/age-verification-return`;
  let providerResponse: Response;
  try {
    providerResponse = await fetch(createSessionUrl, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "accept": "application/json",
        "x-api-key": diditApiKey,
      },
      body: JSON.stringify({
        workflow_id: diditWorkflowId,
        // Stable opaque pseudonym: no email, exact birth date, name or raw
        // Supabase user id leaves Matcher.
        vendor_data: attempt.provider_subject_reference,
        callback,
        callback_method: "both",
        language: "pt-BR",
      }),
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    await service.from("age_verification_attempts").update({ status: "error" })
      .eq("id", attempt.id);
    return jsonResponse({ code: "AGE_PROVIDER_UNAVAILABLE" }, 503);
  }

  if (!providerResponse.ok) {
    await service.from("age_verification_attempts").update({ status: "error" })
      .eq("id", attempt.id);
    return jsonResponse({ code: "AGE_PROVIDER_UNAVAILABLE" }, 503);
  }

  let providerSessionObject: Record<string, unknown>;
  try {
    providerSessionObject = await readJsonResponse(
      providerResponse,
      MAX_PROVIDER_BODY_BYTES,
    );
  } catch {
    await service.from("age_verification_attempts").update({ status: "error" })
      .eq("id", attempt.id);
    return jsonResponse({ code: "AGE_PROVIDER_INVALID_RESPONSE" }, 503);
  }

  const providerSession = providerSessionObject as DiditDecision;
  const providerSessionId = providerSession.session_id;
  if (
    !isProviderSessionId(providerSessionId) ||
    !providerSessionCreationBelongsToAttempt(
      providerSession,
      expectedSession(attempt, providerSessionId, diditEnvironment),
      callback,
    ) || !isTrustedDiditHostedUrl(providerSession.url)
  ) {
    await service.from("age_verification_attempts").update({ status: "error" })
      .eq("id", attempt.id);
    return jsonResponse({ code: "AGE_PROVIDER_INVALID_RESPONSE" }, 503);
  }

  const expiresAt = resumeUntil();
  const { data: updatedAttempt, error: updateError } = await service.from(
    "age_verification_attempts",
  )
    .update({
      provider_session_id: providerSessionId,
      expires_at: expiresAt,
      status: "pending",
    })
    .eq("id", attempt.id)
    .eq("status", "creating")
    .select("id")
    .maybeSingle();
  if (updateError || !updatedAttempt) {
    return jsonResponse({ code: "AGE_SESSION_CREATE_FAILED" }, 503);
  }

  const { data: updatedAccount, error: updateAccountError } = await service
    .from("accounts")
    .update({ age_verification_status: "pending" })
    .eq("id", userId)
    .eq("status", "active")
    .neq("age_verification_status", "manual_review")
    .select("id")
    .maybeSingle();
  if (updateAccountError || !updatedAccount) {
    await service.from("age_verification_attempts")
      .update({ status: "cancelled" })
      .eq("id", attempt.id)
      .eq("status", "pending");
    return jsonResponse({ code: "ACCOUNT_UNAVAILABLE" }, 403);
  }

  return jsonResponse({
    verification_url: providerSession.url,
    expires_at: expiresAt,
  });
});
