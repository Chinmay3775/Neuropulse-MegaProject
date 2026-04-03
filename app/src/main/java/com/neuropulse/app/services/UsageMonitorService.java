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
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.neuropulse.app.features.EnhancedFeatureExtractor;
import com.neuropulse.app.features.ScrollTracker;
import com.neuropulse.app.features.RealTimeAppDetector;
import com.neuropulse.app.features.StreakManager;
import com.neuropulse.app.features.UsageIntelligence;
import com.neuropulse.app.ml.AddictionPredictor;
import com.neuropulse.app.ml.RiskThresholds;
import com.neuropulse.app.models.SessionFeatures;
import com.neuropulse.app.utils.AlertResponseTracker;

/**
 * Background foreground service for usage monitoring.
 * Now uses OverlayManager for system-level interventions (overlays on top of any app)
 * instead of launching Activity-based alerts.
 *
 * This service runs alongside NeuropulseAccessibilityService as a fallback,
 * providing the foreground notification and continuous monitoring when
 * the AccessibilityService is not enabled.
 */
public class UsageMonitorService extends Service {

    private static final String TAG = "UsageMonitorService";
    private static final String CHANNEL_ID = "neuropulse_monitor";
    private static final int NOTIF_ID = 101;

    private static final long CHECK_INTERVAL_MS = 2000L;

    private Handler handler;
    private Runnable monitorRunnable;

    private EnhancedFeatureExtractor featureExtractor;
    private RealTimeAppDetector appDetector;
    private AddictionPredictor predictor;
    private AlertResponseTracker alertTracker;
    private CooldownManager cooldownManager;
    private StreakManager streakManager;
    private OverlayManager overlayManager;

    private String currentPackage = null;
    private long sessionStartTime = 0L;

    // Risk persistence
    private float persistedRisk = 0f;
    private long lastAppExitTime = 0L;
    private long lastAlertTime = 0L;
    private int previousCategory = -1;

    // Notification state
    private String lastNotificationTitle = null;
    private String lastNotificationContent = null;
    private long lastNotificationTime = 0L;

    // ML Pacing
    private long lastMLPredictionTime = 0L;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Monitoring active", "Neuropulse is running in background"));

        handler = new Handler(Looper.getMainLooper());

        featureExtractor = new EnhancedFeatureExtractor(this);
        appDetector = new RealTimeAppDetector(this);
        predictor = new AddictionPredictor(this);
        alertTracker = new AlertResponseTracker(this);
        cooldownManager = new CooldownManager(this);
        streakManager = new StreakManager(this);
        overlayManager = new OverlayManager(this);

