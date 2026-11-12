package com.familyguard.child;

import android.app.*;
import android.content.*;
import android.media.MediaRecorder;
import android.os.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import org.webrtc.*;
import java.util.*;

/** Live microphone uplink, started only after an in-app Child approval and Android mic permission. */
public class LiveAudioService extends Service {
    private static final String CHANNEL="familyguard_live_audio";
    private String sessionId, uid;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private AudioSource source;
    private AudioTrack track;
    private ListenerRegistration sessionListener, candidateListener;
    private final List<IceCandidate> localPending=new ArrayList<>(), remotePending=new ArrayList<>();
    private final Set<String> seen=new HashSet<>();
    private boolean remoteOfferSet=false, approved=false, cleaning=false;

    @Override public void onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"FamilyGuard live microphone",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||(sessionId=intent.getStringExtra("sessionId"))==null){stopSelf();return START_NOT_STICKY;}
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pf=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=23)pf|=PendingIntent.FLAG_IMMUTABLE;
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("FamilyGuard live microphone")
         .setContentText("Microphone is live to your approved Parent. Tap to stop it in Child app.").setOngoing(true)
         .setContentIntent(PendingIntent.getActivity(this,4110,open,pf));
        if(Build.VERSION.SDK_INT>=29)startForeground(4110,b.build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(4110,b.build());
        uid=FirebaseAuth.getInstance().getUid();if(uid==null){cleanup();return START_NOT_STICKY;}
        listen();return START_NOT_STICKY;
    }
    private void listen(){
        DocumentReference ref=FirebaseFirestore.getInstance().collection("audioSessions").document(sessionId);
        sessionListener=ref.addSnapshotListener((snap,error)->{
            if(error!=null||snap==null||!snap.exists()){cleanup();return;}
            String state=snap.getString("status");
            if("cancelled".equals(state)||"ended".equals(state)||"denied".equals(state)){cleanup();return;}
            if(peer==null&&"requested".equals(state)){
                Object offer=snap.get("offer");if(offer instanceof Map)startPeer((Map<String,Object>)offer);
            }
        });
        candidateListener=ref.collection("candidates").addSnapshotListener((snap,error)->{
            if(error!=null||snap==null)return;
            for(DocumentSnapshot d:snap.getDocuments()){
                if(uid.equals(d.getString("senderUid"))||!seen.add(d.getId()))continue;
                String raw=d.getString("candidate");if(raw==null)continue;
                try{org.json.JSONObject j=new org.json.JSONObject(raw);IceCandidate c=new IceCandidate(j.optString("sdpMid"),j.optInt("sdpMLineIndex"),j.getString("sdp"));if(peer!=null&&remoteOfferSet)peer.addIceCandidate(c);else remotePending.add(c);}catch(Exception ignored){}
            }
        });
    }
    private void startPeer(Map<String,Object> offer){
        try{
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
            factory=PeerConnectionFactory.builder().createPeerConnectionFactory();
            PeerConnection.RTCConfiguration cfg=new PeerConnection.RTCConfiguration(Collections.singletonList(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()));
            cfg.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer=factory.createPeerConnection(cfg,new PeerConnection.Observer(){
                public void onSignalingChange(PeerConnection.SignalingState s){}
                public void onIceConnectionChange(PeerConnection.IceConnectionState s){if(s==PeerConnection.IceConnectionState.FAILED)cleanup();}
                public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState s){}
                public void onConnectionChange(PeerConnection.PeerConnectionState s){if(s==PeerConnection.PeerConnectionState.FAILED)cleanup();}
                public void onIceConnectionReceivingChange(boolean b){}
                public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
                public void onIceCandidate(IceCandidate c){if(approved)writeCandidate(c);else localPending.add(c);}
                public void onIceCandidatesRemoved(IceCandidate[] c){}
                public void onAddStream(MediaStream s){}
                public void onRemoveStream(MediaStream s){}
                public void onDataChannel(DataChannel c){}
                public void onRenegotiationNeeded(){}
                public void onAddTrack(RtpReceiver r,MediaStream[] s){}
                public void onTrack(RtpTransceiver t){}
            });
            MediaConstraints constraints=new MediaConstraints();
            source=factory.createAudioSource(constraints);
            track=factory.createAudioTrack("familyguard-live-mic",source);track.setEnabled(true);
            peer.setRemoteDescription(new SimpleSdpObserver(){
                @Override public void onSetSuccess(){
                    remoteOfferSet=true;for(IceCandidate c:new ArrayList<>(remotePending))peer.addIceCandidate(c);remotePending.clear();
                    peer.addTrack(track,Collections.singletonList("familyguard-live-audio"));
                    peer.createAnswer(new SimpleSdpObserver(){
                        @Override public void onCreateSuccess(SessionDescription answer){
                            peer.setLocalDescription(new SimpleSdpObserver(){
                                @Override public void onSetSuccess(){
                                    Map<String,Object>a=new HashMap<>();a.put("type",answer.type.canonicalForm());a.put("sdp",answer.description);
                                    Map<String,Object>u=new HashMap<>();u.put("status","approved");u.put("answer",a);u.put("updatedAt",FieldValue.serverTimestamp());
                                    ref().update(u).addOnSuccessListener(v->{approved=true;for(IceCandidate c:new ArrayList<>(localPending))writeCandidate(c);localPending.clear();}).addOnFailureListener(e->cleanup());
                                }
                            },answer);
                        }
                        @Override public void onCreateFailure(String e){cleanup();}
                    },new MediaConstraints());
                }
                @Override public void onSetFailure(String e){cleanup();}
            },new SessionDescription(SessionDescription.Type.fromCanonicalForm(String.valueOf(offer.get("type"))),String.valueOf(offer.get("sdp"))));
        }catch(Exception e){android.util.Log.e("FamilyGuardLiveAudio","WebRTC setup failed",e);cleanup();}
    }
    private DocumentReference ref(){return FirebaseFirestore.getInstance().collection("audioSessions").document(sessionId);}
    private void writeCandidate(IceCandidate c){
        try{Map<String,Object>d=new HashMap<>();d.put("senderUid",uid);d.put("candidate",new org.json.JSONObject().put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex).put("sdp",c.sdp).toString());d.put("createdAt",FieldValue.serverTimestamp());ref().collection("candidates").add(d);}catch(Exception ignored){}
    }
    private void cleanup(){
        if(cleaning)return;cleaning=true;
        if(sessionListener!=null){sessionListener.remove();sessionListener=null;}if(candidateListener!=null){candidateListener.remove();candidateListener=null;}
        try{if(track!=null){track.setEnabled(false);track.dispose();track=null;}}catch(Exception ignored){}
        try{if(source!=null){source.dispose();source=null;}}catch(Exception ignored){}
        try{if(peer!=null){peer.close();peer.dispose();peer=null;}}catch(Exception ignored){}
        try{if(factory!=null){factory.dispose();factory=null;}}catch(Exception ignored){}
        if(sessionId!=null)ref().update("status",approved?"ended":"denied","updatedAt",FieldValue.serverTimestamp());
        if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE);else stopForeground(true);stopSelf();
    }
    @Override public void onDestroy(){if(!cleaning)cleanup();super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent i){return null;}
    static class SimpleSdpObserver implements SdpObserver{
        public void onCreateSuccess(SessionDescription s){}public void onSetSuccess(){}public void onCreateFailure(String e){}public void onSetFailure(String e){}
    }
}