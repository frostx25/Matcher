import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.49.8";
import {
  type CleanupBatchResult,
  type CleanupConfirmationResult,
  type CleanupDeleteResult,
  type CleanupFailureResult,
  createPrivateAlbumCleanupHandler,
  type PrivateAlbumCleanupLease,
} from "../_shared/privateAlbumCleanup.ts";

type CleanupBatchRow = {
  object_path?: unknown;
  lease_token?: unknown;
  leased_until?: unknown;
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

function getPrivilegedClient(): SupabaseClient | null {
  if (!supabaseUrl || !serviceRoleKey) return null;
  privilegedClient ??= createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  return privilegedClient;
}

function mapBatchError(error: RpcError): CleanupBatchResult {
  const code = (error.code ?? "").toUpperCase();
  const message = (error.message ?? "").toUpperCase();
  if (
    code === "PGRST301" || message.includes("JWT") ||
    message.includes("AUTH_REQUIRED")
  ) {
    return { kind: "unauthenticated" };
  }
  if (
    code === "42501" || message.includes("SERVICE_ROLE_REQUIRED") ||
    message.includes("PERMISSION DENIED")
  ) {
    return { kind: "forbidden" };
  }
  return { kind: "error" };
}

async function getBatch(
  accessToken: string,
  batchSize: number,
): Promise<CleanupBatchResult> {
  if (!supabaseUrl || !publicKey) return { kind: "error" };

  const callerClient = createClient(supabaseUrl, publicKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { headers: { "authorization": `Bearer ${accessToken}` } },
  });
  const { data, error } = await callerClient.rpc(
    "get_private_album_cleanup_batch",
    { batch_size: batchSize },
  );
  if (error) return mapBatchError(error);
  if (!Array.isArray(data)) return { kind: "error" };

  const items: PrivateAlbumCleanupLease[] = [];
  for (const value of data) {
    if (!value || typeof value !== "object") return { kind: "error" };
    const row = value as CleanupBatchRow;
    if (
      typeof row.object_path !== "string" ||
      typeof row.lease_token !== "string" ||
      typeof row.leased_until !== "string"
    ) {
      return { kind: "error" };
    }
    items.push({
      objectPath: row.object_path,
      leaseToken: row.lease_token,
      leasedUntil: row.leased_until,
    });
  }
  return { kind: "ok", items };
}

async function deleteObject(objectPath: string): Promise<CleanupDeleteResult> {
  const service = getPrivilegedClient();
  if (!service) return { kind: "error" };

  const { error } = await service.storage.from("private-albums").remove([
    objectPath,
  ]);
  if (!error) return { kind: "ok" };

  const statusCode = "statusCode" in error ? String(error.statusCode) : "";
  return statusCode === "404" ? { kind: "ok" } : { kind: "error" };
}

async function confirmDeleted(
  objectPath: string,
  leaseToken: string,
): Promise<CleanupConfirmationResult> {
  const service = getPrivilegedClient();
  if (!service) return { kind: "error" };

  const { data, error } = await service.rpc(
    "confirm_private_album_object_deleted",
    { object_path: objectPath, lease_token: leaseToken },
  );
  if (error) return { kind: "error" };
  return data === true ? { kind: "confirmed" } : { kind: "pending" };
}

async function failCleanup(
  objectPath: string,
  leaseToken: string,
  failureCode: "DELETE_FAILED",
): Promise<CleanupFailureResult> {
  const service = getPrivilegedClient();
  if (!service) return { kind: "error" };

  const { data, error } = await service.rpc(
    "fail_private_album_object_cleanup",
    {
      object_path: objectPath,
      lease_token: leaseToken,
      failure_code: failureCode,
    },
  );
  if (error) return { kind: "error" };
  return data === true ? { kind: "released" } : { kind: "stale" };
}

const handler = createPrivateAlbumCleanupHandler({
  getBatch,
  deleteObject,
  confirmDeleted,
  failCleanup,
});

Deno.serve(handler);
