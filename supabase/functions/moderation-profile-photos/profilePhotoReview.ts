export type ProfilePhotoReviewDecision =
  | "approved"
  | "blocked_adult"
  | "blocked_abusive";

export type ProfilePhotoReviewItem = {
  profileId: string;
  displayName: string;
  candidatePath: string;
  submittedAt: string;
  hasApprovedPhoto: boolean;
};

const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export function parseReviewDecisionBody(
  value: unknown,
): { profileId: string; candidatePath: string; decision: ProfilePhotoReviewDecision } | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const body = value as Record<string, unknown>;
  if (
    Object.keys(body).some((key) =>
      !["profile_id", "candidate_path", "decision"].includes(key)
    ) || Object.keys(body).length !== 3
  ) return null;
  if (typeof body.profile_id !== "string" || !UUID.test(body.profile_id)) {
    return null;
  }
  const expectedPath = new RegExp(
    `^${body.profile_id}/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(jpg|jpeg|png|webp)$`,
  );
  if (
    typeof body.candidate_path !== "string" ||
    !expectedPath.test(body.candidate_path)
  ) return null;
  if (
    body.decision !== "approved" && body.decision !== "blocked_adult" &&
    body.decision !== "blocked_abusive"
  ) return null;
  return {
    profileId: body.profile_id,
    candidatePath: body.candidate_path,
    decision: body.decision,
  };
}

export function parseReviewQueueRows(value: unknown): ProfilePhotoReviewItem[] | null {
  if (!Array.isArray(value) || value.length > 50) return null;
  const parsed: ProfilePhotoReviewItem[] = [];
  for (const row of value) {
    if (!row || typeof row !== "object" || Array.isArray(row)) return null;
    const item = row as Record<string, unknown>;
    if (
      typeof item.profile_id !== "string" || !UUID.test(item.profile_id) ||
      typeof item.display_name !== "string" || item.display_name.length > 60 ||
      typeof item.candidate_path !== "string" ||
      !item.candidate_path.startsWith(`${item.profile_id}/`) ||
      typeof item.submitted_at !== "string" ||
      Number.isNaN(Date.parse(item.submitted_at)) ||
      typeof item.has_approved_photo !== "boolean"
    ) return null;
    parsed.push({
      profileId: item.profile_id,
      displayName: item.display_name,
      candidatePath: item.candidate_path,
      submittedAt: item.submitted_at,
      hasApprovedPhoto: item.has_approved_photo,
    });
  }
  return parsed;
}
