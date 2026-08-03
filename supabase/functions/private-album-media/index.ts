import { createClient } from "npm:@supabase/supabase-js@2.49.8";
import {
  createPrivateAlbumMediaHandler,
  createPrivateAlbumSupabaseAdapter,
  parseAllowedOrigins,
  type PrivateAlbumSupabaseAdapter,
  type PrivateAlbumSupabaseClient,
  type PrivateAlbumSupabaseClientFactory,
  resolvePrivateAlbumSupabaseConfig,
} from "../_shared/privateAlbumMedia.ts";

const unavailableAdapter: PrivateAlbumSupabaseAdapter = {
  authorize: () => Promise.resolve({ kind: "error" }),
  download: () => Promise.resolve({ kind: "error" }),
};

const config = resolvePrivateAlbumSupabaseConfig((name) => Deno.env.get(name));
const clientFactory: PrivateAlbumSupabaseClientFactory = (
  url,
  key,
  options,
) =>
  createClient(
    url,
    key,
    options as Parameters<typeof createClient>[2],
  ) as unknown as PrivateAlbumSupabaseClient;

let adapter = unavailableAdapter;
if (config) {
  try {
    adapter = createPrivateAlbumSupabaseAdapter(config, clientFactory);
  } catch {
    // A malformed runtime/client configuration must fail closed at request time.
  }
}

const handler = createPrivateAlbumMediaHandler({
  authorize: adapter.authorize,
  download: adapter.download,
  allowedOrigins: parseAllowedOrigins(
    Deno.env.get("PRIVATE_ALBUM_ALLOWED_ORIGINS"),
  ),
});

Deno.serve(handler);
