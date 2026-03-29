package com.neuropulse.app.ml;

import android.content.Context;
import android.util.Log;

import com.neuropulse.app.models.SessionFeatures;

import java.util.ArrayList;
import java.util.List;

/**
 * NeuroPulse Addiction Predictor — Hybrid Rule + ML Engine.
 * Uses improved rule-based scoring with sigmoid smoothing,
 * plus actual TFLite models (dopamine + addiction) when available.
 */
public class AddictionPredictor {

    private static final String TAG = "AddictionPredictor";

    private final TFLiteModelHelper mlHelper;

    public AddictionPredictor(Context context) {
        mlHelper = new TFLiteModelHelper(context);
    }

    // ================= PREDICTION RESULT =================
    public static class PredictionResult {
        public final float dopamineRisk;     // 0.0–1.0
        public final int addictionLevel;     // 0, 1, 2
        public final String riskLevel;       // LOW, MEDIUM, HIGH
        public final String reason;
        public final float mlDopamineRisk;   // raw ML output (-1 if unavailable)
        public final float[] mlAddictionProbs; // [healthy, atRisk, highRisk] or null
        public final List<String> contributingFactors;

        public PredictionResult(float risk, int level, String reason,
                                float mlDop, float[] mlAdd,
                                List<String> factors) {
            this.dopamineRisk = risk;
            this.addictionLevel = level;
            this.riskLevel = RiskThresholds.getRiskLevel(risk);
            this.reason = reason;
            this.mlDopamineRisk = mlDop;
            this.mlAddictionProbs = mlAdd;
            this.contributingFactors = factors;
        }
    }

    // ================= MAIN PREDICT METHOD =================
    public PredictionResult predict(SessionFeatures f) {

        List<String> factors = new ArrayList<>();
        float ruleRisk = computeRuleRisk(f, factors);

        // -------- ML INFERENCE --------
        float mlDopamine = -1f;
        float[] mlAddiction = null;
        float finalRisk = ruleRisk;

        if (mlHelper.isModelLoaded()) {
            float[] featureArray = f.toFeatureArray();

            mlDopamine = mlHelper.predictDopamineRisk(featureArray);
            mlAddiction = mlHelper.predictAddictionLevel(featureArray);

            // Blend rule-based and ML predictions
            if (mlDopamine >= 0f) {
                // 40% rule-based + 60% ML for best of both
                finalRisk = 0.4f * ruleRisk + 0.6f * mlDopamine;
            }

            // If addiction model disagrees strongly, adjust
            if (mlAddiction != null) {
                float mlHighRiskProb = mlAddiction[2]; // P(high risk)
                if (mlHighRiskProb > 0.7f && finalRisk < RiskThresholds.MEDIUM_RISK) {
                    finalRisk = Math.max(finalRisk, RiskThresholds.MEDIUM_RISK);
                    factors.add("ML model detected high-risk pattern");
                }
            }
        }

        finalRisk = Math.max(0f, Math.min(1f, finalRisk));

        int addictionLevel = RiskThresholds.getAddictionLevel(finalRisk);
        String reason = buildReason(f, finalRisk, factors);

        Log.d(TAG, String.format("Rule=%.3f ML=%.3f Final=%.3f Level=%d",
                ruleRisk, mlDopamine, finalRisk, addictionLevel));

        return new PredictionResult(
                finalRisk, addictionLevel, reason,
                mlDopamine, mlAddiction, factors
        );
    }

    // ================= RULE-BASED SCORING =================
    private float computeRuleRisk(SessionFeatures f, List<String> factors) {
        float risk = 0f;

        // 1. SESSION DURATION — sigmoid curve instead of hard steps
        float durationHours = f.sessionDurationMs / (3600f * 1000f);
        float durationRisk = sigmoid(durationHours, 1.5f, 2.0f) * 0.30f;
        risk += durationRisk;
        if (durationHours > 1f) factors.add("Extended session (" + (int) (durationHours * 60) + " min)");

        // 2. APP CATEGORY
        if (RiskThresholds.isHighStimCategory(f.appCategory)) {
            float catRisk = (f.appCategory == RiskThresholds.CATEGORY_SOCIAL) ? 0.28f :
                    (f.appCategory == RiskThresholds.CATEGORY_ENTERTAINMENT) ? 0.23f : 0.18f;
            risk += catRisk;
            factors.add(RiskThresholds.getCategoryName(f.appCategory) + " app detected");
        }

        // 3. INTERACTION INTENSITY
        float scrollRisk = sigmoid(f.scrollsPerMinute, 10f, 5f) * 0.18f;
        risk += scrollRisk;
        if (f.scrollsPerMinute > 12f) factors.add("High interaction intensity");

        // 4. CONTINUOUS USAGE
        float continuousRisk = sigmoid(f.consecutiveSameAppMin, 60f, 30f) * 0.18f;
        risk += continuousRisk;
        if (f.consecutiveSameAppMin > 60) factors.add("Prolonged same-app usage");

        // 5. TIME OF DAY
        float hour = f.timeOfDay * 24f;
        if (hour >= 23 || hour <= 5) {
            risk += 0.15f;
            factors.add("Late-night usage");
        } else if (hour >= 20) {
            risk += 0.08f;
            factors.add("Evening usage");
        }

        // 6. BINGE FLAG
        if (f.bingeFlag == 1) {
            risk += 0.18f;
            factors.add("Binge session detected (>2h)");
        }

        // 7. UNLOCK FREQUENCY (new)
        if (f.unlockFrequency > 25f) {
            risk += 0.10f;
            factors.add("Frequent device unlocks");
        }

        // 8. SESSION COUNT (new — multiple sessions = compulsive checking)
        if (f.sessionCount > 10) {
            risk += 0.08f;
            factors.add("Frequent app reopening today");
        }

        return Math.min(1f, risk);
    }

    /**
     * Smooth sigmoid transition — avoids hard thresholds.
     * Returns 0–1, centered at 'center' with steepness 'k'.
     */
    private float sigmoid(float value, float center, float k) {
        return (float) (1.0 / (1.0 + Math.exp(-((value - center) / k))));
    }

    // ================= REASON BUILDER =================
    private String buildReason(SessionFeatures f, float risk, List<String> factors) {
        if (risk >= RiskThresholds.HIGH_RISK) {
            if (f.appCategory == RiskThresholds.CATEGORY_SOCIAL)
                return "High dopamine stimulation from social media";
            if (f.bingeFlag == 1)
                return "Extended binge usage detected";
            if (!factors.isEmpty())
                return factors.get(0);
            return "High engagement behavior detected";
        }

        if (risk >= RiskThresholds.MEDIUM_RISK) {
            if (f.scrollsPerMinute > 12)
                return "Increased interaction intensity";
            if (f.consecutiveSameAppMin > 60)
                return "Prolonged continuous usage";
            return "Usage pattern trending upward";
        }

        return "Healthy usage pattern";
    }

    public void close() {
        if (mlHelper != null) {
            mlHelper.close();
        }
    }
}
