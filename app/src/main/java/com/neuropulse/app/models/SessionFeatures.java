package com.neuropulse.app.models;

/**
 * Lightweight session feature holder
 * NO database
 * NO Android context
 * Pure ML input container
 */
public class SessionFeatures {

    public int appCategory;
    public long sessionDurationMs;
    public float scrollsPerMinute;
    public int consecutiveSameAppMin;
    public float timeOfDay;
    public int bingeFlag;

    public SessionFeatures(
            int appCategory,
            long sessionDurationMs,
            float scrollsPerMinute,
            int consecutiveSameAppMin,
            float timeOfDay,
            int bingeFlag
    ) {
        this.appCategory = appCategory;
        this.sessionDurationMs = sessionDurationMs;
        this.scrollsPerMinute = scrollsPerMinute;
        this.consecutiveSameAppMin = consecutiveSameAppMin;
        this.timeOfDay = timeOfDay;
        this.bingeFlag = bingeFlag;
    }
}
