package com.neuropulse.app.ml;

/**
 * Centralized risk thresholds and constants for NeuroPulse.
 * Single source of truth — every class uses these instead of hardcoded values.
 */
public final class RiskThresholds {

    private RiskThresholds() {} // No instantiation

    // ================= RISK LEVELS =================
    public static final float HIGH_RISK = 0.80f;
    public static final float MEDIUM_RISK = 0.50f;
    public static final float ALERT_THRESHOLD = 0.70f;
    public static final float ESCALATION_THRESHOLD = 0.85f;

    // ================= SCANNING & ML =================
    public static final long MONITOR_INTERVAL_MS = 2000L;      // Baseline foreground check
    public static final long ML_PREDICTION_INTERVAL_MS = 25000L; // ML prediction every 25s
    public static final int RISK_SMOOTHING_WINDOW = 5;         // Average last 5 predictions

    // ================= COOLDOWN =================
    public static final int MIN_COOLDOWN_MINUTES = 10;         // Request: 10 min
    public static final int MAX_COOLDOWN_MINUTES = 15;         // Request: 5-15 min
    public static final long ALERT_COOLDOWN_MS = 30_000L;      // 30s between alerts

    // ================= ESCALATION =================
    public static final int MAX_STRIKES = 3;                   // 3-strike policy

    // ================= APP CATEGORIES =================
    public static final int CATEGORY_SOCIAL = 0;
    public static final int CATEGORY_PRODUCTIVITY = 1;
    public static final int CATEGORY_ENTERTAINMENT = 2;
    public static final int CATEGORY_GAMES = 3;
    public static final int CATEGORY_NEWS = 4;
    public static final int CATEGORY_SHOPPING = 5;
    public static final int CATEGORY_COMMUNICATION = 6;
    public static final int CATEGORY_HEALTH = 7;
    public static final int CATEGORY_FINANCE = 8;
    public static final int CATEGORY_UTILITIES = 9;

    // ================= FEATURE COUNT (matches training script) =================
    /** 11 raw features + 5 engineered = 16 total */
    public static final int FEATURE_COUNT = 16;

    // ================= UTILITY METHODS =================

    public static String getRiskLevel(float risk) {
        if (risk >= HIGH_RISK) return "HIGH";
        if (risk >= MEDIUM_RISK) return "MEDIUM";
        return "LOW";
    }

    public static int getAddictionLevel(float risk) {
        if (risk >= HIGH_RISK) return 2;
        if (risk >= MEDIUM_RISK) return 1;
        return 0;
    }

    public static boolean shouldAlert(float risk) {
        return risk >= ALERT_THRESHOLD;
    }

    public static boolean isHighStimCategory(int category) {
        return category == CATEGORY_SOCIAL
                || category == CATEGORY_ENTERTAINMENT
                || category == CATEGORY_GAMES;
    }

    public static boolean isProductiveCategory(int category) {
        return category == CATEGORY_PRODUCTIVITY
                || category == CATEGORY_FINANCE
                || category == CATEGORY_HEALTH
                || category == CATEGORY_UTILITIES;
    }

    public static String getCategoryName(int c) {
        switch (c) {
            case CATEGORY_SOCIAL:        return "Social Media";
            case CATEGORY_PRODUCTIVITY:  return "Productivity";
            case CATEGORY_ENTERTAINMENT: return "Entertainment";
            case CATEGORY_GAMES:         return "Games";
            case CATEGORY_NEWS:          return "News";
            case CATEGORY_SHOPPING:      return "Shopping";
            case CATEGORY_COMMUNICATION: return "Communication";
            case CATEGORY_HEALTH:        return "Health";
            case CATEGORY_FINANCE:       return "Finance";
            case CATEGORY_UTILITIES:     return "Utilities";
            default:                     return "Other";
        }
    }
}
