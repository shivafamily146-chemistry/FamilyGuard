package com.familyguard.child;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.HashSet;
import java.util.Set;

public class AppBlockingAccessibilityService extends AccessibilityService {
    private FirebaseFirestore db;
    private ListenerRegistration rulesListener;
    private final Set<String> blockedPackages = new HashSet<>();
    private String lastBlocked = "";
    private long lastLaunch = 0L;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        db = FirebaseFirestore.getInstance();
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) return;
        String childUid = auth.getCurrentUser().getUid();
        rulesListener = db.collection("appBlockRules")
            .whereEqualTo("childUid", childUid)
            .addSnapshotListener((snap, error) -> {
                if (error != null || snap == null) return;
                synchronized (blockedPackages) {
                    blockedPackages.clear();
                    for (DocumentSnapshot d : snap) {
                        String pkg = d.getString("packageName");
                        if (Boolean.TRUE.equals(d.getBoolean("active")) && pkg != null && !pkg.isEmpty()) blockedPackages.add(pkg);
                    }
                }
            });
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence name = event.getPackageName();
        if (name == null) return;
        String pkg = name.toString();
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")) return;
        boolean blocked;
        synchronized (blockedPackages) { blocked = blockedPackages.contains(pkg); }
        if (!blocked) return;
        long now = System.currentTimeMillis();
        if (pkg.equals(lastBlocked) && now - lastLaunch < 1800L) return;
        lastBlocked = pkg;
        lastLaunch = now;
        Intent i = new Intent(this, AppBlockedActivity.class);
        i.putExtra("blocked_package", pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        if (rulesListener != null) rulesListener.remove();
        super.onDestroy();
    }
}
