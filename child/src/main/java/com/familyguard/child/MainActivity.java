package com.familyguard.child;

import android.app.Activity;
import android.os.Bundle;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.widget.*;

public class MainActivity extends Activity {
    private TextView state;
    private static final int LOCATION_REQUEST = 42;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(32, 48, 32, 32);
        root.setBackgroundColor(0xFFF5F7FB);

        TextView title = new TextView(this);
        title.setText("FamilyGuard Child");
        title.setTextSize(28);
        title.setTextColor(0xFF18243A);

        TextView info = new TextView(this);
        info.setText("Location sessions are visible on this device. Parent sync requires secure backend pairing.");

        Button start = new Button(this);
        start.setText("Start Location Session");
        Button stop = new Button(this);
        stop.setText("Stop Location Session");
        state = new TextView(this);
        state.setText("Location session is off.");

        start.setOnClickListener(v -> startLocationSession());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, LocationSessionService.class));
            state.setText("Location session stopped.");
        });

        root.addView(title);
        root.addView(info);
        root.addView(start);
        root.addView(stop);
        root.addView(state);
        setContentView(root);
    }

    private void startLocationSession() {
        if (Build.VERSION.SDK_INT >= 23 &&
            (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
             checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        launchSession();
    }

    private void launchSession() {
        Intent intent = new Intent(this, LocationSessionService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        state.setText("Location session started. A persistent notification is visible. Parent sync is not configured.");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == LOCATION_REQUEST) {
            boolean granted = grants.length >= 2 && grants[0] == PackageManager.PERMISSION_GRANTED &&
                (grants[1] == PackageManager.PERMISSION_GRANTED || grants[0] == PackageManager.PERMISSION_GRANTED);
            if (granted) launchSession();
            else state.setText("Location permission was not granted.");
        }
    }
}