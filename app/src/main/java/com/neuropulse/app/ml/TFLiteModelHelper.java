package com.neuropulse.app.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Dual TFLite model helper for NeuroPulse.
 * Loads both dopamine_model.tflite (binary) and addiction_model.tflite (3-class).
 * Both models expect a 16-feature input vector (StandardScaler applied on-device).
 */
public class TFLiteModelHelper {

    private static final String TAG = "TFLiteHelper";

    private static final String DOPAMINE_MODEL = "dopamine_model.tflite";
    private static final String ADDICTION_MODEL = "addiction_model.tflite";

    private Interpreter dopamineInterpreter;
    private Interpreter addictionInterpreter;
    private boolean dopamineLoaded = false;
    private boolean addictionLoaded = false;

    // StandardScaler mean/std approximations from training on 15k synthetic samples.
    // These match the scaler fitted in enhanced_ml_training.py.
    // In production, these would be loaded from feature_scaler.pkl.
    private static final float[] SCALER_MEAN = {
            // session_duration, unlock_count, app_category, notif_count,
            // notif_response, app_switch_count, time_of_day, consecutive_same_app,
            // binge_flag, scrolls_per_minute, unlock_frequency,
            // duration_hours, high_stim_app, notif_responsiveness, usage_intensity, evening_usage
            9_500_000f, 12f, 3.5f, 2.5f,
            0.8f, 2f, 0.5f, 30f,
            0.1f, 8f, 15f,
            2.6f, 0.5f, 0.4f, 1.2f, 0.35f
    };

    private static final float[] SCALER_STD = {
            15_000_000f, 10f, 3f, 2.5f,
            0.8f, 2f, 0.28f, 35f,
            0.3f, 5f, 15f,
            4.2f, 0.5f, 0.35f, 2f, 0.48f
    };

    public TFLiteModelHelper(Context context) {
        // Load dopamine model
        try {
            dopamineInterpreter = new Interpreter(loadModel(context, DOPAMINE_MODEL));
            dopamineLoaded = true;
            Log.i(TAG, "✅ Dopamine model loaded");
        } catch (Exception e) {
            Log.w(TAG, "Dopamine model not available: " + e.getMessage());
        }

        // Load addiction model
        try {
            addictionInterpreter = new Interpreter(loadModel(context, ADDICTION_MODEL));
            addictionLoaded = true;
            Log.i(TAG, "✅ Addiction model loaded");
        } catch (Exception e) {
            Log.w(TAG, "Addiction model not available: " + e.getMessage());
        }

        // Warmup inference to avoid first-call latency
        if (dopamineLoaded || addictionLoaded) {
            warmup();
        }
    }

    public boolean isModelLoaded() {
        return dopamineLoaded || addictionLoaded;
    }

    public boolean isDopamineModelLoaded() {
        return dopamineLoaded;
    }

    public boolean isAddictionModelLoaded() {
        return addictionLoaded;
    }

    // ================= LEGACY COMPAT =================
    /**
     * Legacy method for backward compatibility.
     * Now performs full ML inference if models are loaded.
     */
    public float smoothRisk(float ruleRisk) {
        return ruleRisk; // fallback — use predictDopamineRisk() for real ML
    }

    // ================= DOPAMINE PREDICTION =================
    /**
     * Predicts dopamine spike probability from a 16-feature vector.
     * Returns 0.0–1.0 (sigmoid output).
     */
    public float predictDopamineRisk(float[] features) {
        if (!dopamineLoaded || dopamineInterpreter == null) {
            return -1f; // signal: model not available
        }

        try {
            float[] scaled = applyScaler(features);
            float[][] input = new float[1][scaled.length];
            System.arraycopy(scaled, 0, input[0], 0, scaled.length);

            float[][] output = new float[1][1];
            dopamineInterpreter.run(input, output);

            return Math.max(0f, Math.min(1f, output[0][0]));
        } catch (Exception e) {
            Log.e(TAG, "Dopamine inference failed", e);
            return -1f;
        }
    }

    // ================= ADDICTION PREDICTION =================
    /**
     * Predicts addiction level from a 16-feature vector.
     * Returns float[3] with probabilities for [Healthy, AtRisk, HighRisk].
     */
    public float[] predictAddictionLevel(float[] features) {
        if (!addictionLoaded || addictionInterpreter == null) {
            return null;
        }

        try {
            float[] scaled = applyScaler(features);
            float[][] input = new float[1][scaled.length];
            System.arraycopy(scaled, 0, input[0], 0, scaled.length);

            float[][] output = new float[1][3];
            addictionInterpreter.run(input, output);

            return output[0];
        } catch (Exception e) {
            Log.e(TAG, "Addiction inference failed", e);
            return null;
        }
    }

    // ================= SCALING =================
    private float[] applyScaler(float[] raw) {
        float[] scaled = new float[raw.length];
        for (int i = 0; i < raw.length && i < SCALER_MEAN.length; i++) {
            float std = SCALER_STD[i];
            if (std == 0f) std = 1f;
            scaled[i] = (raw[i] - SCALER_MEAN[i]) / std;
        }
        return scaled;
    }

    // ================= WARMUP =================
    private void warmup() {
        try {
            float[] dummy = new float[RiskThresholds.FEATURE_COUNT];
            if (dopamineLoaded) predictDopamineRisk(dummy);
            if (addictionLoaded) predictAddictionLevel(dummy);
            Log.i(TAG, "Model warmup complete");
        } catch (Exception e) {
            Log.w(TAG, "Warmup failed (non-critical)", e);
        }
    }

    // ================= MODEL LOADING =================
    private MappedByteBuffer loadModel(Context context, String modelName) throws Exception {
        try (AssetFileDescriptor fd = context.getAssets().openFd(modelName);
             FileInputStream fis = new FileInputStream(fd.getFileDescriptor())) {

            FileChannel channel = fis.getChannel();
            long startOffset = fd.getStartOffset();
            long declaredLength = fd.getDeclaredLength();

            return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    public void close() {
        if (dopamineInterpreter != null) {
            dopamineInterpreter.close();
            dopamineInterpreter = null;
        }
        if (addictionInterpreter != null) {
            addictionInterpreter.close();
            addictionInterpreter = null;
        }
    }
}
