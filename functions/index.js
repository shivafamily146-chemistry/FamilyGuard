const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

exports.notifyParentOnGeofence = onDocumentCreated("geofenceEvents/{eventId}", async (event) => {
  const data = event.data && event.data.data();
  if (!data || !data.parentUid) return;
  const profile = await getFirestore().collection("parentProfiles").doc(data.parentUid).get();
  const token = profile.exists && profile.get("fcmToken");
  if (!token) return;

  const label = typeof data.label === "string" ? data.label : "Safe zone";
  const type = typeof data.eventType === "string" ? data.eventType : "changed";
  try {
    await getMessaging().send({
      token,
      data: {
        title: "FamilyGuard: " + label,
        body: "Child " + type + " the safe zone",
        eventId: event.params.eventId
      },
      android: { priority: "high" }
    });
  } catch (error) {
    const code = error && error.code;
    if (code === "messaging/registration-token-not-registered" ||
        code === "messaging/invalid-registration-token") {
      await profile.ref.update({ fcmToken: "" });
      return;
    }
    throw error;
  }
});
