package com.neuropulse.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.neuropulse.app.features.EnhancedFeatureExtractor;
import com.neuropulse.app.features.RealTimeAppDetector;
import com.neuropulse.app.ml.AddictionPredictor;
import com.neuropulse.app.models.SessionFeatures;
import com.neuropulse.app.ui.AlertActivity;


public class UsageMonitorService extends Service {

    private static final String CHANNEL_ID = "neuropulse_monitor";
    private static final int NOTIF_ID = 101;

    private static final long CHECK_INTERVAL_MS = 8000L;

    private static final boolean DEBUG_MODE = true;

    private Handler handler;
    private Runnable monitorRunnable;

    private EnhancedFeatureExtractor featureExtractor;
    private RealTimeAppDetector appDetector;
    private AddictionPredictor predictor;

    private String currentPackage = null;
    private long sessionStartTime = 0L;
    // ================= COOLDOWN STATE =================
    private static final long COOLDOWN_WINDOW_MS = 5 * 60 * 1000L; // 5 minutes
    private static final float RISK_DECAY_RATE = 0.05f; // per cycle

    private float persistedRisk = 0f;
    private long lastAlertTime = 0L;
    private long lastAppExitTime = 0L;


    // ================= SERVICE LIFECYCLE =================

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Monitoring started"));

        handler = new Handler(Looper.getMainLooper());

        featureExtractor = new EnhancedFeatureExtractor(this);
        appDetector = new RealTimeAppDetector(this);
        predictor = new AddictionPredictor(this);

        startMonitoringLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && monitorRunnable != null) {
            handler.removeCallbacks(monitorRunnable);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ================= CORE MONITORING LOOP =================

    private void startMonitoringLoop() {
        monitorRunnable = () -> {

            RealTimeAppDetector.CurrentAppInfo app =
                    appDetector.getCurrentAppWithRisk();

            if (app == null || app.packageName.equals("unknown")) {
                reschedule();
                return;
            }

            long now = System.currentTimeMillis();

            // New session
            if (!app.packageName.equals(currentPackage)) {

                // Track exit time of previous app
                if (currentPackage != null) {
                    lastAppExitTime = now;
                }

                currentPackage = app.packageName;
                sessionStartTime = now;
            }


            // Extract features
            SessionFeatures features = featureExtractor.extract(
                    app.category,
                    sessionStartTime,
                    now
            );

            // Predict
            AddictionPredictor.PredictionResult result =
                    predictor.predict(features);
            // ================= RISK PERSISTENCE & COOLDOWN =================
            long timeSinceExit = now - lastAppExitTime;

            // If app reopened within cooldown window → continue risk
            if (timeSinceExit <= COOLDOWN_WINDOW_MS) {
                persistedRisk = Math.max(persistedRisk, result.dopamineRisk);
            }
            // If cooldown expired → decay risk slowly
            else {
                persistedRisk = Math.max(
                        0f,
                        persistedRisk - RISK_DECAY_RATE
                );
            }

            // Risk should never be lower than current model prediction
            persistedRisk = Math.max(persistedRisk, result.dopamineRisk);

            if (DEBUG_MODE) {
                android.util.Log.d(
                        "NeuropulseDebug",
                        "App=" + app.displayName +
                                ", RawRisk=" + String.format("%.4f", result.dopamineRisk) +
                                ", PersistedRisk=" + String.format("%.4f", persistedRisk) +
                                ", Duration=" + (features.sessionDurationMs / 60000) + " min"
                );
            }


            updateNotification(app.displayName, result, features);

            reschedule();
        };

        handler.post(monitorRunnable);
    }

    private void reschedule() {
        handler.postDelayed(monitorRunnable, CHECK_INTERVAL_MS);
    }

    // ================= NOTIFICATION =================

    private void updateNotification(
            String appName,
            AddictionPredictor.PredictionResult result,
            SessionFeatures features
    ) {
        float risk = persistedRisk;
        long duration = features.sessionDurationMs;

        String text = "✅ Healthy usage: " + appName;

        // Alert threshold
        boolean alert =
                risk >= 0.6f &&
                        duration >= 15 * 60 * 1000L &&
                        features.appCategory == 0; // Social media

        if (alert && (System.currentTimeMillis() - lastAlertTime > COOLDOWN_WINDOW_MS)) {
            text = "⚠ Take a break from " + appName;

            lastAlertTime = System.currentTimeMillis();
            persistedRisk = Math.max(persistedRisk, risk); // lock risk

            showPopupAlert(appName);
        }


        Notification notification = buildNotification(text);

        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, notification);
        }
    }


    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Neuropulse Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // ================= CHANNEL =================

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
    private void showPopupAlert(String appName) {
        Intent intent = new Intent(this, AlertActivity.class);
        intent.putExtra("app_name", appName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }


}
