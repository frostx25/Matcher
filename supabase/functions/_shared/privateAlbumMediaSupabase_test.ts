import {
  classifyPrivateAlbumIdentityResult,
  createPrivateAlbumServiceFetch,
  createPrivateAlbumSupabaseAdapter,
  type PrivateAlbumFetch,
  type PrivateAlbumSupabaseClient,
  type PrivateAlbumSupabaseClientFactory,
  type PrivateAlbumSupabaseClientOptions,
  resolvePrivateAlbumSupabaseConfig,
} from "./privateAlbumMedia.ts";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${expected}, received ${actual}`);
  }
}

const userToken = "header.payload.signature";
const itemId = "11111111-1111-4111-8111-111111111111";
const publishableKey = "sb_publishable_synthetic_default";
const secretKey = "sb_secret_synthetic_default";
const legacyPublishableKey = "legacy.anon.key";
const legacySecretKey = "legacy.service.role";

function readEnvironment(
  values: Record<string, string>,
): (name: string) => string | undefined {
  return (name) => values[name];
}

function unavailableClient(): PrivateAlbumSupabaseClient {
  return {
    auth: {
      getUser: () => Promise.reject(new Error("unexpected auth call")),
    },
    rpc: () => Promise.reject(new Error("unexpected RPC call")),
    storage: {
      from: () => ({
        download: () => Promise.reject(new Error("unexpected storage call")),
      }),
    },
  };
}

Deno.test("hosted key maps take precedence and singular plus legacy remain supported", () => {
  const hosted = resolvePrivateAlbumSupabaseConfig(readEnvironment({
    SUPABASE_URL: "https://project.invalid/",
    SUPABASE_PUBLISHABLE_KEYS: JSON.stringify({
      default: publishableKey,
      rotated: "sb_publishable_rotated",
    }),
    SUPABASE_SECRET_KEYS: JSON.stringify({
      default: secretKey,
      worker: "sb_secret_worker",
    }),
    SUPABASE_PUBLISHABLE_KEY: "sb_publishable_single",
    SUPABASE_SECRET_KEY: "sb_secret_single",
    SUPABASE_ANON_KEY: legacyPublishableKey,
    SUPABASE_SERVICE_ROLE_KEY: legacySecretKey,
  }));
  assert(hosted !== null, "hosted configuration resolves");
  assertEquals(hosted.url, "https://project.invalid", "normalized URL");
  assertEquals(hosted.publishableKey, publishableKey, "hosted public key");
  assertEquals(hosted.secretKey, secretKey, "hosted secret key");

  const singular = resolvePrivateAlbumSupabaseConfig(readEnvironment({
    SUPABASE_URL: "http://127.0.0.1:54321",
    SUPABASE_PUBLISHABLE_KEY: "sb_publishable_local",
    SUPABASE_SECRET_KEY: "sb_secret_local",
  }));
  assert(singular !== null, "local singular configuration resolves");
  assertEquals(
    singular.publishableKey,
    "sb_publishable_local",
    "singular public key",
  );
  assertEquals(singular.secretKey, "sb_secret_local", "singular secret key");

  const legacy = resolvePrivateAlbumSupabaseConfig(readEnvironment({
    SUPABASE_URL: "https://legacy.invalid",
    SUPABASE_ANON_KEY: legacyPublishableKey,
    SUPABASE_SERVICE_ROLE_KEY: legacySecretKey,
  }));
  assert(legacy !== null, "legacy configuration resolves");
  assertEquals(
    legacy.publishableKey,
    legacyPublishableKey,
    "legacy anon key",
  );
  assertEquals(legacy.secretKey, legacySecretKey, "legacy service key");
});

Deno.test("malformed hosted maps fail closed instead of silently selecting another key", () => {
  const malformedMap = resolvePrivateAlbumSupabaseConfig(readEnvironment({
    SUPABASE_URL: "https://project.invalid",
    SUPABASE_PUBLISHABLE_KEYS: "not-json",
    SUPABASE_SECRET_KEYS: JSON.stringify({ default: secretKey }),
    SUPABASE_ANON_KEY: legacyPublishableKey,
    SUPABASE_SERVICE_ROLE_KEY: legacySecretKey,
  }));
  assertEquals(malformedMap, null, "malformed map is rejected");

  const crossedRoles = resolvePrivateAlbumSupabaseConfig(readEnvironment({
    SUPABASE_URL: "https://project.invalid",
    SUPABASE_PUBLISHABLE_KEYS: JSON.stringify({ default: secretKey }),
    SUPABASE_SECRET_KEYS: JSON.stringify({ default: publishableKey }),
  }));
  assertEquals(crossedRoles, null, "cross-role keys are rejected");

  const namedWithoutDefault = resolvePrivateAlbumSupabaseConfig(
    readEnvironment({
      SUPABASE_URL: "https://project.invalid",
      SUPABASE_PUBLISHABLE_KEYS: JSON.stringify({ mobile: publishableKey }),
      SUPABASE_SECRET_KEYS: JSON.stringify({ worker: secretKey }),
    }),
  );
  assertEquals(namedWithoutDefault, null, "default key must be explicit");
});

Deno.test("identity classification reserves 401 for invalid credentials", () => {
  assertEquals(
    classifyPrivateAlbumIdentityResult({ id: "synthetic-user" }, null),
    "verified",
    "valid user",
  );
  assertEquals(
    classifyPrivateAlbumIdentityResult(null, { status: 401, code: "bad_jwt" }),
    "unauthenticated",
    "invalid JWT",
  );
  assertEquals(
    classifyPrivateAlbumIdentityResult(null, {
      status: 403,
      code: "session_not_found",
    }),
    "unauthenticated",
    "invalid session",
  );
  assertEquals(
    classifyPrivateAlbumIdentityResult(null, {
      status: 400,
      name: "AuthSessionMissingError",
    }),
    "unauthenticated",
    "missing session error",
  );
  for (
    const error of [
      { status: 429, code: "over_request_rate_limit" },
      { status: 503, code: "service_unavailable" },
      { status: 504, code: "gateway_timeout" },
    ]
  ) {
    assertEquals(
      classifyPrivateAlbumIdentityResult(null, error),
      "error",
      `operational status ${error.status}`,
    );
  }
  assertEquals(
    classifyPrivateAlbumIdentityResult(null, null),
    "error",
    "empty successful response is an upstream contract failure",
  );
});

Deno.test("opaque secret keys stay in apikey while user and legacy JWTs use Authorization", async () => {
  const observed: Headers[] = [];
  const fetchStub: PrivateAlbumFetch = (_input, init) => {
    observed.push(new Headers(init?.headers));
    return Promise.resolve(new Response(null, { status: 204 }));
  };

  const currentFetch = createPrivateAlbumServiceFetch(secretKey, fetchStub);
  await currentFetch("https://project.invalid/storage/v1/object", {
    headers: { authorization: `Bearer ${secretKey}` },
  });
  await currentFetch("https://project.invalid/auth/v1/user", {
    headers: { authorization: `Bearer ${userToken}` },
  });

  assertEquals(observed[0].get("apikey"), secretKey, "opaque secret apikey");
  assertEquals(
    observed[0].get("authorization"),
    null,
    "opaque secret is stripped from Authorization",
  );
  assertEquals(observed[1].get("apikey"), secretKey, "auth request apikey");
  assertEquals(
    observed[1].get("authorization"),
    `Bearer ${userToken}`,
    "user JWT is preserved",
  );

  const legacyFetch = createPrivateAlbumServiceFetch(
    legacySecretKey,
    fetchStub,
  );
  await legacyFetch("https://legacy.invalid/storage/v1/object");
  assertEquals(
    observed[2].get("authorization"),
    `Bearer ${legacySecretKey}`,
    "legacy service-role JWT remains supported",
  );
  assertEquals(
    observed[2].get("apikey"),
    legacySecretKey,
    "legacy apikey",
  );
});

Deno.test("Supabase adapter verifies identity before a caller-scoped RPC", async () => {
  const sequence: string[] = [];
  const factoryCalls: Array<{
    key: string;
    options: PrivateAlbumSupabaseClientOptions;
  }> = [];
  const serviceFetchHeaders: Headers[] = [];
  const serviceFetch: PrivateAlbumFetch = (_input, init) => {
    serviceFetchHeaders.push(new Headers(init?.headers));
    return Promise.resolve(new Response(null, { status: 204 }));
  };

  const factory: PrivateAlbumSupabaseClientFactory = (_url, key, options) => {
    factoryCalls.push({ key, options });
    if (key === secretKey) {
      return {
        auth: {
          getUser: (token) => {
            sequence.push("verify");
            assertEquals(token, userToken, "verified token");
            return Promise.resolve({
              data: { user: { id: "synthetic-user" } },
              error: null,
            });
          },
        },
        rpc: () => Promise.reject(new Error("service client RPC forbidden")),
        storage: {
          from: (bucket) => ({
            download: (path) => {
              sequence.push("download");
              assertEquals(bucket, "private-albums", "private bucket");
              assertEquals(path, "owner/album/item.webp", "private path");
              return Promise.resolve({
                data: new Blob([new ArrayBuffer(1024)], {
                  type: "image/webp",
                }),
                error: null,
              });
            },
          }),
        },
      };
    }

    assertEquals(key, publishableKey, "caller uses publishable key");
    return {
      ...unavailableClient(),
      rpc: (functionName, parameters) => {
        sequence.push("authorize");
        assertEquals(
          functionName,
          "authorize_private_album_item",
          "authorization RPC",
        );
        assertEquals(
          parameters.album_item_id,
          itemId,
          "authorized item id",
        );
        return Promise.resolve({
          data: [{
            object_path: "owner/album/item.webp",
            mime_type: "image/webp",
          }],
          error: null,
        });
      },
    };
  };

  const adapter = createPrivateAlbumSupabaseAdapter(
    {
      url: "https://project.invalid",
      publishableKey,
      secretKey,
    },
    factory,
    serviceFetch,
  );
  const authorization = await adapter.authorize(userToken, itemId);
  assertEquals(authorization.kind, "authorized", "authorized adapter result");
  assertEquals(sequence.join(","), "verify,authorize", "call order");
  assertEquals(factoryCalls.length, 2, "service and caller clients");
  assertEquals(factoryCalls[0].key, secretKey, "service client key");
  assertEquals(
    factoryCalls[1].options.global?.headers?.authorization,
    `Bearer ${userToken}`,
    "exact user JWT scopes RPC",
  );

  const wrappedServiceFetch = factoryCalls[0].options.global?.fetch;
  assert(wrappedServiceFetch !== undefined, "service fetch is hardened");
  await wrappedServiceFetch("https://project.invalid/storage/v1/object", {
    headers: { authorization: `Bearer ${secretKey}` },
  });
  assertEquals(
    serviceFetchHeaders[0].get("authorization"),
    null,
    "adapter strips opaque secret bearer",
  );
  assertEquals(
    serviceFetchHeaders[0].get("apikey"),
    secretKey,
    "adapter keeps secret apikey",
  );

  const download = await adapter.download("owner/album/item.webp");
  assertEquals(download.kind, "ok", "storage adapter result");
  assert(
    download.kind === "ok" && download.bytes.byteLength === 1024,
    "storage bytes are returned",
  );
});

Deno.test("adapter fails closed before RPC for invalid and unavailable identity checks", async () => {
  const cases: Array<{
    name: string;
    getUser: PrivateAlbumSupabaseClient["auth"]["getUser"];
    expected: "unauthenticated" | "error";
  }> = [
    {
      name: "bad JWT",
      getUser: () =>
        Promise.resolve({
          data: { user: null },
          error: { status: 401, code: "bad_jwt" },
        }),
      expected: "unauthenticated",
    },
    {
      name: "Auth unavailable",
      getUser: () =>
        Promise.resolve({
          data: { user: null },
          error: { status: 503, code: "service_unavailable" },
        }),
      expected: "error",
    },
    {
      name: "network exception",
      getUser: () => Promise.reject(new Error("synthetic network failure")),
      expected: "error",
    },
    {
      name: "malformed success",
      getUser: () => Promise.resolve({ data: { user: null }, error: null }),
      expected: "error",
    },
  ];

  for (const testCase of cases) {
    let callerClientCreations = 0;
    const factory: PrivateAlbumSupabaseClientFactory = (
      _url,
      key,
      _options,
    ) => {
      if (key === secretKey) {
        return {
          ...unavailableClient(),
          auth: { getUser: testCase.getUser },
        };
      }
      callerClientCreations++;
      return unavailableClient();
    };
    const adapter = createPrivateAlbumSupabaseAdapter({
      url: "https://project.invalid",
      publishableKey,
      secretKey,
    }, factory);
    const result = await adapter.authorize(userToken, itemId);
    assertEquals(result.kind, testCase.expected, testCase.name);
    assertEquals(
      callerClientCreations,
      0,
      `${testCase.name} never reaches RPC client`,
    );
  }
});

Deno.test("adapter sanitizes RPC and storage failures", async () => {
  let rpcError: unknown = { message: "PRIVATE_ALBUM_FORBIDDEN" };
  let storageResult: { data: Blob | null; error: unknown } = {
    data: null,
    error: { statusCode: "404", message: "synthetic provider detail" },
  };
  const factory: PrivateAlbumSupabaseClientFactory = (_url, key) => {
    if (key === secretKey) {
      return {
        auth: {
          getUser: () =>
            Promise.resolve({
              data: { user: { id: "synthetic-user" } },
              error: null,
            }),
        },
        rpc: () => Promise.reject(new Error("unexpected service RPC")),
        storage: {
          from: () => ({ download: () => Promise.resolve(storageResult) }),
        },
      };
    }
    return {
      ...unavailableClient(),
      rpc: () => Promise.resolve({ data: null, error: rpcError }),
    };
  };
  const adapter = createPrivateAlbumSupabaseAdapter({
    url: "https://project.invalid",
    publishableKey,
    secretKey,
  }, factory);

  assertEquals(
    (await adapter.authorize(userToken, itemId)).kind,
    "forbidden",
    "domain denial",
  );
  rpcError = { status: 503, message: "database unavailable" };
  assertEquals(
    (await adapter.authorize(userToken, itemId)).kind,
    "error",
    "operational RPC failure",
  );
  assertEquals(
    (await adapter.download("owner/album/item.webp")).kind,
    "not_found",
    "storage 404",
  );
  storageResult = {
    data: null,
    error: { statusCode: 503, message: "storage unavailable" },
  };
  assertEquals(
    (await adapter.download("owner/album/item.webp")).kind,
    "error",
    "storage operational failure",
  );
});
