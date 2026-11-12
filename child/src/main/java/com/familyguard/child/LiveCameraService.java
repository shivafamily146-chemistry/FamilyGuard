package com.familyguard.child;

import android.app.*;
import android.content.*;
import android.os.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import org.webrtc.*;
import java.util.*;

/** Live camera uplink. Starts only after Child in-app approval and Android CAMERA permission. */
public class LiveCameraService extends Service {
 private static final String CHANNEL="familyguard_live_camera";
 private String sessionId,uid; private PeerConnectionFactory factory; private PeerConnection peer;
 private VideoSource source; private VideoTrack track; private CameraVideoCapturer capturer; private SurfaceTextureHelper texture; private EglBase egl;
 private ListenerRegistration sessionListener,candidateListener;
 private final List<IceCandidate> localPending=new ArrayList<>(),remotePending=new ArrayList<>();
 private final Set<String> seen=new HashSet<>(); private boolean approved=false,remoteOfferSet=false,cleaning=false;
 @Override public void onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"FamilyGuard live camera",NotificationManager.IMPORTANCE_LOW));}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent==null||(sessionId=intent.getStringExtra("sessionId"))==null){stopSelf();return START_NOT_STICKY;}
  Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
  Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);int pf=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=23)pf|=PendingIntent.FLAG_IMMUTABLE;
  b.setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("FamilyGuard live camera").setContentText("Camera is live to your approved Parent. Tap Child app to stop.").setOngoing(true).setContentIntent(PendingIntent.getActivity(this,4111,open,pf));
  if(Build.VERSION.SDK_INT>=30)startForeground(4111,b.build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);else startForeground(4111,b.build());
  uid=FirebaseAuth.getInstance().getUid();if(uid==null){cleanup();return START_NOT_STICKY;}listen();return START_NOT_STICKY;
 }
 private void listen(){
  DocumentReference ref=ref();
  sessionListener=ref.addSnapshotListener((snap,error)->{if(error!=null||snap==null||!snap.exists()){cleanup();return;}String s=snap.getString("status");if("cancelled".equals(s)||"ended".equals(s)||"denied".equals(s)){cleanup();return;}if(peer==null&&"requested".equals(s)){Object o=snap.get("offer");if(o instanceof Map)startPeer((Map<String,Object>)o);}});
  candidateListener=ref.collection("candidates").addSnapshotListener((snap,error)->{if(error!=null||snap==null)return;for(DocumentSnapshot d:snap.getDocuments()){if(uid.equals(d.getString("senderUid"))||!seen.add(d.getId()))continue;String raw=d.getString("candidate");if(raw==null)continue;try{org.json.JSONObject j=new org.json.JSONObject(raw);IceCandidate c=new IceCandidate(j.optString("sdpMid"),j.optInt("sdpMLineIndex"),j.getString("sdp"));if(peer!=null&&remoteOfferSet)peer.addIceCandidate(c);else remotePending.add(c);}catch(Exception ignored){}}});
 }
 private void startPeer(Map<String,Object> offer){
  try{
   PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
   egl=EglBase.create();
   factory=PeerConnectionFactory.builder().setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(),true,true)).setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext())).createPeerConnectionFactory();
   PeerConnection.RTCConfiguration cfg=new PeerConnection.RTCConfiguration(Collections.singletonList(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()));cfg.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
   peer=factory.createPeerConnection(cfg,new PeerConnection.Observer(){
    public void onSignalingChange(PeerConnection.SignalingState s){} public void onIceConnectionChange(PeerConnection.IceConnectionState s){if(s==PeerConnection.IceConnectionState.FAILED)cleanup();}
    public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState s){} public void onConnectionChange(PeerConnection.PeerConnectionState s){if(s==PeerConnection.PeerConnectionState.FAILED)cleanup();}
    public void onIceConnectionReceivingChange(boolean b){} public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
    public void onIceCandidate(IceCandidate c){if(approved)writeCandidate(c);else localPending.add(c);} public void onIceCandidatesRemoved(IceCandidate[] c){}
    public void onAddStream(MediaStream s){} public void onRemoveStream(MediaStream s){} public void onDataChannel(DataChannel c){} public void onRenegotiationNeeded(){} public void onAddTrack(RtpReceiver r,MediaStream[] s){} public void onTrack(RtpTransceiver t){}
   });
   CameraEnumerator enumerator=Camera2Enumerator.isSupported(this)?new Camera2Enumerator(this):new Camera1Enumerator(true);String camera=null;for(String name:enumerator.getDeviceNames())if(enumerator.isFrontFacing(name)){camera=name;break;}if(camera==null)for(String name:enumerator.getDeviceNames())if(enumerator.isBackFacing(name)){camera=name;break;}if(camera==null)throw new IllegalStateException("No available camera");
   capturer=enumerator.createCapturer(camera,new CameraVideoCapturer.CameraEventsHandler(){
    public void onCameraError(String e){android.util.Log.e("FamilyGuardCamera",e);cleanup();}public void onCameraDisconnected(){cleanup();}public void onCameraFreezed(String e){android.util.Log.e("FamilyGuardCamera",e);}public void onCameraOpening(String n){}public void onFirstFrameAvailable(){}public void onCameraClosed(){}
   });
   source=factory.createVideoSource(false);texture=SurfaceTextureHelper.create("FamilyGuardCameraCapture",egl.getEglBaseContext());capturer.initialize(texture,getApplicationContext(),source.getCapturerObserver());track=factory.createVideoTrack("familyguard-live-camera",source);track.setEnabled(true);
   peer.setRemoteDescription(new SdpObserverAdapter(){public void onSetSuccess(){remoteOfferSet=true;for(IceCandidate c:new ArrayList<>(remotePending))peer.addIceCandidate(c);remotePending.clear();peer.addTrack(track,Collections.singletonList("familyguard-live-camera"));peer.createAnswer(new SdpObserverAdapter(){public void onCreateSuccess(SessionDescription answer){peer.setLocalDescription(new SdpObserverAdapter(){public void onSetSuccess(){Map<String,Object>a=new HashMap<>();a.put("type",answer.type.canonicalForm());a.put("sdp",answer.description);Map<String,Object>u=new HashMap<>();u.put("status","approved");u.put("answer",a);u.put("updatedAt",FieldValue.serverTimestamp());ref().update(u).addOnSuccessListener(v->{approved=true;for(IceCandidate c:new ArrayList<>(localPending))writeCandidate(c);localPending.clear();try{capturer.startCapture(1280,720,24);}catch(Exception e){android.util.Log.e("FamilyGuardCamera","Camera start failed",e);cleanup();}}).addOnFailureListener(e->cleanup());}},answer);}public void onCreateFailure(String e){cleanup();}},new MediaConstraints());}public void onSetFailure(String e){cleanup();}},new SessionDescription(SessionDescription.Type.fromCanonicalForm(String.valueOf(offer.get("type"))),String.valueOf(offer.get("sdp"))));
  }catch(Exception e){android.util.Log.e("FamilyGuardCamera","Setup failed",e);cleanup();}
 }
 private DocumentReference ref(){return FirebaseFirestore.getInstance().collection("cameraSessions").document(sessionId);}
 private void writeCandidate(IceCandidate c){try{Map<String,Object>d=new HashMap<>();d.put("senderUid",uid);d.put("candidate",new org.json.JSONObject().put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex).put("sdp",c.sdp).toString());d.put("createdAt",FieldValue.serverTimestamp());ref().collection("candidates").add(d);}catch(Exception ignored){}}
 private void cleanup(){if(cleaning)return;cleaning=true;if(sessionListener!=null){sessionListener.remove();sessionListener=null;}if(candidateListener!=null){candidateListener.remove();candidateListener=null;}try{if(capturer!=null){capturer.stopCapture();capturer.dispose();capturer=null;}}catch(Exception ignored){}try{if(track!=null){track.dispose();track=null;}}catch(Exception ignored){}try{if(source!=null){source.dispose();source=null;}}catch(Exception ignored){}try{if(texture!=null){texture.dispose();texture=null;}}catch(Exception ignored){}try{if(peer!=null){peer.close();peer.dispose();peer=null;}}catch(Exception ignored){}try{if(factory!=null){factory.dispose();factory=null;}}catch(Exception ignored){}try{if(egl!=null){egl.release();egl=null;}}catch(Exception ignored){}if(sessionId!=null)ref().update("status",approved?"ended":"denied","updatedAt",FieldValue.serverTimestamp());if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE);else stopForeground(true);stopSelf();}
 @Override public void onDestroy(){if(!cleaning)cleanup();super.onDestroy();}@Override public IBinder onBind(Intent i){return null;}
 static class SdpObserverAdapter implements SdpObserver{public void onCreateSuccess(SessionDescription s){}public void onSetSuccess(){}public void onCreateFailure(String e){}public void onSetFailure(String e){}}
}