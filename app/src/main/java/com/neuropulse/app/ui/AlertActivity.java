package com.neuropulse.app.ui;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.neuropulse.app.R;
import com.neuropulse.app.ml.RiskThresholds;
import com.neuropulse.app.utils.AlertResponseTracker;
import com.neuropulse.app.utils.InterventionContentProvider;

/**
 * Premium intervention alert — shows when doomscrolling is detected.
 * User can "Take a Break" or "Continue Using".
 * Tracks responses for escalation logic.
 */
public class AlertActivity extends Activity {

    public static final String EXTRA_APP_NAME = "app_name";
    public static final String EXTRA_DURATION_MIN = "duration_min";
    public static final String EXTRA_RISK = "risk";
    public static final String EXTRA_REASON = "reason";

    /** Static callback — set by UsageMonitorService before launching */
    public static AlertCallback callback;

    private AlertResponseTracker responseTracker;
    private boolean responded = false;

    public interface AlertCallback {
        void onTakeBreak();
        void onContinue();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_alert);

        responseTracker = new AlertResponseTracker(this);

        // Extract data
        String appName = getIntent().getStringExtra(EXTRA_APP_NAME);
        int durationMin = getIntent().getIntExtra(EXTRA_DURATION_MIN, 0);
        float risk = getIntent().getFloatExtra(EXTRA_RISK, 0f);
        String reason = getIntent().getStringExtra(EXTRA_REASON);

        if (appName == null) appName = "Unknown App";
        if (reason == null) reason = "High engagement detected";

        // Bind views
        TextView titleView = findViewById(R.id.alertTitle);
        TextView appInfoView = findViewById(R.id.alertAppInfo);
        TextView reasonView = findViewById(R.id.alertReason);
        TextView escalationView = findViewById(R.id.escalationWarning);
        TextView suggestionView = findViewById(R.id.alertSuggestion);
        View riskBar = findViewById(R.id.riskIndicatorBar);
        MaterialButton btnBreak = findViewById(R.id.btnTakeBreak);
        MaterialButton btnContinue = findViewById(R.id.btnContinue);

        // Set content
        appInfoView.setText("You've been on " + appName + " for " + durationMin + " min");
        reasonView.setText(reason);

        // Risk indicator color
        if (risk >= RiskThresholds.HIGH_RISK) {
            riskBar.setBackgroundColor(getColor(R.color.accent_red));
            titleView.setText("⚠️ High Risk Detected");
        } else {
            riskBar.setBackgroundColor(getColor(R.color.accent_amber));
            titleView.setText("Time for a Break?");
        }

        // Escalation warning
        int continueCount = responseTracker.getConsecutiveContinueCount();
        if (continueCount >= 1) {
            escalationView.setVisibility(View.VISIBLE);
            int remaining = RiskThresholds.MAX_STRIKES - continueCount;
            if (remaining <= 0) {
                escalationView.setText("⚠️ FINAL STRIKE: Access will be blocked");
                escalationView.setTextColor(getColor(R.color.accent_red));
            } else {
                escalationView.setText("⚠️ Strike " + continueCount + " of " + RiskThresholds.MAX_STRIKES + 
                        " (Next will block app)");
            }
        }

        // Random suggestion
        suggestionView.setText("💡 " + InterventionContentProvider.getProductivitySuggestion());

        // Button handlers
        btnBreak.setOnClickListener(v -> {
            responded = true;
            responseTracker.recordResponse(false);
            responseTracker.resetOnBreak();
            if (callback != null) callback.onTakeBreak();
            finish();
        });

        btnContinue.setOnClickListener(v -> {
            responded = true;
            responseTracker.recordResponse(true);
            if (callback != null) callback.onContinue();
            finish();
        });

        // Fade-in animation
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(400);
        fadeIn.setInterpolator(new DecelerateInterpolator());
        findViewById(android.R.id.content).startAnimation(fadeIn);

        // Auto-dismiss after 60 seconds (counted as ignore)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!responded && !isFinishing()) {
                responseTracker.recordIgnore();
                finish();
            }
        }, 60_000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!responded) {
            responseTracker.recordIgnore();
        }
    }
}
