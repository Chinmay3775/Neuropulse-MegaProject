package com.neuropulse.app.services;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.neuropulse.app.MainActivity;
import com.neuropulse.app.R;
import com.neuropulse.app.ml.RiskThresholds;
import com.neuropulse.app.utils.AlertResponseTracker;
import com.neuropulse.app.utils.InterventionContentProvider;

/**
 * Manages overlay windows drawn on top of any application.
 * Uses SYSTEM_ALERT_WINDOW permission and WindowManager to display:
 * - Alert popups (non-intrusive, center dialog)
 * - Full-screen blocking overlays (cooldown mode)
 *
 * All overlay views are created and managed on the main thread.
 */
public class OverlayManager {

    private static final String TAG = "OverlayManager";

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler;

    // Current overlay views
    private View alertOverlayView = null;
    private View blockingOverlayView = null;

    // Blocking overlay state
    private CountDownTimer countDownTimer;
    private ValueAnimator breathingAnimator;
    private Handler contentRotateHandler;
    private Runnable contentRotateRunnable;

    // Callback for alert responses
    public interface AlertActionCallback {
        void onTakeBreak(String packageName, int category, float risk);
        void onContinue(String packageName, int category, float risk);
        void onAlertDismissed();
    }

    public OverlayManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // ========================= PERMISSION CHECK =========================

    /**
     * Returns true if the app has the "Draw over other apps" permission.
     */
    public static boolean canDrawOverlays(Context context) {
        if (context instanceof android.accessibilityservice.AccessibilityService) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true; // Pre-M, permission is granted at install
    }

    // ========================= ALERT OVERLAY =========================

    /**
     * Shows a non-intrusive alert popup over the current app.
     */
    public void showAlertOverlay(String appName, int durationMin, float risk, String reason,
                                  String packageName, int category,
                                  AlertActionCallback callback) {
        mainHandler.post(() -> {
            if (!canDrawOverlays(context)) {
                Log.w(TAG, "Cannot draw overlays — permission not granted");
                return;
            }

            // Don't stack alerts
            dismissAlertOverlayInternal();

            try {
                LayoutInflater inflater = LayoutInflater.from(context);
                alertOverlayView = inflater.inflate(R.layout.overlay_alert, null);

                // Configure WindowManager params
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        context instanceof android.accessibilityservice.AccessibilityService
                                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                                : (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                        : WindowManager.LayoutParams.TYPE_PHONE),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.CENTER;

                // Bind views
                TextView titleView = alertOverlayView.findViewById(R.id.overlayAlertTitle);
                TextView appInfoView = alertOverlayView.findViewById(R.id.overlayAlertAppInfo);
                TextView reasonView = alertOverlayView.findViewById(R.id.overlayAlertReason);
                TextView escalationView = alertOverlayView.findViewById(R.id.overlayEscalationWarning);
                TextView suggestionView = alertOverlayView.findViewById(R.id.overlayAlertSuggestion);
                View riskBar = alertOverlayView.findViewById(R.id.overlayRiskBar);
                Button btnBreak = alertOverlayView.findViewById(R.id.overlayBtnTakeBreak);
                Button btnContinue = alertOverlayView.findViewById(R.id.overlayBtnContinue);

                // Set content
                appInfoView.setText("You've been on " + appName + " for " + durationMin + " min");
                reasonView.setText(reason);

                // Risk indicator
                if (risk >= RiskThresholds.HIGH_RISK) {
                    riskBar.setBackgroundColor(context.getColor(R.color.accent_red));
                    titleView.setText("⚠️ High Risk Detected");
                } else {
                    riskBar.setBackgroundColor(context.getColor(R.color.accent_amber));
                    titleView.setText("Time for a Break?");
                }

                // Escalation warning
                AlertResponseTracker tracker = new AlertResponseTracker(context);
                int continueCount = tracker.getConsecutiveContinueCount();
                if (continueCount >= 1) {
                    escalationView.setVisibility(View.VISIBLE);
                    int remaining = RiskThresholds.MAX_STRIKES - continueCount;
                    if (remaining <= 0) {
                        escalationView.setText("⚠️ FINAL STRIKE: Access will be blocked");
                        escalationView.setTextColor(context.getColor(R.color.accent_red));
                    } else {
                        escalationView.setText("⚠️ Strike " + continueCount + " of " +
                                RiskThresholds.MAX_STRIKES + " — Next will block app");
                    }
                }

                // Random suggestion
                suggestionView.setText("💡 " + InterventionContentProvider.getProductivitySuggestion());

                // Button handlers
                btnBreak.setOnClickListener(v -> {
                    tracker.recordResponse(false);
                    tracker.resetOnBreak();
                    dismissAlertOverlay();
                    if (callback != null) callback.onTakeBreak(packageName, category, risk);
                });

                btnContinue.setOnClickListener(v -> {
                    tracker.recordResponse(true);
                    dismissAlertOverlay();
                    if (callback != null) callback.onContinue(packageName, category, risk);
                });

                // Add to window
                windowManager.addView(alertOverlayView, params);

                // Fade-in animation
                AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
                fadeIn.setDuration(400);
                fadeIn.setInterpolator(new DecelerateInterpolator());
                alertOverlayView.startAnimation(fadeIn);

                // Auto-dismiss after 60 seconds (counted as ignore)
                final View capturedView = alertOverlayView;
                mainHandler.postDelayed(() -> {
                    if (capturedView != null && capturedView == alertOverlayView) {
                        tracker.recordIgnore();
                        dismissAlertOverlayInternal();
                        if (callback != null) callback.onAlertDismissed();
                    }
                }, 60_000);

                Log.i(TAG, "Alert overlay shown for: " + appName);

            } catch (Exception e) {
                Log.e(TAG, "Failed to show alert overlay", e);
            }
        });
    }

