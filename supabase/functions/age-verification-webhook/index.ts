import { createClient } from "npm:@supabase/supabase-js@2.49.8";
import {
  diditApiUrl,
  type DiditDecision,
  HttpInputError,
  isPassingResult,
  isProviderSessionId,
  jsonResponse,
  MAX_NOTIFICATION_BODY_BYTES,
  MAX_PROVIDER_BODY_BYTES,
  normalizeProviderState,
  parseDiditEnvironment,
  providerResultBelongsToAttempt,
  readJsonRequestWithRaw,
  readJsonResponse,
  verifyDiditWebhookSignature,
} from "../_shared/ageVerification.ts";

type Attempt = {
  user_id: string;
  provider_subject_reference: string;
  provider_reference: string;
  provider_workflow_id: string;
  provider_workflow_version: number;
  status: string;
};

type AccountState = {
  status: string;
  age_verification_status: string;
};

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const diditApiKey = Deno.env.get("DIDIT_API_KEY") ?? "";
const diditWebhookSecret = Deno.env.get("DIDIT_WEBHOOK_SECRET") ?? "";
const diditEnvironment = parseDiditEnvironment(
  Deno.env.get("DIDIT_ENVIRONMENT"),
);
const diditApiBaseUrl = Deno.env.get("DIDIT_API_BASE_URL") ??
  "https://verification.didit.me";

function asAttempt(value: unknown): Attempt | null {
  if (!value || typeof value !== "object") return null;
  const attempt = value as Partial<Attempt>;
  return typeof attempt.user_id === "string" &&
      typeof attempt.provider_subject_reference === "string" &&
      typeof attempt.provider_reference === "string" &&
      typeof attempt.provider_workflow_id === "string" &&
      typeof attempt.provider_workflow_version === "number" &&
      typeof attempt.status === "string"
    ? attempt as Attempt
    : null;
}

function asAccountState(value: unknown): AccountState | null {
  if (!value || typeof value !== "object") return null;
  const account = value as Partial<AccountState>;
  return typeof account.status === "string" &&
      typeof account.age_verification_status === "string"
    ? account as AccountState
    : null;
}

function accountCannotBeVerified(account: AccountState | null): boolean {
  return !account || account.status === "suspended" ||
    account.status === "deleted" ||
    account.age_verification_status === "manual_review";
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return jsonResponse({ accepted: false }, 405);

  let notification: Record<string, unknown>;
  let rawBody: Uint8Array;
  try {
    const parsed = await readJsonRequestWithRaw(
      request,
      MAX_NOTIFICATION_BODY_BYTES,
    );
    notification = parsed.value;
    rawBody = parsed.rawBody;
  } catch (error) {
    if (error instanceof HttpInputError) {
      return jsonResponse({ accepted: false }, error.status);
    }
    return jsonResponse({ accepted: false }, 400);
  }

  if (
    !supabaseUrl || !serviceRoleKey || !diditApiKey || !diditWebhookSecret ||
    diditEnvironment === null
  ) {
    return jsonResponse({ accepted: false }, 503);
  }

  if (
    !await verifyDiditWebhookSignature(
      notification,
      rawBody,
      request.headers,
      diditWebhookSecret,
    )
  ) {
    return jsonResponse({ accepted: false }, 401);
  }

  // Console test payloads contain sample identities and must never mutate data.
  if (request.headers.get("x-didit-test-webhook") === "true") {
    return jsonResponse({ accepted: true }, 202);
  }

  const eventType = notification.webhook_type;
  if (eventType !== "status.updated" && eventType !== "data.updated") {
    return jsonResponse({ accepted: true }, 202);
  }

  const sessionId = notification.session_id;
  if (!isProviderSessionId(sessionId)) {
    return jsonResponse({ accepted: false }, 400);
  }

  let decisionUrl: string;
  try {
    decisionUrl = diditApiUrl(
      diditApiBaseUrl,
      `/v3/session/${sessionId}/decision/`,
    );
  } catch {
    return jsonResponse({ accepted: false }, 503);
  }

  const service = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: attemptData, error: attemptError } = await service.from(
    "age_verification_attempts",
  )
    .select(
      "user_id, provider_subject_reference, provider_reference, provider_workflow_id, " +
        "provider_workflow_version, status",
    )
    .eq("provider_session_id", sessionId)
    .maybeSingle();
  if (attemptError) return jsonResponse({ accepted: false }, 503);

  const attempt = asAttempt(attemptData);
  // An authenticated event for an unknown/deleted local session has no work.
  if (!attempt) return jsonResponse({ accepted: true }, 202);

  const { data: accountData, error: accountError } = await service.from(
    "accounts",
  )
    .select("status, age_verification_status")
    .eq("id", attempt.user_id)
    .maybeSingle();
  if (accountError) return jsonResponse({ accepted: false }, 503);
  const account = asAccountState(accountData);

  if (accountCannotBeVerified(account)) {
    return jsonResponse({ accepted: true });
  }
  if (
    attempt.status === "verified" ||
    account?.age_verification_status === "verified"
  ) {
    return jsonResponse({ accepted: true });
  }

  // Webhook contents are only a signed trigger. The complete decision is
  // retrieved again with the API key and is never logged or persisted.
  let providerResponse: Response;
  try {
    providerResponse = await fetch(decisionUrl, {
      headers: { "x-api-key": diditApiKey, "accept": "application/json" },
      signal: AbortSignal.timeout(3_500),
    });
  } catch {
    return jsonResponse({ accepted: false }, 503);
  }
  if (!providerResponse.ok) return jsonResponse({ accepted: false }, 503);

  let resultObject: Record<string, unknown>;
  try {
    resultObject = await readJsonResponse(
      providerResponse,
      MAX_PROVIDER_BODY_BYTES,
    );
  } catch {
    return jsonResponse({ accepted: false }, 502);
  }
  const result = resultObject as DiditDecision;
  if (
    !providerResultBelongsToAttempt(result, {
      sessionId,
      vendorData: attempt.provider_subject_reference,
      workflowId: attempt.provider_workflow_id,
      workflowVersion: attempt.provider_workflow_version,
      environment: diditEnvironment,
    })
  ) {
    return jsonResponse({ accepted: false }, 502);
  }

  let state = normalizeProviderState(result.status);
  const passed = state === "COMPLETE" && isPassingResult(result);
  if (state === "COMPLETE" && !passed) state = "ERROR";

  // Re-check moderation immediately before the service-role-only transition.
  const { data: freshAccountData, error: freshAccountError } = await service
    .from("accounts")
    .select("status, age_verification_status")
    .eq("id", attempt.user_id)
    .maybeSingle();
  if (freshAccountError) return jsonResponse({ accepted: false }, 503);
  const freshAccount = asAccountState(freshAccountData);
  if (accountCannotBeVerified(freshAccount)) {
    return jsonResponse({ accepted: true });
  }
  if (freshAccount?.age_verification_status === "verified") {
    return jsonResponse({ accepted: true });
  }
  if (passed && freshAccount?.status !== "active") {
    return jsonResponse({ accepted: true });
  }

  const { error: finalizeError } = await service.rpc(
    "finalize_age_verification",
    {
      provider_session_id: sessionId,
      provider_state: state,
      provider_method: passed ? "DOCUMENT" : null,
      provider_check_type: passed ? "PASSIVE" : null,
    },
  );
  if (finalizeError) return jsonResponse({ accepted: false }, 503);

  return jsonResponse({ accepted: true });
});
