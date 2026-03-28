package com.neuropulse.app.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Lightweight TFLite helper for Neuropulse
 * Purpose: Smooth rule-based dopamine risk
 */
public class TFLiteModelHelper {

    private static final String TAG = "TFLiteHelper";
    private static final String MODEL_NAME = "neuropulse.tflite";

    private Interpreter interpreter;
    private boolean modelLoaded = false;

    public TFLiteModelHelper(Context context) {
        try {
            interpreter = new Interpreter(loadModel(context));
            modelLoaded = true;
            Log.i(TAG, "TFLite model loaded successfully");
        } catch (Exception e) {
            Log.w(TAG, "TFLite model not available, running rule-only mode");
            modelLoaded = false;
        }
    }

    public boolean isModelLoaded() {
        return modelLoaded;
    }

    /**
     * Smooths the rule-based risk score
     * Input:  0.0 – 1.0
     * Output: 0.0 – 1.0
     */
    public float smoothRisk(float ruleRisk) {

        if (!modelLoaded || interpreter == null) {
            return ruleRisk;
        }

        try {
            float[][] input = new float[][]{{ ruleRisk }};
            float[][] output = new float[1][1];

            interpreter.run(input, output);

            float mlRisk = output[0][0];

            // Safety clamp
            return Math.max(0f, Math.min(1f, mlRisk));

        } catch (Exception e) {
            Log.e(TAG, "ML inference failed, fallback to rule risk", e);
            return ruleRisk;
        }
    }

    // ================= MODEL LOADING =================
    private MappedByteBuffer loadModel(Context context) throws Exception {
        AssetFileDescriptor fileDescriptor =
                context.getAssets().openFd(MODEL_NAME);
        FileInputStream inputStream =
                new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();

        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();

        return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                startOffset,
                declaredLength
        );
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}
