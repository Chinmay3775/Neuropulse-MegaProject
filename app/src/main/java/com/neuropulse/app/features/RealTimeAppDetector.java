// RealTimeAppDetector.java (ENHANCED)
package com.neuropulse.app.features;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.neuropulse.app.ml.RiskThresholds;

import java.util.ArrayList;
import java.util.Collections;
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

    // Stabilization state
    private String lastStablePackage = "unknown";
    private String candidatePackage = null;
    private long candidateSince = 0L;

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

        if (pkg == null || "unknown".equals(pkg)) {
            return new CurrentAppInfo(
                    "unknown",
                    "Unknown App",
                    RiskThresholds.CATEGORY_UTILITIES,
                    0.0f,
                    "No active app detected"
            );
        }

        AppRiskProfile profile = appRiskProfiles.getOrDefault(pkg, getDynamicRiskProfile(pkg));
        String displayName = getAppDisplayName(pkg);
        float risk = profile.baseRisk; // Live ML engine will override this anyway
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

        detected = getFromUsageStats();
        if (detected != null && !detected.equals("unknown")) {
            return stabilizePackage(detected);
        }

        detected = getFromUsageEvents();
        if (detected != null && !detected.equals("unknown")) {
            return stabilizePackage(detected);
        }

        detected = getFromActivityManager();
        if (detected != null && !detected.equals("unknown")) {
            return stabilizePackage(detected);
        }

        detected = getFromRunningProcesses();
        if (detected != null && !detected.equals("unknown")) {
            return stabilizePackage(detected);
        }

        return lastStablePackage;
    }

    // METHOD 1: UsageStats - Query recent usage
    private String getFromUsageStats() {
        if (usageStatsManager == null) return null;

        try {
            long now = System.currentTimeMillis();
            long startTime = now - TimeUnit.MINUTES.toMillis(1);

            List<UsageStats> statsList = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST, startTime, now);

            if (statsList == null || statsList.isEmpty()) return null;

            SortedMap<Long, UsageStats> sortedStats = new TreeMap<>();
            for (UsageStats stats : statsList) {
                if (stats.getLastTimeUsed() > 0) {
                    sortedStats.put(stats.getLastTimeUsed(), stats);
                }
            }

            if (!sortedStats.isEmpty()) {
                UsageStats mostRecent = sortedStats.get(sortedStats.lastKey());
                String pkg = mostRecent.getPackageName();
                if (!isIgnorablePackage(pkg)) return pkg;
            }
        } catch (Exception e) {
            Log.e(TAG, "UsageStats method failed", e);
        }
        return null;
    }

    // METHOD 2: UsageEvents
    private String getFromUsageEvents() {
        if (usageStatsManager == null) return null;
        try {
            long now = System.currentTimeMillis();
            long startTime = now - TimeUnit.SECONDS.toMillis(5);

            UsageEvents events = usageStatsManager.queryEvents(startTime, now);
            if (events == null) return null;

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
            if (lastApp != null) return lastApp;
        } catch (Exception e) {}
        return null;
    }

    // METHOD 3: ActivityManager
    private String getFromActivityManager() {
        if (activityManager == null) return null;
        try {
            List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                ActivityManager.RunningTaskInfo taskInfo = tasks.get(0);
                if (taskInfo.topActivity != null) {
                    String pkg = taskInfo.topActivity.getPackageName();
                    if (!isIgnorablePackage(pkg)) return pkg;
                }
            }
        } catch (Exception e) {}
        return null;
    }

    // METHOD 4: ActivityManager Processes
    private String getFromRunningProcesses() {
        if (activityManager == null) return null;
        try {
            List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
                    if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        String pkg = processInfo.processName;
                        if (!isIgnorablePackage(pkg)) return pkg;
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    // ========================= UTILS & STABILIZATION =========================

    private boolean isIgnorablePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        String ownPackage = context.getPackageName();
        return pkg.equals(ownPackage) ||
                pkg.equals("android") ||
                pkg.contains("systemui") ||
                pkg.contains("launcher") ||
                pkg.startsWith("com.google.android.permission");
    }

    private String stabilizePackage(String candidate) {
        if (candidate == null || candidate.equals("unknown")) {
            return lastStablePackage;
        }

        long now = System.currentTimeMillis();

        if (candidate.equals(lastStablePackage)) {
            // Unchanged
            candidatePackage = null;
            return lastStablePackage;
        }

        // It's a new app. Have we seen it as candidate yet?
        if (!candidate.equals(candidatePackage)) {
            // First time seeing this new app — start the clock
            candidatePackage = candidate;
            candidateSince = now;
            return lastStablePackage; // wait until stable
        }

        // We've seen it before. Has it been stable long enough? (800ms)
        if (now - candidateSince >= 800) {
            Log.i(TAG, "🔄 App stabilized: " + lastStablePackage + " → " + candidate);
            lastStablePackage = candidate;
            candidatePackage = null;
        }

        return lastStablePackage;
    }

    /**
     * Immediately updates the stable package, bypassing the stabilization delay.
     * Useful when another component (like AccessibilityService) has already confirmed the change.
     */
    public void forcePackageUpdate(String packageName) {
        if (packageName != null && !packageName.equals("unknown")) {
            this.lastStablePackage = packageName;
            this.candidatePackage = null;
            this.candidateSince = 0;
            Log.d(TAG, "🚀 Forcing stable package: " + packageName);
        }
    }

    private static final Map<String, String> FALLBACK_NAMES = new HashMap<>();
    static {
        FALLBACK_NAMES.put("com.instagram.android", "Instagram");
        FALLBACK_NAMES.put("com.zhiliaoapp.musically", "TikTok");
        FALLBACK_NAMES.put("com.facebook.katana", "Facebook");
        FALLBACK_NAMES.put("com.twitter.android", "X (Twitter)");
        FALLBACK_NAMES.put("com.snapchat.android", "Snapchat");
        FALLBACK_NAMES.put("com.google.android.youtube", "YouTube");
        FALLBACK_NAMES.put("com.netflix.mediaclient", "Netflix");
        FALLBACK_NAMES.put("com.reddit.frontpage", "Reddit");
        FALLBACK_NAMES.put("com.whatsapp", "WhatsApp");
        FALLBACK_NAMES.put("com.android.chrome", "Chrome");
        FALLBACK_NAMES.put("com.neuropulse.app", "Neuropulse");
    }

    private String getAppDisplayName(String packageName) {
        if (FALLBACK_NAMES.containsKey(packageName)) {
            return FALLBACK_NAMES.get(packageName);
        }
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            return packageManager.getApplicationLabel(info).toString();
        } catch (Exception e) {
            if (packageName != null && packageName.contains(".")) {
                String[] parts = packageName.split("\\.");
                String name = parts[parts.length - 1];
                return name.substring(0, 1).toUpperCase() + name.substring(1);
            }
            return packageName;
        }
    }

    private String generateRiskReason(String pkg, AppRiskProfile profile, float risk) {
        if (UsageIntelligence.isProductivePackage(pkg)) {
            return "Productive session detected";
        }
        return profile.primaryConcern;
    }

    // ========================= TOP APPS =========================

    public List<AppUsageStats> getTopAddictingAppsToday(int limit) {
        List<AppUsageStats> result = new ArrayList<>();
        if (usageStatsManager == null) return result;

        long now = System.currentTimeMillis();
        long startTime = now - TimeUnit.HOURS.toMillis(24); // Look back 24 hours

        try {
            Map<String, UsageStats> stats = usageStatsManager.queryAndAggregateUsageStats(startTime, now);
            for (Map.Entry<String, UsageStats> entry : stats.entrySet()) {
                String pkg = entry.getKey();
                UsageStats usage = entry.getValue();

                if (isIgnorableSystemPackage(pkg)) continue;

                long duration = usage.getTotalTimeInForeground();
                if (duration > 60000) { // Min 1 minute
                    AppRiskProfile profile = appRiskProfiles.getOrDefault(pkg, getDynamicRiskProfile(pkg));
                    
                    // Only include apps that are potentially addictive
                    if (profile.category == RiskThresholds.CATEGORY_SOCIAL || 
                        profile.category == RiskThresholds.CATEGORY_ENTERTAINMENT ||
                        profile.category == RiskThresholds.CATEGORY_GAMES ||
                        FALLBACK_NAMES.containsKey(pkg)) {
                        
                        String name = getAppDisplayName(pkg);
                        result.add(new AppUsageStats(pkg, name, duration, profile.baseRisk));
                    }
                }
            }

            // Sort descending by duration
            Collections.sort(result, (a, b) -> Long.compare(b.totalTimeMs, a.totalTimeMs));

            if (result.size() > limit) {
                return result.subList(0, limit);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get top apps", e);
        }
        return result;
    }

    private boolean isIgnorableSystemPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        return pkg.equals(context.getPackageName()) ||
               pkg.equals("android") ||
               pkg.contains("systemui") ||
               pkg.contains("launcher") ||
               pkg.startsWith("com.google.android.permission");
    }

    // ========================= RISK PROFILES =========================

    private Map<String, AppRiskProfile> initializeAppRiskProfiles() {
        Map<String, AppRiskProfile> profiles = new HashMap<>();

        // High Risk - Social
        profiles.put("com.instagram.android", new AppRiskProfile(RiskThresholds.CATEGORY_SOCIAL, 0.4f, "Algorithm-driven feed"));
        profiles.put("com.zhiliaoapp.musically", new AppRiskProfile(RiskThresholds.CATEGORY_SOCIAL, 0.4f, "Short-form video loops"));
        profiles.put("com.facebook.katana", new AppRiskProfile(RiskThresholds.CATEGORY_SOCIAL, 0.35f, "Infinite scrolling"));
        profiles.put("com.twitter.android", new AppRiskProfile(RiskThresholds.CATEGORY_SOCIAL, 0.3f, "Real-time doomscrolling"));
        profiles.put("com.snapchat.android", new AppRiskProfile(RiskThresholds.CATEGORY_SOCIAL, 0.3f, "Streak maintenance compulsion"));

        // Entertainment
        profiles.put("com.google.android.youtube", new AppRiskProfile(RiskThresholds.CATEGORY_ENTERTAINMENT, 0.3f, "Autoplay recommendation rabbit holes"));
        profiles.put("com.netflix.mediaclient", new AppRiskProfile(RiskThresholds.CATEGORY_ENTERTAINMENT, 0.25f, "Binge-watching tendency"));

        // Productive Apps (Intelligence Layer overrides risks, but base profiles help)
        profiles.put("com.google.android.apps.docs.editors.docs", new AppRiskProfile(RiskThresholds.CATEGORY_PRODUCTIVITY, 0.05f, "Focused document editing"));
        profiles.put("com.google.android.apps.docs", new AppRiskProfile(RiskThresholds.CATEGORY_PRODUCTIVITY, 0.05f, "File management"));
        profiles.put("com.slack", new AppRiskProfile(RiskThresholds.CATEGORY_PRODUCTIVITY, 0.1f, "Workplace communication"));
        profiles.put("notion.id", new AppRiskProfile(RiskThresholds.CATEGORY_PRODUCTIVITY, 0.05f, "Knowledge base work"));

        // Comms
        profiles.put("com.whatsapp", new AppRiskProfile(RiskThresholds.CATEGORY_COMMUNICATION, 0.15f, "Social obligations"));
        profiles.put("com.android.chrome", new AppRiskProfile(RiskThresholds.CATEGORY_UTILITIES, 0.15f, "General web browsing"));

        return profiles;
    }

    private AppRiskProfile getDynamicRiskProfile(String pkg) {
        int category = RiskThresholds.CATEGORY_UTILITIES;
        float baseRisk = 0.2f;
        String reason = "Generic application usage";

        // Query PackageManager for application category (Android O+)
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(pkg, 0);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                switch (info.category) {
                    case ApplicationInfo.CATEGORY_GAME:
                        category = RiskThresholds.CATEGORY_GAMES;
                        baseRisk = 0.3f;
                        reason = "Gaming session";
                        break;
                    case ApplicationInfo.CATEGORY_SOCIAL:
                        category = RiskThresholds.CATEGORY_SOCIAL;
                        baseRisk = 0.35f;
                        reason = "Social interaction";
                        break;
                    case ApplicationInfo.CATEGORY_VIDEO:
                        category = RiskThresholds.CATEGORY_ENTERTAINMENT;
                        break;
                    case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                        category = RiskThresholds.CATEGORY_PRODUCTIVITY;
                        baseRisk = 0.05f;
                        reason = "Productive usage";
                        break;
                }
            }
        } catch (Exception ignored) {}

        // Supplemental check via UsageIntelligence
        if (UsageIntelligence.isProductivePackage(pkg)) {
            category = RiskThresholds.CATEGORY_PRODUCTIVITY;
            baseRisk = 0.05f;
            reason = "Productive usage";
        }

        return new AppRiskProfile(category, baseRisk, reason);
    }

    // ========================= DATA CLASSES =========================

    public static class CurrentAppInfo {
        public final String packageName;
        public final String displayName;
        public final int category;
        public final float addictionRisk;
        public final String riskReason;

        public CurrentAppInfo(String packageName, String displayName, int category, float addictionRisk, String riskReason) {
            this.packageName = packageName;
            this.displayName = displayName;
            this.category = category;
            this.addictionRisk = addictionRisk;
            this.riskReason = riskReason;
        }
    }

    private static class AppRiskProfile {
        public final int category;
        public final float baseRisk;
        public final String primaryConcern;

        public AppRiskProfile(int category, float baseRisk, String primaryConcern) {
            this.category = category;
            this.baseRisk = baseRisk;
            this.primaryConcern = primaryConcern;
        }
    }

    public static class AppUsageStats {
        public final String packageName;
        public final String displayName;
        public final long totalTimeMs;
        public final float riskLevel;

        public AppUsageStats(String packageName, String displayName, long totalTimeMs, float riskLevel) {
            this.packageName = packageName;
            this.displayName = displayName;
            this.totalTimeMs = totalTimeMs;
            this.riskLevel = riskLevel;
        }
    }
}