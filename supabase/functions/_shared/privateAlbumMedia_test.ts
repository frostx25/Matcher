import {
  type AlbumAuthorizationResult,
  type AlbumDownloadResult,
  createPrivateAlbumMediaHandler,
  extractPrivateAlbumBearer,
  parseAllowedOrigins,
} from "./privateAlbumMedia.ts";

function fromBase64(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}

const validWebp = fromBase64(
  "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89",
);
const validPng = fromBase64(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
);

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${expected}, received ${actual}`);
  }
}

const itemId = "11111111-1111-4111-8111-111111111111";
const itemUrl =
  `https://functions.matcher.invalid/private-album-media?item_id=${itemId}`;
const token = "header.payload.signature";

function authorizedRequest(
  method = "GET",
  url = itemUrl,
  headers: HeadersInit = {},
): Request {
  return new Request(url, {
    method,
    headers: { "authorization": `Bearer ${token}`, ...headers },
  });
}

function testHandler(options: {
  authorization?: AlbumAuthorizationResult;
  download?: AlbumDownloadResult;
  allowedOrigins?: ReadonlySet<string>;
  counters?: { authorize: number; download: number };
} = {}) {
  const counters = options.counters ?? { authorize: 0, download: 0 };
  return createPrivateAlbumMediaHandler({
    allowedOrigins: options.allowedOrigins,
    authorize: (_accessToken, _itemId) => {
      counters.authorize++;
      return Promise.resolve(
        options.authorization ?? {
          kind: "authorized",
          objectPath: "owner/album/item.webp",
          mimeType: "image/webp",
        },
      );
    },
    download: (_objectPath) => {
      counters.download++;
      return Promise.resolve(
        options.download ?? {
          kind: "ok",
          bytes: validWebp,
          mimeType: "image/webp",
        },
      );
    },
  });
}

Deno.test("private album bearer parser accepts one strict JWT only", () => {
  assertEquals(
    extractPrivateAlbumBearer(authorizedRequest()),
    token,
    "valid bearer",
  );
  assertEquals(
    extractPrivateAlbumBearer(new Request(itemUrl)),
    null,
    "missing bearer",
  );
  assertEquals(
    extractPrivateAlbumBearer(
      new Request(itemUrl, { headers: { authorization: "bearer abc" } }),
    ),
    null,
    "scheme is case-sensitive",
  );
  assertEquals(
    extractPrivateAlbumBearer(
      new Request(itemUrl, {
        headers: { authorization: "Bearer not-a-jwt" },
      }),
    ),
    null,
    "opaque malformed input is rejected",
  );
});

Deno.test("origin parser keeps exact HTTPS and loopback origins only", () => {
  const origins = parseAllowedOrigins(
    "https://app.matcher.example, https://app.matcher.example/path, " +
      "http://localhost:3000, http://insecure.example, invalid",
  );
  assert(origins.has("https://app.matcher.example"), "https origin");
  assert(origins.has("http://localhost:3000"), "local test origin");
  assertEquals(origins.size, 2, "unsafe origins are ignored");
});

Deno.test("unsupported methods are rejected before authorization", async () => {
  const counters = { authorize: 0, download: 0 };
  const response = await testHandler({ counters })(authorizedRequest("POST"));
  assertEquals(response.status, 405, "method status");
  assertEquals(response.headers.get("allow"), "GET, HEAD, OPTIONS", "allow");
  assertEquals(counters.authorize, 0, "no authorization call");
  assertEquals(counters.download, 0, "no download call");
});

Deno.test("item query must contain one UUID and no extra parameters", async () => {
  const handler = testHandler();
  const missing = await handler(
    authorizedRequest(
      "GET",
      "https://functions.matcher.invalid/private-album-media",
    ),
  );
  const malformed = await handler(
    authorizedRequest(
      "GET",
      "https://functions.matcher.invalid/private-album-media?item_id=bad",
    ),
  );
  const extra = await handler(
    authorizedRequest("GET", `${itemUrl}&object_path=forbidden`),
  );
  assertEquals(missing.status, 400, "missing item id");
  assertEquals(malformed.status, 400, "malformed item id");
  assertEquals(extra.status, 400, "extra query input");
});

