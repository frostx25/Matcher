import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.49.8";
import {
  type AlbumFinalizationResult,
  createPrivateAlbumDeleteHandler,
  type DeleteAuthorizationResult,
  type DeleteObjectResult,
} from "../_shared/privateAlbumDelete.ts";

type ObjectPathRow = {
  object_path?: unknown;
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

async function markItem(
  accessToken: string,
  itemId: string,
): Promise<DeleteAuthorizationResult<string>> {
  const client = callerClient(accessToken);
  if (!client) return { kind: "error" };
  const { data, error } = await client.rpc(
    "mark_private_album_item_for_deletion",
    { album_item_id: itemId },
  );
  if (error) return mapAuthorizationError(error);
  return typeof data === "string"
    ? { kind: "authorized", value: data }
    : { kind: "error" };
}

async function beginAlbum(
  accessToken: string,
): Promise<DeleteAuthorizationResult<string[]>> {
  const client = callerClient(accessToken);
  if (!client) return { kind: "error" };
  const { data, error } = await client.rpc("begin_private_album_deletion");
  if (error) return mapAuthorizationError(error);
  if (!Array.isArray(data)) return { kind: "error" };

  const paths: string[] = [];
  for (const value of data) {
    if (!value || typeof value !== "object") return { kind: "error" };
    const row = value as ObjectPathRow;
    if (typeof row.object_path !== "string") return { kind: "error" };
    paths.push(row.object_path);
  }
  return { kind: "authorized", value: paths };
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
): Promise<AlbumFinalizationResult> {
  const client = callerClient(accessToken);
  if (!client) return { kind: "error" };
  const { data, error } = await client.rpc("finalize_private_album_deletion");
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
