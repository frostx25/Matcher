import {
  type CleanupBatchResult,
  type CleanupConfirmationResult,
  type CleanupDeleteResult,
  createPrivateAlbumCleanupHandler,
  isPrivateAlbumCleanupObjectPath,
} from "./privateAlbumCleanup.ts";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${expected}, received ${actual}`);
  }
}

const endpoint = "https://functions.matcher.invalid/private-album-cleanup";
const token = "header.payload.signature";
const ownerId = "11111111-1111-4111-8111-111111111111";
const albumId = "22222222-2222-4222-8222-222222222222";

function objectPath(itemSuffix: string, extension = "jpg"): string {
  return `${ownerId}/${albumId}/33333333-3333-4333-8333-${itemSuffix}.${extension}`;
}

function cleanupRequest(
  body: unknown = { batch_size: 100 },
  options: {
    url?: string;
    method?: string;
    authorization?: string | null;
    contentType?: string | null;
    origin?: string;
  } = {},
): Request {
  const headers = new Headers();
  if (options.authorization !== null) {
    headers.set(
      "authorization",
      options.authorization ?? `Bearer ${token}`,
    );
  }
  if (options.contentType !== null) {
    headers.set("content-type", options.contentType ?? "application/json");
  }
  if (options.origin) headers.set("origin", options.origin);
  return new Request(options.url ?? endpoint, {
    method: options.method ?? "POST",
    headers,
    body: options.method === "GET" || options.method === "OPTIONS"
      ? undefined
      : typeof body === "string"
      ? body
      : JSON.stringify(body),
  });
}

function testHandler(options: {
  batch?: CleanupBatchResult;
  deleteResult?: CleanupDeleteResult;
  confirmation?: CleanupConfirmationResult;
  trace?: string[];
} = {}) {
  const trace = options.trace ?? [];
  return createPrivateAlbumCleanupHandler({
    getBatch: (_accessToken, batchSize) => {
      trace.push(`batch:${batchSize}`);
      return Promise.resolve(
        options.batch ?? { kind: "ok", objectPaths: [] },
      );
    },
    deleteObject: (path) => {
      trace.push(`delete:${path}`);
      return Promise.resolve(options.deleteResult ?? { kind: "ok" });
    },
    confirmDeleted: (path) => {
      trace.push(`confirm:${path}`);
      return Promise.resolve(
        options.confirmation ?? { kind: "confirmed" },
      );
    },
  });
}

async function assertZeroCountBody(
  response: Response,
  message: string,
): Promise<void> {
  const body = await response.json();
  assertEquals(body.processed, 0, `${message} processed`);
  assertEquals(body.deleted, 0, `${message} deleted`);
  assertEquals(body.failed, 0, `${message} failed`);
  assertEquals(
    Object.keys(body).sort().join(","),
    "deleted,failed,processed",
    message,
  );
}

Deno.test("cleanup path accepts only canonical private album object names", () => {
  for (const extension of ["jpg", "jpeg", "png", "webp"]) {
    assert(
      isPrivateAlbumCleanupObjectPath(
        objectPath("333333333333", extension),
      ),
      `${extension} path is valid`,
    );
  }
  for (
    const path of [
      "../private.jpg",
      `${ownerId}/${albumId}/file.jpg`,
      `${ownerId}/${albumId}/${ownerId}/extra.jpg`,
      `${ownerId}/${albumId}/${ownerId}.gif`,
      `AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA/${albumId}/${ownerId}.jpg`,
      `${ownerId}/${albumId}/${ownerId}.JPG`,
      `${ownerId}/${albumId}/${ownerId}.jpg?download=1`,
      `${ownerId}//${ownerId}.jpg`,
    ]
  ) {
    assert(!isPrivateAlbumCleanupObjectPath(path), `unsafe path: ${path}`);
  }
});

