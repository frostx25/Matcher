import { createClient } from "npm:@supabase/supabase-js@2.49.8";
import {
  type AlbumAuthorizationResult,
  type AlbumDownloadResult,
  createPrivateAlbumMediaHandler,
  parseAllowedOrigins,
} from "../_shared/privateAlbumMedia.ts";
import {
  PRIVATE_ALBUM_MAX_IMAGE_BYTES,
  PRIVATE_ALBUM_MIN_IMAGE_BYTES,
} from "../_shared/privateAlbumImage.ts";

type AuthorizationRow = {
  object_path?: unknown;
  mime_type?: unknown;
};

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const publicKey = Deno.env.get("SUPABASE_ANON_KEY") ??
  Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const allowedOrigins = parseAllowedOrigins(
  Deno.env.get("PRIVATE_ALBUM_ALLOWED_ORIGINS"),
);

function oneAuthorizationRow(value: unknown): AuthorizationRow | null {
  if (Array.isArray(value)) {
    if (value.length !== 1) return null;
    return oneAuthorizationRow(value[0]);
  }
  return value && typeof value === "object" ? value as AuthorizationRow : null;
}

function mapAuthorizationError(error: { message?: string }):
  | "unauthenticated"
  | "forbidden"
  | "not_found"
  | "error" {
  const marker = (error.message ?? "").toUpperCase();
  if (marker.includes("AUTH_REQUIRED")) return "unauthenticated";
  if (
    marker.includes("PRIVATE_ALBUM_FORBIDDEN") ||
    marker.includes("ALBUM_ACCESS_DENIED")
  ) {
    return "forbidden";
  }
  if (
    marker.includes("PRIVATE_ALBUM_ITEM_NOT_FOUND") ||
    marker.includes("ALBUM_ITEM_NOT_FOUND")
  ) {
    return "not_found";
  }
  return "error";
}

async function authorize(
  accessToken: string,
  itemId: string,
): Promise<AlbumAuthorizationResult> {
  if (!supabaseUrl || !publicKey) return { kind: "error" };

  const client = createClient(supabaseUrl, publicKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { headers: { "authorization": `Bearer ${accessToken}` } },
  });

  const { data: userData, error: userError } = await client.auth.getUser(
    accessToken,
  );
  if (userError || !userData.user) return { kind: "unauthenticated" };

  const { data, error } = await client.rpc("authorize_private_album_item", {
    album_item_id: itemId,
  });
  if (error) return { kind: mapAuthorizationError(error) };

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
}

async function download(objectPath: string): Promise<AlbumDownloadResult> {
  if (!supabaseUrl || !serviceRoleKey) return { kind: "error" };
  const service = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await service.storage.from("private-albums").download(
    objectPath,
  );
  if (error || !data) {
    const status = error && "statusCode" in error
      ? String(error.statusCode)
      : "";
    return status === "404" ? { kind: "not_found" } : { kind: "error" };
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
}

const handler = createPrivateAlbumMediaHandler({
  authorize,
  download,
  allowedOrigins,
});

Deno.serve(handler);
