package com.neuropulse.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.neuropulse.app.features.ScrollTracker;

import androidx.core.app.NotificationCompat;

import com.neuropulse.app.features.EnhancedFeatureExtractor;
import com.neuropulse.app.features.RealTimeAppDetector;
import com.neuropulse.app.features.StreakManager;
import com.neuropulse.app.features.UsageIntelligence;
import com.neuropulse.app.ml.AddictionPredictor;
import com.neuropulse.app.ml.RiskThresholds;
import com.neuropulse.app.models.SessionFeatures;
import com.neuropulse.app.utils.AlertResponseTracker;

/**
 * NeuroPulse Accessibility Service — the core real-time intervention engine.
 *
 * Monitors foreground app transitions using AccessibilityService events,
 * runs ML predictions when target (addictive) apps are detected, and
 * triggers system-level overlay interventions via OverlayManager.
 *
 * This replaces the polling-based approach in UsageMonitorService with
 * event-driven detection that's more responsive and battery-efficient.
 */
public class NeuropulseAccessibilityService extends AccessibilityService {

    private static final String TAG = "NeuropulseA11yService";
    private static final String CHANNEL_ID = "neuropulse_a11y";
    private static final int NOTIF_ID = 201;

    // Singleton reference for status checks from other components
    private static NeuropulseAccessibilityService instance = null;

    // Core components
    private EnhancedFeatureExtractor featureExtractor;
    private RealTimeAppDetector appDetector;
    private AddictionPredictor predictor;
    private AlertResponseTracker alertTracker;
    private CooldownManager cooldownManager;
    private StreakManager streakManager;
    private OverlayManager overlayManager;

    // Handler for ML pacing + periodic checks
    private Handler handler;
    private Runnable periodicRunnable;

    // State tracking
    private String currentForegroundPackage = null;
    private long sessionStartTime = 0L;
    private float persistedRisk = 0f;
    private long lastAlertTime = 0L;
    private int previousCategory = -1;
    private long lastMLPredictionTime = 0L;
    private long lastAppExitTime = 0L;

    // Notification dedup
    private String lastNotificationTitle = null;
    private String lastNotificationContent = null;
    private long lastNotificationTime = 0L;

    // Stabilization — require package to remain in foreground
    private String candidatePackage = null;
    private long candidateSince = 0L;
    private static final long STABILIZATION_MS = 800L;
    private Runnable transitionRunnable = null;

