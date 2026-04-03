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

    private final List<Float> scoreHistory = new ArrayList<>();
    private static final int HISTORY_WINDOW = 5;

    // ================= MAIN PREDICT METHOD =================
    public PredictionResult predict(SessionFeatures f) {

        List<String> factors = new ArrayList<>();
        float ruleRisk = computeRuleRisk(f, factors);

        // -------- ML INFERENCE --------
        float mlDopamine = -1f;
        float[] mlAddiction = null;
        float rawFinalRisk = ruleRisk;

        if (mlHelper.isModelLoaded()) {
            float[] featureArray = f.toFeatureArray();

            mlDopamine = mlHelper.predictDopamineRisk(featureArray);
            mlAddiction = mlHelper.predictAddictionLevel(featureArray);

            // Prioritize ML predictions ONLY when confident or high-risk
            if (mlDopamine >= 0f) {
                if (mlDopamine < 0) {
                    rawFinalRisk = ruleRisk;
                } else {
                    rawFinalRisk = (0.6f * mlDopamine) + (0.4f * ruleRisk);
                }
                
                if (mlDopamine >= RiskThresholds.ALERT_THRESHOLD) {
                    if (!factors.contains("AI identified aggressive engagement pattern")) {
                        factors.add("AI identified aggressive engagement pattern");
                    }
                }
            }

            // High-confidence addiction classification
            if (mlAddiction != null) {
                float mlHighRiskProb = mlAddiction[2];
                if (mlHighRiskProb > 0.8f) {
                    rawFinalRisk = Math.max(rawFinalRisk, RiskThresholds.HIGH_RISK);
                    factors.add("AI detected chronic doomscrolling signals");
                }
            }
        }

        // Apply Temporal Smoothing (Rolling Average)
        float smoothedRisk = applyTemporalSmoothing(rawFinalRisk);
        smoothedRisk = Math.max(0f, Math.min(1f, smoothedRisk));

        int addictionLevel = RiskThresholds.getAddictionLevel(smoothedRisk);
        String reason = buildReason(f, smoothedRisk, factors);

        Log.d(TAG, String.format("Rule=%.3f ML=%.3f Raw=%.3f Smoothed=%.3f Level=%d",
                ruleRisk, mlDopamine, rawFinalRisk, smoothedRisk, addictionLevel));

        return new PredictionResult(
                smoothedRisk, addictionLevel, reason,
                mlDopamine, mlAddiction, factors
        );
    }

    public void resetHistory() {
        scoreHistory.clear();
    }

    private float applyTemporalSmoothing(float currentRisk) {
        scoreHistory.add(currentRisk);
        if (scoreHistory.size() > HISTORY_WINDOW) {
            scoreHistory.remove(0);
        }
        float sum = 0;
        for (float r : scoreHistory) sum += r;
        return sum / scoreHistory.size();
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

        // 3. INTERACTION INTENSITY (now from real scroll data when available)
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

        // 7. UNLOCK FREQUENCY
        if (f.unlockFrequency > 25f) {
            risk += 0.10f;
            factors.add("Frequent device unlocks");
        }

        // 8. SESSION COUNT (multiple sessions = compulsive checking)
        if (f.sessionCount > 10) {
            risk += 0.08f;
            factors.add("Frequent app reopening today");
        }

        // 9. RAPID SCROLL BURSTS (NEW — strong doomscrolling signal)
        //    A burst = 6+ scroll events in 5 seconds (erratic flicking)
        if (f.rapidBurstCount >= 3) {
            risk += 0.15f;
            factors.add("Rapid scroll bursts detected (" + f.rapidBurstCount + ")");
        } else if (f.rapidBurstCount >= 1) {
            risk += 0.07f;
            factors.add("Intermittent rapid scrolling");
        }

        // 10. SCROLL CADENCE VARIANCE (NEW — false positive reducer)
        //     Low variance = steady reading pace (educational). High = erratic (doomscrolling).
        //     Only apply for productive/utility categories to reduce false alarms.
        if (RiskThresholds.isProductiveCategory(f.appCategory)
                && f.scrollCadenceVariance < 50000f
                && f.scrollCadenceVariance > 0f) {
            // Steady scroll pattern on a productive app — apply discount
            risk *= 0.6f;
            factors.add("Steady reading pattern (educational)");
        } else if (f.scrollCadenceVariance > 500000f) {
            // Very erratic scrolling — boost risk regardless of category
            risk += 0.08f;
            factors.add("Erratic scroll pattern detected");
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
