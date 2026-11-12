package com.familyguard.child;

import android.app.*;
import android.content.*;
import android.media.projection.MediaProjection;
import android.os.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import org.webrtc.*;
import java.util.*;

/** Child-approved, visible MediaProjection screen stream. */
public class ScreenMirrorService extends Service {
    static final String CHANNEL = "familyguard_screen_mirror";
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private EglBase egl;
    private SurfaceTextureHelper textureHelper;
    private ScreenCapturerAndroid capturer;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private ListenerRegistration sessionListener, candidateListener;
    private String sessionId, uid;
    private boolean approved = false;
    private final List<IceCandidate> pendingLocalCandidates = new ArrayList<>();
    private final Set<String> seenRemoteCandidates = new HashSet<>();
    private final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();
    private boolean remoteOfferSet = false;

    @Override public void onCreate() { super.onCreate(); createChannel(); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel(CHANNEL, "FamilyGuard live screen sharing", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getParcelableExtra("projectionData") == null) { stopSelf(); return START_NOT_STICKY; }
        sessionId = intent.getStringExtra("sessionId");
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent projectionData = intent.getParcelableExtra("projectionData");
        if (sessionId == null || resultCode != Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY; }

        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.ic_menu_view)
          .setContentTitle("FamilyGuard screen sharing")
          .setContentText("Your screen is shared. Tap to open Child app and stop sharing.")
          .setOngoing(true);
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        nb.setContentIntent(PendingIntent.getActivity(this, 4108, openApp, pendingFlags));
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(4108, nb.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(4108, nb.build());

        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { stopSelf(); return START_NOT_STICKY; }
        listenForSessionAndStart(projectionData, resultCode);
        return START_NOT_STICKY;
    }

