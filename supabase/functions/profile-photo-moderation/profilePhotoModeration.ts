export type ProfilePhotoModerationLease = {
  profileId: string;
  leaseToken: string;
  objectPath: string;
  mimeType: "image/jpeg" | "image/png" | "image/webp";
};

export type ProfilePhotoModerationDecision =
  | "approved"
  | "adult"
  | "abusive"
  | "review";

const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PROFILE_PATH = /^[0-9a-f-]{36}\/[0-9a-f-]{36}\.(jpg|jpeg|png|webp)$/;

export function isProfilePhotoModerationLease(
  value: unknown,
): value is ProfilePhotoModerationLease {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const item = value as Partial<ProfilePhotoModerationLease>;
  return typeof item.profileId === "string" && UUID.test(item.profileId) &&
    typeof item.leaseToken === "string" && UUID.test(item.leaseToken) &&
    typeof item.objectPath === "string" && PROFILE_PATH.test(item.objectPath) &&
    ["image/jpeg", "image/png", "image/webp"].includes(item.mimeType ?? "");
}

export function extractOpenAIModerationDecision(
  value: unknown,
): ProfilePhotoModerationDecision | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const results = (value as Record<string, unknown>).results;
  if (!Array.isArray(results) || results.length !== 1) return null;
  const result = results[0];
  if (!result || typeof result !== "object" || Array.isArray(result)) {
    return null;
  }
  const row = result as Record<string, unknown>;
  if (typeof row.flagged !== "boolean") return null;
  if (
    !row.categories || typeof row.categories !== "object" ||
    Array.isArray(row.categories)
  ) {
    return null;
  }

  const categories = row.categories as Record<string, unknown>;
  const entries = Object.entries(categories);
  if (
    entries.length < 1 ||
    entries.some(([, enabled]) => typeof enabled !== "boolean")
  ) {
    return null;
  }
  for (
    const required of [
      "sexual",
      "sexual/minors",
      "violence",
      "violence/graphic",
    ]
  ) {
    if (typeof categories[required] !== "boolean") return null;
  }

  const anyCategory = entries.some(([, enabled]) => enabled === true);
  if (row.flagged !== anyCategory) return null;
  if (
    categories["sexual/minors"] || categories.violence ||
    categories["violence/graphic"]
  ) {
    return "abusive";
  }
  if (categories.sexual) return "adult";
  return row.flagged ? "review" : "approved";
}
