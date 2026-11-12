const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

async function removeTokenIfStillCurrent(profileRef, sentToken) {
  await getFirestore().runTransaction(async (transaction) => {
    const latest = await transaction.get(profileRef);
    if (latest.exists && latest.get("fcmToken") === sentToken) {
      transaction.update(profileRef, {
        fcmToken: FieldValue.delete(),
        updatedAt: FieldValue.serverTimestamp()
      });
    }
  });
}

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
      await removeTokenIfStillCurrent(profile.ref, token);
      return;
    }
    throw error;
  }
});

exports.notifyParentOnPermissionRequest = onDocumentCreated("approvalRequests/{requestId}", async (event) => {
  const data = event.data && event.data.data();
  if (!data || data.status !== "pending" || !data.parentUid) return;
  const profile = await getFirestore().collection("parentProfiles").doc(data.parentUid).get();
  const token = profile.exists && profile.get("fcmToken");
  if (!token) return;
  const permission = typeof data.permission === "string" ? data.permission : "a device permission";
  const child = typeof data.childLabel === "string" ? data.childLabel : "Child device";
  try {
    await getMessaging().send({
      token,
      data: {
        title: "FamilyGuard permission request",
        body: child + " requests " + permission + " permission",
        requestId: event.params.requestId
      },
      android: { priority: "high" }
    });
  } catch (error) {
    const code = error && error.code;
    if (code === "messaging/registration-token-not-registered" ||
        code === "messaging/invalid-registration-token") {
      await removeTokenIfStillCurrent(profile.ref, token);
      return;
    }
    throw error;
  }
});