Deno.test("missing or malformed bearer maps to sanitized 401", async () => {
  const handler = testHandler();
  const missing = await handler(new Request(itemUrl));
  const malformed = await handler(
    new Request(itemUrl, {
      headers: { authorization: "Bearer invalid" },
    }),
  );
  for (const response of [missing, malformed]) {
    assertEquals(response.status, 401, "auth status");
    assertEquals(
      response.headers.get("www-authenticate"),
      "Bearer",
      "challenge",
    );
    assertEquals((await response.json()).code, "AUTH_REQUIRED", "safe code");
  }
});

Deno.test("authorization outcomes map without provider details", async () => {
  const cases: Array<[AlbumAuthorizationResult, number, string]> = [
    [{ kind: "unauthenticated" }, 401, "AUTH_REQUIRED"],
    [{ kind: "forbidden" }, 403, "ACCESS_DENIED"],
    [{ kind: "not_found" }, 404, "NOT_FOUND"],
    [{ kind: "error" }, 503, "BACKEND_UNAVAILABLE"],
  ];
  for (const [authorization, status, code] of cases) {
    const response = await testHandler({ authorization })(authorizedRequest());
    const body = await response.text();
    assertEquals(response.status, status, `status for ${authorization.kind}`);
    assert(body.includes(code), `safe code for ${authorization.kind}`);
    assert(!body.includes("objectPath"), "path is never disclosed");
    assert(!body.includes("private-albums"), "bucket is never disclosed");
  }
});

Deno.test("GET returns private bytes with strict no-store headers", async () => {
  const response = await testHandler()(authorizedRequest());
  assertEquals(response.status, 200, "download status");
  assertEquals(response.headers.get("content-type"), "image/webp", "mime");
  assertEquals(
    response.headers.get("cache-control"),
    "private, no-store, max-age=0",
    "cache control",
  );
  assertEquals(response.headers.get("pragma"), "no-cache", "legacy cache");
  assertEquals(
    response.headers.get("x-content-type-options"),
    "nosniff",
    "nosniff",
  );
  assertEquals(response.headers.get("location"), null, "no redirect");
  assertEquals(response.redirected, false, "response is not redirected");
  const bytes = new Uint8Array(await response.arrayBuffer());
  assertEquals(bytes.length, validWebp.length, "exact private byte count");
  assertEquals(
    bytes.every((value, index) => value === validWebp[index]),
    true,
    "exact private bytes",
  );
});

Deno.test("HEAD reauthorizes and downloads metadata but emits no body", async () => {
  const counters = { authorize: 0, download: 0 };
  const response = await testHandler({ counters })(authorizedRequest("HEAD"));
  assertEquals(response.status, 200, "head status");
  assertEquals(
    response.headers.get("content-length"),
    validWebp.length.toString(),
    "head length",
  );
  assertEquals((await response.arrayBuffer()).byteLength, 0, "head body");
  assertEquals(counters.authorize, 1, "head reauthorizes");
  assertEquals(counters.download, 1, "head validates current object");
});

Deno.test("unsafe authorization path fails closed before service download", async () => {
  const counters = { authorize: 0, download: 0 };
  const response = await testHandler({
    counters,
    authorization: {
      kind: "authorized",
      objectPath: "../secret.webp",
      mimeType: "image/webp",
    },
  })(authorizedRequest());
  assertEquals(response.status, 503, "invalid path status");
  assertEquals(counters.download, 0, "unsafe path never reaches service role");
});

Deno.test("only matching JPEG PNG or WebP MIME reaches the response", async () => {
  const unsupported = await testHandler({
    authorization: {
      kind: "authorized",
      objectPath: "owner/album/item.gif",
      mimeType: "image/gif",
    },
    download: {
      kind: "ok",
      bytes: new Uint8Array([1]),
      mimeType: "image/gif",
    },
  })(authorizedRequest());
  assertEquals(unsupported.status, 503, "unapproved RPC mime fails closed");

  const mismatch = await testHandler({
    authorization: {
      kind: "authorized",
      objectPath: "owner/album/item.jpg",
      mimeType: "image/jpeg",
    },
    download: {
      kind: "ok",
      bytes: new Uint8Array([1]),
      mimeType: "image/png",
    },
  })(authorizedRequest());
  assertEquals(mismatch.status, 415, "storage MIME mismatch");
  assertEquals(
    (await mismatch.json()).code,
    "UNSUPPORTED_MEDIA_TYPE",
    "safe mismatch code",
  );
});

