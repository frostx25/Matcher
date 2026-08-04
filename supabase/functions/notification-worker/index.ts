import { createClient } from "npm:@supabase/supabase-js@2.49.8";
import {
  classifyFcmResponse,
  fcmEnvelope,
  isNotificationLease,
  type NotificationLease,
} from "../_shared/pushDelivery.ts";
import {
  hasWorkerAuthorization,
  readWorkerBatchSize,
  workerJson,
} from "../_shared/workerRequest.ts";

type ServiceAccount = { project_id: string; client_email: string; private_key: string };
type TokenCache = { value: string; expiresAt: number };

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const workerSecret = Deno.env.get("WORKER_SHARED_SECRET") ?? "";
const serviceAccountJson = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") ?? "";
let tokenCache: TokenCache | null = null;

function parseServiceAccount(): ServiceAccount | null {
  try {
    const value = JSON.parse(serviceAccountJson) as Partial<ServiceAccount>;
    return typeof value.project_id === "string" &&
        /^[a-z0-9][a-z0-9-]{4,62}$/.test(value.project_id) &&
        typeof value.client_email === "string" && value.client_email.endsWith(".gserviceaccount.com") &&
        typeof value.private_key === "string" && value.private_key.includes("BEGIN PRIVATE KEY")
      ? value as ServiceAccount
      : null;
  } catch {
    return null;
  }
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function encodedJson(value: unknown): string {
  return base64Url(new TextEncoder().encode(JSON.stringify(value)));
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const content = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = Uint8Array.from(atob(content), (character) => character.charCodeAt(0));
  return await crypto.subtle.importKey(
    "pkcs8",
    binary,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

async function accessToken(account: ServiceAccount): Promise<string | null> {
  if (tokenCache && tokenCache.expiresAt > Date.now() + 60_000) return tokenCache.value;
  const issuedAt = Math.floor(Date.now() / 1000);
  const unsigned = `${encodedJson({ alg: "RS256", typ: "JWT" })}.${encodedJson({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: issuedAt,
    exp: issuedAt + 3600,
  })}`;
  let signature: ArrayBuffer;
  try {
    signature = await crypto.subtle.sign(
      "RSASSA-PKCS1-v1_5",
      await importPrivateKey(account.private_key),
      new TextEncoder().encode(unsigned),
    );
  } catch {
    return null;
  }
  const assertion = `${unsigned}.${base64Url(new Uint8Array(signature))}`;
  let response: Response;
  try {
    response = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
      signal: AbortSignal.timeout(8_000),
    });
  } catch {
    return null;
  }
  if (!response.ok) return null;
  try {
    const body = await response.json() as { access_token?: unknown; expires_in?: unknown };
    if (typeof body.access_token !== "string") return null;
    const expiresIn = typeof body.expires_in === "number" ? body.expires_in : 3600;
    tokenCache = { value: body.access_token, expiresAt: Date.now() + expiresIn * 1000 };
    return body.access_token;
  } catch {
    return null;
  }
}

function asLeases(value: unknown): NotificationLease[] | null {
  if (!Array.isArray(value) || value.length > 100) return null;
  const leases = value.map((row) => {
    if (!row || typeof row !== "object") return null;
    const value = row as Record<string, unknown>;
    return {
      deliveryId: value.delivery_id,
      leaseToken: value.lease_token,
      firebaseInstallationId: value.firebase_installation_id,
      payload: value.payload,
    };
  });
  return leases.every(isNotificationLease) ? leases as NotificationLease[] : null;
}

Deno.serve(async (request) => {
  const empty = { processed: 0, sent: 0, failed: 0 };
  if (request.method !== "POST") return workerJson(empty, 405);
  if (!await hasWorkerAuthorization(request, workerSecret)) return workerJson(empty, 401);
  const batchSize = await readWorkerBatchSize(request, 100);
  if (batchSize === null) return workerJson(empty, 400);
  const account = parseServiceAccount();
  if (!supabaseUrl || !serviceRoleKey || !account) return workerJson(empty, 503);
  const oauth = await accessToken(account);
  if (!oauth) return workerJson(empty, 503);

  const service = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await service.rpc("claim_notification_deliveries", { batch_size: batchSize });
  if (error) return workerJson(empty, 503);
  const leases = asLeases(data);
  if (!leases) return workerJson(empty, 503);

  const counts = { processed: leases.length, sent: 0, failed: 0 };
  for (const item of leases) {
    let outcome: ReturnType<typeof classifyFcmResponse> = {
      outcome: "retry",
      errorCode: "FCM_TRANSIENT",
    };
    try {
      const response = await fetch(
        `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,
        {
          method: "POST",
          headers: { authorization: `Bearer ${oauth}`, "content-type": "application/json" },
          body: JSON.stringify(fcmEnvelope(item)),
          signal: AbortSignal.timeout(8_000),
        },
      );
      let responseBody: unknown = {};
      try {
        const raw = await response.text();
        if (raw.length <= 32_768) responseBody = JSON.parse(raw || "{}");
      } catch {
        responseBody = {};
      }
      outcome = classifyFcmResponse(response.status, responseBody);
    } catch {
      // Sanitized transient outcome; token and provider detail are never logged.
    }
    const { data: completed, error: completeError } = await service.rpc(
      "complete_notification_delivery",
      {
        target_delivery_id: item.deliveryId,
        target_lease_token: item.leaseToken,
        outcome: outcome.outcome,
        error_code: outcome.errorCode,
      },
    );
    if (!completeError && completed === true && outcome.outcome === "sent") counts.sent += 1;
    else counts.failed += 1;
  }
  return workerJson(counts, 200);
});