        // Only start the polling loop if AccessibilityService is NOT running
        // (AccessibilityService handles monitoring more efficiently via events)
        if (!NeuropulseAccessibilityService.isServiceRunning()) {
            startMonitoringLoop();
        } else {
            Log.i(TAG, "AccessibilityService is active — skipping polling loop");
            stopForeground(true);
            stopSelf();
        }
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
        if (predictor != null) {
            predictor.close();
        }
        if (overlayManager != null) {
            overlayManager.dismissAll();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Monitoring state
    private boolean isMonitoring = false;

    // ================= CORE MONITORING LOOP =================

    private void startMonitoringLoop() {
        monitorRunnable = () -> {
            if (isMonitoring) return;
            isMonitoring = true;

            try {
                if (NeuropulseAccessibilityService.isServiceRunning()) {
                    Log.i(TAG, "AccessibilityService now active — stopping poll loop");
                    stopForeground(true);
                    stopSelf();
                    isMonitoring = false;
                    return;
                }

                RealTimeAppDetector.CurrentAppInfo app = appDetector.getCurrentAppWithRisk();

                if (app == null || app.packageName.equals("unknown")) {
                    decayRisk();
                    return;
                }

                long now = System.currentTimeMillis();

                // 1. Check if App is Blocked
                if (cooldownManager.isInCooldown(app.packageName) || cooldownManager.isCategoryBlocked(app.category)) {
                    showBlockingOverlay(app.packageName);
                    decayRisk();
                    return;
                } else if (overlayManager.isBlockingShowing()) {
                    overlayManager.dismissBlockingOverlay();
                }

                // 2. Handle App Transition
                if (!app.packageName.equals(currentPackage)) {
                    if (currentPackage != null) {
                        lastAppExitTime = now;
                    }
                    currentPackage = app.packageName;
                    sessionStartTime = now;
                    
                    if (overlayManager.isBlockingShowing()) {
                        overlayManager.dismissBlockingOverlay();
                    }
                    if (overlayManager.isAlertShowing()) {
                        overlayManager.dismissAlertOverlay();
                    }

                    featureExtractor.recordSession();
                    predictor.resetHistory();
                    lastMLPredictionTime = 0;
                    ScrollTracker.getInstance().onAppTransition();
                }

                // 3. Paced ML Inference (every ~25s)
                if (now - lastMLPredictionTime >= RiskThresholds.ML_PREDICTION_INTERVAL_MS) {
                    lastMLPredictionTime = now;

                    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                    executor.execute(() -> {
                        SessionFeatures features = featureExtractor.extract(app.category, sessionStartTime, now);
                        AddictionPredictor.PredictionResult result = predictor.predict(features);

                        handler.post(() -> {
                            if (!app.packageName.equals(currentPackage)) {
                                return;
                            }
                            
                            float currentAppRisk = result.dopamineRisk;
                            persistedRisk = UsageIntelligence.adjustRiskForContext(currentAppRisk, previousCategory, app.category);
                            featureExtractor.recordRisk(persistedRisk);

                            long timeSinceExit = now - lastAppExitTime;
                            if (timeSinceExit <= 30000 && lastAppExitTime > 0) {
                                persistedRisk = Math.max(persistedRisk, currentAppRisk);
                            }

                            previousCategory = app.category;
                            evaluateIntervention(app, features, result);
                        });
                    });
                } else {
                    updateNotification(app.displayName, RiskThresholds.getRiskLevel(persistedRisk) + " Risk", false);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in monitoring loop", e);
            } finally {
                isMonitoring = false;
                reschedule();
            }
        };

        handler.post(monitorRunnable);
    }

    private void decayRisk() {
        persistedRisk = Math.max(0f, persistedRisk - 0.02f);
    }

    private void reschedule() {
        handler.postDelayed(monitorRunnable, RiskThresholds.MONITOR_INTERVAL_MS);
    }

    // ================= INTERVENTION LOGIC =================

    private void evaluateIntervention(RealTimeAppDetector.CurrentAppInfo app, SessionFeatures features,
            AddictionPredictor.PredictionResult result) {

        if (UsageIntelligence.shouldSuppressAlert(app.packageName, app.category, persistedRisk)) {
            updateNotification(app.displayName, "Productive Session", false);
            return;
        }

        if (persistedRisk >= RiskThresholds.ALERT_THRESHOLD) {

            if (persistedRisk >= RiskThresholds.HIGH_RISK) {
                streakManager.recordHighRiskSession();
            }

            long now = System.currentTimeMillis();
            if (now - lastAlertTime > RiskThresholds.ALERT_COOLDOWN_MS) {

                if (overlayManager.isAlertShowing()) return;

                if (alertTracker.shouldEscalate()) {
                    Log.i(TAG, "Escalating to cooldown: " + alertTracker.getConsecutiveContinueCount() + " continues");
                    cooldownManager.startCooldown(app.packageName, app.category, persistedRisk, features.sessionCount);
                    alertTracker.resetOnBreak();
                    showBlockingOverlay(app.packageName);
                } else {
                    Log.i(TAG, "Triggering Overlay Alert for: " + app.packageName + " Risk: " + persistedRisk);
                    lastAlertTime = now;
                    showAlert(app, features, result);
                }
                updateNotification(app.displayName, "Take a break!", true);
            } else {
                updateNotification(app.displayName, "Monitoring Risk...", false);
            }
        } else {
            updateNotification(app.displayName, RiskThresholds.getRiskLevel(persistedRisk) + " Risk", false);
        }
    }

    // ================= OVERLAY-BASED INTERVENTIONS =================

    private void showAlert(RealTimeAppDetector.CurrentAppInfo app, SessionFeatures features,
                           AddictionPredictor.PredictionResult result) {

        final String pkgName = app.packageName;
        final int category = app.category;

        overlayManager.showAlertOverlay(
                app.displayName,
                (int) (features.sessionDurationMs / 60000),
                persistedRisk,
                result.reason,
                pkgName, category,
                new OverlayManager.AlertActionCallback() {
                    @Override
                    public void onTakeBreak(String packageName, int cat, float risk) {
                        cooldownManager.startCooldown(packageName, cat, risk, 1);
                        showBlockingOverlay(packageName);
                    }

                    @Override
                    public void onContinue(String packageName, int cat, float risk) {
                        if (alertTracker.shouldEscalate()) {
                            Log.i(TAG, "Escalating on 3rd continue for: " + packageName);
                            cooldownManager.startCooldown(packageName, cat, risk, 5);
                            showBlockingOverlay(packageName);
                        }
                    }

                    @Override
                    public void onAlertDismissed() {
                        // Ignored alert — escalation tracker handles it
                    }
                }
        );
    }

    private void showBlockingOverlay(String packageName) {
        long durationMs = cooldownManager.getGlobalCooldownRemainingMs();
        if (durationMs <= 0) {
            durationMs = cooldownManager.getCooldownRemainingMs(packageName);
        }
        if (durationMs <= 0) return;

        overlayManager.showBlockingOverlay(durationMs);
    }

    // ================= NOTIFICATION =================

    private void updateNotification(String appName, String status, boolean isAlert) {
        long now = System.currentTimeMillis();
        String title = isAlert ? "⚠️ Dopamine Spike Detected" : "NeuroPulse: Monitoring";
        String content = appName + " | " + status;

        boolean contentChanged = !title.equals(lastNotificationTitle) || !content.equals(lastNotificationContent);
        boolean isCooldownOver = (now - lastNotificationTime >= 5000L);

        if (!contentChanged && !isAlert) return;
        if (!isCooldownOver && !isAlert && contentChanged) return;

        lastNotificationTitle = title;
        lastNotificationContent = content;
        lastNotificationTime = now;

        Notification notification = buildNotification(title, content);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, notification);
        }
    }

    private Notification buildNotification(String title, String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Neuropulse Monitoring",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
