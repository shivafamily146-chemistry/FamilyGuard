package com.familyguard.child;

import android.accessibilityservice.AccessibilityService;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppBlockingAccessibilityService extends AccessibilityService {
    private FirebaseFirestore db;
    private ListenerRegistration rulesListener;
    private ListenerRegistration limitsListener;
    private final Set<String> blockedPackages = new HashSet<>();
    private final Set<String> limitedPackages = new HashSet<>();
    private final Map<String, Integer> dailyLimits = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastBlocked = "";
    private long lastLaunch = 0L;

    private final Runnable limitRefresh = new Runnable() {
        @Override public void run() {
            refreshTimeLimits();
            handler.postDelayed(this, 30000L);
        }
    };

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
        limitsListener = db.collection("screenTimeLimits")
            .whereEqualTo("childUid", childUid)
            .addSnapshotListener((snap, error) -> {
                if (error != null || snap == null) return;
                synchronized (dailyLimits) {
                    dailyLimits.clear();
                    for (DocumentSnapshot d : snap) {
                        String pkg = d.getString("packageName");
                        Long minutes = d.getLong("dailyLimitMinutes");
                        if (Boolean.TRUE.equals(d.getBoolean("active")) && pkg != null && minutes != null && minutes > 0) {
                            dailyLimits.put(pkg, minutes.intValue());
                        }
                    }
                }
                refreshTimeLimits();
            });
        handler.post(limitRefresh);
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshTimeLimits() {
        if (dailyLimits.isEmpty() || !hasUsageAccess()) {
            synchronized (limitedPackages) { limitedPackages.clear(); }
            return;
        }
        try {
            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            long now = System.currentTimeMillis();
            UsageStatsManager manager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            List<UsageStats> stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, day.getTimeInMillis(), now);
            Map<String, Long> used = new HashMap<>();
            if (stats != null) {
                for (UsageStats item : stats) {
                    String pkg = item.getPackageName();
                    if (pkg != null) used.put(pkg,
                        used.getOrDefault(pkg, 0L) + item.getTotalTimeInForeground());
                }
            }
            Set<String> reached = new HashSet<>();
            synchronized (dailyLimits) {
                for (Map.Entry<String, Integer> rule : dailyLimits.entrySet()) {
                    long millis = used.getOrDefault(rule.getKey(), 0L);
                    if (millis >= rule.getValue() * 60000L) reached.add(rule.getKey());
                }
            }
            synchronized (limitedPackages) {
                limitedPackages.clear();
                limitedPackages.addAll(reached);
            }
        } catch (Exception ignored) { }
    }

    private boolean isBlocked(String pkg) {
        synchronized (blockedPackages) {
            if (blockedPackages.contains(pkg)) return true;
        }
        synchronized (limitedPackages) { return limitedPackages.contains(pkg); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence name = event.getPackageName();
        if (name == null) return;
        String pkg = name.toString();
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")) return;
        refreshTimeLimits();
        if (!isBlocked(pkg)) return;
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
        handler.removeCallbacks(limitRefresh);
        if (rulesListener != null) rulesListener.remove();
        if (limitsListener != null) limitsListener.remove();
        super.onDestroy();
    }
}
