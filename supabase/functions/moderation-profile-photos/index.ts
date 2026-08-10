import { createClient } from "npm:@supabase/supabase-js@2.49.8";
import {
  parseReviewDecisionBody,
  parseReviewQueueRows,
} from "./profilePhotoReview.ts";

const HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "private, no-store, max-age=0",
  "pragma": "no-cache",
  "x-content-type-options": "nosniff",
};

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const allowedOrigins = new Set(
  (Deno.env.get("MODERATION_ALLOWED_ORIGINS") ?? "")
    .split(",")
    .map((origin) => origin.trim())
    .filter(Boolean),
);

function response(
  body: Record<string, unknown>,
  status: number,
  origin: string | null,
): Response {
  const cors = origin && allowedOrigins.has(origin)
    ? { "access-control-allow-origin": origin, "vary": "origin" }
    : {};
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...HEADERS, ...cors },
  });
}

function bearer(request: Request): string | null {
  const value = request.headers.get("authorization") ?? "";
  return value.startsWith("Bearer ") && value.length > 20 ? value.slice(7) : null;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const CONSOLE_ACTIONS = new Set([
  "resolve_case", "dismiss_case", "remove_album_item", "remove_album",
  "warn_user", "suspend_user", "ban_user", "reactivate_user",
]);

async function smallJson(request: Request, limit = 2048): Promise<Record<string, unknown> | null> {
  const declared = Number(request.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > limit) return null;
  try {
    const raw = await request.text();
    if (raw.length > limit) return null;
    const value = JSON.parse(raw);
    return value && typeof value === "object" && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

function rpcStatus(error: { code?: string; message?: string } | null): number {
  if (!error) return 200;
  if (error.code === "42501") return 403;
  if (error.message?.includes("STALE")) return 409;
  if (error.message?.includes("RATE_LIMITED")) return 429;
  if (error.message?.startsWith("INVALID_") || error.message === "LAST_ACTIVE_ADMIN") return 400;
  return 503;
}

Deno.serve(async (request) => {
  const origin = request.headers.get("origin");
  if (origin && !allowedOrigins.has(origin)) return response({ error: "FORBIDDEN" }, 403, null);
  if (request.method === "OPTIONS") {
    if (!origin) return response({ error: "FORBIDDEN" }, 403, null);
    return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-origin": origin,
        "access-control-allow-methods": "GET, POST, OPTIONS",
        "access-control-allow-headers": "authorization, content-type",
        "access-control-max-age": "600",
        "vary": "origin",
      },
    });
  }
  if (!supabaseUrl || !anonKey || !serviceRoleKey) {
    return response({ error: "UNAVAILABLE" }, 503, origin);
  }
  const token = bearer(request);
  if (!token) return response({ error: "UNAUTHORIZED" }, 401, origin);

  const userClient = createClient(supabaseUrl, anonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { headers: { Authorization: `Bearer ${token}` } },
  });
  const serviceClient = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: userError } = await serviceClient.auth.getUser(token);
  if (userError || !userData.user) return response({ error: "UNAUTHORIZED" }, 401, origin);

  const url = new URL(request.url);
  const route = url.searchParams.get("route") ??
    url.pathname.split("/").filter(Boolean).at(-1) ?? "";

  if (request.method === "GET" && route === "overview") {
    const { data, error } = await userClient.rpc("get_moderation_console_overview");
    const status = rpcStatus(error);
    return response(status === 200 ? { overview: data } : { error: status === 403 ? "FORBIDDEN" : "UNAVAILABLE" }, status, origin);
  }

  if (request.method === "GET" && route === "cases") {
    const size = Number(url.searchParams.get("page_size") ?? "30");
    const state = url.searchParams.get("state") ?? "open";
    if (!Number.isInteger(size) || size < 1 || size > 50 || !["open", "resolved", "dismissed", "all"].includes(state)) {
      return response({ error: "INVALID_REQUEST" }, 400, origin);
    }
    const { data, error } = await userClient.rpc("list_moderation_cases", { page_size: size, state_filter: state });
    const status = rpcStatus(error);
    return response(status === 200 ? { items: data ?? [] } : { error: status === 403 ? "FORBIDDEN" : "UNAVAILABLE" }, status, origin);
  }

  if (request.method === "GET" && route === "album-evidence") {
    const caseId = url.searchParams.get("case_id") ?? "";
    if (!UUID.test(caseId)) return response({ error: "INVALID_REQUEST" }, 400, origin);
    const { data, error } = await userClient.rpc("list_moderation_album_evidence", { target_case_id: caseId });
    const status = rpcStatus(error);
    if (status !== 200) return response({ error: status === 403 ? "FORBIDDEN" : "UNAVAILABLE" }, status, origin);
    const evidence = Array.isArray(data) ? data : [];
    const items = await Promise.all(evidence.map(async (item: Record<string, unknown>) => {
      if (typeof item.object_path !== "string" || typeof item.album_id !== "string") return null;
      const { data: signed, error: signingError } = await serviceClient.storage.from("private-albums").createSignedUrl(item.object_path, 60);
      if (signingError || !signed?.signedUrl) return null;
      return {
        album_id: item.album_id,
        album_item_id: item.album_item_id,
        hold_until: item.hold_until,
        preview_url: signed.signedUrl,
        preview_expires_in: 60,
      };
    }));
    if (items.some((item) => item === null)) return response({ error: "PREVIEW_UNAVAILABLE" }, 503, origin);
    return response({ items }, 200, origin);
  }

  if (request.method === "GET" && route === "audit") {
    const size = Number(url.searchParams.get("page_size") ?? "50");
    if (!Number.isInteger(size) || size < 1 || size > 100) return response({ error: "INVALID_REQUEST" }, 400, origin);
    const { data, error } = await userClient.rpc("list_moderation_audit", { page_size: size });
    const status = rpcStatus(error);
    return response(status === 200 ? { items: data ?? [] } : { error: status === 403 ? "FORBIDDEN" : "UNAVAILABLE" }, status, origin);
  }

  if (request.method === "GET" && route === "users") {
    const search = url.searchParams.get("q") ?? "";
    if (search.length > 60) return response({ error: "INVALID_REQUEST" }, 400, origin);
    const { data, error } = await userClient.rpc("search_moderation_users", { search_text: search, page_size: 30 });
    const status = rpcStatus(error);
    return response(status === 200 ? { items: data ?? [] } : { error: status === 403 ? "FORBIDDEN" : "UNAVAILABLE" }, status, origin);
  }

  if (request.method === "GET" && route === "staff") {
    const { data, error } = await userClient.rpc("list_moderation_staff");
    const status = rpcStatus(error);
    return response(status === 200 ? { items: data ?? [] } : { error: status === 403 ? "FORBIDDEN" : "UNAVAILABLE" }, status, origin);
  }

  if (request.method === "POST" && route === "actions") {
    const body = await smallJson(request);
    const action = typeof body?.action === "string" ? body.action : "";
    if (!body || !CONSOLE_ACTIONS.has(action)) return response({ error: "INVALID_REQUEST" }, 400, origin);
    const id = (key: string) => typeof body[key] === "string" && UUID.test(body[key] as string) ? body[key] : null;
    const { data, error } = await userClient.rpc("moderation_console_action", {
      action,
      target_user_id: id("target_user_id"),
      target_case_id: id("target_case_id"),
      target_album_id: id("target_album_id"),
      target_album_item_id: id("target_album_item_id"),
      reason: typeof body.reason === "string" ? body.reason : null,
      suspension_hours: Number.isInteger(body.suspension_hours) ? body.suspension_hours : null,
    });
    const status = rpcStatus(error);
    return response(status === 200 ? { action: data } : { error: status === 403 ? "FORBIDDEN" : status === 409 ? "STALE" : status === 429 ? "RATE_LIMITED" : status === 400 ? "INVALID_REQUEST" : "UNAVAILABLE" }, status, origin);
  }

  if (request.method === "POST" && route === "staff") {
    const body = await smallJson(request, 1024);
    if (!body || typeof body.email !== "string" || body.email.length > 254 || !["reviewer", "admin"].includes(String(body.role)) || typeof body.active !== "boolean") {
      return response({ error: "INVALID_REQUEST" }, 400, origin);
    }
    const { data, error } = await userClient.rpc("manage_moderation_staff", { target_email: body.email, new_role: body.role, new_active: body.active });
    const status = rpcStatus(error);
    return response(status === 200 ? { role: data } : { error: status === 403 ? "FORBIDDEN" : status === 400 ? "INVALID_REQUEST" : "UNAVAILABLE" }, status, origin);
  }

  if (request.method === "GET") {
    const rawSize = url.searchParams.get("page_size") ?? "20";
    const pageSize = Number(rawSize);
    if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) {
      return response({ error: "INVALID_PAGE_SIZE" }, 400, origin);
    }
    const { data, error } = await userClient.rpc("list_profile_photo_review_queue", {
      page_size: pageSize,
      cursor_created_at: url.searchParams.get("cursor_created_at"),
      cursor_profile_id: url.searchParams.get("cursor_profile_id"),
    });
    if (error) {
      return response(
        { error: error.code === "42501" ? "FORBIDDEN" : "UNAVAILABLE" },
        error.code === "42501" ? 403 : 503,
        origin,
      );
    }
    const items = parseReviewQueueRows(data);
    if (!items) return response({ error: "INVALID_RESPONSE" }, 503, origin);

    const previews = await Promise.all(items.map(async (item) => {
      const { data: signed, error: signingError } = await serviceClient.storage
        .from("profile-photos")
        .createSignedUrl(item.candidatePath, 60);
      if (signingError || !signed?.signedUrl) return null;
      return {
        profile_id: item.profileId,
        display_name: item.displayName,
        submitted_at: item.submittedAt,
        has_approved_photo: item.hasApprovedPhoto,
        candidate_path: item.candidatePath,
        preview_url: signed.signedUrl,
        preview_expires_in: 60,
      };
    }));
    if (previews.some((item) => item === null)) {
      return response({ error: "PREVIEW_UNAVAILABLE" }, 503, origin);
    }
    return response({ items: previews }, 200, origin);
  }

  if (request.method === "POST") {
    const declared = Number(request.headers.get("content-length"));
    if (Number.isFinite(declared) && declared > 512) {
      return response({ error: "INVALID_REQUEST" }, 400, origin);
    }
    let parsed: ReturnType<typeof parseReviewDecisionBody> = null;
    try {
      const raw = await request.text();
      if (raw.length <= 512) parsed = parseReviewDecisionBody(JSON.parse(raw));
    } catch {
      parsed = null;
    }
    if (!parsed) return response({ error: "INVALID_REQUEST" }, 400, origin);
    const { data, error } = await userClient.rpc("decide_profile_photo_review", {
      target_profile_id: parsed.profileId,
      expected_candidate_path: parsed.candidatePath,
      decision: parsed.decision,
    });
    if (error) {
      const status = error.code === "42501" ? 403 : error.message === "PROFILE_PHOTO_REVIEW_STALE" ? 409 : 503;
      return response({ error: status === 403 ? "FORBIDDEN" : status === 409 ? "STALE_REVIEW" : "UNAVAILABLE" }, status, origin);
    }
    return response({ decision: data }, 200, origin);
  }

  return response({ error: "METHOD_NOT_ALLOWED" }, 405, origin);
});