Deno.test("worker accepts POST only and never exposes public CORS", async () => {
  const trace: string[] = [];
  const handler = testHandler({ trace });
  const getResponse = await handler(
    cleanupRequest(undefined, { method: "GET" }),
  );
  const preflight = await handler(cleanupRequest(undefined, {
    method: "OPTIONS",
    origin: "https://app.matcher.example",
  }));

  for (const response of [getResponse, preflight]) {
    assertEquals(response.status, 405, "method status");
    assertEquals(response.headers.get("allow"), "POST", "allow header");
    assertEquals(
      response.headers.get("access-control-allow-origin"),
      null,
      "no CORS",
    );
    assertEquals(
      response.headers.get("cache-control"),
      "private, no-store, max-age=0",
      "no store",
    );
    assertEquals(
      response.headers.get("x-content-type-options"),
      "nosniff",
      "nosniff",
    );
    await assertZeroCountBody(response, "method body");
  }
  assertEquals(trace.length, 0, "unsupported methods do no work");
});

Deno.test("Bearer is required before request body or batch access", async () => {
  const trace: string[] = [];
  const handler = testHandler({ trace });
  const missing = await handler(cleanupRequest("invalid-json", {
    authorization: null,
  }));
  const malformed = await handler(cleanupRequest({ batch_size: 10 }, {
    authorization: "Bearer opaque-token",
  }));

  for (const response of [missing, malformed]) {
    assertEquals(response.status, 401, "auth status");
    assertEquals(
      response.headers.get("www-authenticate"),
      "Bearer",
      "challenge",
    );
    await assertZeroCountBody(response, "auth body");
  }
  assertEquals(trace.length, 0, "invalid auth never requests batch");
});

Deno.test("batch body is strict JSON with an integer from 1 through 100", async () => {
  const trace: string[] = [];
  const handler = testHandler({ trace });
  const cases: Request[] = [
    cleanupRequest({ batch_size: 0 }),
    cleanupRequest({ batch_size: 101 }),
    cleanupRequest({ batch_size: 1.5 }),
    cleanupRequest({ batch_size: 10, extra: true }),
    cleanupRequest({}),
    cleanupRequest("not-json"),
    cleanupRequest({ batch_size: 10 }, { contentType: "text/plain" }),
    cleanupRequest({ batch_size: 10 }, { url: `${endpoint}?batch_size=10` }),
  ];
  for (const request of cases) {
    const response = await handler(request);
    assertEquals(response.status, 400, "invalid body status");
    await assertZeroCountBody(response, "invalid body");
  }
  assertEquals(trace.length, 0, "invalid input never requests batch");

  const minimum = await handler(cleanupRequest({ batch_size: 1 }));
  const maximum = await handler(cleanupRequest({ batch_size: 100 }));
  assertEquals(minimum.status, 200, "minimum accepted");
  assertEquals(maximum.status, 200, "maximum accepted");
  assertEquals(trace.join(","), "batch:1,batch:100", "exact RPC sizes");
});

Deno.test("ordinary caller fails before any privileged dependency", async () => {
  const trace: string[] = [];
  const handler = createPrivateAlbumCleanupHandler({
    getBatch: () => {
      trace.push("caller-rpc");
      return Promise.resolve({ kind: "forbidden" });
    },
    deleteObject: () => {
      trace.push("service-delete");
      throw new Error("must not run");
    },
    confirmDeleted: () => {
      trace.push("service-confirm");
      throw new Error("must not run");
    },
  });

  const response = await handler(cleanupRequest({ batch_size: 25 }));
  assertEquals(response.status, 403, "service-only status");
  await assertZeroCountBody(response, "forbidden body");
  assertEquals(trace.join(","), "caller-rpc", "caller RPC is the only call");
});

Deno.test("authorization failures remain sanitized and do not use service role", async () => {
  for (
    const batch of [
      { kind: "unauthenticated" } as const,
      { kind: "error" } as const,
    ]
  ) {
    const trace: string[] = [];
    const response = await testHandler({ batch, trace })(cleanupRequest());
    assertEquals(
      response.status,
      batch.kind === "unauthenticated" ? 401 : 503,
      `${batch.kind} status`,
    );
    await assertZeroCountBody(response, `${batch.kind} body`);
    assertEquals(trace.join(","), "batch:100", "no privileged call");
  }
});

