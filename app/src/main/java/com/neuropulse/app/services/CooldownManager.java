package com.neuropulse.app.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.neuropulse.app.ml.RiskThresholds;

import java.util.HashSet;
import java.util.Set;

/**
 * Manages cooldown periods for addictive apps.
 * Supports adaptive duration (5–15 min) and category-based blocking.
 */
public class CooldownManager {

    private static final String TAG = "CooldownManager";
    private static final String PREFS_NAME = "neuropulse_cooldown";

    private final SharedPreferences prefs;

    // Blocked category group mapping
    private static final int[][] CATEGORY_GROUPS = {
            {RiskThresholds.CATEGORY_SOCIAL, RiskThresholds.CATEGORY_COMMUNICATION},  // Social group
            {RiskThresholds.CATEGORY_ENTERTAINMENT, RiskThresholds.CATEGORY_GAMES},    // Entertainment group
            {RiskThresholds.CATEGORY_NEWS, RiskThresholds.CATEGORY_SHOPPING}           // Browsing group
    };

    public CooldownManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Starts a cooldown for the given package and its category group.
     */
    public void startCooldown(String packageName, int category, float riskScore, int sessionCount) {
        long durationMs = getAdaptiveDurationMs(riskScore, sessionCount);
        long endTime = System.currentTimeMillis() + durationMs;

        SharedPreferences.Editor editor = prefs.edit();

        // Block specific package
        editor.putLong("cooldown_" + packageName, endTime);

        // Block entire category group
        Set<String> blockedCategories = new HashSet<>();
        blockedCategories.add(String.valueOf(category));

        for (int[] group : CATEGORY_GROUPS) {
            for (int cat : group) {
                if (cat == category) {
                    for (int groupCat : group) {
                        blockedCategories.add(String.valueOf(groupCat));
                    }
                    break;
                }
            }
        }

        editor.putStringSet("blocked_categories", blockedCategories);
        editor.putLong("cooldown_end_global", endTime);
        editor.putInt("cooldown_duration_min", (int) (durationMs / 60000));
        editor.apply();

        Log.i(TAG, "Cooldown started: " + packageName +
                " for " + (durationMs / 60000) + " min" +
                " (categories: " + blockedCategories + ")");
    }

    /**
     * Checks if the given package is currently in cooldown.
     */
    public boolean isInCooldown(String packageName) {
        long now = System.currentTimeMillis();
        long endTime = prefs.getLong("cooldown_" + packageName, 0);
        return now < endTime;
    }

    /**
     * Checks if the given category is currently blocked.
     */
    public boolean isCategoryBlocked(int category) {
        long now = System.currentTimeMillis();
        long globalEnd = prefs.getLong("cooldown_end_global", 0);
        if (now >= globalEnd) return false;

        Set<String> blocked = prefs.getStringSet("blocked_categories", new HashSet<>());
        return blocked.contains(String.valueOf(category));
    }

    /**
     * Returns remaining cooldown time in milliseconds.
     */
    public long getCooldownRemainingMs(String packageName) {
        long endTime = prefs.getLong("cooldown_" + packageName, 0);
        long remaining = endTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    /**
     * Returns remaining global cooldown in milliseconds.
     */
    public long getGlobalCooldownRemainingMs() {
        long endTime = prefs.getLong("cooldown_end_global", 0);
        long remaining = endTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    /**
     * Returns the last set cooldown duration in minutes.
     */
    public int getLastCooldownDurationMin() {
        return prefs.getInt("cooldown_duration_min", RiskThresholds.MIN_COOLDOWN_MINUTES);
    }

    /**
     * Checks if any cooldown is currently active.
     */
    public boolean isAnyCooldownActive() {
        return getGlobalCooldownRemainingMs() > 0;
    }

    /**
     * Clears all cooldowns (e.g., when cooldown expires or is manually dismissed).
     */
    public void clearAllCooldowns() {
        prefs.edit().clear().apply();
        Log.i(TAG, "All cooldowns cleared");
    }

    /**
     * Calculates adaptive cooldown duration (5–15 minutes).
     * Higher risk, more sessions, and night usage = longer cooldown.
     */
    private long getAdaptiveDurationMs(float riskScore, int sessionCount) {
        // Base: 5 minutes
        float baseMins = RiskThresholds.MIN_COOLDOWN_MINUTES;

        // Risk adjustment: +0–5 min based on risk score
        float riskMins = riskScore * 5f;

        // Session count adjustment: +0–3 min for frequent sessions
        float sessionMins = Math.min(3f, sessionCount / 5f);

        // Night-time adjustment: +2 min between 11 PM – 5 AM
        float nightMins = 0f;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 23 || hour <= 5) {
            nightMins = 2f;
        }

        float totalMins = baseMins + riskMins + sessionMins + nightMins;
        totalMins = Math.max(RiskThresholds.MIN_COOLDOWN_MINUTES,
                Math.min(RiskThresholds.MAX_COOLDOWN_MINUTES, totalMins));

        Log.d(TAG, String.format("Adaptive cooldown: %.1f min (risk=%.2f, sessions=%d, night=%.0f)",
                totalMins, riskScore, sessionCount, nightMins));

        return (long) (totalMins * 60 * 1000);
    }
}
