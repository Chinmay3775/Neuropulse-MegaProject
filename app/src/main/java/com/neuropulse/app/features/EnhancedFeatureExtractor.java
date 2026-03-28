package com.neuropulse.app.features;

import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;

import com.neuropulse.app.models.SessionFeatures;

import java.util.Calendar;

/**
 * Buildathon-safe feature extractor
 * Converts live app usage into ML-ready features
 */
public class EnhancedFeatureExtractor {

    private static final String TAG = "FeatureExtractor";

    private final Context context;
    private final UsageStatsManager usageStatsManager;

    public EnhancedFeatureExtractor(Context context) {
        this.context = context.getApplicationContext();
        this.usageStatsManager =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    // ================= FEATURE EXTRACTION =================
    public SessionFeatures extract(
            int appCategory,
            long sessionStartTime,
            long currentTime
    ) {
        long durationMs = Math.max(0, currentTime - sessionStartTime);

        // Duration-aware interaction estimate
        float scrollsPerMinute = estimateScrollRate(appCategory, durationMs);
        int consecutiveSameAppMin = (int) (durationMs / 60000L);
        int bingeFlag = durationMs > (2 * 60 * 60 * 1000L) ? 1 : 0;

        float timeOfDay = calculateTimeOfDay(currentTime);

        return new SessionFeatures(
                appCategory,
                durationMs,
                scrollsPerMinute,
                consecutiveSameAppMin,
                timeOfDay,
                bingeFlag
        );
    }

    // ================= HELPERS =================

    private float estimateScrollRate(int category, long durationMs) {
        float base;
        switch (category) {
            case 0: base = 18f; break;   // Social
            case 2: base = 15f; break;   // Video
            case 3: base = 12f; break;   // Games
            default: base = 6f;
        }

        // Scale with duration (visible risk growth)
        float factor = Math.min(
                2.0f,
                durationMs / (8f * 60f * 1000f)
        );

        return base * factor;
    }

    private float calculateTimeOfDay(long timeMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timeMs);
        return cal.get(Calendar.HOUR_OF_DAY) / 24f;
    }

    // ================= APP LABEL =================
    public String getAppLabel(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(
                    pm.getApplicationInfo(packageName, 0)
            ).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
}
