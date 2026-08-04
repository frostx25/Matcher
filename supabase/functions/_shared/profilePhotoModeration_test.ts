import { isProfilePhotoModerationLease } from "./profilePhotoModeration.ts";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("profile moderation lease accepts only canonical owner paths", () => {
  const valid = {
    profileId: "11111111-1111-4111-8111-111111111111",
    leaseToken: "22222222-2222-4222-8222-222222222222",
    objectPath: "11111111-1111-4111-8111-111111111111/33333333-3333-4333-8333-333333333333.jpg",
    mimeType: "image/jpeg",
  };
  assert(isProfilePhotoModerationLease(valid), "valid profile-photo lease");
  assert(!isProfilePhotoModerationLease({ ...valid, objectPath: "../private.jpg" }), "unsafe path");
  assert(!isProfilePhotoModerationLease({ ...valid, mimeType: "image/gif" }), "unsafe MIME");
});
