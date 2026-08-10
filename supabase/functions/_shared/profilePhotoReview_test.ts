import {
  parseReviewDecisionBody,
  parseReviewQueueRows,
} from "../moderation-profile-photos/profilePhotoReview.ts";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("human review accepts only a normalized decision for an owned path", () => {
  const profileId = "11111111-1111-4111-8111-111111111111";
  const parsed = parseReviewDecisionBody({
    profile_id: profileId,
    candidate_path: `${profileId}/22222222-2222-4222-8222-222222222222.jpg`,
    decision: "blocked_adult",
  });
  assert(parsed?.decision === "blocked_adult", "valid decision must be accepted");
});

Deno.test("human review rejects another profile path and extra fields", () => {
  const profileId = "11111111-1111-4111-8111-111111111111";
  const other = "33333333-3333-4333-8333-333333333333";
  assert(parseReviewDecisionBody({
    profile_id: profileId,
    candidate_path: `${other}/22222222-2222-4222-8222-222222222222.jpg`,
    decision: "approved",
  }) === null, "foreign path must be rejected");
  assert(parseReviewDecisionBody({
    profile_id: profileId,
    candidate_path: `${profileId}/22222222-2222-4222-8222-222222222222.jpg`,
    decision: "approved",
    notes: "must not enter audit",
  }) === null, "free-form data must be rejected");
});

Deno.test("review queue parser rejects malformed or oversized responses", () => {
  assert(parseReviewQueueRows([{ profile_id: "bad" }]) === null, "malformed row");
  assert(parseReviewQueueRows(new Array(51).fill({})) === null, "oversized page");
});
