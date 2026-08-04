import {
  classifySafeSearch,
  extractSafeSearchAnnotation,
  isChatModerationLease,
} from "./chatMediaModeration.ts";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const safe = { adult: "UNLIKELY", racy: "VERY_UNLIKELY", violence: "UNLIKELY" };

Deno.test("SafeSearch approves only clearly safe annotations", () => {
  assert(classifySafeSearch(safe) === "approved", "safe approved");
  assert(classifySafeSearch({ ...safe, adult: "POSSIBLE" }) === "review", "possible review");
  assert(classifySafeSearch({ ...safe, adult: "UNKNOWN" }) === "review", "unknown review");
  assert(classifySafeSearch({ ...safe, racy: "LIKELY" }) === "adult", "racy adult");
  assert(classifySafeSearch({ ...safe, adult: "VERY_LIKELY" }) === "adult", "adult blocked");
  assert(classifySafeSearch({ ...safe, violence: "LIKELY" }) === "abusive", "violence blocked");
  assert(classifySafeSearch({ adult: "UNLIKELY" }) === "review", "missing review");
});

Deno.test("Vision response extraction fails closed", () => {
  assert(extractSafeSearchAnnotation({ responses: [{ safeSearchAnnotation: safe }] }) === safe, "annotation");
  assert(extractSafeSearchAnnotation({ responses: [] }) === null, "empty");
  assert(extractSafeSearchAnnotation({ responses: [{ error: { code: 7 } }] }) === null, "provider error");
});

Deno.test("moderation lease accepts only canonical private chat paths", () => {
  const valid = {
    messageId: "11111111-1111-4111-8111-111111111111",
    leaseToken: "22222222-2222-4222-8222-222222222222",
    objectPath: "33333333-3333-4333-8333-333333333333/44444444-4444-4444-8444-444444444444/55555555-5555-4555-8555-555555555555.jpg",
    mimeType: "image/jpeg",
  };
  assert(isChatModerationLease(valid), "valid lease");
  assert(!isChatModerationLease({ ...valid, objectPath: "../private.jpg" }), "unsafe path");
  assert(!isChatModerationLease({ ...valid, mimeType: "image/gif" }), "unsafe type");
});
