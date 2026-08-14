import { createClient } from "npm:@supabase/supabase-js@2.49.8";
import {
  extractOpenAIModerationDecision,
  isProfilePhotoModerationLease,
  type ProfilePhotoModerationDecision,
  type ProfilePhotoModerationLease,
} from "./profilePhotoModeration.ts";

const PRIVATE_WORKER_HEADERS = {
  "content-type": "application/json",
  "cache-control": "private, no-store, max-age=0",
  "pragma": "no-cache",
  "x-content-type-options": "nosniff",
};
const OPENAI_MODERATION_MODEL = "omni-moderation-latest";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const workerSecret = Deno.env.get("WORKER_SHARED_SECRET") ?? "";
const openAIApiKey = Deno.env.get("OPENAI_API_KEY") ?? "";

async function hasWorkerAuthorization(
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

async function readWorkerBatchSize(
  request: Request,
  maximum: number,
): Promise<number | null> {
  if (
    request.headers.get("content-type")?.split(";", 1)[0].trim() !==
      "application/json"
  ) return null;
  const declared = Number(request.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > 128) return null;
  try {
    const raw = await request.text();
    if (!raw || raw.length > 128) return null;
    const body = JSON.parse(raw) as Record<string, unknown>;
    if (
      !body || typeof body !== "object" || Array.isArray(body) ||
      Object.keys(body).length !== 1 || !Number.isInteger(body.batch_size)
    ) return null;
    const size = body.batch_size as number;
    return size >= 1 && size <= maximum ? size : null;
  } catch {
    return null;
  }
}

function workerJson(value: Record<string, number>, status: number): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: PRIVATE_WORKER_HEADERS,
  });
}

function asLeases(value: unknown): ProfilePhotoModerationLease[] | null {
  if (!Array.isArray(value) || value.length > 25) return null;
  const leases = value.map((row) => {
    if (!row || typeof row !== "object") return null;
    const value = row as Record<string, unknown>;
    return {
      profileId: value.profile_id,
      leaseToken: value.lease_token,
      objectPath: value.object_path,
      mimeType: value.mime_type,
    };
  });
  return leases.every(isProfilePhotoModerationLease)
    ? leases as ProfilePhotoModerationLease[]
    : null;
}

function base64(bytes: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

Deno.serve(async (request) => {
  const empty = { processed: 0, approved: 0, blocked: 0, review: 0, failed: 0 };
  if (request.method !== "POST") return workerJson(empty, 405);
  if (!await hasWorkerAuthorization(request, workerSecret)) {
    return workerJson(empty, 401);
  }
  const batchSize = await readWorkerBatchSize(request, 25);
  if (batchSize === null) return workerJson(empty, 400);
  if (!supabaseUrl || !serviceRoleKey || !openAIApiKey) {
    return workerJson(empty, 503);
  }

  const service = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await service.rpc("claim_profile_photo_moderation", {
    batch_size: batchSize,
  });
  if (error) return workerJson(empty, 503);
  const leases = asLeases(data);
  if (!leases) return workerJson(empty, 503);

  const counts = { ...empty, processed: leases.length };
  for (const item of leases) {
    let outcome: ProfilePhotoModerationDecision | "retry" = "retry";
    let errorCode:
      | "MODERATION_UNAVAILABLE"
      | "MODERATION_INVALID_RESPONSE"
      | "MEDIA_NOT_FOUND"
      | null = null;
    try {
      const { data: bytes, error: downloadError } = await service.storage
        .from("profile-photos").download(item.objectPath);
      if (
        downloadError || !bytes || bytes.size < 1 ||
        bytes.size > 5 * 1024 * 1024
      ) {
        errorCode = "MEDIA_NOT_FOUND";
      } else {
        let image: Uint8Array | null = null;
        try {
          image = new Uint8Array(await bytes.arrayBuffer());
          const response = await fetch(
            "https://api.openai.com/v1/moderations",
            {
              method: "POST",
              headers: {
                "authorization": `Bearer ${openAIApiKey}`,
                "content-type": "application/json",
              },
              body: JSON.stringify({
                model: OPENAI_MODERATION_MODEL,
                input: [{
                  type: "image_url",
                  image_url: {
                    url: `data:${item.mimeType};base64,${base64(image)}`,
                  },
                }],
              }),
              signal: AbortSignal.timeout(10_000),
            },
          );
          if (!response.ok) {
            console.warn("profile-photo-moderation provider request failed", {
              status: response.status,
            });
            errorCode = response.status >= 500 || response.status === 429
              ? "MODERATION_UNAVAILABLE"
              : "MODERATION_INVALID_RESPONSE";
          } else {
            const raw = await response.text();
            if (raw.length > 64_000) errorCode = "MODERATION_INVALID_RESPONSE";
            else {
              let parsed: unknown = null;
              try {
                parsed = JSON.parse(raw);
              } catch {
                // Fail closed below without recording the provider response.
              }
              const decision = extractOpenAIModerationDecision(parsed);
              if (!decision) {
                console.warn(
                  "profile-photo-moderation provider response shape invalid",
                );
                errorCode = "MODERATION_INVALID_RESPONSE";
              }
              else outcome = decision;
            }
          }
        } finally {
          image?.fill(0);
        }
      }
    } catch {
      errorCode = "MODERATION_UNAVAILABLE";
    }
    if (errorCode) outcome = "retry";
    const { data: completed, error: completeError } = await service.rpc(
      "complete_profile_photo_moderation",
      {
        target_profile_id: item.profileId,
        target_lease_token: item.leaseToken,
        outcome,
        error_code: errorCode,
      },
    );
    if (completeError || completed !== true) counts.failed += 1;
    else if (outcome === "approved") counts.approved += 1;
    else if (outcome === "adult" || outcome === "abusive") counts.blocked += 1;
    else if (outcome === "review") counts.review += 1;
    else counts.failed += 1;
  }
  return workerJson(counts, 200);
});
