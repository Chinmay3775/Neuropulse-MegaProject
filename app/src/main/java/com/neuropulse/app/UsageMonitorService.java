package com.neuropulse.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class UsageMonitorService extends Service {
    private static final String TAG = "UsageMonitorService";
    private static final int FOREGROUND_ID = 1337;
    private Handler handler;
    private Runnable poller;
    private UsageStatsManager usm;

    private String currentPackage = "";
    private long sessionStartTs = 0;
    private int appSwitchCount = 0;
    private int consecutiveSameAppMinutes = 0;

    private long lastPollTs = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        handler = new Handler();
        startForegroundServiceWithNotification();

        poller = new Runnable() {
            @Override
            public void run() {
                try {
                    pollForegroundApp();
                } catch (Exception e) {
                    Log.e(TAG, "poll error", e);
                } finally {
                    handler.postDelayed(this, 5000); // poll every 5s
                }
            }
        };
        handler.post(poller);
    }

    private void startForegroundServiceWithNotification() {
        createChannel();
        Notification n = new NotificationCompat.Builder(this, "neuropulse_channel")
                .setContentTitle("Neuropulse monitoring")
                .setContentText("Monitoring usage")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(FOREGROUND_ID, n);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("neuropulse_channel", "Neuropulse", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }

    private void pollForegroundApp() {
        long now = System.currentTimeMillis();
        if (now - lastPollTs < 4000) return;
        lastPollTs = now;

        UsageEvents usageEvents = usm.queryEvents(now - 60000, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String topPackage = null;
        long topTs = 0;
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(e);
            if (e.getEventType() == Event.MOVE_TO_FOREGROUND) {
                if (e.getTimeStamp() >= topTs) {
                    topTs = e.getTimeStamp();
                    topPackage = e.getPackageName();
                }
            }
        }

        if (topPackage == null) return;

        if (!topPackage.equals(currentPackage)) {
            // package changed → end previous session (if any)
            if (!currentPackage.isEmpty()) {
                long endTs = topTs;
                long durSec = (endTs - sessionStartTs) / 1000;
                createAndStoreSession(currentPackage, sessionStartTs, endTs, durSec, appSwitchCount, consecutiveSameAppMinutes);
            }
            // start new session
            currentPackage = topPackage;
            sessionStartTs = topTs;
            appSwitchCount = 0;
            consecutiveSameAppMinutes = 0;
        } else {
            // same package: increase consecutive same-app minutes (approximate)
            long deltaSec = (now - lastPollTs) / 1000;
            consecutiveSameAppMinutes += Math.max(0, (int) deltaSec / 60);
        }
        // increment app switch if needed (simple heuristic)
        appSwitchCount++;
    }

    private void createAndStoreSession(String pkg, long startTs, long endTs, long durSec, int appSwitches, int consecutiveMinutes) {
        SessionEntity s = new SessionEntity();
        s.userInstallId = getUserId();
        s.appPackage = pkg;
        s.appCategory = Utils.pkgToCategory(pkg);
        s.sessionStartTs = startTs;
        s.sessionEndTs = endTs;
        s.sessionDurationSec = durSec;
        s.unlocksLastHour = UnlockReceiver.getUnlockCount(this);
        s.appSwitchCount = appSwitches;
        s.consecutiveSameAppMinutes = consecutiveMinutes;
        s.notifCountLast30Min = NLService.getNotifCountLast30Min();
        s.returnAfterNotificationSec = -1; // advanced tracking omitted (requires correlating notif -> launch)
        s.nightFlag = Utils.isNight(startTs) ? 1 : 0;

        // Heuristic flags (bootstrap)
        s.bingeFlag = (durSec >= 30 * 60 && (s.appCategory.equals("social") || s.appCategory.equals("short-video") || s.appCategory.equals("gaming"))) ? 1 : 0;
        s.dopamineSpikeLabel = (s.bingeFlag == 1 || (s.unlocksLastHour >= 10 && s.appCategory.equals("social"))) ? 1 : 0;
        // addictionFlag can be computed in aggregates (not per-session now)

        // store synchronously for quick verification (use background thread for scale)
        AppDatabase.getInstance(getApplicationContext()).sessionDao().insert(s);
        Log.i(TAG, "Inserted session: " + pkg + " dur=" + durSec + "s binge=" + s.bingeFlag + " spike=" + s.dopamineSpikeLabel);
    }

    private String getUserId() {
        // persistent per-install ID
        android.content.SharedPreferences sp = getSharedPreferences("neuropulse_prefs", MODE_PRIVATE);
        String id = sp.getString("install_id", null);
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
            sp.edit().putString("install_id", id).apply();
        }
        return id;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poller);
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
