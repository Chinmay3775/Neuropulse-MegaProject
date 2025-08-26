package com.neuropulse.app;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * MainActivity
 * - Entry point of Neuropulse app.
 * - Requests usage access permission from user.
 * - Lets user start/stop the background monitoring service.
 */
public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private Button startBtn, stopBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Ensure you have res/layout/activity_main.xml

        statusText = findViewById(R.id.statusText);
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);

        // Check usage access
        if (!hasUsageStatsPermission(this)) {
            Toast.makeText(this, "Please grant Usage Access Permission", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }

        startBtn.setOnClickListener(v -> {
            if (hasUsageStatsPermission(this)) {
                Intent serviceIntent = new Intent(this, UsageMonitorService.class);
                startForegroundService(serviceIntent);
                statusText.setText("Monitoring Started");
            } else {
                Toast.makeText(this, "Grant Usage Access first!", Toast.LENGTH_SHORT).show();
            }
        });

        stopBtn.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, UsageMonitorService.class);
            stopService(serviceIntent);
            statusText.setText("Monitoring Stopped");
        });
    }

    private boolean hasUsageStatsPermission(Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(
                    "android:get_usage_stats",
                    android.os.Process.myUid(),
                    context.getPackageName()
            );
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }
}
