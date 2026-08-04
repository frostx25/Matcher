export type ProfilePhotoModerationLease = {
  profileId: string;
  leaseToken: string;
  objectPath: string;
  mimeType: "image/jpeg" | "image/png" | "image/webp";
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
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
