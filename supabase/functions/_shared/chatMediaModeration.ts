export type SafeSearchLikelihood =
  | "UNKNOWN"
  | "VERY_UNLIKELY"
  | "UNLIKELY"
  | "POSSIBLE"
  | "LIKELY"
  | "VERY_LIKELY";

export type SafeSearchOutcome = "approved" | "adult" | "abusive" | "review";

export type ChatModerationLease = {
  messageId: string;
  leaseToken: string;
  objectPath: string;
  mimeType: "image/jpeg" | "image/png" | "image/webp";
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PATH = /^[0-9a-f-]{36}\/[0-9a-f-]{36}\/[0-9a-f-]{36}\.(jpg|jpeg|png|webp)$/;
const LIKELIHOODS = new Set<SafeSearchLikelihood>([
  "UNKNOWN", "VERY_UNLIKELY", "UNLIKELY", "POSSIBLE", "LIKELY", "VERY_LIKELY",
]);

export function isChatModerationLease(value: unknown): value is ChatModerationLease {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const item = value as Partial<ChatModerationLease>;
  return typeof item.messageId === "string" && UUID.test(item.messageId) &&
    typeof item.leaseToken === "string" && UUID.test(item.leaseToken) &&
    typeof item.objectPath === "string" && PATH.test(item.objectPath) &&
    ["image/jpeg", "image/png", "image/webp"].includes(item.mimeType ?? "");
}

export function classifySafeSearch(value: unknown): SafeSearchOutcome {
  if (!value || typeof value !== "object" || Array.isArray(value)) return "review";
  const record = value as Record<string, unknown>;
  const adult = record.adult;
  const racy = record.racy;
  const violence = record.violence;
  if (
    typeof adult !== "string" || typeof racy !== "string" ||
    typeof violence !== "string" || !LIKELIHOODS.has(adult as SafeSearchLikelihood) ||
    !LIKELIHOODS.has(racy as SafeSearchLikelihood) ||
    !LIKELIHOODS.has(violence as SafeSearchLikelihood)
  ) return "review";

  const high = new Set(["LIKELY", "VERY_LIKELY"]);
  if (high.has(violence)) return "abusive";
  if (high.has(adult) || high.has(racy)) return "adult";
  const safe = new Set(["VERY_UNLIKELY", "UNLIKELY"]);
  return safe.has(adult) && safe.has(racy) && safe.has(violence)
    ? "approved"
    : "review";
}

export function extractSafeSearchAnnotation(value: unknown): unknown {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const responses = (value as Record<string, unknown>).responses;
  if (!Array.isArray(responses) || responses.length !== 1) return null;
  const response = responses[0];
  if (!response || typeof response !== "object" || Array.isArray(response)) return null;
  const row = response as Record<string, unknown>;
  if (row.error) return null;
  return row.safeSearchAnnotation ?? null;
}
