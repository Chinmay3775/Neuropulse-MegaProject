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
import com.neuropulse.app.features.RealTimeAppDetector;
import com.neuropulse.app.features.StreakManager;
import com.neuropulse.app.features.UsageIntelligence;
import com.neuropulse.app.ml.AddictionPredictor;
import com.neuropulse.app.ml.RiskThresholds;
import com.neuropulse.app.models.SessionFeatures;
import com.neuropulse.app.ui.AlertActivity;
import com.neuropulse.app.ui.BlockingOverlayActivity;
import com.neuropulse.app.utils.AlertResponseTracker;

public class UsageMonitorService extends Service {

    private static final String TAG = "UsageMonitorService";
    private static final String CHANNEL_ID = "neuropulse_monitor";
    private static final int NOTIF_ID = 101;

    private static final long CHECK_INTERVAL_MS = 2000L; // Check every 2 seconds

    private Handler handler;
    private Runnable monitorRunnable;

    private EnhancedFeatureExtractor featureExtractor;
    private RealTimeAppDetector appDetector;
    private AddictionPredictor predictor;
    private AlertResponseTracker alertTracker;
    private CooldownManager cooldownManager;
    private StreakManager streakManager;

    private String currentPackage = null;
    private long sessionStartTime = 0L;
    
    // Risk persistence
    private float persistedRisk = 0f;
    private long lastAppExitTime = 0L;
    private long lastAlertTime = 0L;
    private int previousCategory = -1;

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
        if (predictor != null) {
            predictor.close();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ================= CORE MONITORING LOOP =================

    private void startMonitoringLoop() {
        monitorRunnable = () -> {
            try {
                RealTimeAppDetector.CurrentAppInfo app = appDetector.getCurrentAppWithRisk();

                if (app == null || app.packageName.equals("unknown")) {
                    decayRisk();
                    reschedule();
                    return;
                }

                long now = System.currentTimeMillis();

                // 1. Check if App is Blocked
                if (cooldownManager.isInCooldown(app.packageName) || cooldownManager.isCategoryBlocked(app.category)) {
                    showBlockingOverlay(app.packageName);
                    decayRisk();
                    reschedule();
                    return;
                }

                // 2. Handle App Transition
                if (!app.packageName.equals(currentPackage)) {
                    if (currentPackage != null) {
                        lastAppExitTime = now;
                    }
                    currentPackage = app.packageName;
                    sessionStartTime = now;
                    featureExtractor.recordSession();
                }

                // 3. Extract Features & Predict
                SessionFeatures features = featureExtractor.extract(app.category, sessionStartTime, now);
                AddictionPredictor.PredictionResult result = predictor.predict(features);

                // 4. Update Risk State (Contextual)
                float currentAppRisk = result.dopamineRisk;
                currentAppRisk = UsageIntelligence.adjustRiskForContext(currentAppRisk, previousCategory, app.category);
                
                // Track trend
                featureExtractor.recordRisk(currentAppRisk);

                // Update persistent risk
                long timeSinceExit = now - lastAppExitTime;
                if (timeSinceExit <= 30000) { // 30s window to resume risk level
                    persistedRisk = Math.max(persistedRisk, currentAppRisk);
                } else if (!RiskThresholds.isHighStimCategory(app.category) && UsageIntelligence.isProductivePackage(app.packageName)) {
                   // Faster decay for productive apps
                   persistedRisk = Math.max(0f, persistedRisk - 0.05f); 
                } else {
                   // Normal decay
                   persistedRisk = Math.max(0f, persistedRisk - 0.02f);
                }
                persistedRisk = Math.max(persistedRisk, currentAppRisk); // Floor to current
                previousCategory = app.category;

                // 5. Evaluate Alert & Escalation Logic
                evaluateIntervention(app, features, result);

            } catch (Exception e) {
                Log.e(TAG, "Error in monitoring loop", e);
            } finally {
                reschedule();
            }
        };

        handler.post(monitorRunnable);
    }
    
    private void decayRisk() {
        persistedRisk = Math.max(0f, persistedRisk - 0.02f);
    }

    private void reschedule() {
        handler.postDelayed(monitorRunnable, CHECK_INTERVAL_MS);
    }

    // ================= INTERVENTION LOGIC =================
    
    private void evaluateIntervention(RealTimeAppDetector.CurrentAppInfo app, SessionFeatures features, AddictionPredictor.PredictionResult result) {
        
        // Check if we should suppress the alert (e.g., productive app)
        if (UsageIntelligence.shouldSuppressAlert(app.packageName, app.category, persistedRisk)) {
            updateNotification(app.displayName, "Productive Session", false);
            return;
        }

        // Check if alert threshold met
        if (persistedRisk >= RiskThresholds.ALERT_THRESHOLD) {
            
            // Record high risk session for streaks
            if (persistedRisk >= RiskThresholds.HIGH_RISK) {
                streakManager.recordHighRiskSession();
            }

            long now = System.currentTimeMillis();
            if (now - lastAlertTime > RiskThresholds.ALERT_COOLDOWN_MS) {
                
                // Escalate to block?
                if (alertTracker.shouldEscalate()) {
                    Log.i(TAG, "Escalating to cooldown: " + alertTracker.getConsecutiveContinueCount() + " continues");
                    cooldownManager.startCooldown(app.packageName, app.category, persistedRisk, features.sessionCount);
                    alertTracker.resetOnBreak();
                    showBlockingOverlay(app.packageName);
                } else {
                    // Show standard alert
                    Log.i(TAG, "Triggering Alert for: " + app.packageName + " Risk: " + persistedRisk);
                    lastAlertTime = now;
                    showAlert(app.displayName, (int)(features.sessionDurationMs / 60000), persistedRisk, result.reason);
                }
                updateNotification(app.displayName, "Take a break!", true);
            } else {
                updateNotification(app.displayName, "Monitoring Risk...", false);
            }
        } else {
            updateNotification(app.displayName, RiskThresholds.getRiskLevel(persistedRisk) + " Risk", false);
        }
    }

    // ================= UI NAVIGATION =================

    private void showAlert(String appName, int durationMin, float risk, String reason) {
        Intent intent = new Intent(this, AlertActivity.class);
        intent.putExtra(AlertActivity.EXTRA_APP_NAME, appName);
        intent.putExtra(AlertActivity.EXTRA_DURATION_MIN, durationMin);
        intent.putExtra(AlertActivity.EXTRA_RISK, risk);
        intent.putExtra(AlertActivity.EXTRA_REASON, reason);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        final String finalPackageName = currentPackage;
        final int finalCategory = previousCategory;

        // Setup the callback
        AlertActivity.callback = new AlertActivity.AlertCallback() {
            @Override
            public void onTakeBreak() {
                // User chose break - start a short cooldown
                if (finalPackageName != null) {
                    cooldownManager.startCooldown(finalPackageName, finalCategory, risk, 1);
                    showBlockingOverlay(finalPackageName);
                }
            }

            @Override
            public void onContinue() {
                // Do nothing
            }
        };
        
        startActivity(intent);
    }

    private void showBlockingOverlay(String packageName) {
        long durationMs = cooldownManager.getGlobalCooldownRemainingMs();
        if (durationMs <= 0) {
           durationMs = cooldownManager.getCooldownRemainingMs(packageName);
        }
        if (durationMs <= 0) return; // safeguard

        Intent intent = new Intent(this, BlockingOverlayActivity.class);
        intent.putExtra(BlockingOverlayActivity.EXTRA_APP_NAME, packageName);
        intent.putExtra(BlockingOverlayActivity.EXTRA_DURATION_MS, durationMs);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    // ================= NOTIFICATION =================

    private void updateNotification(String appName, String status, boolean isAlert) {
        String title = isAlert ? "⚠️ High Risk Detected" : "Neuropulse Active";
        String content = appName + " - " + status;
        
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
