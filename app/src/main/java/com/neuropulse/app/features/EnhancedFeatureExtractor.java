package com.neuropulse.app.features;

import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import com.neuropulse.app.ml.RiskThresholds;
import com.neuropulse.app.models.SessionFeatures;

import java.util.Calendar;

/**
 * Feature extractor — converts live app usage into ML-ready features.
 * Removes synthetic scroll estimation; uses duration-based intensity instead.
 */
public class EnhancedFeatureExtractor {

    private static final String TAG = "FeatureExtractor";
    private static final String PREFS_NAME = "neuropulse_features";

    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final SharedPreferences prefs;

    // Sliding window for risk trend calculation
    private final float[] recentRisks = new float[10];
    private int riskIndex = 0;
    private int riskCount = 0;

    public EnhancedFeatureExtractor(Context context) {
        this.context = context.getApplicationContext();
        this.usageStatsManager =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ================= FEATURE EXTRACTION =================
    public SessionFeatures extract(
            int appCategory,
            long sessionStartTime,
            long currentTime
    ) {
        long durationMs = Math.max(0, currentTime - sessionStartTime);

        // Duration-based interaction intensity (replaces fake estimateScrollRate)
        float interactionIntensity = computeInteractionIntensity(appCategory, durationMs);
        int consecutiveSameAppMin = (int) (durationMs / 60000L);
        int bingeFlag = durationMs > (2 * 60 * 60 * 1000L) ? 1 : 0;
        float timeOfDay = calculateTimeOfDay(currentTime);

        SessionFeatures features = new SessionFeatures(
                appCategory,
                durationMs,
                interactionIntensity,
                consecutiveSameAppMin,
                timeOfDay,
                bingeFlag
        );

        // Enrich with additional context
        features.unlockCount = getUnlockEstimate(durationMs);
        features.notifCount = getNotifEstimate(appCategory);
        features.notifResponse = getNotifResponseEstimate(appCategory);
        features.appSwitchCount = getAppSwitchEstimate(durationMs);
        features.unlockFrequency = features.unlockCount > 0 && durationMs > 0
                ? (features.unlockCount * 3600000f) / durationMs
                : 0f;
        features.sessionCount = getSessionCountToday();
        features.riskTrend = computeRiskTrend();

        return features;
    }

    // ================= INTERACTION INTENSITY =================
    /**
     * Computes interaction intensity based on app category and session duration.
     * Higher for addictive categories, scales with time spent.
     * This replaces the old estimateScrollRate which fabricated fake data.
     */
    private float computeInteractionIntensity(int category, long durationMs) {
        // Base interaction rate by category (interactions per minute)
        float baseRate;
        if (RiskThresholds.isHighStimCategory(category)) {
            // Social/entertainment/games have high natural interaction rates
            baseRate = category == RiskThresholds.CATEGORY_SOCIAL ? 14f :
                    category == RiskThresholds.CATEGORY_ENTERTAINMENT ? 10f : 8f;
        } else if (category == RiskThresholds.CATEGORY_NEWS) {
            baseRate = 7f;
        } else if (RiskThresholds.isProductiveCategory(category)) {
            baseRate = 3f;
        } else {
            baseRate = 5f;
        }

        // Engagement escalation: longer sessions = higher intensity
        // Uses a log curve that saturates — models the "getting hooked" effect
        float durationMinutes = durationMs / 60000f;
        float engagementMultiplier = 1f + (float) (0.5 * Math.log1p(durationMinutes / 10.0));
        engagementMultiplier = Math.min(engagementMultiplier, 2.5f);

        // Add time-of-day modifier (people scroll faster at night)
        float hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        float timeModifier = (hour >= 22 || hour <= 5) ? 1.2f : 1.0f;

        return baseRate * engagementMultiplier * timeModifier;
    }

    // ================= CONTEXT ESTIMATES =================
    private int getUnlockEstimate(long durationMs) {
        // Estimate from session duration — rough proxy
        int base = (int) (durationMs / (15 * 60000L)); // ~1 unlock per 15 min
        return Math.max(1, base + 1);
    }

    private int getNotifEstimate(int category) {
        return RiskThresholds.isHighStimCategory(category) ? 4 : 1;
    }

    private int getNotifResponseEstimate(int category) {
        return RiskThresholds.isHighStimCategory(category) ? 2 : 0;
    }

    private int getAppSwitchEstimate(long durationMs) {
        return Math.max(0, (int) (durationMs / 300000L) + 1);
    }

    // ================= SESSION TRACKING =================
    public void recordSession() {
        int today = getDayOfYear();
        int storedDay = prefs.getInt("session_day", -1);
        int count = prefs.getInt("session_count", 0);

        if (storedDay != today) {
            count = 1;
        } else {
            count++;
        }

        prefs.edit()
                .putInt("session_day", today)
                .putInt("session_count", count)
                .apply();
    }

    private int getSessionCountToday() {
        int today = getDayOfYear();
        int storedDay = prefs.getInt("session_day", -1);
        if (storedDay != today) return 1;
        return prefs.getInt("session_count", 1);
    }

    private int getDayOfYear() {
        return Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
    }

    // ================= RISK TREND =================
    public void recordRisk(float risk) {
        recentRisks[riskIndex] = risk;
        riskIndex = (riskIndex + 1) % recentRisks.length;
        if (riskCount < recentRisks.length) riskCount++;
    }

    /**
     * Returns risk trend: positive = rising, negative = falling, ~0 = stable.
     */
    private float computeRiskTrend() {
        if (riskCount < 3) return 0f;

        // Compare average of last 3 vs previous 3
        float recent = 0f, older = 0f;
        int n = Math.min(riskCount, recentRisks.length);
        int half = n / 2;

        for (int i = 0; i < half; i++) {
            int idx = (riskIndex - 1 - i + recentRisks.length) % recentRisks.length;
            recent += recentRisks[idx];
        }
        for (int i = half; i < n; i++) {
            int idx = (riskIndex - 1 - i + recentRisks.length) % recentRisks.length;
            older += recentRisks[idx];
        }

        recent /= half;
        older /= (n - half);

        float diff = recent - older;
        if (diff > 0.05f) return 1f;  // rising
        if (diff < -0.05f) return -1f; // falling
        return 0f; // stable
    }

    // ================= HELPERS =================
    private float calculateTimeOfDay(long timeMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timeMs);
        return cal.get(Calendar.HOUR_OF_DAY) / 24f;
    }

    public String getAppLabel(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(
                    pm.getApplicationInfo(packageName, 0)
            ).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
}
