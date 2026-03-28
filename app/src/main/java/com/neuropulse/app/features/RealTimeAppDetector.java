// RealTimeAppDetector.java (FIXED - Multiple Detection Methods)
package com.neuropulse.app.features;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public class RealTimeAppDetector {

    private static final String TAG = "RealTimeAppDetector";

    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final PackageManager packageManager;
    private final ActivityManager activityManager;
    private final Map<String, AppRiskProfile> appRiskProfiles;

    private String lastStablePackage = "unknown";
    private long lastStableSince = 0L;

    public RealTimeAppDetector(Context context) {
        this.context = context.getApplicationContext();
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.packageManager = context.getPackageManager();
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        this.appRiskProfiles = initializeAppRiskProfiles();
    }

    // ========================= PUBLIC API =========================

    public CurrentAppInfo getCurrentAppWithRisk() {
        String pkg = getCurrentForegroundApp();

        Log.d(TAG, "Final detected package: " + pkg);

        if (pkg == null || "unknown".equals(pkg)) {
            return new CurrentAppInfo(
                    "unknown",
                    "Unknown App",
                    5,
                    0.0f,
                    "No active app detected"
            );
        }

        AppRiskProfile profile = appRiskProfiles.getOrDefault(pkg, createDefaultRiskProfile());
        String displayName = getAppDisplayName(pkg);

        float risk = calculateRealTimeRisk(pkg, profile);
        String reason = generateRiskReason(pkg, profile, risk);

        return new CurrentAppInfo(
                pkg,
                displayName,
                profile.category,
                risk,
                reason
        );
    }

    // ========================= MULTI-METHOD APP DETECTION =========================

    private String getCurrentForegroundApp() {
        String detected = null;

        // METHOD 1: UsageStats with recent time window
        detected = getFromUsageStats();
        if (detected != null && !detected.equals("unknown")) {
            Log.i(TAG, "✅ Method 1 (UsageStats) detected: " + detected);
            return stabilizePackage(detected);
        }

        // METHOD 2: UsageEvents (different approach)
        detected = getFromUsageEvents();
        if (detected != null && !detected.equals("unknown")) {
            Log.i(TAG, "✅ Method 2 (UsageEvents) detected: " + detected);
            return stabilizePackage(detected);
        }

        // METHOD 3: ActivityManager RunningTasks
        detected = getFromActivityManager();
        if (detected != null && !detected.equals("unknown")) {
            Log.i(TAG, "✅ Method 3 (ActivityManager) detected: " + detected);
            return stabilizePackage(detected);
        }

        // METHOD 4: ActivityManager RunningAppProcesses
        detected = getFromRunningProcesses();
        if (detected != null && !detected.equals("unknown")) {
            Log.i(TAG, "✅ Method 4 (RunningProcesses) detected: " + detected);
            return stabilizePackage(detected);
        }

        Log.w(TAG, "⚠️ All detection methods failed, using last stable: " + lastStablePackage);
        return lastStablePackage;
    }

    // METHOD 1: UsageStats - Query recent usage
    private String getFromUsageStats() {
        if (usageStatsManager == null) {
            Log.w(TAG, "UsageStatsManager is null");
            return null;
        }

        try {
            long now = System.currentTimeMillis();
            long startTime = now - TimeUnit.MINUTES.toMillis(1); // Last 1 minute

            List<UsageStats> statsList = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    startTime,
                    now
            );

            if (statsList == null || statsList.isEmpty()) {
                Log.w(TAG, "UsageStats list is empty - check permission");
                return null;
            }

            // Sort by last time used
            SortedMap<Long, UsageStats> sortedStats = new TreeMap<>();
            for (UsageStats stats : statsList) {
                if (stats.getLastTimeUsed() > 0) {
                    sortedStats.put(stats.getLastTimeUsed(), stats);
                }
            }

            if (!sortedStats.isEmpty()) {
                UsageStats mostRecent = sortedStats.get(sortedStats.lastKey());
                String pkg = mostRecent.getPackageName();

                if (!isIgnorablePackage(pkg)) {
                    Log.d(TAG, "UsageStats: " + pkg + " (last used: " +
                            (now - mostRecent.getLastTimeUsed()) + "ms ago)");
                    return pkg;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "UsageStats method failed", e);
        }
        return null;
    }

    // METHOD 2: UsageEvents - Check recent events
    private String getFromUsageEvents() {
        if (usageStatsManager == null) return null;

        try {
            long now = System.currentTimeMillis();
            long startTime = now - TimeUnit.SECONDS.toMillis(5);

            UsageEvents events = usageStatsManager.queryEvents(startTime, now);
            if (events == null) {
                Log.w(TAG, "UsageEvents is null");
                return null;
            }

            String lastApp = null;
            long lastTime = 0;

            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);

                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    String pkg = event.getPackageName();

                    if (!isIgnorablePackage(pkg) && event.getTimeStamp() > lastTime) {
                        lastTime = event.getTimeStamp();
                        lastApp = pkg;
                    }
                }
            }

            if (lastApp != null) {
                Log.d(TAG, "UsageEvents: " + lastApp);
                return lastApp;
            }

        } catch (Exception e) {
            Log.e(TAG, "UsageEvents method failed", e);
        }
        return null;
    }

    // METHOD 3: ActivityManager RunningTasks
    private String getFromActivityManager() {
        if (activityManager == null) return null;

        try {
            List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);

            if (tasks != null && !tasks.isEmpty()) {
                ActivityManager.RunningTaskInfo taskInfo = tasks.get(0);

                if (taskInfo.topActivity != null) {
                    String pkg = taskInfo.topActivity.getPackageName();

                    if (!isIgnorablePackage(pkg)) {
                        Log.d(TAG, "ActivityManager: " + pkg);
                        return pkg;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "ActivityManager method failed", e);
        }
        return null;
    }

    // METHOD 4: Running App Processes
    private String getFromRunningProcesses() {
        if (activityManager == null) return null;

        try {
            List<ActivityManager.RunningAppProcessInfo> processes =
                    activityManager.getRunningAppProcesses();

            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
                    if (processInfo.importance ==
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {

                        String pkg = processInfo.processName;
                        if (!isIgnorablePackage(pkg)) {
                            Log.d(TAG, "RunningProcesses: " + pkg);
                            return pkg;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "RunningProcesses method failed", e);
        }
        return null;
    }

    // Package filtering
    private boolean isIgnorablePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;

        String ownPackage = context.getPackageName();

        return pkg.equals(ownPackage) ||
                pkg.equals("android") ||
                pkg.equals("com.android.systemui") ||
                pkg.equals("com.android.launcher3") ||
                pkg.equals("com.google.android.apps.nexuslauncher") ||
                pkg.contains("launcher") ||
                pkg.startsWith("com.android.") ||
                pkg.startsWith("android.") ||
                pkg.startsWith("com.google.android.permissioncontroller") ||
                pkg.startsWith("com.google.android.gms");
    }

    // Stabilization
    private String stabilizePackage(String candidate) {
        if (candidate == null || candidate.equals("unknown")) {
            return lastStablePackage;
        }

        long now = System.currentTimeMillis();

        if (!candidate.equals(lastStablePackage)) {
            // Require 1 second of stability
            if (now - lastStableSince < 1000) {
                return lastStablePackage;
            } else {
                Log.i(TAG, "🔄 App changed: " + lastStablePackage + " → " + candidate);
                lastStablePackage = candidate;
                lastStableSince = now;
            }
        } else {
            lastStableSince = now;
        }

        return lastStablePackage;
    }

    // ========================= RISK CALCULATION =========================

    private float calculateRealTimeRisk(String packageName, AppRiskProfile profile) {
        float baseRisk = profile.baseRisk;

        try {
            float timeOfDayRisk = getTimeOfDayRisk();
            float continuousRisk = getContinuousUsageRisk(profile);

            float risk = baseRisk + (timeOfDayRisk * 0.2f) + (continuousRisk * 0.3f);

            return Math.max(0f, Math.min(1f, risk));
        } catch (Exception e) {
            return baseRisk;
        }
    }

    private float getTimeOfDayRisk() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        if (hour >= 23 || hour <= 5) return 0.3f;
        if (hour >= 20) return 0.15f;
        return 0.05f;
    }

    private float getContinuousUsageRisk(AppRiskProfile profile) {
        if (profile.category == 0 || profile.category == 3 || profile.category == 2) {
            return 0.25f;
        }
        return 0.1f;
    }

    private String getAppDisplayName(String packageName) {
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(info).toString();
        } catch (Exception e) {
            if (packageName != null && packageName.contains(".")) {
                String[] parts = packageName.split("\\.");
                return parts[parts.length - 1];
            }
            return packageName;
        }
    }

    private String generateRiskReason(String pkg, AppRiskProfile profile, float risk) {
        if (risk >= 0.7f) {
            return "High addiction risk - " + profile.riskFactors[0];
        } else if (risk >= 0.4f) {
            return "Moderate risk - " + profile.primaryConcern;
        } else {
            return "Low risk - healthy usage pattern";
        }
    }

    // ========================= RISK PROFILES =========================

    private Map<String, AppRiskProfile> initializeAppRiskProfiles() {
        Map<String, AppRiskProfile> profiles = new HashMap<>();

        profiles.put("com.instagram.android", new AppRiskProfile(
                0, 0.8f, "Infinite scroll addiction",
                new String[]{"Infinite scroll mechanism", "Dopamine-driven engagement"}));

        profiles.put("com.zhiliaoapp.musically", new AppRiskProfile(
                0, 0.9f, "Short-form video addiction",
                new String[]{"Endless video stream", "Algorithmic feed"}));

        profiles.put("com.facebook.katana", new AppRiskProfile(
                0, 0.7f, "Social validation seeking",
                new String[]{"News feed algorithm", "Social interactions"}));

        profiles.put("com.snapchat.android", new AppRiskProfile(
                0, 0.7f, "Streak maintenance compulsion",
                new String[]{"Streak pressure", "FOMO triggers"}));

        profiles.put("com.twitter.android", new AppRiskProfile(
                0, 0.6f, "Information overload",
                new String[]{"Real-time updates", "Infinite timeline"}));

        profiles.put("com.google.android.youtube", new AppRiskProfile(
                2, 0.7f, "Binge-watching tendency",
                new String[]{"Autoplay feature", "Recommendation algorithm"}));

        profiles.put("com.netflix.mediaclient", new AppRiskProfile(
                2, 0.6f, "Episode binge-watching",
                new String[]{"Autoplay next episode", "Binge-friendly interface"}));

        profiles.put("com.android.chrome", new AppRiskProfile(
                1, 0.3f, "Web browsing",
                new String[]{"General web usage"}));

        profiles.put("com.whatsapp", new AppRiskProfile(
                6, 0.3f, "Communication necessity",
                new String[]{"Social obligation"}));

        return profiles;
    }

    private AppRiskProfile createDefaultRiskProfile() {
        return new AppRiskProfile(
                5, 0.2f, "Unknown app",
                new String[]{"Standard usage pattern"}
        );
    }

    // ========================= DATA CLASSES =========================

    public static class CurrentAppInfo {
        public final String packageName;
        public final String displayName;
        public final int category;
        public final float addictionRisk;
        public final String riskReason;

        public CurrentAppInfo(String packageName, String displayName,
                              int category, float addictionRisk, String riskReason) {
            this.packageName = packageName;
            this.displayName = displayName;
            this.category = category;
            this.addictionRisk = addictionRisk;
            this.riskReason = riskReason;
        }

        public String getRiskLevel() {
            if (addictionRisk >= 0.7f) return "HIGH";
            else if (addictionRisk >= 0.4f) return "MEDIUM";
            else return "LOW";
        }
    }

    private static class AppRiskProfile {
        public final int category;
        public final float baseRisk;
        public final String primaryConcern;
        public final String[] riskFactors;

        public AppRiskProfile(int category, float baseRisk,
                              String primaryConcern, String[] riskFactors) {
            this.category = category;
            this.baseRisk = baseRisk;
            this.primaryConcern = primaryConcern;
            this.riskFactors = riskFactors;
        }
    }
}