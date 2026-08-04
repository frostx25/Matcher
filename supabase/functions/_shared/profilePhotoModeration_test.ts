import {
  extractOpenAIModerationDecision,
  isProfilePhotoModerationLease,
} from "../profile-photo-moderation/profilePhotoModeration.ts";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("profile moderation lease accepts only canonical owner paths", () => {
  const valid = {
    profileId: "11111111-1111-4111-8111-111111111111",
    leaseToken: "22222222-2222-4222-8222-222222222222",
    objectPath:
      "11111111-1111-4111-8111-111111111111/33333333-3333-4333-8333-333333333333.jpg",
    mimeType: "image/jpeg",
  };
  assert(isProfilePhotoModerationLease(valid), "valid profile-photo lease");
  assert(
    !isProfilePhotoModerationLease({ ...valid, objectPath: "../private.jpg" }),
    "unsafe path",
  );
  assert(
    !isProfilePhotoModerationLease({ ...valid, mimeType: "image/gif" }),
    "unsafe MIME",
  );
});

function response(
  categories: Record<string, boolean>,
  flagged = Object.values(categories).some(Boolean),
) {
  return {
    results: [{ flagged, categories, category_scores: { sexual: 0.999 } }],
  };
}

const safeCategories = {
  sexual: false,
  "sexual/minors": false,
  violence: false,
  "violence/graphic": false,
  harassment: false,
};

Deno.test("OpenAI moderation approves a consistent unflagged image", () => {
  assert(
    extractOpenAIModerationDecision(response(safeCategories)) === "approved",
    "safe image should be approved",
  );
});

Deno.test("OpenAI moderation maps sexual content to adult", () => {
  assert(
    extractOpenAIModerationDecision(
      response({ ...safeCategories, sexual: true }),
    ) === "adult",
    "sexual category should be adult",
  );
});

Deno.test("OpenAI moderation gives abusive categories precedence", () => {
  assert(
    extractOpenAIModerationDecision(
      response({ ...safeCategories, sexual: true, violence: true }),
    ) ===
      "abusive",
    "violence should be abusive even when sexual is also true",
  );
  assert(
    extractOpenAIModerationDecision(
      response({ ...safeCategories, "sexual/minors": true }),
    ) ===
      "abusive",
    "sexual minors should be abusive",
  );
  assert(
    extractOpenAIModerationDecision(
      response({ ...safeCategories, "violence/graphic": true }),
    ) ===
      "abusive",
    "graphic violence should be abusive",
  );
});

Deno.test("OpenAI moderation routes another flagged category to review", () => {
  assert(
    extractOpenAIModerationDecision(
      response({ ...safeCategories, harassment: true }),
    ) === "review",
    "another flagged category should require review",
  );
});

Deno.test("OpenAI moderation rejects malformed or inconsistent responses", () => {
  assert(extractOpenAIModerationDecision(null) === null, "null response");
  assert(
    extractOpenAIModerationDecision({ results: [] }) === null,
    "missing result",
  );
  assert(
    extractOpenAIModerationDecision(response(safeCategories, true)) === null,
    "flagged must agree with categories",
  );
  const { violence: _violence, ...missingRequired } = safeCategories;
  assert(
    extractOpenAIModerationDecision(response(missingRequired)) === null,
    "required category must exist",
  );
});
