package com.neuropulse.app;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.animation.ObjectAnimator;
import android.view.animation.DecelerateInterpolator;

import com.google.android.material.button.MaterialButton;
import com.neuropulse.app.adapters.EnhancedDebugAdapter;
import com.neuropulse.app.features.EnhancedFeatureExtractor;
import com.neuropulse.app.features.RealTimeAppDetector;
import com.neuropulse.app.features.StreakManager;
import com.neuropulse.app.ml.AddictionPredictor;
import com.neuropulse.app.models.EnhancedDebugInfo;
import com.neuropulse.app.models.SessionFeatures;
import com.neuropulse.app.services.NeuropulseAccessibilityService;
import com.neuropulse.app.services.OverlayManager;
import com.neuropulse.app.services.UsageMonitorService;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // Sub-components
    private RealTimeAppDetector appDetector;
    private EnhancedFeatureExtractor featureExtractor;
    private AddictionPredictor addictionPredictor;
    private StreakManager streakManager;

    // UI — Permission
    private View permissionContainer;
    private MaterialButton permissionsBtn;

    // UI — Dashboard
    private View dashboardContainer;
    private TextView streakBadgeText;
    private TextView streakCountText;
    private TextView streakMessageText;
    private ProgressBar riskProgressBar;
    private TextView riskScoreText;
    private TextView currentAppText;
    private TextView riskLevelText;
    private TextView riskTrendText;
    private TextView usageClassText;

    // UI — Stats Row
    private TextView sessionDurationText;
    private TextView interventionsText;

    // UI — Advanced Metrics
    private EnhancedDebugAdapter debugAdapter;

    // UI — Top Apps
    private android.widget.LinearLayout topAppsContainer;

    // Timing state
    private long sessionStartTime = System.currentTimeMillis();
    private String currentPackage = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isVisible = false;
    private int dashboardUpdateTick = 0;

    // Updater Runnable
    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isVisible) return;
            updateDashboard();
            handler.postDelayed(this, 2000); // 2-second refresh
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Core components
        appDetector = new RealTimeAppDetector(this);
        featureExtractor = new EnhancedFeatureExtractor(this);
        addictionPredictor = new AddictionPredictor(this);
        streakManager = new StreakManager(this);

        initViews();
        setupRecyclerView();

        permissionsBtn.setOnClickListener(v -> {
            // Open the full permission helper
            Intent intent = new Intent(this, PermissionHelperActivity.class);
            startActivity(intent);
        });
    }

    private void initViews() {
        permissionContainer = findViewById(R.id.permissionContainer);
        permissionsBtn = findViewById(R.id.permissionsBtn);

        dashboardContainer = findViewById(R.id.dashboardContainer);
        streakBadgeText = findViewById(R.id.streakBadge);
        streakCountText = findViewById(R.id.streakCountText);
        streakMessageText = findViewById(R.id.streakMessageText);

        riskProgressBar = findViewById(R.id.riskProgressBar);
        riskScoreText = findViewById(R.id.riskScoreText);
        currentAppText = findViewById(R.id.currentAppText);
        riskLevelText = findViewById(R.id.riskLevelText);
        riskTrendText = findViewById(R.id.riskTrendText);
        usageClassText = findViewById(R.id.usageClassText);

        sessionDurationText = findViewById(R.id.sessionDurationText);
        interventionsText = findViewById(R.id.interventionsText);

        topAppsContainer = findViewById(R.id.topAppsContainer);
    }

    private void setupRecyclerView() {
        RecyclerView recycler = findViewById(R.id.recyclerEnhancedDebug);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setHasFixedSize(true);
        debugAdapter = new EnhancedDebugAdapter();
        recycler.setAdapter(debugAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isVisible = true;

        if (!hasUsageStatsPermission()) {
            permissionContainer.setVisibility(View.VISIBLE);
            dashboardContainer.setVisibility(View.GONE);
        } else {
            permissionContainer.setVisibility(View.GONE);
            dashboardContainer.setVisibility(View.VISIBLE);

            // Trigger streaks evaluation early today
            streakManager.evaluateDay();

            // Start Monitoring Service (provides foreground notification)
            Intent intent = new Intent(this, UsageMonitorService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            // Check for overlay + accessibility permissions
            checkSystemPermissions();

            handler.post(uiUpdater);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isVisible = false;
        handler.removeCallbacks(uiUpdater);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (addictionPredictor != null) {
            addictionPredictor.close();
        }
    }

    // ================= DASHBOARD UPDATES =================

    private void updateDashboard() {
        long now = System.currentTimeMillis();
        RealTimeAppDetector.CurrentAppInfo app = appDetector.getCurrentAppWithRisk();

        // Handle app transition
        if (!app.packageName.equals(currentPackage)) {
            currentPackage = app.packageName;
            sessionStartTime = now;
            // Record if it's the app itself measuring for debug info purposes
        }

        // Feature Extraction
        SessionFeatures features = featureExtractor.extract(app.category, sessionStartTime, now);
        
        // Ensure UI doesn't track its own app as doomscrolling
        if (app.packageName.equals(getPackageName())) {
             features.appCategory = 1; // force productive
             features.sessionDurationMs = 0;
        }

        // ML Prediction
        AddictionPredictor.PredictionResult result = addictionPredictor.predict(features);

        // Update Gauge with smooth animation
        int progress = (int) (result.dopamineRisk * 100);
        animateRiskProgress(progress);
        riskScoreText.setText(progress + "%");

        int color = getColor(result.addictionLevel >= 2 ? R.color.accent_red :
                             result.addictionLevel == 1 ? R.color.accent_amber : R.color.accent_green);

        riskScoreText.setTextColor(color);
        riskLevelText.setTextColor(color);
        riskLevelText.setText(result.riskLevel);

        currentAppText.setText(app.displayName);

        // Update Extended Metrics
        EnhancedDebugInfo info = EnhancedDebugInfo.from(features, app, result);

        usageClassText.setText(info.usageClass);
        riskTrendText.setText(info.lastTrend);
        
        // Colors for usage class indicator
        if (info.usageClass.contains("PRODUCTIVE")) {
            usageClassText.setTextColor(getColor(R.color.accent_green));
        } else if (info.usageClass.contains("ADDICTIVE")) {
            usageClassText.setTextColor(getColor(R.color.accent_red));
        } else {
            usageClassText.setTextColor(getColor(R.color.text_muted));
        }

        // Stats Row
        long sec = features.sessionDurationMs / 1000;
        sessionDurationText.setText((sec > 60) ? (sec / 60) + "m" : sec + "s");

        com.neuropulse.app.utils.AlertResponseTracker alertTracker = new com.neuropulse.app.utils.AlertResponseTracker(this);
        interventionsText.setText(String.valueOf(alertTracker.getTodayInterventions()));

        // Streak Card
        int currentStreak = streakManager.getCurrentStreak();
        streakBadgeText.setText("🔥 " + currentStreak);
        streakCountText.setText(currentStreak == 1 ? "1 day" : currentStreak + " days");
        streakMessageText.setText(streakManager.getStreakMessage());

        // Recycler View
        debugAdapter.updateEnhancedInfo(info);

        // Top Apps update (throttle to every 60 seconds basically, or if empty)
        dashboardUpdateTick++;
        if (dashboardUpdateTick % 30 == 1 || topAppsContainer.getChildCount() == 0) {
            updateTopApps();
        }
    }

    private void animateRiskProgress(int progress) {
        ObjectAnimator animation = ObjectAnimator.ofInt(riskProgressBar, "progress", riskProgressBar.getProgress(), progress);
        animation.setDuration(1200);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.start();
    }

    private void updateTopApps() {
        java.util.List<RealTimeAppDetector.AppUsageStats> topApps = appDetector.getTopAddictingAppsToday(3);
        topAppsContainer.removeAllViews();

        if (topApps.isEmpty()) {
            TextView emptyText = new TextView(this);
            emptyText.setText("No addictive app usage detected today! 🎉");
            emptyText.setTextColor(getColor(R.color.text_muted));
            emptyText.setTextSize(14f);
            topAppsContainer.addView(emptyText);
            return;
        }

        for (RealTimeAppDetector.AppUsageStats stats : topApps) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, 12, 0, 12);

            TextView nameText = new TextView(this);
            nameText.setText(stats.displayName);
            nameText.setTextColor(getColor(R.color.text_primary));
            nameText.setTextSize(16f);
            nameText.setTypeface(null, android.graphics.Typeface.BOLD);

            android.widget.LinearLayout.LayoutParams nameParams = new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            nameText.setLayoutParams(nameParams);

            TextView timeText = new TextView(this);
            long minutes = stats.totalTimeMs / 60000;
            long hours = minutes / 60;
            long remMinutes = minutes % 60;
            String timeStr = hours > 0 ? hours + "h " + remMinutes + "m" : remMinutes + "m";

            timeText.setText(timeStr);
            timeText.setTextColor(getColor(R.color.accent_amber));
            timeText.setTextSize(15f);
            timeText.setTypeface(null, android.graphics.Typeface.BOLD);

            row.addView(nameText);
            row.addView(timeText);
            topAppsContainer.addView(row);
        }
    }

    // ================= PERMISSIONS =================

    private void checkSystemPermissions() {
        boolean overlayOk = OverlayManager.canDrawOverlays(this);
        boolean a11yOk = NeuropulseAccessibilityService.isAccessibilityEnabled(this);

        if (!overlayOk) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivity(intent);
            }
        }

        if (!overlayOk || !a11yOk) {
            // Show a banner or toast prompting user to grant permissions
            StringBuilder missing = new StringBuilder();
            if (!overlayOk) missing.append("Draw Over Apps");
            if (!a11yOk) {
                if (missing.length() > 0) missing.append(", ");
                missing.append("Accessibility Service");
            }
            Toast.makeText(this,
                    "⚠️ Missing: " + missing + ". Tap 'Grant Permissions' for full protection.",
                    Toast.LENGTH_LONG).show();

            // Update the permissions button text
            permissionsBtn.setText("Grant Required Permissions");
            permissionsBtn.setVisibility(View.VISIBLE);
        }
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getPackageName()
        );

        if (mode == AppOpsManager.MODE_DEFAULT) {
            return checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }
}
