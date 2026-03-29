package com.neuropulse.app.features;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * Tracks consecutive healthy days and reward tiers.
 * A "healthy day" = no HIGH risk sessions triggered.
 */
public class StreakManager {

    private static final String PREFS_NAME = "neuropulse_streaks";
    private static final String KEY_CURRENT_STREAK = "current_streak";
    private static final String KEY_LONGEST_STREAK = "longest_streak";
    private static final String KEY_LAST_HEALTHY_DAY = "last_healthy_day";
    private static final String KEY_TODAY_HIGH_RISK = "today_high_risk";
    private static final String KEY_TOTAL_HEALTHY_DAYS = "total_healthy_days";

    private final SharedPreferences prefs;

    public StreakManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Call at end of day (or app open next day) to evaluate the previous day.
     */
    public void evaluateDay() {
        int today = getDayOfYear();
        int lastDay = prefs.getInt(KEY_LAST_HEALTHY_DAY, -1);

        // Already evaluated today
        if (lastDay == today)
            return;

        boolean hadHighRisk = prefs.getBoolean(KEY_TODAY_HIGH_RISK, false);
        SharedPreferences.Editor editor = prefs.edit();

        if (!hadHighRisk) {
            // Healthy day!
            int streak = prefs.getInt(KEY_CURRENT_STREAK, 0) + 1;
            int longest = prefs.getInt(KEY_LONGEST_STREAK, 0);
            int totalHealthy = prefs.getInt(KEY_TOTAL_HEALTHY_DAYS, 0) + 1;

            editor.putInt(KEY_CURRENT_STREAK, streak);
            editor.putInt(KEY_LONGEST_STREAK, Math.max(longest, streak));
            editor.putInt(KEY_TOTAL_HEALTHY_DAYS, totalHealthy);
        } else {
            // Streak broken
            editor.putInt(KEY_CURRENT_STREAK, 0);
        }

        editor.putInt(KEY_LAST_HEALTHY_DAY, today);
        editor.putBoolean(KEY_TODAY_HIGH_RISK, false); // reset for new day
        editor.apply();
    }

    /**
     * Records that a high-risk session was detected today.
     * Call this when addiction level = 2.
     */
    public void recordHighRiskSession() {
        prefs.edit().putBoolean(KEY_TODAY_HIGH_RISK, true).apply();
    }

    public int getCurrentStreak() {
        return prefs.getInt(KEY_CURRENT_STREAK, 0);
    }

    public int getLongestStreak() {
        return prefs.getInt(KEY_LONGEST_STREAK, 0);
    }

    public int getTotalHealthyDays() {
        return prefs.getInt(KEY_TOTAL_HEALTHY_DAYS, 0);
    }

    /**
     * Returns reward tier based on current streak.
     */
    public String getRewardLevel() {
        int streak = getCurrentStreak();
        if (streak >= 30)
            return "🏆 Platinum";
        if (streak >= 14)
            return "🥇 Gold";
        if (streak >= 7)
            return "🥈 Silver";
        if (streak >= 3)
            return "🥉 Bronze";
        return "🌱 Starter";
    }

    /**
     * Returns a motivational message based on streak status.
     */
    public String getStreakMessage() {
        int streak = getCurrentStreak();
        if (streak == 0)
            return "Start your streak today! Stay mindful.";
        if (streak == 1)
            return "1 day strong! Keep it going!";
        if (streak < 7)
            return streak + " days! Building momentum! 🔥";
        if (streak < 14)
            return streak + " days! Silver tier unlocked! 🥈";
        if (streak < 30)
            return streak + " days! Gold tier achieved! 🥇";
        return streak + " days! Platinum champion! 🏆";
    }

    private int getDayOfYear() {
        return Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
    }
}
