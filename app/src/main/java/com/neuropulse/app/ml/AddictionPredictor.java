package com.neuropulse.app.ml;

import android.content.Context;
import android.util.Log;

import com.neuropulse.app.models.SessionFeatures;

/**
 * Neuropulse – Buildathon Predictor
 * Rule-based logic + optional ML smoothing
 */
public class AddictionPredictor {

    private static final String TAG = "AddictionPredictor";

    private final TFLiteModelHelper mlHelper;

    public AddictionPredictor(Context context) {
        mlHelper = new TFLiteModelHelper(context);
    }

    // ================= PREDICTION RESULT =================
    public static class PredictionResult {
        public final float dopamineRisk;   // 0.0 – 1.0
        public final int addictionLevel;   // 0,1,2
        public final String riskLevel;     // LOW, MEDIUM, HIGH
        public final String reason;

        public PredictionResult(float risk, int level, String reason) {
            this.dopamineRisk = risk;
            this.addictionLevel = level;
            this.riskLevel = risk >= 0.7f ? "HIGH" :
                    risk >= 0.4f ? "MEDIUM" : "LOW";
            this.reason = reason;
        }
    }

    // ================= MAIN PREDICT METHOD =================
    public PredictionResult predict(SessionFeatures f) {

        float risk = 0f;

        // -------- 1. SESSION DURATION --------
        if (f.sessionDurationMs > 3 * 60 * 60 * 1000) {
            risk += 0.35f;
        } else if (f.sessionDurationMs > 1 * 60 * 60 * 1000) {
            risk += 0.25f;
        } else if (f.sessionDurationMs > 30 * 60 * 1000) {
            risk += 0.15f;
        }

        // -------- 2. APP CATEGORY --------
        if (f.appCategory == 0) {            // Social media
            risk += 0.30f;
        } else if (f.appCategory == 2) {     // Entertainment / Video
            risk += 0.25f;
        } else if (f.appCategory == 3) {     // Games
            risk += 0.20f;
        }

        // -------- 3. SCROLL INTENSITY --------
        if (f.scrollsPerMinute > 18) {
            risk += 0.20f;
        } else if (f.scrollsPerMinute > 12) {
            risk += 0.15f;
        }

        // -------- 4. CONTINUOUS USAGE --------
        if (f.consecutiveSameAppMin > 120) {
            risk += 0.20f;
        } else if (f.consecutiveSameAppMin > 60) {
            risk += 0.10f;
        }

        // -------- 5. TIME OF DAY --------
        float hour = f.timeOfDay * 24f;
        if (hour >= 23 || hour <= 5) {
            risk += 0.15f;
        }

        // -------- 6. BINGE FLAG --------
        if (f.bingeFlag == 1) {
            risk += 0.20f;
        }

        // Clamp before ML
        risk = Math.min(1f, risk);

        // -------- 7. ML SMOOTHING (OPTIONAL) --------
        float finalRisk = mlHelper.isModelLoaded()
                ? mlHelper.smoothRisk(risk)
                : risk;

        finalRisk = Math.min(1f, Math.max(0f, finalRisk));

        int addictionLevel =
                finalRisk >= 0.7f ? 2 :
                        finalRisk >= 0.4f ? 1 : 0;

        String reason = buildReason(f, finalRisk);

        Log.d(TAG, "Risk=" + finalRisk + " Level=" + addictionLevel);

        return new PredictionResult(finalRisk, addictionLevel, reason);
    }

    // ================= REASON EXPLAINER =================
    private String buildReason(SessionFeatures f, float risk) {

        if (risk >= 0.7f) {
            if (f.appCategory == 0)
                return "High dopamine stimulation from social media";
            if (f.bingeFlag == 1)
                return "Extended binge usage detected";
            return "High engagement behavior detected";
        }

        if (risk >= 0.4f) {
            if (f.scrollsPerMinute > 12)
                return "Increased interaction intensity";
            return "Usage pattern trending upward";
        }

        return "Healthy usage pattern";
    }
}
