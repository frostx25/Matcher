import { createClient } from "npm:@supabase/supabase-js@2.49.8";
import {
  classifySafeSearch,
  extractSafeSearchAnnotation,
  isChatModerationLease,
  type ChatModerationLease,
} from "../_shared/chatMediaModeration.ts";
import {
  hasWorkerAuthorization,
  readWorkerBatchSize,
  workerJson,
} from "../_shared/workerRequest.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const workerSecret = Deno.env.get("WORKER_SHARED_SECRET") ?? "";
const visionApiKey = Deno.env.get("GOOGLE_CLOUD_VISION_API_KEY") ?? "";

function asLeases(value: unknown): ChatModerationLease[] | null {
  if (!Array.isArray(value) || value.length > 25) return null;
  const leases = value.map((row) => {
    if (!row || typeof row !== "object") return null;
    const value = row as Record<string, unknown>;
    return {
      messageId: value.message_id,
      leaseToken: value.lease_token,
      objectPath: value.object_path,
      mimeType: value.mime_type,
    };
  });
  return leases.every(isChatModerationLease) ? leases as ChatModerationLease[] : null;
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
  if (!await hasWorkerAuthorization(request, workerSecret)) return workerJson(empty, 401);
  const batchSize = await readWorkerBatchSize(request, 25);
  if (batchSize === null) return workerJson(empty, 400);
  if (!supabaseUrl || !serviceRoleKey || !visionApiKey) return workerJson(empty, 503);

  const service = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await service.rpc("claim_chat_media_moderation", { batch_size: batchSize });
  if (error) return workerJson(empty, 503);
  const leases = asLeases(data);
  if (!leases) return workerJson(empty, 503);

  const counts = { ...empty, processed: leases.length };
  for (const item of leases) {
    let outcome: "approved" | "adult" | "abusive" | "review" | "retry" = "retry";
    let errorCode: "VISION_UNAVAILABLE" | "VISION_INVALID_RESPONSE" | "MEDIA_NOT_FOUND" | null = null;
    try {
      const { data: bytes, error: downloadError } = await service.storage
        .from("chat-media").download(item.objectPath);
      if (downloadError || !bytes || bytes.size < 1 || bytes.size > 5 * 1024 * 1024) {
        errorCode = "MEDIA_NOT_FOUND";
      } else {
        let image: Uint8Array | null = null;
        try {
          image = new Uint8Array(await bytes.arrayBuffer());
          const response = await fetch("https://vision.googleapis.com/v1/images:annotate", {
            method: "POST",
            headers: { "content-type": "application/json", "x-goog-api-key": visionApiKey },
            body: JSON.stringify({
              requests: [{ image: { content: base64(image) }, features: [{ type: "SAFE_SEARCH_DETECTION" }] }],
            }),
            signal: AbortSignal.timeout(10_000),
          });
          if (!response.ok) {
            errorCode = response.status >= 500 || response.status === 429
              ? "VISION_UNAVAILABLE"
              : "VISION_INVALID_RESPONSE";
          } else {
            const raw = await response.text();
            if (raw.length > 64_000) errorCode = "VISION_INVALID_RESPONSE";
            else {
              let parsed: unknown = null;
              try { parsed = JSON.parse(raw); } catch { /* fail closed below */ }
              const annotation = extractSafeSearchAnnotation(parsed);
              if (!annotation) errorCode = "VISION_INVALID_RESPONSE";
              else outcome = classifySafeSearch(annotation);
            }
          }
        } finally {
          image?.fill(0);
        }
      }
    } catch {
      errorCode = "VISION_UNAVAILABLE";
    }
    if (errorCode) outcome = "retry";
    const { data: completed, error: completeError } = await service.rpc(
      "complete_chat_media_moderation",
      {
        target_message_id: item.messageId,
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
