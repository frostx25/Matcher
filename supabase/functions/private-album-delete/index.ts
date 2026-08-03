import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.49.8";
import {
  type AlbumFinalizationResult,
  createPrivateAlbumDeleteHandler,
  type DeleteAuthorizationResult,
  type DeleteObjectResult,
  type PrivateAlbumDeletionCandidate,
} from "../_shared/privateAlbumDelete.ts";

type DeletionCandidateRow = {
  object_path?: unknown;
  delete_now?: unknown;
  hold_until?: unknown;
};

type RpcError = {
  code?: string;
  message?: string;
};

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const publicKey = Deno.env.get("SUPABASE_ANON_KEY") ??
  Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

let privilegedClient: SupabaseClient | null = null;

function callerClient(accessToken: string): SupabaseClient | null {
  if (!supabaseUrl || !publicKey) return null;
  return createClient(supabaseUrl, publicKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { headers: { "authorization": `Bearer ${accessToken}` } },
  });
}

function serviceClient(): SupabaseClient | null {
  if (!supabaseUrl || !serviceRoleKey) return null;
  privilegedClient ??= createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  return privilegedClient;
}

function mapAuthorizationError<T>(
  error: RpcError,
): DeleteAuthorizationResult<T> {
  const code = (error.code ?? "").toUpperCase();
  const message = (error.message ?? "").toUpperCase();
  if (
    code === "PGRST301" || message.includes("JWT") ||
    message.includes("AUTH_REQUIRED")
  ) {
    return { kind: "unauthenticated" };
  }
  if (code === "42501" || message.includes("PERMISSION DENIED")) {
    return { kind: "forbidden" };
  }
  if (
    message.includes("PRIVATE_ALBUM_ITEM_NOT_FOUND") ||
    message.includes("PRIVATE_ALBUM_NOT_FOUND")
  ) {
    return { kind: "not_found" };
  }
  return { kind: "error" };
}

function parseDeletionCandidate(
  value: unknown,
): PrivateAlbumDeletionCandidate | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const row = value as DeletionCandidateRow;
  if (
    typeof row.object_path !== "string" ||
    typeof row.delete_now !== "boolean" ||
    (row.hold_until !== null && typeof row.hold_until !== "string")
  ) {
    return null;
  }
  return {
    objectPath: row.object_path,
    deleteNow: row.delete_now,
    holdUntil: row.hold_until,
  };
}

async function markItem(
  accessToken: string,
  itemId: string,
): Promise<DeleteAuthorizationResult<PrivateAlbumDeletionCandidate>> {
  const client = callerClient(accessToken);
  if (!client) return { kind: "error" };
  const { data, error } = await client.rpc(
    "mark_private_album_item_for_deletion",
    { album_item_id: itemId },
  );
  if (error) return mapAuthorizationError(error);
  if (!Array.isArray(data)) return { kind: "error" };
  if (data.length === 0) return { kind: "not_found" };
  if (data.length !== 1) return { kind: "error" };
  const candidate = parseDeletionCandidate(data[0]);
  return candidate
    ? { kind: "authorized", value: candidate }
    : { kind: "error" };
}

async function beginAlbum(
  accessToken: string,
  albumId: string,
): Promise<DeleteAuthorizationResult<PrivateAlbumDeletionCandidate[]>> {
  const client = callerClient(accessToken);
  if (!client) return { kind: "error" };
  const { data, error } = await client.rpc("begin_private_album_deletion", {
    target_album_id: albumId,
  });
  if (error) return mapAuthorizationError(error);
  if (!Array.isArray(data)) return { kind: "error" };

  const candidates: PrivateAlbumDeletionCandidate[] = [];
  for (const value of data) {
    const candidate = parseDeletionCandidate(value);
    if (!candidate) return { kind: "error" };
    candidates.push(candidate);
  }
  return { kind: "authorized", value: candidates };
}

async function removeObject(objectPath: string): Promise<DeleteObjectResult> {
  const client = serviceClient();
  if (!client) return { kind: "error" };
  const { error } = await client.storage.from("private-albums").remove([
    objectPath,
  ]);
  if (!error) return { kind: "ok" };
  const statusCode = "statusCode" in error ? String(error.statusCode) : "";
  return statusCode === "404" ? { kind: "ok" } : { kind: "error" };
}

async function finalizeAlbum(
  accessToken: string,
  albumId: string,
): Promise<AlbumFinalizationResult> {
  const client = callerClient(accessToken);
  if (!client) return { kind: "error" };
  const { data, error } = await client.rpc(
    "finalize_private_album_deletion",
    { target_album_id: albumId },
  );
  if (error) {
    const mapped = mapAuthorizationError<never>(error);
    if (mapped.kind === "unauthenticated" || mapped.kind === "forbidden") {
      return mapped;
    }
    return { kind: "error" };
  }
  return data === true ? { kind: "finalized" } : { kind: "pending" };
}

const handler = createPrivateAlbumDeleteHandler({
  markItem,
  beginAlbum,
  removeObject,
  finalizeAlbum,
});

Deno.serve(handler);
