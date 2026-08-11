export type NotificationLease = {
  deliveryId: number;
  leaseToken: string;
  firebaseInstallationId: string;
  payload: Record<string, unknown>;
};

export type DeliveryOutcome =
  | { outcome: "sent"; errorCode: null }
  | { outcome: "invalid"; errorCode: "FCM_INVALID_INSTALLATION" }
  | { outcome: "retry"; errorCode: "FCM_TRANSIENT" | "FCM_AUTH" };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const FID = /^[A-Za-z0-9_\-]{11,128}$/;

export function isNotificationLease(value: unknown): value is NotificationLease {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const item = value as Partial<NotificationLease>;
  return Number.isSafeInteger(item.deliveryId) && (item.deliveryId ?? 0) > 0 &&
    typeof item.leaseToken === "string" && UUID.test(item.leaseToken) &&
    typeof item.firebaseInstallationId === "string" && FID.test(item.firebaseInstallationId) &&
    isPrivateNotificationPayload(item.payload);
}

export function isPrivateNotificationPayload(
  value: unknown,
): value is Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const payload = value as Record<string, unknown>;
  return Object.keys(payload).sort().join(",") === "body,conversation_id,title" &&
    payload.title === "VibeAli" && payload.body === "Nova mensagem" &&
    typeof payload.conversation_id === "string" && UUID.test(payload.conversation_id);
}

export function fcmEnvelope(item: NotificationLease): Record<string, unknown> {
  if (!isNotificationLease(item)) throw new Error("INVALID_NOTIFICATION_LEASE");
  return {
    message: {
      fid: item.firebaseInstallationId,
      notification: { title: "VibeAli", body: "Nova mensagem" },
      data: { conversation_id: item.payload.conversation_id },
      android: {
        priority: "high",
        notification: { channel_id: "matcher_messages", sound: "default" },
      },
    },
  };
}

export function classifyFcmResponse(
  status: number,
  response: unknown,
): DeliveryOutcome {
  if (status >= 200 && status < 300) return { outcome: "sent", errorCode: null };
  const text = JSON.stringify(response ?? {}).toUpperCase();
  if (
    status === 404 || text.includes("UNREGISTERED") ||
    text.includes("REGISTRATION_TOKEN_NOT_REGISTERED")
  ) {
    return { outcome: "invalid", errorCode: "FCM_INVALID_INSTALLATION" };
  }
  if (status === 401 || status === 403) {
    return { outcome: "retry", errorCode: "FCM_AUTH" };
  }
  return { outcome: "retry", errorCode: "FCM_TRANSIENT" };
}
