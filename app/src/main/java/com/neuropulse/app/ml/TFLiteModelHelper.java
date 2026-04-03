package com.neuropulse.app.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Dual TFLite model helper for NeuroPulse.
 * Loads both dopamine_model.tflite (binary) and addiction_model.tflite (3-class).
 * Both models expect a 16-feature input vector (StandardScaler applied on-device).
 *
 * Scaler parameters (mean/std) are loaded dynamically from assets/scaler_config.json
 * so that retraining the Python pipeline automatically propagates to the Android app
 * without requiring Java code changes.
 */
public class TFLiteModelHelper {

    private static final String TAG = "TFLiteHelper";

    private static final String DOPAMINE_MODEL = "dopamine_model.tflite";
    private static final String ADDICTION_MODEL = "addiction_model.tflite";
    private static final String SCALER_CONFIG  = "scaler_config.json";

    private volatile Interpreter dopamineInterpreter;
    private volatile Interpreter addictionInterpreter;
    private volatile boolean dopamineLoaded = false;
    private volatile boolean addictionLoaded = false;

    // Dynamically loaded from scaler_config.json
    private volatile float[] scalerMean;
    private volatile float[] scalerScale;
    private volatile boolean scalerLoaded = false;

    public TFLiteModelHelper(Context context) {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            // 1. Load scaler configuration first
            loadScalerConfig(context);

            // 2. Load dopamine model
            try {
                dopamineInterpreter = new Interpreter(loadModel(context, DOPAMINE_MODEL));
                dopamineLoaded = true;
                Log.i(TAG, "✅ Dopamine model loaded");
            } catch (Exception e) {
                Log.w(TAG, "Dopamine model not available: " + e.getMessage());
            }

            // 3. Load addiction model
            try {
                addictionInterpreter = new Interpreter(loadModel(context, ADDICTION_MODEL));
                addictionLoaded = true;
                Log.i(TAG, "✅ Addiction model loaded");
            } catch (Exception e) {
                Log.w(TAG, "Addiction model not available: " + e.getMessage());
            }

            // 4. Warmup inference to avoid first-call latency
            if (dopamineLoaded || addictionLoaded) {
                warmup();
            }
        });
    }

    // ================= SCALER CONFIG =================
    /**
     * Loads StandardScaler mean/scale arrays from assets/scaler_config.json.
     * Falls back to hardcoded defaults if the file is missing or malformed.
     */
    private void loadScalerConfig(Context context) {
        try {
            InputStream is = context.getAssets().open(SCALER_CONFIG);
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();

            JSONObject json = new JSONObject(new String(buf, "UTF-8"));
            JSONArray meansArr = json.getJSONArray("means");
            JSONArray scalesArr = json.getJSONArray("scales");

            scalerMean = new float[meansArr.length()];
            scalerScale = new float[scalesArr.length()];

            for (int i = 0; i < meansArr.length(); i++) {
                scalerMean[i] = (float) meansArr.getDouble(i);
            }
            for (int i = 0; i < scalesArr.length(); i++) {
                scalerScale[i] = (float) scalesArr.getDouble(i);
            }

            scalerLoaded = true;
            Log.i(TAG, "✅ Scaler config loaded from JSON (" + scalerMean.length + " features)");

        } catch (Exception e) {
            Log.w(TAG, "scaler_config.json not found or malformed — using hardcoded defaults", e);
            // Hardcoded fallback (matches training script v1)
            scalerMean = new float[]{
                    9_500_000f, 12f, 3.5f, 2.5f, 0.8f, 2f, 0.5f, 30f,
                    0.1f, 8f, 15f, 2.6f, 0.5f, 0.4f, 1.2f, 0.35f
            };
            scalerScale = new float[]{
                    15_000_000f, 10f, 3f, 2.5f, 0.8f, 2f, 0.28f, 35f,
                    0.3f, 5f, 15f, 4.2f, 0.5f, 0.35f, 2f, 0.48f
            };
            scalerLoaded = true;
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

    public boolean isScalerLoaded() {
        return scalerLoaded;
    }

    // ================= LEGACY COMPAT =================
    /**
     * Legacy method for backward compatibility.
     */
    public float smoothRisk(float ruleRisk) {
        return ruleRisk;
    }

    // ================= DOPAMINE PREDICTION =================
    /**
     * Predicts dopamine spike probability from a 16-feature vector.
     * Returns 0.0–1.0 (sigmoid output).
     */
    public float predictDopamineRisk(float[] features) {
        if (!dopamineLoaded || dopamineInterpreter == null) {
            return -1f;
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
        for (int i = 0; i < raw.length && i < scalerMean.length; i++) {
            float std = scalerScale[i];
            if (std == 0f) std = 1f;
            scaled[i] = (raw[i] - scalerMean[i]) / std;
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
        AssetFileDescriptor fd = context.getAssets().openFd(modelName);
        if (fd == null) {
            throw new Exception("Asset missing: " + modelName);
        }

        try (FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
             FileChannel channel = fis.getChannel()) {

            long startOffset = fd.getStartOffset();
            long declaredLength = fd.getDeclaredLength();

            Log.d(TAG, String.format("Mapping model %s (offset=%d, length=%d)",
                    modelName, startOffset, declaredLength));

            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
            fd.close();
            return buffer;
        } catch (Exception e) {
            Log.e(TAG, "Failed to map model " + modelName, e);
            fd.close();
            throw e;
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
