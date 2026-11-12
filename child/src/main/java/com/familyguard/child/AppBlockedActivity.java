package com.familyguard.child;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AppBlockedActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String pkg = getIntent().getStringExtra("blocked_package");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(0xFFF5F7FB);
        TextView title = new TextView(this);
        title.setText("App blocked by FamilyGuard");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        TextView detail = new TextView(this);
        detail.setText((pkg == null ? "This app" : pkg) + "\nA Parent has set an app restriction.");
        detail.setTextSize(16);
        detail.setGravity(Gravity.CENTER);
        Button home = new Button(this);
        home.setText("Return to Home");
        home.setOnClickListener(v -> {
            IntentHome.go(this);
        });
        root.addView(title);
        root.addView(detail);
        root.addView(home);
        setContentView(root);
    }
    @Override public void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }
    private static class IntentHome {
        static void go(Activity a) {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            i.addCategory(android.content.Intent.CATEGORY_HOME);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            a.finish();
        }
    }
}
