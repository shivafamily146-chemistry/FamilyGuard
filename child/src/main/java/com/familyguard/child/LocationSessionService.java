package com.familyguard.child;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

public class LocationSessionService extends Service implements LocationListener {
    private static final String CHANNEL = "familyguard_location";
    private LocationManager manager;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setContentTitle("FamilyGuard location session")
            .setContentText("Location session is active and visible.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build();
        startForeground(7, notification);
        manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            if (manager != null) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000L, 10f, this);
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000L, 25f, this);
            }
        } catch (SecurityException ignored) {
            stopSelf();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Location session", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override public void onLocationChanged(Location location) {
        getSharedPreferences("familyguard_child", MODE_PRIVATE).edit()
            .putLong("last_location_time", location.getTime())
            .putString("last_latitude", String.valueOf(location.getLatitude()))
            .putString("last_longitude", String.valueOf(location.getLongitude()))
            .apply();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() {
        if (manager != null) manager.removeUpdates(this);
        super.onDestroy();
    }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}