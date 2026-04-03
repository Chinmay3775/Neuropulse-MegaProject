package com.neuropulse.app.models;

import com.neuropulse.app.ml.RiskThresholds;

/**
 * ML-ready session feature container.
 * Matches the 16-feature vector used in the training pipeline:
 *   11 raw features + 5 engineered features.
 */
public class SessionFeatures {

    // ================= RAW FEATURES (11) =================
    public long   sessionDurationMs;
    public int    unlockCount;
    public int    appCategory;
    public int    notifCount;
    public int    notifResponse;       // 0=ignored, 1=viewed, 2=acted
    public int    appSwitchCount;
    public float  timeOfDay;           // 0.0–1.0 (fraction of day)
    public int    consecutiveSameAppMin;
    public int    bingeFlag;           // 1 if session > 2h
    public float  scrollsPerMinute;
    public float  unlockFrequency;     // unlocks per hour

    // ================= DERIVED (for display / logic) =================
    public int    sessionCount;        // sessions today
    public float  riskTrend;           // +1 rising, 0 stable, -1 falling

    // ================= AUTHENTIC SCROLL METRICS (from ScrollTracker) =================
    /** Variance of inter-scroll intervals. High = erratic (doomscrolling). Low = steady (reading). */
    public float  scrollCadenceVariance;
    /** Number of rapid scroll bursts (6+ scrolls in 5 seconds). Strong doomscrolling indicator. */
    public int    rapidBurstCount;

    public SessionFeatures(
            int appCategory,
            long sessionDurationMs,
            float scrollsPerMinute,
            int consecutiveSameAppMin,
            float timeOfDay,
            int bingeFlag
    ) {
        this.appCategory = appCategory;
        this.sessionDurationMs = sessionDurationMs;
        this.scrollsPerMinute = scrollsPerMinute;
        this.consecutiveSameAppMin = consecutiveSameAppMin;
        this.timeOfDay = timeOfDay;
        this.bingeFlag = bingeFlag;

        // defaults
        this.unlockCount = 1;
        this.notifCount = 0;
        this.notifResponse = 0;
        this.appSwitchCount = 0;
        this.unlockFrequency = 0f;
        this.sessionCount = 1;
        this.riskTrend = 0f;
    }

    /**
     * Returns the 16-element feature vector that matches the ML training pipeline.
     * Order: session_duration, unlock_count, app_category, notif_count,
     *        notif_response, app_switch_count, time_of_day, consecutive_same_app,
     *        binge_flag, scrolls_per_minute, unlock_frequency,
     *        duration_hours, high_stim_app, notif_responsiveness,
     *        usage_intensity, evening_usage
     */
    public float[] toFeatureArray() {
        float durationHours = sessionDurationMs / (1000f * 3600f);
        float highStim = RiskThresholds.isHighStimCategory(appCategory) ? 1f : 0f;
        float notifResponsiveness = notifResponse / 2.0f;
        float usageIntensity = (unlockFrequency * scrollsPerMinute) / 100f;
        float eveningUsage = (timeOfDay >= 0.79f || timeOfDay <= 0.25f) ? 1f : 0f;

        return new float[]{
                // 11 raw features
                sessionDurationMs,
                unlockCount,
                appCategory,
                notifCount,
                notifResponse,
                appSwitchCount,
                timeOfDay,
                consecutiveSameAppMin,
                bingeFlag,
                scrollsPerMinute,
                unlockFrequency,
                // 5 engineered features
                durationHours,
                highStim,
                notifResponsiveness,
                usageIntensity,
                eveningUsage
        };
    }
}
