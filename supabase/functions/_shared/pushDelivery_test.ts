import {
  classifyFcmResponse,
  fcmEnvelope,
  isNotificationLease,
  isPrivateNotificationPayload,
} from "./pushDelivery.ts";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const lease = {
  deliveryId: 7,
  leaseToken: "11111111-1111-4111-8111-111111111111",
  firebaseInstallationId: "synthetic_fid_abcdefghijkl",
  payload: {
    title: "Matcher",
    body: "Nova mensagem",
    conversation_id: "22222222-2222-4222-8222-222222222222",
  },
};

Deno.test("notification lease accepts only neutral payload and opaque identifiers", () => {
  assert(isNotificationLease(lease), "valid synthetic lease");
  assert(!isNotificationLease({ ...lease, firebaseInstallationId: "short" }), "short FID");
  assert(!isPrivateNotificationPayload({ ...lease.payload, message: "secret" }), "extra body");
  assert(!isPrivateNotificationPayload({ ...lease.payload, body: "Oi" }), "custom body");
});

Deno.test("FCM envelope never contains private message or media data", () => {
  const text = JSON.stringify(fcmEnvelope(lease));
  assert(text.includes("Nova mensagem"), "neutral body");
  assert(text.includes("conversation_id"), "opaque route");
  for (const forbidden of ["message_body", "media_path", "object_path", "sender_name"]) {
    assert(!text.includes(forbidden), `forbidden ${forbidden}`);
  }
});

Deno.test("FCM results distinguish success, invalid token, auth and transient errors", () => {
  assert(classifyFcmResponse(200, {}).outcome === "sent", "sent");
  assert(classifyFcmResponse(404, {}).outcome === "invalid", "404 invalid");
  assert(classifyFcmResponse(400, { error: { details: [{ errorCode: "UNREGISTERED" }] } }).outcome === "invalid", "unregistered");
  assert(classifyFcmResponse(401, {}).errorCode === "FCM_AUTH", "auth");
  assert(classifyFcmResponse(503, {}).errorCode === "FCM_TRANSIENT", "transient");
});