    /**
     * Removes the alert overlay if it's currently displayed.
     */
    public void dismissAlertOverlay() {
        mainHandler.post(this::dismissAlertOverlayInternal);
    }

    private void dismissAlertOverlayInternal() {
        if (alertOverlayView != null) {
            try {
                windowManager.removeView(alertOverlayView);
                Log.d(TAG, "Alert overlay removed");
            } catch (Exception e) {
                Log.w(TAG, "Alert overlay removal failed: " + e.getMessage());
            }
            alertOverlayView = null;
        }
    }

    public boolean isAlertShowing() {
        return alertOverlayView != null;
    }

    // ========================= BLOCKING OVERLAY =========================

    /**
     * Shows a full-screen blocking overlay during cooldown.
     * Blocks all touch interaction with the underlying app.
     */
    public void showBlockingOverlay(long durationMs) {
        mainHandler.post(() -> {
            if (!canDrawOverlays(context)) {
                Log.w(TAG, "Cannot draw overlays — permission not granted");
                return;
            }

            // Don't stack blocking overlays
            if (blockingOverlayView != null) return;

            // Always dismiss any alert first
            dismissAlertOverlayInternal();

            try {
                LayoutInflater inflater = LayoutInflater.from(context);
                blockingOverlayView = inflater.inflate(R.layout.overlay_blocking, null);

                // Full-screen, blocks all touches
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        context instanceof android.accessibilityservice.AccessibilityService
                                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                                : (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                        : WindowManager.LayoutParams.TYPE_PHONE),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.CENTER;

                // Make it fully blocking (intercept all touches)
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

                // Bind views
                View breathingCircle = blockingOverlayView.findViewById(R.id.overlayBreathingCircle);
                TextView breathingTitle = blockingOverlayView.findViewById(R.id.overlayBreathingTitle);
                TextView breathingInstructions = blockingOverlayView.findViewById(R.id.overlayBreathingInstructions);
                TextView cooldownTimer = blockingOverlayView.findViewById(R.id.overlayCooldownTimer);
                ProgressBar cooldownProgress = blockingOverlayView.findViewById(R.id.overlayCooldownProgress);
                TextView quoteText = blockingOverlayView.findViewById(R.id.overlayQuoteText);
                TextView suggestionText = blockingOverlayView.findViewById(R.id.overlaySuggestionText);
                Button btnOpen = blockingOverlayView.findViewById(R.id.overlayBtnOpenNeuropulse);

                // Set initial content
                String[] bundle = InterventionContentProvider.getInterventionBundle();
                breathingTitle.setText(bundle[0]);
                breathingInstructions.setText(bundle[1]);
                quoteText.setText(bundle[2]);
                suggestionText.setText(bundle[3]);

                // Breathing animation
                startBreathingAnimation(breathingCircle);

                // Countdown timer
                final long totalDuration = durationMs;
                countDownTimer = new CountDownTimer(durationMs, 1000) {
                    @Override
                    public void onTick(long millisRemaining) {
                        int min = (int) (millisRemaining / 60000);
                        int sec = (int) ((millisRemaining % 60000) / 1000);
                        cooldownTimer.setText(String.format("%d:%02d", min, sec));
                        int progress = (int) ((millisRemaining * 100) / totalDuration);
                        cooldownProgress.setProgress(progress);
                    }

                    @Override
                    public void onFinish() {
                        cooldownTimer.setText("0:00");
                        cooldownProgress.setProgress(0);
                        btnOpen.setText("Cooldown Complete — Continue");

                        // Auto-dismiss after 5 seconds
                        mainHandler.postDelayed(() -> dismissBlockingOverlay(), 5000);
                    }
                };
                countDownTimer.start();

                // Open NeuroPulse button
                btnOpen.setOnClickListener(v -> {
                    Intent intent = new Intent(context, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    context.startActivity(intent);
                    // Don't dismiss — cooldown persists
                });

                // Content rotation every 30 seconds
                contentRotateHandler = new Handler(Looper.getMainLooper());
                contentRotateRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (blockingOverlayView == null) return;
                        String[] newBundle = InterventionContentProvider.getInterventionBundle();
                        breathingTitle.setText(newBundle[0]);
                        breathingInstructions.setText(newBundle[1]);
                        quoteText.setText(newBundle[2]);
                        suggestionText.setText(newBundle[3]);
                        contentRotateHandler.postDelayed(this, 30_000);
                    }
                };
                contentRotateHandler.postDelayed(contentRotateRunnable, 30_000);

                // Add to window
                windowManager.addView(blockingOverlayView, params);

                Log.i(TAG, "Blocking overlay shown for " + (durationMs / 60000) + " min");

            } catch (Exception e) {
                Log.e(TAG, "Failed to show blocking overlay", e);
            }
        });
    }

    /**
     * Removes the blocking overlay if displayed.
     */
    public void dismissBlockingOverlay() {
        mainHandler.post(this::dismissBlockingOverlayInternal);
    }

    private void dismissBlockingOverlayInternal() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
            breathingAnimator = null;
        }
        if (contentRotateHandler != null && contentRotateRunnable != null) {
            contentRotateHandler.removeCallbacks(contentRotateRunnable);
            contentRotateHandler = null;
            contentRotateRunnable = null;
        }
        if (blockingOverlayView != null) {
            try {
                windowManager.removeView(blockingOverlayView);
                Log.d(TAG, "Blocking overlay removed");
            } catch (Exception e) {
                Log.w(TAG, "Blocking overlay removal failed: " + e.getMessage());
            }
            blockingOverlayView = null;
        }
    }

    public boolean isBlockingShowing() {
        return blockingOverlayView != null;
    }

    /**
     * Dismiss all overlays and clean up all resources.
     */
    public void dismissAll() {
        mainHandler.post(() -> {
            dismissAlertOverlayInternal();
            dismissBlockingOverlayInternal();
        });
    }

    // ========================= PRIVATE HELPERS =========================

    private void startBreathingAnimation(View breathingCircle) {
        breathingAnimator = ValueAnimator.ofFloat(0.8f, 1.3f);
        breathingAnimator.setDuration(4000);
        breathingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        breathingAnimator.setRepeatMode(ValueAnimator.REVERSE);
        breathingAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        breathingAnimator.addUpdateListener(animation -> {
            float scale = (float) animation.getAnimatedValue();
            breathingCircle.setScaleX(scale);
            breathingCircle.setScaleY(scale);
            breathingCircle.setAlpha(0.5f + (scale - 0.8f) * 0.6f);
        });
        breathingAnimator.start();
    }
}