    // ========================= LIFECYCLE =========================

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AccessibilityService created");
        instance = this;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "AccessibilityService connected — initializing components");

        createNotificationChannel();

        // Initialize all components
        featureExtractor = new EnhancedFeatureExtractor(this);
        appDetector = new RealTimeAppDetector(this);
        predictor = new AddictionPredictor(this);
        alertTracker = new AlertResponseTracker(this);
        cooldownManager = new CooldownManager(this);
        streakManager = new StreakManager(this);
        overlayManager = new OverlayManager(this);

        handler = new Handler(Looper.getMainLooper());

        // Configure event types: window changes (app detection) + scroll events (gesture tracking)
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.notificationTimeout = 100;
            setServiceInfo(info);
            Log.i(TAG, "Configured for TYPE_WINDOW_STATE_CHANGED + TYPE_VIEW_SCROLLED");
        }

        // Periodic ML check (runs every 2s to evaluate even when no window changes occur)
        startPeriodicCheck();

        // Show persistent notification
        showPersistentNotification("Monitoring active", "NeuroPulse is protecting you");

        Log.i(TAG, "AccessibilityService fully initialized");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();

        // ---- SCROLL EVENT: record real user gestures ----
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            CharSequence pkgCs = event.getPackageName();
            if (pkgCs != null && !isIgnorablePackage(pkgCs.toString())) {
                ScrollTracker.getInstance().recordScrollEvent();
            }
            return;
        }

        // ---- WINDOW STATE CHANGED: detect app transitions ----
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence packageCs = event.getPackageName();
            if (packageCs == null) return;

            String packageName = packageCs.toString();

            // Ignore system overlays but NOT transitions to launcher/settings
            if (isSystemOverlay(packageName)) return;

            if (packageName.equals(currentForegroundPackage)) {
                candidatePackage = packageName;
                if (transitionRunnable != null) {
                    handler.removeCallbacks(transitionRunnable);
                    transitionRunnable = null;
                }
                return;
            }

            // Stabilize — require the package to remain in foreground
            if (!packageName.equals(candidatePackage)) {
                candidatePackage = packageName;
                
                if (transitionRunnable != null) {
                    handler.removeCallbacks(transitionRunnable);
                }
                
                transitionRunnable = () -> {
                    if (packageName.equals(candidatePackage) && !packageName.equals(currentForegroundPackage)) {
                        onAppTransition(packageName, System.currentTimeMillis());
                    }
                };
                handler.postDelayed(transitionRunnable, STABILIZATION_MS);
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "AccessibilityService destroyed");
        instance = null;

        if (handler != null && periodicRunnable != null) {
            handler.removeCallbacks(periodicRunnable);
        }
        if (predictor != null) predictor.close();
        if (overlayManager != null) overlayManager.dismissAll();
    }

    // ========================= STATIC HELPERS =========================

    /**
     * Returns true if the AccessibilityService is currently running.
     */
    public static boolean isServiceRunning() {
        return instance != null;
    }

    /**
     * Returns true if the AccessibilityService is enabled in device settings.
     */
    public static boolean isAccessibilityEnabled(Context context) {
        String prefString = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (prefString == null) return false;

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(prefString);

        ComponentName myService = new ComponentName(context, NeuropulseAccessibilityService.class);
        while (splitter.hasNext()) {
            String componentName = splitter.next();
            ComponentName enabled = ComponentName.unflattenFromString(componentName);
            if (enabled != null && enabled.equals(myService)) {
                return true;
            }
        }
        return false;
    }

    // ========================= APP TRANSITION =========================

    private void onAppTransition(String newPackage, long now) {
        Log.i(TAG, "🔄 App transition: " + currentForegroundPackage + " → " + newPackage);

        if (currentForegroundPackage != null) {
            lastAppExitTime = now;
        }

        currentForegroundPackage = newPackage;
        sessionStartTime = now;

        // Inform the app detector immediately to prevent stale state 
        // during the immediate evaluation (fixes the 200ms race condition)
        appDetector.forcePackageUpdate(newPackage);

        featureExtractor.recordSession();
        predictor.resetHistory();
        lastMLPredictionTime = 0; // Force immediate prediction

        // Clear scroll buffer for the new app session
        ScrollTracker.getInstance().onAppTransition();

        // Check if the new app is in cooldown
        RealTimeAppDetector.CurrentAppInfo appInfo = appDetector.getCurrentAppWithRisk();
        if (cooldownManager.isInCooldown(newPackage) || cooldownManager.isCategoryBlocked(appInfo.category)) {
            showBlockingOverlay(newPackage);
            return;
        }

        // Clean up any overlays from the previous app
        if (overlayManager.isBlockingShowing()) {
            overlayManager.dismissBlockingOverlay();
        }
        if (overlayManager.isAlertShowing()) {
            overlayManager.dismissAlertOverlay();
        }

        // Trigger immediate evaluation
        evaluateCurrentApp();
    }

    // ========================= PERIODIC MONITOR =========================

    private void startPeriodicCheck() {
        periodicRunnable = () -> {
            try {
                if (currentForegroundPackage != null) {
                    evaluateCurrentApp();
                } else {
                    decayRisk();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in periodic check", e);
            }
            handler.postDelayed(periodicRunnable, RiskThresholds.MONITOR_INTERVAL_MS);
        };
        handler.postDelayed(periodicRunnable, RiskThresholds.MONITOR_INTERVAL_MS);
    }

    // ========================= EVALUATION =========================

    private void evaluateCurrentApp() {
        if (currentForegroundPackage == null || isIgnorablePackage(currentForegroundPackage)) {
            decayRisk();
            return;
        }

        long now = System.currentTimeMillis();
        RealTimeAppDetector.CurrentAppInfo app = appDetector.getCurrentAppWithRisk();
        Log.d(TAG, "Evaluating app: " + app.packageName + " | Risk: " + persistedRisk);

        // 1. Cooldown check
        if (cooldownManager.isInCooldown(currentForegroundPackage) ||
                cooldownManager.isCategoryBlocked(app.category)) {
            
            // Only dismiss and show if not already showing the blocking view
            if (!overlayManager.isBlockingShowing()) {
                Log.i(TAG, "🚧 Entering blocking overlay (Cooldown/Category)");
                showBlockingOverlay(currentForegroundPackage);
            }
            decayRisk();
            return;
        }

        // 2. If blocking overlay is showing but no cooldown — dismiss it
        if (overlayManager.isBlockingShowing()) {
            overlayManager.dismissBlockingOverlay();
        }

        // 3. Paced ML Inference (every ~25s)
        if (now - lastMLPredictionTime >= RiskThresholds.ML_PREDICTION_INTERVAL_MS) {
            lastMLPredictionTime = now;

            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                SessionFeatures features = featureExtractor.extract(app.category, sessionStartTime, now);
                AddictionPredictor.PredictionResult result = predictor.predict(features);

                handler.post(() -> {
                    // Abort if the user switched apps while ML was running
                    if (!app.packageName.equals(currentForegroundPackage)) {
                        return;
                    }

                    // Update risk state
                    float currentAppRisk = result.dopamineRisk;
                    persistedRisk = UsageIntelligence.adjustRiskForContext(currentAppRisk, previousCategory, app.category);
                    featureExtractor.recordRisk(persistedRisk);

                    // Risk persistence after app transition
                    long timeSinceExit = now - lastAppExitTime;
                    if (timeSinceExit <= 30000 && lastAppExitTime > 0) {
                        persistedRisk = Math.max(persistedRisk, currentAppRisk);
                    }

                    previousCategory = app.category;

                    // Evaluate intervention
                    evaluateIntervention(app, features, result);
                });
            });
        } else {
            // Update notification even when no ML run
            updateNotification(app.displayName, RiskThresholds.getRiskLevel(persistedRisk) + " Risk", false);
        }
    }

    // ========================= INTERVENTION =========================

    private void evaluateIntervention(RealTimeAppDetector.CurrentAppInfo app, SessionFeatures features,
                                       AddictionPredictor.PredictionResult result) {

        // Suppress for productive apps
        if (UsageIntelligence.shouldSuppressAlert(app.packageName, app.category, persistedRisk)) {
            updateNotification(app.displayName, "Productive Session", false);
            return;
        }

        if (persistedRisk >= RiskThresholds.ALERT_THRESHOLD) {
            Log.i(TAG, "Risk above threshold: " + persistedRisk + " vs " + RiskThresholds.ALERT_THRESHOLD);

            // Record high-risk session for streaks
            if (persistedRisk >= RiskThresholds.HIGH_RISK) {
                streakManager.recordHighRiskSession();
            }

            long now = System.currentTimeMillis();
            if (now - lastAlertTime > RiskThresholds.ALERT_COOLDOWN_MS) {

                // Don't show alert if one is already on screen
                if (overlayManager.isAlertShowing()) {
                    Log.d(TAG, "Alert already showing, skipping trigger");
                    return;
                }

                if (alertTracker.shouldEscalate()) {
                    // Escalate to cooldown
                    Log.i(TAG, "Escalating to cooldown: " + alertTracker.getConsecutiveContinueCount() + " continues");
                    cooldownManager.startCooldown(app.packageName, app.category, persistedRisk, features.sessionCount);
                    alertTracker.resetOnBreak();
                    showBlockingOverlay(app.packageName);

                    // Force user to home screen
                    minimizeApp();
                } else {
                    // Show alert overlay
                    Log.i(TAG, "Triggering overlay alert for: " + app.packageName + " Risk: " + persistedRisk);
                    lastAlertTime = now;

                    final String pkgName = app.packageName;
                    final int cat = app.category;

                    overlayManager.showAlertOverlay(
                            app.displayName,
                            (int) (features.sessionDurationMs / 60000),
                            persistedRisk,
                            result.reason,
                            pkgName, cat,
                            new OverlayManager.AlertActionCallback() {
                                @Override
                                public void onTakeBreak(String packageName, int category, float risk) {
                                    cooldownManager.startCooldown(packageName, category, risk, 1);
                                    showBlockingOverlay(packageName);
                                    minimizeApp();
                                }

                                @Override
                                public void onContinue(String packageName, int category, float risk) {
                                    if (alertTracker.shouldEscalate()) {
                                        Log.i(TAG, "Escalating on 3rd continue for: " + packageName);
                                        cooldownManager.startCooldown(packageName, category, risk, 5);
                                        showBlockingOverlay(packageName);
                                        minimizeApp();
                                    }
                                }

                                @Override
                                public void onAlertDismissed() {
                                    // Ignored — escalation tracker already updated
                                }
                            }
                    );
                }
                updateNotification(app.displayName, "Take a break!", true);
            } else {
                updateNotification(app.displayName, "Monitoring Risk...", false);
            }
        } else {
            updateNotification(app.displayName, RiskThresholds.getRiskLevel(persistedRisk) + " Risk", false);
        }
    }

    // ========================= OVERLAYS =========================

    private void showBlockingOverlay(String packageName) {
        long durationMs = cooldownManager.getGlobalCooldownRemainingMs();
        if (durationMs <= 0) {
            durationMs = cooldownManager.getCooldownRemainingMs(packageName);
        }
        if (durationMs <= 0) return;

        overlayManager.showBlockingOverlay(durationMs);
    }

    /**
     * Uses AccessibilityService's global action to navigate to the home screen,
     * effectively minimizing the addictive app.
     */
    private void minimizeApp() {
        boolean success = performGlobalAction(GLOBAL_ACTION_HOME);
        Log.i(TAG, "Minimize app (GLOBAL_ACTION_HOME): " + (success ? "success" : "failed"));
    }

    // ========================= NOTIFICATION =========================

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

        showPersistentNotification(title, content);
    }

    private void showPersistentNotification(String title, String content) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "NeuroPulse Protection",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows NeuroPulse monitoring status");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    // ========================= HELPERS =========================

    private void decayRisk() {
        persistedRisk = Math.max(0f, persistedRisk - 0.02f);
    }

    private boolean isSystemOverlay(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        String ownPackage = getPackageName();
        return pkg.equals(ownPackage) ||
                pkg.equals("android") ||
                pkg.contains("systemui") ||
                pkg.startsWith("com.google.android.permission");
    }

    private boolean isIgnorablePackage(String pkg) {
        if (isSystemOverlay(pkg)) return true;
        return pkg.contains("launcher") ||
                pkg.equals("com.android.settings");
    }
}
