package com.familyguard.parent;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import org.webrtc.*;
import java.util.*;

/** Parent receiver for a Child-approved live microphone session. */
public class LiveAudioActivity extends Activity{
 private String childUid,parentUid,sessionId;private DocumentReference ref;private TextView status;private Button start,stop;
 private PeerConnectionFactory factory;private PeerConnection peer;private ListenerRegistration sessionListener,candidateListener;
 private final List<IceCandidate> localPending=new ArrayList<>(),remotePending=new ArrayList<>();private final Set<String> seen=new HashSet<>();private boolean created=false,answerSet=false,ending=false;
 @Override public void onCreate(Bundle b){super.onCreate(b);childUid=getIntent().getStringExtra("childUid");parentUid=FirebaseAuth.getInstance().getUid();LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,32,24,24);root.setBackgroundColor(Color.WHITE);TextView h=new TextView(this);h.setText("FamilyGuard — Live one-way audio");h.setTextSize(22);status=new TextView(this);status.setText("Microphone is off. Child approval and Android microphone permission are required.");start=new Button(this);start.setText("Request live microphone");start.setOnClickListener(v->request());stop=new Button(this);stop.setText("Stop listening");stop.setEnabled(false);stop.setOnClickListener(v->end());root.addView(h);root.addView(status);root.addView(start);root.addView(stop);setContentView(root);if(childUid==null||parentUid==null){status.setText("Missing Parent or Child account.");start.setEnabled(false);}}
 private void request(){
  if(peer!=null)return;ending=false;created=false;answerSet=false;seen.clear();localPending.clear();remotePending.clear();start.setEnabled(false);stop.setEnabled(true);status.setText("Preparing audio connection. Waiting for Child approval.");
  try{
   PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());factory=PeerConnectionFactory.builder().createPeerConnectionFactory();
   PeerConnection.RTCConfiguration cfg=new PeerConnection.RTCConfiguration(Collections.singletonList(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()));cfg.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
   peer=factory.createPeerConnection(cfg,new PeerConnection.Observer(){
    public void onSignalingChange(PeerConnection.SignalingState s){}public void onIceConnectionChange(PeerConnection.IceConnectionState s){runOnUiThread(()->status.setText("Audio connection: "+s));if(s==PeerConnection.IceConnectionState.FAILED)fail("Audio connection failed. TURN may be needed.");}
    public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState s){}public void onConnectionChange(PeerConnection.PeerConnectionState s){}
    public void onIceConnectionReceivingChange(boolean b){}public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
    public void onIceCandidate(IceCandidate c){if(created)writeCandidate(c);else localPending.add(c);}
    public void onIceCandidatesRemoved(IceCandidate[] c){}public void onAddStream(MediaStream s){}public void onRemoveStream(MediaStream s){}public void onDataChannel(DataChannel c){}public void onRenegotiationNeeded(){}
    public void onAddTrack(RtpReceiver r,MediaStream[] s){if(r.track() instanceof AudioTrack)((AudioTrack)r.track()).setEnabled(true);}
    public void onTrack(RtpTransceiver t){MediaStreamTrack track=t.getReceiver().track();if(track instanceof AudioTrack)((AudioTrack)track).setEnabled(true);}
   });
   peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
   ref=FirebaseFirestore.getInstance().collection("audioSessions").document();sessionId=ref.getId();
   peer.createOffer(new SimpleSdpObserver(){public void onCreateSuccess(SessionDescription offer){peer.setLocalDescription(new SimpleSdpObserver(){public void onSetSuccess(){Map<String,Object>o=new HashMap<>();o.put("type",offer.type.canonicalForm());o.put("sdp",offer.description);Map<String,Object>d=new HashMap<>();d.put("parentUid",parentUid);d.put("childUid",childUid);d.put("status","requested");d.put("createdAt",FieldValue.serverTimestamp());d.put("offer",o);ref.set(d).addOnSuccessListener(v->{created=true;for(IceCandidate c:new ArrayList<>(localPending))writeCandidate(c);localPending.clear();listen();status.setText("Request sent. Child must approve and grant microphone permission.");}).addOnFailureListener(e->fail("Could not send request: "+e.getMessage()));}} ,offer);}},new MediaConstraints());
  }catch(Exception e){fail("Audio setup failed: "+e.getMessage());}
 }
 private void listen(){
  sessionListener=ref.addSnapshotListener((s,e)->{if(e!=null||s==null||!s.exists()){fail("Audio session unavailable.");return;}String state=s.getString("status");if("denied".equals(state)){ending=true;cleanup();status.setText("Child denied microphone access.");return;}if("ended".equals(state)||"cancelled".equals(state)){ending=true;cleanup();status.setText("Live audio ended.");return;}Object a=s.get("answer");if("approved".equals(state)&&a instanceof Map&&!answerSet){Map<String,Object>m=(Map<String,Object>)a;peer.setRemoteDescription(new SimpleSdpObserver(){public void onSetSuccess(){answerSet=true;for(IceCandidate c:new ArrayList<>(remotePending))peer.addIceCandidate(c);remotePending.clear();status.setText("Receiving Child-approved live microphone audio.");}public void onSetFailure(String x){fail("Invalid audio answer: "+x);}},new SessionDescription(SessionDescription.Type.fromCanonicalForm(String.valueOf(m.get("type"))),String.valueOf(m.get("sdp"))));}});
  candidateListener=ref.collection("candidates").addSnapshotListener((s,e)->{if(e!=null||s==null||peer==null)return;for(DocumentSnapshot d:s.getDocuments())if(!parentUid.equals(d.getString("senderUid"))&&seen.add(d.getId()))try{org.json.JSONObject j=new org.json.JSONObject(d.getString("candidate"));IceCandidate c=new IceCandidate(j.optString("sdpMid"),j.optInt("sdpMLineIndex"),j.getString("sdp"));if(answerSet)peer.addIceCandidate(c);else remotePending.add(c);}catch(Exception ignored){}});
 }
 private void writeCandidate(IceCandidate c){try{Map<String,Object>d=new HashMap<>();d.put("senderUid",parentUid);d.put("candidate",new org.json.JSONObject().put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex).put("sdp",c.sdp).toString());d.put("createdAt",FieldValue.serverTimestamp());ref.collection("candidates").add(d);}catch(Exception ignored){}}
 private void end(){ending=true;if(ref!=null)ref.update("status","cancelled","updatedAt",FieldValue.serverTimestamp());cleanup();status.setText("Stopped listening.");start.setEnabled(true);stop.setEnabled(false);}
 private void fail(String m){runOnUiThread(()->{status.setText(m);ending=true;cleanup();start.setEnabled(true);stop.setEnabled(false);});}
 private void cleanup(){if(sessionListener!=null){sessionListener.remove();sessionListener=null;}if(candidateListener!=null){candidateListener.remove();candidateListener=null;}try{if(peer!=null){peer.close();peer.dispose();peer=null;}}catch(Exception ignored){}try{if(factory!=null){factory.dispose();factory=null;}}catch(Exception ignored){}if(!ending&&ref!=null)ref.update("status","cancelled","updatedAt",FieldValue.serverTimestamp());}
 @Override public void onDestroy(){if(!ending&&ref!=null)ref.update("status","cancelled","updatedAt",FieldValue.serverTimestamp());ending=true;cleanup();super.onDestroy();}
 static class SimpleSdpObserver implements SdpObserver{public void onCreateSuccess(SessionDescription s){}public void onSetSuccess(){}public void onCreateFailure(String e){}public void onSetFailure(String e){}}
}