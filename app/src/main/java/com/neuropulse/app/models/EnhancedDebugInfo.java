package com.neuropulse.app.models;

import com.neuropulse.app.features.RealTimeAppDetector;
import com.neuropulse.app.ml.AddictionPredictor;

/**
 * Debug-only model for displaying live ML + rule-based signals
 * NO database, NO historical context
 */
public class EnhancedDebugInfo {

    public String[] featureLabels;
    public String[] featureValues;

    private EnhancedDebugInfo() {}

    // ================= FACTORY METHOD =================

    public static EnhancedDebugInfo from(
            SessionFeatures features,
            RealTimeAppDetector.CurrentAppInfo app,
            AddictionPredictor.PredictionResult result
    ) {
        EnhancedDebugInfo info = new EnhancedDebugInfo();

        info.featureLabels = new String[]{
                "📱 Current App",
                "📂 App Category",
                "⏱ Session Duration",
                "📜 Scrolls / Minute",
                "🔁 Same App Time",
                "🕒 Time of Day",
                "🎮 Binge Flag",
                "🧠 Dopamine Risk",
                "⚠️ Risk Level",
                "🎯 Addiction State",
                "💡 Recommendation",
                "📊 Confidence"
        };

        info.featureValues = new String[]{
                app.displayName,
                getCategoryName(features.appCategory),
                formatDuration(features.sessionDurationMs),
                String.format("%.1f", features.scrollsPerMinute),
                features.consecutiveSameAppMin + " min",
                formatTime(features.timeOfDay),
                features.bingeFlag == 1 ? "YES" : "NO",
                String.format("%.2f", result.dopamineRisk),
                getRiskLevel(result.dopamineRisk),
                getAddictionState(result.addictionLevel),
                result.dopamineRisk >= 0.6f
                        ? "Take a short break or switch activity"
                        : "Usage looks healthy"
        };


        return info;
    }

    // ================= HELPERS =================

    private static String getRiskLevel(float risk) {
        if (risk >= 0.7f) return "HIGH";
        if (risk >= 0.4f) return "MEDIUM";
        return "LOW";
    }

    private static String getAddictionState(int level) {
        switch (level) {
            case 2: return "High Risk";
            case 1: return "At Risk";
            default: return "Healthy";
        }
    }

    private static String formatDuration(long ms) {
        long sec = ms / 1000;
        long min = sec / 60;
        return min + " min";
    }

    private static String formatTime(float timeOfDay) {
        int hour = (int) (timeOfDay * 24);
        if (hour < 6) return "Night";
        if (hour < 12) return "Morning";
        if (hour < 18) return "Afternoon";
        return "Evening";
    }

    private static String getCategoryName(int c) {
        switch (c) {
            case 0: return "Social Media";
            case 1: return "Productivity";
            case 2: return "Entertainment";
            case 3: return "Games";
            case 4: return "News";
            case 5: return "Shopping";
            case 6: return "Communication";
            default: return "Other";
        }
    }
}
