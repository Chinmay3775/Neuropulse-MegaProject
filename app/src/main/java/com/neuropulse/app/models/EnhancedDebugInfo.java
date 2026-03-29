package com.neuropulse.app.models;

import com.neuropulse.app.features.RealTimeAppDetector;
import com.neuropulse.app.features.UsageIntelligence;
import com.neuropulse.app.ml.AddictionPredictor;
import com.neuropulse.app.ml.RiskThresholds;

/**
 * Debug-only model for displaying live ML + rule-based signals.
 * Enhanced to show ML outputs, risk trends, and session limits.
 */
public class EnhancedDebugInfo {

    public String[] featureLabels;
    public String[] featureValues;

    // Direct values for dashboard binding
    public float rawDopamineRisk;
    public String rawRiskLevel;
    public String rawAppName;
    public String usageClass;
    public String lastTrend;

    private EnhancedDebugInfo() {}

    // ================= FACTORY METHOD =================

    public static EnhancedDebugInfo from(
            SessionFeatures features,
            RealTimeAppDetector.CurrentAppInfo app,
            AddictionPredictor.PredictionResult result
    ) {
        EnhancedDebugInfo info = new EnhancedDebugInfo();

        info.rawDopamineRisk = result.dopamineRisk;
        info.rawRiskLevel = result.riskLevel;
        info.rawAppName = app.displayName;
        info.usageClass = UsageIntelligence.classifyUsage(app.category, result.dopamineRisk);

        if (features.riskTrend > 0f) info.lastTrend = "↑";
        else if (features.riskTrend < 0f) info.lastTrend = "↓";
        else info.lastTrend = "→";

        // Collect string factor array if available
        String mainFactor = "None";
        if (result.contributingFactors != null && !result.contributingFactors.isEmpty()) {
            mainFactor = result.contributingFactors.get(0);
        }

        info.featureLabels = new String[]{
                "📱 App Category",
                "📊 Usage Classification",
                "📈 ML Dopamine Prob",
                "🤖 ML Addiction State",
                "⏱ Session Duration",
                "📜 Inferred Intensity",
                "🔁 Apps Switched",
                "🔑 Unlocks",
                "🎯 Primary Risk Factor",
                "💡 Action Required",
                "📉 Trend"
        };

        String mlAddictionValue = "N/A";
        if (result.mlAddictionProbs != null && result.mlAddictionProbs.length == 3) {
             float maxProb = 0f;
             int maxIdx = 0;
             for (int i=0; i<3; i++) {
                if(result.mlAddictionProbs[i] > maxProb) {
                    maxProb = result.mlAddictionProbs[i];
                    maxIdx = i;
                }
             }
             mlAddictionValue = (maxIdx == 2 ? "High Risk" : maxIdx == 1 ? "At Risk" : "Healthy") + 
                                String.format(" (%.0f%%)", maxProb * 100);
        }

        info.featureValues = new String[]{
                RiskThresholds.getCategoryName(features.appCategory),
                info.usageClass,
                result.mlDopamineRisk >= 0f ? String.format("%.1f%%", result.mlDopamineRisk * 100f) : "Rule Engine Fallback",
                mlAddictionValue,
                formatDuration(features.sessionDurationMs),
                String.format("%.1f/min", features.scrollsPerMinute),
                String.valueOf(features.appSwitchCount),
                String.valueOf(features.unlockCount),
                mainFactor,
                result.dopamineRisk >= RiskThresholds.ALERT_THRESHOLD ? "Take a short break" : "Healthy, continue",
                info.lastTrend
        };

        return info;
    }

    // ================= HELPERS =================

    private static String formatDuration(long ms) {
        long sec = ms / 1000;
        long min = sec / 60;
        return min + " min";
    }
}
