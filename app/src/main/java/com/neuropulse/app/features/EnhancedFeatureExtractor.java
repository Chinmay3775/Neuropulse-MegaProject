package com.neuropulse.app.features;

import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import com.neuropulse.app.ml.RiskThresholds;
import com.neuropulse.app.models.SessionFeatures;

import java.util.Calendar;

/**
 * Feature extractor — converts live app usage into ML-ready features.
 *
 * Now uses REAL scroll data from {@link ScrollTracker} (populated by the
 * AccessibilityService capturing TYPE_VIEW_SCROLLED events) instead of
 * the old synthetic duration-based intensity estimates.
 *
 * Falls back to category-based estimates only when the AccessibilityService
 * is not running (i.e., ScrollTracker has no data).
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

        // Use REAL scroll data from ScrollTracker, falling back to synthetic estimate
        float scrollsPerMinute = getScrollsPerMinute(appCategory, durationMs);
        int consecutiveSameAppMin = (int) (durationMs / 60000L);
        int bingeFlag = durationMs > (2 * 60 * 60 * 1000L) ? 1 : 0;
        float timeOfDay = calculateTimeOfDay(currentTime);

        SessionFeatures features = new SessionFeatures(
                appCategory,
                durationMs,
                scrollsPerMinute,
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

        // NEW: Populate authentic scroll cadence metrics from ScrollTracker
        ScrollTracker tracker = ScrollTracker.getInstance();
        features.scrollCadenceVariance = tracker.getScrollCadenceVariance();
        features.rapidBurstCount = tracker.getRapidBurstCount();

        return features;
    }

    // ================= SCROLL DATA (REAL + FALLBACK) =================

    /**
     * Returns scrolls-per-minute using real data from ScrollTracker.
     * Falls back to a category-based synthetic estimate only when
     * the AccessibilityService hasn't recorded any scroll events yet.
     */
    private float getScrollsPerMinute(int category, long durationMs) {
        ScrollTracker tracker = ScrollTracker.getInstance();
        float realScrollRate = tracker.getScrollsPerMinute();

        // If we have real data (at least a few events), use it
        if (tracker.getWindowEventCount() >= 2) {
            return realScrollRate;
        }

        // Fallback: category-based estimate (used when AccessibilityService
        // is not enabled or session just started with no scrolls yet)
        return computeFallbackIntensity(category, durationMs);
    }

    /**
     * Legacy fallback: estimates interaction intensity from category and duration.
     * Used only when real scroll data is unavailable.
     */
    private float computeFallbackIntensity(int category, long durationMs) {
        float baseRate;
        if (RiskThresholds.isHighStimCategory(category)) {
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
        float durationMinutes = durationMs / 60000f;
        float engagementMultiplier = 1f + (float) (0.5 * Math.log1p(durationMinutes / 10.0));
        engagementMultiplier = Math.min(engagementMultiplier, 2.5f);

        // Time-of-day modifier (people scroll faster at night)
        float hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        float timeModifier = (hour >= 22 || hour <= 5) ? 1.2f : 1.0f;

        return baseRate * engagementMultiplier * timeModifier;
    }

    // ================= CONTEXT ESTIMATES =================
    private int getUnlockEstimate(long durationMs) {
        int base = (int) (durationMs / (15 * 60000L));
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
        if (diff > 0.05f) return 1f;
        if (diff < -0.05f) return -1f;
        return 0f;
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