    private void listenForSessionAndStart(Intent projectionData, int resultCode) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference ref = db.collection("screenSessions").document(sessionId);
        sessionListener = ref.addSnapshotListener((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) { stopWithMessage("Screen session unavailable."); return; }
            String status = snapshot.getString("status");
            if ("cancelled".equals(status) || "ended".equals(status) || "denied".equals(status)) { stopWithMessage("Parent or Child ended the screen session."); return; }
            if (peer == null && "requested".equals(status)) {
                Object offerObj = snapshot.get("offer");
                if (offerObj instanceof Map) startPeer((Map<String,Object>)offerObj, projectionData, resultCode);
            }
        });
        candidateListener = ref.collection("candidates").addSnapshotListener((snap, error) -> {
            if (error != null || snap == null) return;
            for (DocumentSnapshot d : snap.getDocuments()) {
                if (d.getId().startsWith("child_")) continue;
                String sender = d.getString("senderUid");
                if (uid.equals(sender)) continue;
                String encoded = d.getString("candidate");
                if (encoded != null && seenRemoteCandidates.add(d.getId())) {
                    try {
                        org.json.JSONObject value = new org.json.JSONObject(encoded);
                        IceCandidate candidate = new IceCandidate(value.optString("sdpMid"), value.optInt("sdpMLineIndex"), value.getString("sdp"));
                        if (peer != null && remoteOfferSet) peer.addIceCandidate(candidate); else pendingRemoteCandidates.add(candidate);
                    } catch (Exception ignored) { }
                }
            }
        });
        // The one-time projection token is passed to ScreenCapturerAndroid only after foreground start.
        pendingProjectionData = projectionData;
        pendingResultCode = resultCode;
    }

    private Intent pendingProjectionData;
    private int pendingResultCode;

    private void startPeer(Map<String,Object> offer, Intent projectionData, int resultCode) {
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
            egl = EglBase.create();
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();

            List<PeerConnection.IceServer> ice = Collections.singletonList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(ice);
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer = factory.createPeerConnection(config, new PeerConnection.Observer() {
                @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                    if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.DISCONNECTED)
                        stopWithMessage("Screen connection lost.");
                }
                @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState s) {}
                @Override public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                    if (s == PeerConnection.PeerConnectionState.FAILED || s == PeerConnection.PeerConnectionState.DISCONNECTED)
                        stopWithMessage("Screen connection lost.");
                }
                @Override public void onIceConnectionReceivingChange(boolean b) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
                @Override public void onIceCandidate(IceCandidate c) {
                    Map<String,Object> data = new HashMap<>();
                    data.put("senderUid", uid);
                    try { data.put("candidate", new org.json.JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("sdp", c.sdp).toString()); }
                    catch (Exception ignored) { return; }
                    data.put("createdAt", FieldValue.serverTimestamp());
                    if (approved) FirebaseFirestore.getInstance().collection("screenSessions").document(sessionId)
                        .collection("candidates").add(data);
                    else pendingLocalCandidates.add(c);
                }
                @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
                @Override public void onAddStream(MediaStream s) {}
                @Override public void onRemoveStream(MediaStream s) {}
                @Override public void onDataChannel(DataChannel c) {}
                @Override public void onRenegotiationNeeded() {}
                @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}
                @Override public void onTrack(RtpTransceiver transceiver) {}
            });
            capturer = new ScreenCapturerAndroid(projectionData, new MediaProjection.Callback() {
                @Override public void onStop() { stopWithMessage("Android screen-sharing permission was stopped."); }
            });
            textureHelper = SurfaceTextureHelper.create("FamilyGuardScreenCapture", egl.getEglBaseContext());
            videoSource = factory.createVideoSource(true);
            capturer.initialize(textureHelper, getApplicationContext(), videoSource.getCapturerObserver());
            videoTrack = factory.createVideoTrack("familyguard-screen", videoSource);
            Map<String,Object> o = offer;
            peer.setRemoteDescription(new SimpleSdpObserver() {
                @Override public void onSetSuccess() {
                    remoteOfferSet = true;
                    for (IceCandidate candidate : new ArrayList<>(pendingRemoteCandidates)) peer.addIceCandidate(candidate);
                    pendingRemoteCandidates.clear();
                    peer.addTrack(videoTrack, Collections.singletonList("familyguard-screen-stream"));
                    peer.createAnswer(new SimpleSdpObserver() {
                        @Override public void onCreateSuccess(SessionDescription answer) {
                            peer.setLocalDescription(new SimpleSdpObserver() {
                                @Override public void onSetSuccess() {
                                    Map<String,Object> response = new HashMap<>();
                                    response.put("status", "approved");
                                    Map<String,Object> answerMap = new HashMap<>();
                                    answerMap.put("type", answer.type.canonicalForm());
                                    answerMap.put("sdp", answer.description);
                                    response.put("answer", answerMap);
                                    response.put("updatedAt", FieldValue.serverTimestamp());
                                    FirebaseFirestore.getInstance().collection("screenSessions").document(sessionId)
                                        .update(response).addOnSuccessListener(v -> {
                                            approved = true; flushLocalCandidates();
                                            try { capturer.startCapture(1280, 720, 15); }
                                            catch (Exception e) { stopWithMessage("Screen capture could not start: " + e.getMessage()); }
                                        }).addOnFailureListener(e -> stopWithMessage("Could not approve screen session: " + e.getMessage()));
                                }
                            }, answer);
                        }
                        @Override public void onCreateFailure(String error) { stopWithMessage("Could not create screen answer: " + error); }
                    }, new MediaConstraints());
                }
                @Override public void onSetFailure(String error) { stopWithMessage("Invalid Parent offer: " + error); }
            }, new SessionDescription(SessionDescription.Type.fromCanonicalForm(String.valueOf(o.get("type"))), String.valueOf(o.get("sdp"))));
        } catch (Exception e) { stopWithMessage("WebRTC setup failed: " + e.getMessage()); }
    }

    private void flushLocalCandidates() {
        if (!approved || pendingLocalCandidates.isEmpty()) return;
        for (IceCandidate c : new ArrayList<>(pendingLocalCandidates)) {
            Map<String,Object> data = new HashMap<>();
            data.put("senderUid", uid);
            try { data.put("candidate", new org.json.JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("sdp", c.sdp).toString()); }
            catch (Exception ignored) { continue; }
            data.put("createdAt", FieldValue.serverTimestamp());
            FirebaseFirestore.getInstance().collection("screenSessions").document(sessionId).collection("candidates").add(data);
        }
        pendingLocalCandidates.clear();
    }

    private void stopWithMessage(String message) { android.util.Log.i("FamilyGuardMirror", message); cleanup(); }
    private void cleanup() {
        try { if (sessionListener != null) { sessionListener.remove(); sessionListener = null; } } catch (Exception ignored) {}
        try { if (candidateListener != null) { candidateListener.remove(); candidateListener = null; } } catch (Exception ignored) {}
        try { if (capturer != null) { capturer.stopCapture(); capturer.dispose(); capturer = null; } } catch (Exception ignored) {}
        try { if (peer != null) { peer.close(); peer.dispose(); peer = null; } } catch (Exception ignored) {}
        try { if (videoTrack != null) { videoTrack.dispose(); videoTrack = null; } } catch (Exception ignored) {}
        try { if (videoSource != null) { videoSource.dispose(); videoSource = null; } } catch (Exception ignored) {}
        try { if (textureHelper != null) { textureHelper.dispose(); textureHelper = null; } } catch (Exception ignored) {}
        try { if (factory != null) { factory.dispose(); factory = null; } } catch (Exception ignored) {}
        try { if (egl != null) { egl.release(); egl = null; } } catch (Exception ignored) {}
        if (sessionId != null) FirebaseFirestore.getInstance().collection("screenSessions").document(sessionId)
            .update("status", approved ? "ended" : "denied", "updatedAt", FieldValue.serverTimestamp());
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }
    @Override public void onDestroy() { cleanup(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String e) {}
        @Override public void onSetFailure(String e) {}
    }
}
