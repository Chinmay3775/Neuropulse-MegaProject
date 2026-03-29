package com.neuropulse.app.features;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.neuropulse.app.ml.RiskThresholds;
import com.neuropulse.app.services.CooldownManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Focus Mode — user-initiated blocking of selected app categories.
 * Integrates with CooldownManager for enforcement.
 */
public class FocusModeManager {

    private static final String TAG = "FocusMode";
    private static final String PREFS_NAME = "neuropulse_focus";

    private final SharedPreferences prefs;
    private final CooldownManager cooldownManager;

    public FocusModeManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.cooldownManager = new CooldownManager(context);
    }

    /**
     * Starts focus mode — blocks all specified categories for the given duration.
     */
    public void startFocusMode(int durationMinutes, Set<Integer> blockedCategories) {
        long endTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000L);

        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("focus_active", true);
        editor.putLong("focus_end_time", endTime);
        editor.putInt("focus_duration_min", durationMinutes);

        Set<String> catStrings = new HashSet<>();
        for (int cat : blockedCategories) {
            catStrings.add(String.valueOf(cat));
        }
        editor.putStringSet("focus_categories", catStrings);
        editor.apply();

        Log.i(TAG, "Focus mode started: " + durationMinutes + " min, categories: " + catStrings);
    }

    /**
     * Checks if focus mode is currently active.
     */
    public boolean isFocusModeActive() {
        if (!prefs.getBoolean("focus_active", false)) return false;
        long endTime = prefs.getLong("focus_end_time", 0);
        if (System.currentTimeMillis() >= endTime) {
            endFocusMode();
            return false;
        }
        return true;
    }

    /**
     * Checks if a specific category is blocked by focus mode.
     */
    public boolean isCategoryBlockedByFocus(int category) {
        if (!isFocusModeActive()) return false;
        Set<String> blocked = prefs.getStringSet("focus_categories", new HashSet<>());
        return blocked.contains(String.valueOf(category));
    }

    /**
     * Returns remaining focus mode time in milliseconds.
     */
    public long getRemainingMs() {
        long endTime = prefs.getLong("focus_end_time", 0);
        return Math.max(0, endTime - System.currentTimeMillis());
    }

    /**
     * Ends focus mode early.
     */
    public void endFocusMode() {
        prefs.edit()
                .putBoolean("focus_active", false)
                .putLong("focus_end_time", 0)
                .remove("focus_categories")
                .apply();
        Log.i(TAG, "Focus mode ended");
    }

    public int getFocusDurationMin() {
        return prefs.getInt("focus_duration_min", 30);
    }
}
