import {
  type AlbumFinalizationResult,
  createPrivateAlbumDeleteHandler,
  type DeleteAuthorizationResult,
  type DeleteObjectResult,
  parsePrivateAlbumDeleteObjectPath,
} from "./privateAlbumDelete.ts";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${expected}, received ${actual}`);
  }
}

const endpoint = "https://functions.matcher.invalid/private-album-delete";
const token = "header.payload.signature";
const ownerId = "11111111-1111-4111-8111-111111111111";
const albumId = "22222222-2222-4222-8222-222222222222";
const itemId = "33333333-3333-4333-8333-333333333333";
const anotherItemId = "44444444-4444-4444-8444-444444444444";

function objectPath(id = itemId, extension = "jpg"): string {
  return `${ownerId}/${albumId}/${id}.${extension}`;
}

function deleteRequest(
  body: unknown = { item_id: itemId },
  options: {
    method?: string;
    url?: string;
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
  const method = options.method ?? "POST";
  return new Request(options.url ?? endpoint, {
    method,
    headers,
    body: method === "GET" || method === "OPTIONS"
      ? undefined
      : typeof body === "string"
      ? body
      : JSON.stringify(body),
  });
}

function testHandler(options: {
  marked?: DeleteAuthorizationResult<string>;
  begun?: DeleteAuthorizationResult<string[]>;
  removal?: DeleteObjectResult;
  finalized?: AlbumFinalizationResult;
  trace?: string[];
} = {}) {
  const trace = options.trace ?? [];
  return createPrivateAlbumDeleteHandler({
    markItem: (accessToken, id) => {
      trace.push(`mark:${accessToken}:${id}`);
      return Promise.resolve(
        options.marked ?? {
          kind: "authorized",
          value: objectPath(id),
        },
      );
    },
    beginAlbum: (accessToken) => {
      trace.push(`begin:${accessToken}`);
      return Promise.resolve(
        options.begun ?? { kind: "authorized", value: [] },
      );
    },
    removeObject: (path) => {
      trace.push(`remove:${path}`);
      return Promise.resolve(options.removal ?? { kind: "ok" });
    },
    finalizeAlbum: (accessToken) => {
      trace.push(`finalize:${accessToken}`);
      return Promise.resolve(
        options.finalized ?? { kind: "finalized" },
      );
    },
  });
}

async function assertDeletedBody(
  response: Response,
  expected: boolean,
  message: string,
): Promise<string> {
  const text = await response.text();
  const body = JSON.parse(text);
  assertEquals(body.deleted, expected, `${message} value`);
  assertEquals(Object.keys(body).join(","), "deleted", `${message} keys`);
  return text;
}

Deno.test("delete path parser accepts canonical image paths and maps MIME", () => {
  const expectedMimes = new Map([
    ["jpg", "image/jpeg"],
    ["jpeg", "image/jpeg"],
    ["png", "image/png"],
    ["webp", "image/webp"],
  ]);
  for (const [extension, mime] of expectedMimes) {
    const parsed = parsePrivateAlbumDeleteObjectPath(
      objectPath(itemId, extension),
    );
    assert(parsed !== null, `${extension} is accepted`);
    assertEquals(parsed.ownerId, ownerId, "owner binding");
    assertEquals(parsed.albumId, albumId, "album binding");
    assertEquals(parsed.itemId, itemId, "item binding");
    assertEquals(parsed.mimeType, mime, `${extension} MIME`);
  }

  for (
    const unsafe of [
      "../object.jpg",
      `${ownerId}/${albumId}/object.jpg`,
      `${ownerId}/${albumId}/${itemId}.gif`,
      `${ownerId}/${albumId}/${itemId}.JPG`,
      `${ownerId}/${albumId}/${itemId}.jpg?download=1`,
      `${ownerId}/${albumId}/${itemId}.jpg/extra`,
      `AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA/${albumId}/${itemId}.jpg`,
    ]
  ) {
    assertEquals(
      parsePrivateAlbumDeleteObjectPath(unsafe),
      null,
      `unsafe path ${unsafe}`,
    );
  }
});

Deno.test("endpoint accepts POST only and emits no public CORS", async () => {
  const trace: string[] = [];
  const handler = testHandler({ trace });
  const responses = [
    await handler(deleteRequest(undefined, { method: "GET" })),
    await handler(deleteRequest(undefined, {
      method: "OPTIONS",
      origin: "https://app.matcher.example",
    })),
  ];
  for (const response of responses) {
    assertEquals(response.status, 405, "method status");
    assertEquals(response.headers.get("allow"), "POST", "allow header");
    assertEquals(
      response.headers.get("access-control-allow-origin"),
      null,
      "no CORS header",
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
    await assertDeletedBody(response, false, "method body");
  }
  assertEquals(trace.length, 0, "unsupported method does no work");
});

Deno.test("Bearer is required before parsing or authorization", async () => {
  const trace: string[] = [];
  const handler = testHandler({ trace });
  const responses = [
    await handler(deleteRequest("invalid-json", { authorization: null })),
    await handler(deleteRequest({ item_id: itemId }, {
      authorization: "Bearer opaque-token",
    })),
  ];
  for (const response of responses) {
    assertEquals(response.status, 401, "auth status");
    assertEquals(
      response.headers.get("www-authenticate"),
      "Bearer",
      "challenge",
    );
    await assertDeletedBody(response, false, "auth body");
  }
  assertEquals(trace.length, 0, "invalid token reaches no dependency");
});

Deno.test("request contains exactly one valid delete action", async () => {
  const trace: string[] = [];
  const handler = testHandler({ trace });
  const invalidRequests = [
    deleteRequest({}),
    deleteRequest({ item_id: "invalid" }),
    deleteRequest({ delete_album: false }),
    deleteRequest({ item_id: itemId, delete_album: true }),
    deleteRequest({ delete_album: true, extra: true }),
    deleteRequest("not-json"),
    deleteRequest({ delete_album: true }, { contentType: "text/plain" }),
    deleteRequest({ delete_album: true }, { url: `${endpoint}?confirm=true` }),
  ];
  for (const request of invalidRequests) {
    const response = await handler(request);
    assertEquals(response.status, 400, "invalid action status");
    await assertDeletedBody(response, false, "invalid action body");
  }
  assertEquals(trace.length, 0, "invalid action is never authorized");
});

Deno.test("item authorization precedes exact service-role removal", async () => {
  const trace: string[] = [];
  const path = objectPath();
  const response = await testHandler({ trace })(deleteRequest());
  const body = await assertDeletedBody(response, true, "item success");

  assertEquals(response.status, 200, "item status");
  assertEquals(
    trace.join(","),
    `mark:${token}:${itemId},remove:${path}`,
    "authorized order and exact path",
  );
  assert(!body.includes(path), "path is not returned");
  assert(!body.includes("private-albums"), "bucket is not returned");
  assert(!body.includes("http"), "URL is not returned");
});

Deno.test("missing item is idempotent and never reaches service role", async () => {
  const trace: string[] = [];
  const response = await testHandler({
    marked: { kind: "not_found" },
    trace,
  })(deleteRequest());
  assertEquals(response.status, 200, "idempotent status");
  await assertDeletedBody(response, true, "idempotent item");
  assertEquals(
    trace.join(","),
    `mark:${token}:${itemId}`,
    "not found does not remove",
  );
});

Deno.test("item authorization failures never reach service role", async () => {
  const cases: Array<[DeleteAuthorizationResult<string>, number]> = [
    [{ kind: "unauthenticated" }, 401],
    [{ kind: "forbidden" }, 403],
    [{ kind: "error" }, 503],
  ];
  for (const [marked, status] of cases) {
    const trace: string[] = [];
    const response = await testHandler({ marked, trace })(deleteRequest());
    assertEquals(response.status, status, `${marked.kind} status`);
    await assertDeletedBody(response, false, `${marked.kind} body`);
    assertEquals(trace.length, 1, `${marked.kind} only marks`);
    assert(trace[0].startsWith("mark:"), `${marked.kind} caller RPC first`);
  }
});

Deno.test("item path must be canonical and bound to requested UUID", async () => {
  for (
    const value of [
      "../private.jpg",
      objectPath(anotherItemId),
      objectPath(itemId, "gif"),
    ]
  ) {
    const trace: string[] = [];
    const response = await testHandler({
      marked: { kind: "authorized", value },
      trace,
    })(deleteRequest());
    assertEquals(response.status, 503, "invalid path status");
    await assertDeletedBody(response, false, "invalid path body");
    assertEquals(trace.length, 1, "invalid path is never removed");
  }
});

Deno.test("item removal failure is retryable and sanitized", async () => {
  for (const throws of [false, true]) {
    const trace: string[] = [];
    const handler = createPrivateAlbumDeleteHandler({
      markItem: () =>
        Promise.resolve({
          kind: "authorized",
          value: objectPath(),
        }),
      beginAlbum: () => Promise.resolve({ kind: "authorized", value: [] }),
      removeObject: () => {
        trace.push("remove");
        if (throws) throw new Error("sensitive storage detail");
        return Promise.resolve({ kind: "error" });
      },
      finalizeAlbum: () => Promise.resolve({ kind: "finalized" }),
    });
    const response = await handler(deleteRequest());
    const body = await assertDeletedBody(response, false, "remove failure");
    assertEquals(response.status, 503, "remove failure status");
    assert(!body.includes("sensitive"), "provider detail is sanitized");
    assertEquals(trace.length, 1, "one removal attempt");
  }
});

Deno.test("album removes only authorized canonical paths then finalizes", async () => {
  const first = objectPath(itemId, "jpg");
  const second = objectPath(anotherItemId, "webp");
  const trace: string[] = [];
  const response = await testHandler({
    begun: { kind: "authorized", value: [first, second] },
    trace,
  })(deleteRequest({ delete_album: true }));
  const body = await assertDeletedBody(response, true, "album success");

  assertEquals(response.status, 200, "album status");
  assertEquals(
    trace.join(","),
    `begin:${token},remove:${first},remove:${second},finalize:${token}`,
    "album operation order",
  );
  assert(!body.includes(first), "first path is hidden");
  assert(!body.includes(second), "second path is hidden");
});

Deno.test("empty album deletion remains idempotent and still finalizes", async () => {
  const trace: string[] = [];
  const handler = testHandler({ trace });
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await handler(deleteRequest({ delete_album: true }));
    assertEquals(response.status, 200, `attempt ${attempt} status`);
    await assertDeletedBody(response, true, `attempt ${attempt}`);
  }
  assertEquals(
    trace.join(","),
    `begin:${token},finalize:${token},begin:${token},finalize:${token}`,
    "repeated empty deletion",
  );
});

Deno.test("invalid album RPC paths fail closed before privileged calls", async () => {
  const valid = objectPath();
  const anotherAlbumPath =
    `${ownerId}/66666666-6666-4666-8666-666666666666/${anotherItemId}.jpg`;
  const tooMany = Array.from(
    { length: 11 },
    (_, index) =>
      objectPath(
        `55555555-5555-4555-8555-${index.toString().padStart(12, "0")}`,
      ),
  );
  for (
    const value of [
      ["../private.jpg"],
      [valid, valid],
      [valid, anotherAlbumPath],
      tooMany,
    ]
  ) {
    const trace: string[] = [];
    const response = await testHandler({
      begun: { kind: "authorized", value },
      trace,
    })(deleteRequest({ delete_album: true }));
    assertEquals(response.status, 503, "invalid album paths status");
    await assertDeletedBody(response, false, "invalid album paths");
    assertEquals(trace.join(","), `begin:${token}`, "no privileged call");
  }
});

Deno.test("album attempts all removals but never finalizes a partial failure", async () => {
  const first = objectPath();
  const second = objectPath(anotherItemId);
  const trace: string[] = [];
  const handler = createPrivateAlbumDeleteHandler({
    markItem: () => Promise.resolve({ kind: "not_found" }),
    beginAlbum: () => {
      trace.push("begin");
      return Promise.resolve({ kind: "authorized", value: [first, second] });
    },
    removeObject: (path) => {
      trace.push(`remove:${path}`);
      return Promise.resolve(
        path === first ? { kind: "error" } : { kind: "ok" },
      );
    },
    finalizeAlbum: () => {
      trace.push("finalize");
      return Promise.resolve({ kind: "finalized" });
    },
  });

  const response = await handler(deleteRequest({ delete_album: true }));
  assertEquals(response.status, 503, "partial failure status");
  await assertDeletedBody(response, false, "partial failure body");
  assertEquals(
    trace.join(","),
    `begin,remove:${first},remove:${second}`,
    "all removals attempted without finalize",
  );
});

Deno.test("album finalization outcomes remain sanitized", async () => {
  const cases: Array<[AlbumFinalizationResult, number]> = [
    [{ kind: "pending" }, 503],
    [{ kind: "unauthenticated" }, 401],
    [{ kind: "forbidden" }, 403],
    [{ kind: "error" }, 503],
  ];
  for (const [finalized, status] of cases) {
    const response = await testHandler({ finalized })(
      deleteRequest({ delete_album: true }),
    );
    assertEquals(response.status, status, `${finalized.kind} status`);
    await assertDeletedBody(response, false, `${finalized.kind} body`);
  }
});
