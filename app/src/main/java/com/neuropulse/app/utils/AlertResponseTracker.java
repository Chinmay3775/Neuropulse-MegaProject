package com.neuropulse.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.neuropulse.app.ml.RiskThresholds;

/**
 * Tracks user responses to alerts and manages escalation logic.
 * After MAX_CONTINUES consecutive "Continue" presses, forces cooldown.
 */
public class AlertResponseTracker {

    private static final String PREFS_NAME = "neuropulse_alerts";
    private static final String KEY_CONSECUTIVE = "consecutive_continues";
    private static final String KEY_TOTAL_ALERTS = "total_alerts";
    private static final String KEY_TOTAL_BREAKS = "total_breaks";
    private static final String KEY_TOTAL_IGNORES = "total_ignores";
    private static final String KEY_LAST_ALERT_TIME = "last_alert_time";
    private static final String KEY_IGNORED_COUNT = "ignored_count";

    private final SharedPreferences prefs;

    public AlertResponseTracker(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Records the user's response to an alert.
     * @param continued true = user pressed "Continue", false = user pressed "Take a Break"
     */
    public void recordResponse(boolean continued) {
        SharedPreferences.Editor editor = prefs.edit();

        if (continued) {
            int count = prefs.getInt(KEY_CONSECUTIVE, 0) + 1;
            editor.putInt(KEY_CONSECUTIVE, count);
            editor.putInt(KEY_IGNORED_COUNT, 0); // reset ignore counter
        } else {
            editor.putInt(KEY_CONSECUTIVE, 0); // reset on break
            editor.putInt(KEY_IGNORED_COUNT, 0);
            int breaks = prefs.getInt(KEY_TOTAL_BREAKS, 0) + 1;
            editor.putInt(KEY_TOTAL_BREAKS, breaks);
        }

        int total = prefs.getInt(KEY_TOTAL_ALERTS, 0) + 1;
        editor.putInt(KEY_TOTAL_ALERTS, total);
        editor.putLong(KEY_LAST_ALERT_TIME, System.currentTimeMillis());
        editor.apply();
    }

    /**
     * Records that the user ignored (dismissed) an alert without responding.
     */
    public void recordIgnore() {
        SharedPreferences.Editor editor = prefs.edit();
        int ignores = prefs.getInt(KEY_IGNORED_COUNT, 0) + 1;
        int totalIgnores = prefs.getInt(KEY_TOTAL_IGNORES, 0) + 1;
        editor.putInt(KEY_IGNORED_COUNT, ignores);
        editor.putInt(KEY_TOTAL_IGNORES, totalIgnores);
        editor.apply();
    }

    public int getConsecutiveContinueCount() {
        return prefs.getInt(KEY_CONSECUTIVE, 0);
    }

    public int getIgnoredCount() {
        return prefs.getInt(KEY_IGNORED_COUNT, 0);
    }

    /**
     * Returns true if the user should be forced into cooldown.
     * Triggers on 3+ consecutive continues OR 3+ ignored alerts.
     */
    public boolean shouldEscalate() {
        return getConsecutiveContinueCount() >= RiskThresholds.MAX_STRIKES
                || getIgnoredCount() >= RiskThresholds.MAX_STRIKES;
    }

    /**
     * Resets escalation counter — call when user voluntarily takes a break.
     */
    public void resetOnBreak() {
        prefs.edit()
                .putInt(KEY_CONSECUTIVE, 0)
                .putInt(KEY_IGNORED_COUNT, 0)
                .apply();
    }

    public int getTotalAlerts() {
        return prefs.getInt(KEY_TOTAL_ALERTS, 0);
    }

    public int getTotalBreaks() {
        return prefs.getInt(KEY_TOTAL_BREAKS, 0);
    }

    public int getTodayInterventions() {
        // Simple: count total alerts as proxy. Could be day-scoped.
        return prefs.getInt(KEY_TOTAL_ALERTS, 0);
    }
}
