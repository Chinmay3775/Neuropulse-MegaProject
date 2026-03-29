package com.neuropulse.app.features;

import com.neuropulse.app.ml.RiskThresholds;

/**
 * Intelligence layer for distinguishing productive vs addictive usage patterns.
 * Provides context-aware risk adjustment and alert suppression.
 */
public class UsageIntelligence {

    /**
     * Returns true if the app is productive (should suppress alerts even for long sessions).
     */
    public static boolean isProductivePackage(String packageName) {
        if (packageName == null) return false;
        return packageName.contains("docs.editors") ||
                packageName.contains("android.apps.docs") ||
                packageName.contains("google.android.calendar") ||
                packageName.contains("google.android.keep") ||
                packageName.contains("microsoft.office") ||
                packageName.contains("notion.android") ||
                packageName.contains("slack") ||
                packageName.contains("zoom.videomeetings") ||
                packageName.contains("google.android.apps.meetings") ||
                packageName.contains("skype") ||
                packageName.contains("teams") ||
                packageName.contains("evernote") ||
                packageName.contains("todoist") ||
                packageName.contains("spotify") || // music for focus
                packageName.contains("kindle") ||
                packageName.contains("duolingo"); // learning
    }

    /**
     * Determines if we should suppress an alert for this app based on pattern.
     * Returns true to suppress (don't alert).
     */
    public static boolean shouldSuppressAlert(String packageName, int category, float risk) {
        // Never alert for productive apps unless extremely high risk
        if (isProductivePackage(packageName)) {
            return risk < RiskThresholds.ESCALATION_THRESHOLD;
        }

        // Communication apps get more leeway (could be important conversations)
        if (category == RiskThresholds.CATEGORY_COMMUNICATION) {
            return risk < RiskThresholds.HIGH_RISK;
        }

        // Productive categories get suppressed below high risk
        if (RiskThresholds.isProductiveCategory(category)) {
            return risk < RiskThresholds.HIGH_RISK;
        }

        // Default: don't suppress
        return false;
    }

    /**
     * Context-aware risk adjustment.
     * If user switches from productive to addictive app, initial risk is higher.
     */
    public static float adjustRiskForContext(float baseRisk, int previousCategory, int currentCategory) {
        // Productive → Addictive transition = higher starting risk
        if (RiskThresholds.isProductiveCategory(previousCategory) &&
                RiskThresholds.isHighStimCategory(currentCategory)) {
            return Math.min(1f, baseRisk + 0.1f);
        }

        // Addictive → Addictive transition = slightly higher (app hopping)
        if (RiskThresholds.isHighStimCategory(previousCategory) &&
                RiskThresholds.isHighStimCategory(currentCategory) &&
                previousCategory != currentCategory) {
            return Math.min(1f, baseRisk + 0.05f);
        }

        return baseRisk;
    }

    /**
     * Returns the usage classification label.
     */
    public static String classifyUsage(int category, float risk) {
        if (RiskThresholds.isProductiveCategory(category) && risk < RiskThresholds.MEDIUM_RISK) {
            return "PRODUCTIVE";
        }
        if (risk >= RiskThresholds.HIGH_RISK) {
            return "ADDICTIVE";
        }
        if (risk >= RiskThresholds.MEDIUM_RISK) {
            return "MODERATE";
        }
        return "NEUTRAL";
    }
}
