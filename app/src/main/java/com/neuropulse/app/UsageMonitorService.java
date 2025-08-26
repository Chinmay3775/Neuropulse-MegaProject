package com.neuropulse.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class UsageMonitorService extends Service {

    private static final String CHANNEL_ID = "NeuropulseMonitorChannel";
    private Handler handler;
    private Runnable monitorRunnable;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Neuropulse")
                .setContentText("Monitoring app usage in background")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        startForeground(1, notification);

        handler = new Handler();
        monitorRunnable = this::checkUsageEvents;
        handler.postDelayed(monitorRunnable, 2000); // Start monitoring
    }

    private void checkUsageEvents() {
        try {
            long endTime = System.currentTimeMillis();
            long startTime = endTime - 2000;

            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                UsageEvents events = usm.queryEvents(startTime, endTime);
                UsageEvents.Event event = new UsageEvents.Event();

                while (events.hasNextEvent()) {
                    events.getNextEvent(event);

                    if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                        Log.d("Neuropulse", "App opened: " + event.getPackageName());

                        // Send app usage data to SpikeDetectionService
                        Intent intent = new Intent(this, SpikeDetectionService.class);
                        intent.putExtra("packageName", event.getPackageName());
                        startService(intent);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("Neuropulse", "Error monitoring usage: " + e.getMessage());
        }

        handler.postDelayed(monitorRunnable, 2000); // Repeat every 2 seconds
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(monitorRunnable);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not used
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Neuropulse Monitoring",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
