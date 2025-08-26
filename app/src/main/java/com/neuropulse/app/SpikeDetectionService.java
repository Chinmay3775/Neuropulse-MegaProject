package com.neuropulse.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;

public class SpikeDetectionService extends Service {

    private static final String CHANNEL_ID = "NeuropulseSpikeChannel";

    // Store app usage counts and last open times
    private final HashMap<String, Long> lastUsageTime = new HashMap<>();
    private final HashMap<String, Integer> usageCount = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String packageName = intent.getStringExtra("packageName");
        if (packageName != null) {
            detectSpike(packageName);
        }
        return START_NOT_STICKY;
    }

    private void detectSpike(String packageName) {
        long currentTime = System.currentTimeMillis();

        // Track last usage time
        long lastTime = lastUsageTime.getOrDefault(packageName, 0L);
        int count = usageCount.getOrDefault(packageName, 0);

        // Update usage stats
        if (currentTime - lastTime < 10000) { // if reopened within 10 sec
            count++;
        } else {
            count = 1; // reset
        }

        lastUsageTime.put(packageName, currentTime);
        usageCount.put(packageName, count);

        Log.d("Neuropulse", "App: " + packageName + " count: " + count);

        // Spike detected if opened more than 3 times within short time
        if (count >= 3) {
            Log.w("Neuropulse", "⚠ Dopamine spike detected in: " + packageName);
            sendSpikeNotification(packageName);
            usageCount.put(packageName, 0); // reset after spike
        }
    }

    private void sendSpikeNotification(String packageName) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Neuropulse Monitoring")
                .setContentText("Detecting dopamine spikes...")
                .setSmallIcon(R.mipmap.ic_launcher)   // ✅ fixed here
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();


        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify((int) System.currentTimeMillis(), notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Neuropulse Spike Detection",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not used
    }
}
