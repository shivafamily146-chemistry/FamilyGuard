package com.familyguard.child;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class ScreenSnapshotService extends Service {
    static final String CHANNEL="familyguard_screen_snapshot";
    MediaProjection projection;
    VirtualDisplay display;
    ImageReader reader;
    Handler handler;
    boolean captured=false;

    @Override public void onCreate(){super.onCreate();handler=new Handler(Looper.getMainLooper());createChannel();}
    void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"FamilyGuard screen snapshot",NotificationManager.IMPORTANCE_LOW);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("FamilyGuard screen snapshot").setContentText("Child-approved one-time screen capture is active").setOngoing(true);
        if(Build.VERSION.SDK_INT>=29)startForeground(4106,nb.build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(4106,nb.build());
        int result=intent.getIntExtra("resultCode",Activity.RESULT_CANCELED);
        Intent data=intent.getParcelableExtra("projectionData");
        if(result!=Activity.RESULT_OK||data==null){stopSelf();return START_NOT_STICKY;}
        try{
            MediaProjectionManager mgr=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection=mgr.getMediaProjection(result,data);
            projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){cleanup();}},handler);
            DisplayMetrics dm=new DisplayMetrics();
            WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
            if(Build.VERSION.SDK_INT>=30){android.graphics.Rect bounds=wm.getCurrentWindowMetrics().getBounds();dm.widthPixels=bounds.width();dm.heightPixels=bounds.height();dm.densityDpi=getResources().getDisplayMetrics().densityDpi;}
            else wm.getDefaultDisplay().getRealMetrics(dm);
            int width=dm.widthPixels,height=dm.heightPixels,density=dm.densityDpi;
            reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
            reader.setOnImageAvailableListener(r->captureFrame(width,height),handler);
            display=projection.createVirtualDisplay("FamilyGuardOneShotSnapshot",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
            handler.postDelayed(()->{if(!captured){stopWithError("No screen frame received. Try again.");}},1800);
        }catch(Exception e){stopWithError("Screen snapshot failed: "+e.getMessage());}
        return START_NOT_STICKY;
    }
    void captureFrame(int width,int height){
        if(captured)return;
        Image image=null;
        try{
            image=reader.acquireLatestImage();if(image==null)return;
            captured=true;
            Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();
            int pixelStride=plane.getPixelStride(),rowStride=plane.getRowStride(),rowPadding=rowStride-pixelStride*width;
            android.graphics.Bitmap padded=android.graphics.Bitmap.createBitmap(width+rowPadding/pixelStride,height,android.graphics.Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(padded,0,0,width,height);padded.recycle();
            ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,78,out);bitmap.recycle();
            upload(out.toByteArray());
        }catch(Exception e){stopWithError("Could not encode screen snapshot: "+e.getMessage());}
        finally{if(image!=null)image.close();}
    }
    void upload(byte[] bytes){
        String uid=FirebaseAuth.getInstance().getUid();
        String parent=getSharedPreferences("familyguard_child",MODE_PRIVATE).getString("parent_uid",null);
        if(uid==null||parent==null){stopWithError("Pair this Child device first.");return;}
        long stamp=System.currentTimeMillis();String path="screenSnapshots/"+uid+"/"+stamp+".jpg";
        FirebaseStorage.getInstance().getReference().child(path).putBytes(bytes).addOnSuccessListener(task->
            FirebaseStorage.getInstance().getReference().child(path).getDownloadUrl().addOnSuccessListener(uri->{
                Map<String,Object> meta=new HashMap<>();meta.put("childUid",uid);meta.put("parentUid",parent);meta.put("storagePath",path);meta.put("downloadUrl",uri.toString());meta.put("createdAt",FieldValue.serverTimestamp());meta.put("captureType","child_consented_one_time_media_projection");
                FirebaseFirestore.getInstance().collection("screenSnapshots").add(meta).addOnSuccessListener(v->stopWithError("One-time screen snapshot shared with Parent. Capture session ended.")).addOnFailureListener(e->stopWithError("Image uploaded but record save failed: "+e.getMessage()));
            }).addOnFailureListener(e->stopWithError("Could not create snapshot link: "+e.getMessage()))
        ).addOnFailureListener(e->stopWithError("Snapshot upload failed: "+e.getMessage()));
    }
    void stopWithError(String message){android.util.Log.i("FamilyGuardSnapshot",message);cleanup();}
    void cleanup(){try{if(display!=null){display.release();display=null;}}catch(Exception ignored){}try{if(reader!=null){reader.close();reader=null;}}catch(Exception ignored){}try{if(projection!=null){projection.stop();projection=null;}}catch(Exception ignored){}if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE);else stopForeground(true);stopSelf();}
    @Override public void onDestroy(){cleanup();super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}