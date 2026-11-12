package com.familyguard.parent;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.*;
import android.content.Intent;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import org.webrtc.*;
import java.util.*;

/** Parent-side viewer for a Child-approved WebRTC screen session. */
public class ScreenMirrorActivity extends Activity {
    private SurfaceViewRenderer renderer;
    private TextView status;
    private Button startButton, stopButton;
    private String childUid, parentUid, sessionId;
    private DocumentReference sessionRef;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private EglBase egl;
    private ListenerRegistration sessionListener, candidateListener;
    private final List<IceCandidate> pendingCandidates = new ArrayList<>();
    private final Set<String> seenCandidates = new HashSet<>();
    private boolean sessionCreated = false, remoteAnswerSet = false, ending = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        childUid = getIntent().getStringExtra("childUid");
        parentUid = FirebaseAuth.getInstance().getUid();
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20,24,20,20); root.setBackgroundColor(Color.BLACK);
        TextView heading = new TextView(this); heading.setText("FamilyGuard — Live screen mirror"); heading.setTextColor(Color.WHITE); heading.setTextSize(21);
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setText("Screen sharing is off. The Child must approve the request and Android screen capture prompt.");
        renderer = new SurfaceViewRenderer(this); renderer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        startButton = new Button(this); startButton.setText("Request live screen view"); startButton.setOnClickListener(v -> requestSession());
        stopButton = new Button(this); stopButton.setText("Stop / cancel session"); stopButton.setEnabled(false); stopButton.setOnClickListener(v -> endSession());
        root.addView(heading); root.addView(status); root.addView(renderer); root.addView(startButton); root.addView(stopButton);
        setContentView(root);
        if (childUid == null || parentUid == null) { status.setText("Missing Child or Parent account. Return to Parent app and try again."); startButton.setEnabled(false); }
    }

    private void requestSession() {
        if (peer != null || sessionRef != null) return;
        try {
            status.setText("Preparing secure WebRTC connection…");
            startButton.setEnabled(false); stopButton.setEnabled(true);
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
            egl = EglBase.create();
            renderer.init(egl.getEglBaseContext(), null);
            renderer.setEnableHardwareScaler(true);
            renderer.setMirror(false);
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.singletonList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()));
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer = factory.createPeerConnection(config, new PeerConnection.Observer() {
                @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                    runOnUiThread(() -> status.setText("WebRTC connection: " + s));
                    if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.DISCONNECTED)
                        runOnUiThread(() -> status.setText("Connection lost. Check network and TURN availability."));
                }
                @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState s) {}
                @Override public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                    runOnUiThread(() -> status.setText("Peer connection: " + s));
                }
                @Override public void onIceConnectionReceivingChange(boolean b) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
                @Override public void onIceCandidate(IceCandidate c) {
                    if (sessionCreated) writeCandidate(c); else pendingCandidates.add(c);
                }
                @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
                @Override public void onAddStream(MediaStream s) { if (s.videoTracks.size() > 0) showTrack(s.videoTracks.get(0)); }
                @Override public void onRemoveStream(MediaStream s) {}
                @Override public void onDataChannel(DataChannel c) {}
                @Override public void onRenegotiationNeeded() {}
                @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                    if (receiver.track() instanceof VideoTrack) showTrack((VideoTrack)receiver.track());
                }
                @Override public void onTrack(RtpTransceiver transceiver) {
                    MediaStreamTrack track = transceiver.getReceiver().track();
                    if (track instanceof VideoTrack) showTrack((VideoTrack)track);
                }
            });
            peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
            sessionRef = FirebaseFirestore.getInstance().collection("screenSessions").document();
            sessionId = sessionRef.getId();
            peer.createOffer(new SimpleSdpObserver() {
                @Override public void onCreateSuccess(SessionDescription offer) {
                    peer.setLocalDescription(new SimpleSdpObserver() {
                        @Override public void onSetSuccess() {
                            Map<String,Object> offerData = new HashMap<>();
                            offerData.put("type", offer.type.canonicalForm()); offerData.put("sdp", offer.description);
                            Map<String,Object> data = new HashMap<>();
                            data.put("parentUid", parentUid); data.put("childUid", childUid);
                            data.put("status", "requested"); data.put("createdAt", FieldValue.serverTimestamp()); data.put("offer", offerData);
                            sessionRef.set(data).addOnSuccessListener(v -> {
                                sessionCreated = true; flushCandidates(); listenForSession();
                                status.setText("Request sent. Waiting for Child approval and Android's screen-sharing consent.");
                            }).addOnFailureListener(e -> fail("Could not send screen request: " + e.getMessage()));
                        }
                        @Override public void onSetFailure(String error) { fail("Could not prepare offer: " + error); }
                    }, offer);
                }
                @Override public void onCreateFailure(String error) { fail("Could not create offer: " + error); }
            }, new MediaConstraints());
        } catch (Exception e) { fail("WebRTC initialization failed: " + e.getMessage()); }
    }

    private void listenForSession() {
        sessionListener = sessionRef.addSnapshotListener((snap,error) -> {
            if (error != null || snap == null || !snap.exists()) { fail("Screen session could not be read."); return; }
            String state = snap.getString("status");
            if ("denied".equals(state)) { runOnUiThread(() -> status.setText("Child denied screen sharing.")); return; }
            if ("ended".equals(state) || "cancelled".equals(state)) { runOnUiThread(() -> status.setText("Screen sharing ended.")); return; }
            Object answerObj = snap.get("answer");
            if ("approved".equals(state) && answerObj instanceof Map && !remoteAnswerSet) {
                Map<String,Object> answer = (Map<String,Object>)answerObj;
                peer.setRemoteDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() { remoteAnswerSet = true; runOnUiThread(() -> status.setText("Connected. Child-approved screen is being shared live.")); }
                    @Override public void onSetFailure(String error) { fail("Could not apply Child answer: " + error); }
                }, new SessionDescription(SessionDescription.Type.fromCanonicalForm(String.valueOf(answer.get("type"))), String.valueOf(answer.get("sdp"))));
            }
        });
        candidateListener = sessionRef.collection("candidates").addSnapshotListener((snap,error) -> {
            if (error != null || snap == null || peer == null) return;
            for (DocumentSnapshot d : snap.getDocuments()) {
                if (!parentUid.equals(d.getString("senderUid")) && seenCandidates.add(d.getId())) {
                    String encoded = d.getString("candidate");
                    if (encoded != null) try {
                        org.json.JSONObject value = new org.json.JSONObject(encoded);
                        peer.addIceCandidate(new IceCandidate(value.optString("sdpMid"), value.optInt("sdpMLineIndex"), value.getString("sdp")));
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void writeCandidate(IceCandidate c) {
        if (sessionRef == null) return;
        try {
            Map<String,Object> data = new HashMap<>(); data.put("senderUid", parentUid);
            data.put("candidate", new org.json.JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("sdp", c.sdp).toString());
            data.put("createdAt", FieldValue.serverTimestamp());
            sessionRef.collection("candidates").add(data);
        } catch (Exception ignored) {}
    }
    private void flushCandidates() { for (IceCandidate c : new ArrayList<>(pendingCandidates)) writeCandidate(c); pendingCandidates.clear(); }
    private void showTrack(VideoTrack track) { runOnUiThread(() -> { if (renderer != null) { track.addSink(renderer); status.setText("Receiving Child-approved live screen video."); } }); }

    private void endSession() {
        ending = true;
        if (sessionRef != null) sessionRef.update("status", "cancelled", "updatedAt", FieldValue.serverTimestamp());
        cleanup();
        status.setText("Screen session stopped.");
        startButton.setEnabled(true); stopButton.setEnabled(false);
    }
    private void fail(String message) { runOnUiThread(() -> { status.setText(message); startButton.setEnabled(true); stopButton.setEnabled(false); }); cleanup(); }
    private void cleanup() {
        if (sessionListener != null) { sessionListener.remove(); sessionListener = null; }
        if (candidateListener != null) { candidateListener.remove(); candidateListener = null; }
        try { if (peer != null) { peer.close(); peer.dispose(); peer = null; } } catch (Exception ignored) {}
        try { if (factory != null) { factory.dispose(); factory = null; } } catch (Exception ignored) {}
        try { if (renderer != null) { renderer.release(); } } catch (Exception ignored) {}
        try { if (egl != null) { egl.release(); egl = null; } } catch (Exception ignored) {}
        if (!ending && sessionRef != null) sessionRef.update("status", "cancelled", "updatedAt", FieldValue.serverTimestamp());
    }
    @Override public void onDestroy() { ending = true; cleanup(); super.onDestroy(); }

    static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String e) {}
        @Override public void onSetFailure(String e) {}
    }
}