Deno.test("each item is validated, deleted and confirmed with count-only output", async () => {
  const successful = objectPath("333333333331", "webp");
  const unsafe = "../not-an-object.jpg";
  const deleteFailure = objectPath("333333333332", "jpg");
  const pendingConfirmation = objectPath("333333333333", "png");
  const confirmationFailure = objectPath("333333333334", "jpeg");
  const paths = [
    successful,
    unsafe,
    deleteFailure,
    pendingConfirmation,
    confirmationFailure,
  ];
  const trace: string[] = [];
  const handler = createPrivateAlbumCleanupHandler({
    getBatch: (_token, batchSize) => {
      trace.push(`batch:${batchSize}`);
      return Promise.resolve({ kind: "ok", objectPaths: paths });
    },
    deleteObject: (path) => {
      trace.push(`delete:${path}`);
      return Promise.resolve(
        path === deleteFailure ? { kind: "error" } : { kind: "ok" },
      );
    },
    confirmDeleted: (path) => {
      trace.push(`confirm:${path}`);
      if (path === confirmationFailure) throw new Error("provider detail");
      return Promise.resolve(
        path === pendingConfirmation
          ? { kind: "pending" }
          : { kind: "confirmed" },
      );
    },
  });

  const response = await handler(cleanupRequest({ batch_size: 5 }));
  const responseText = await response.text();
  const counts = JSON.parse(responseText);
  assertEquals(response.status, 200, "batch status");
  assertEquals(counts.processed, 5, "processed count");
  assertEquals(counts.deleted, 1, "deleted count");
  assertEquals(counts.failed, 4, "failed count");
  assertEquals(
    Object.keys(counts).sort().join(","),
    "deleted,failed,processed",
    "count-only keys",
  );
  for (const path of paths) {
    assert(!responseText.includes(path), "object path is not disclosed");
  }
  assert(!responseText.includes("private-albums"), "bucket is not disclosed");
  assert(!responseText.includes("provider detail"), "exception is sanitized");
  assertEquals(trace[0], "batch:5", "caller RPC happens first");
  assert(
    !trace.includes(`delete:${unsafe}`),
    "unsafe path never reaches service deletion",
  );
  assert(
    !trace.includes(`confirm:${deleteFailure}`),
    "failed deletion is not confirmed",
  );
});

Deno.test("empty and repeated cleanup batches are idempotent", async () => {
  const emptyTrace: string[] = [];
  const empty = await testHandler({ trace: emptyTrace })(
    cleanupRequest({ batch_size: 10 }),
  );
  assertEquals(empty.status, 200, "empty batch status");
  await assertZeroCountBody(empty, "empty batch");
  assertEquals(
    emptyTrace.join(","),
    "batch:10",
    "no service call for empty batch",
  );

  const path = objectPath("333333333335", "webp");
  const handler = testHandler({
    batch: { kind: "ok", objectPaths: [path] },
  });
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await handler(cleanupRequest({ batch_size: 1 }));
    const counts = await response.json();
    assertEquals(response.status, 200, `repeat ${attempt} status`);
    assertEquals(counts.processed, 1, `repeat ${attempt} processed`);
    assertEquals(counts.deleted, 1, `repeat ${attempt} deleted`);
    assertEquals(counts.failed, 0, `repeat ${attempt} failed`);
  }
});

Deno.test("oversized or duplicate RPC batches fail closed before deletion", async () => {
  const path = objectPath("333333333336", "jpg");
  for (
    const objectPaths of [[path, objectPath("333333333337")], [path, path]]
  ) {
    const trace: string[] = [];
    const response = await testHandler({
      batch: { kind: "ok", objectPaths },
      trace,
    })(cleanupRequest({ batch_size: 1 }));
    assertEquals(response.status, 503, "invalid RPC batch status");
    await assertZeroCountBody(response, "invalid RPC batch");
    assertEquals(trace.join(","), "batch:1", "no service role call");
  }
});
