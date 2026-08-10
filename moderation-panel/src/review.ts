export type ReviewDecision = "approved" | "blocked_adult" | "blocked_abusive";

export type ReviewItem = {
  profile_id: string;
  display_name: string;
  submitted_at: string;
  has_approved_photo: boolean;
  candidate_path: string;
  preview_url: string;
  preview_expires_in: number;
};

export function formatSubmittedAt(value: string, now = Date.now()): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return "Horário indisponível";
  const minutes = Math.max(0, Math.floor((now - timestamp) / 60_000));
  if (minutes < 1) return "Enviada agora";
  if (minutes < 60) return `Enviada há ${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `Enviada há ${hours} h`;
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(timestamp));
}

export function decisionLabel(decision: ReviewDecision): string {
  if (decision === "approved") return "Aprovar foto";
  if (decision === "blocked_adult") return "Bloquear: conteúdo adulto";
  return "Bloquear: conteúdo abusivo";
}