Deno.test("matching metadata cannot disguise a different binary format", async () => {
  const response = await testHandler({
    download: {
      kind: "ok",
      bytes: validPng,
      mimeType: "image/webp",
    },
  })(authorizedRequest());
  const body = await response.text();
  assertEquals(response.status, 415, "magic mismatch status");
  assert(body.includes("UNSUPPORTED_MEDIA_TYPE"), "sanitized media code");
  assert(!body.includes("owner/album"), "path is not disclosed");
  assert(!body.includes(validPng.join(",")), "bytes are not disclosed");
});

Deno.test("invalid storage size is sanitized before a response", async () => {
  const response = await testHandler({ download: { kind: "invalid" } })(
    authorizedRequest(),
  );
  assertEquals(response.status, 415, "invalid size status");
  assertEquals(
    (await response.json()).code,
    "UNSUPPORTED_MEDIA_TYPE",
    "safe invalid size code",
  );
});

Deno.test("HEAD validates invalid bytes and never emits an error body", async () => {
  const counters = { authorize: 0, download: 0 };
  const response = await testHandler({
    counters,
    download: {
      kind: "ok",
      bytes: validPng.slice(0, validPng.length - 1),
      mimeType: "image/webp",
    },
  })(authorizedRequest("HEAD"));
  assertEquals(response.status, 415, "invalid HEAD status");
  assertEquals((await response.arrayBuffer()).byteLength, 0, "no error body");
  assertEquals(counters.authorize, 1, "HEAD reauthorizes");
  assertEquals(counters.download, 1, "HEAD validates downloaded bytes");
});

Deno.test("storage missing and failure map to sanitized statuses", async () => {
  const missing = await testHandler({ download: { kind: "not_found" } })(
    authorizedRequest(),
  );
  const failed = await testHandler({ download: { kind: "error" } })(
    authorizedRequest(),
  );
  assertEquals(missing.status, 404, "missing object");
  assertEquals((await missing.json()).code, "NOT_FOUND", "missing code");
  assertEquals(failed.status, 503, "storage failure");
  assertEquals(
    (await failed.json()).code,
    "BACKEND_UNAVAILABLE",
    "failure code",
  );
});

Deno.test("CORS reflects exact allowlisted origins and never wildcard", async () => {
  const origin = "https://app.matcher.example";
  const counters = { authorize: 0, download: 0 };
  const handler = testHandler({
    counters,
    allowedOrigins: new Set([origin]),
  });

  const denied = await handler(authorizedRequest("GET", itemUrl, {
    origin: "https://evil.example",
  }));
  assertEquals(denied.status, 403, "unlisted origin");
  assertEquals(
    denied.headers.get("access-control-allow-origin"),
    null,
    "denied origin is not reflected",
  );
  assertEquals(counters.authorize, 0, "CORS denial happens first");

  const allowed = await handler(authorizedRequest("GET", itemUrl, { origin }));
  assertEquals(allowed.status, 200, "allowlisted origin");
  assertEquals(
    allowed.headers.get("access-control-allow-origin"),
    origin,
    "exact origin reflection",
  );
  assert(
    allowed.headers.get("access-control-allow-origin") !== "*",
    "wildcard is forbidden",
  );
});

Deno.test("CORS preflight is narrow and never downloads media", async () => {
  const origin = "https://app.matcher.example";
  const counters = { authorize: 0, download: 0 };
  const handler = testHandler({
    counters,
    allowedOrigins: new Set([origin]),
  });
  const allowed = await handler(
    new Request(itemUrl, {
      method: "OPTIONS",
      headers: {
        origin,
        "access-control-request-method": "GET",
        "access-control-request-headers": "authorization",
      },
    }),
  );
  assertEquals(allowed.status, 204, "preflight status");
  assertEquals(counters.authorize, 0, "preflight does not authorize");
  assertEquals(counters.download, 0, "preflight does not download");

  const rejected = await handler(
    new Request(itemUrl, {
      method: "OPTIONS",
      headers: {
        origin,
        "access-control-request-method": "POST",
      },
    }),
  );
  assertEquals(rejected.status, 405, "non-read preflight is rejected");
});
