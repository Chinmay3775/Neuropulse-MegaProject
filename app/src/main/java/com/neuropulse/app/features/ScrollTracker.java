package com.neuropulse.app.features;

import android.util.Log;

import java.util.LinkedList;

/**
 * Thread-safe, lock-free scroll event tracker.
 *
 * The AccessibilityService writes scroll events here via {@link #recordScrollEvent()}.
 * The FeatureExtractor reads aggregated metrics via {@link #getScrollsPerMinute()},
 * {@link #getScrollCadenceVariance()}, and {@link #getRapidBurstCount()}.
 *
 * Maintains a sliding window of the last 120 seconds of scroll timestamps
 * to compute authentic interaction intensity instead of synthetic estimates.
 *
 * DESIGN: This is a process-global singleton because both the AccessibilityService
 * and the monitoring loop share the same process.
 */
public final class ScrollTracker {

    private static final String TAG = "ScrollTracker";

    // Singleton
    private static final ScrollTracker INSTANCE = new ScrollTracker();

    /** Sliding window duration in milliseconds (2 minutes). */
    private static final long WINDOW_MS = 120_000L;

    /** Rapid burst detection: N events within BURST_WINDOW_MS. */
    private static final int BURST_THRESHOLD = 6;
    private static final long BURST_WINDOW_MS = 5_000L;

    /** Circular buffer of scroll event timestamps (newest at tail). */
    private final LinkedList<Long> scrollTimestamps = new LinkedList<>();

    /** Total lifetime scroll count (for diagnostics). */
    private volatile long totalScrollCount = 0;

    private ScrollTracker() {}

    public static ScrollTracker getInstance() {
        return INSTANCE;
    }

    // ========================= WRITE (from AccessibilityService) =========================

    /**
     * Records a single scroll event. Called from the Accessibility thread.
     */
    public synchronized void recordScrollEvent() {
        long now = System.currentTimeMillis();
        scrollTimestamps.addLast(now);
        totalScrollCount++;
        pruneOldEvents(now);
    }

    /**
     * Called when the user switches to a different app.
     * Clears the scroll buffer so the new app starts with a clean slate.
     */
    public synchronized void onAppTransition() {
        scrollTimestamps.clear();
    }

    // ========================= READ (from FeatureExtractor) =========================

    /**
     * Returns the number of scroll events per minute over the current window.
     */
    public synchronized float getScrollsPerMinute() {
        long now = System.currentTimeMillis();
        pruneOldEvents(now);

        int count = scrollTimestamps.size();
        if (count == 0) return 0f;

        // Effective window = time between oldest event and now
        long oldest = scrollTimestamps.getFirst();
        long windowMs = Math.max(now - oldest, 1000L); // at least 1 second
        return (count * 60_000f) / windowMs;
    }

    /**
     * Returns the variance of inter-scroll intervals (milliseconds²).
     *
     * Low variance → steady reading (educational content).
     * High variance → erratic flicking (doomscrolling / short-form video).
     *
     * Returns 0 if fewer than 3 scroll events are available.
     */
    public synchronized float getScrollCadenceVariance() {
        long now = System.currentTimeMillis();
        pruneOldEvents(now);

        int n = scrollTimestamps.size();
        if (n < 3) return 0f;

        // Compute inter-event intervals
        float[] gaps = new float[n - 1];
        Long[] arr = scrollTimestamps.toArray(new Long[0]);
        float mean = 0f;
        for (int i = 1; i < n; i++) {
            gaps[i - 1] = (float) (arr[i] - arr[i - 1]);
            mean += gaps[i - 1];
        }
        mean /= gaps.length;

        // Variance
        float variance = 0f;
        for (float g : gaps) {
            float diff = g - mean;
            variance += diff * diff;
        }
        variance /= gaps.length;

        return variance;
    }

    /**
     * Counts the number of "rapid scroll bursts" in the current window.
     * A burst = BURST_THRESHOLD or more scroll events within BURST_WINDOW_MS.
     * High burst count is a strong doomscrolling signal.
     */
    public synchronized int getRapidBurstCount() {
        long now = System.currentTimeMillis();
        pruneOldEvents(now);

        int n = scrollTimestamps.size();
        if (n < BURST_THRESHOLD) return 0;

        Long[] arr = scrollTimestamps.toArray(new Long[0]);
        int burstCount = 0;

        for (int i = 0; i <= n - BURST_THRESHOLD; i++) {
            long windowEnd = arr[i] + BURST_WINDOW_MS;
            // Count how many events fall within [arr[i], arr[i] + BURST_WINDOW_MS]
            int eventsInWindow = 0;
            for (int j = i; j < n && arr[j] <= windowEnd; j++) {
                eventsInWindow++;
            }
            if (eventsInWindow >= BURST_THRESHOLD) {
                burstCount++;
                // Skip ahead so we don't double-count overlapping bursts
                i += eventsInWindow - 1;
            }
        }
        return burstCount;
    }

    /**
     * Returns the total number of scroll events recorded in the current sliding window.
     */
    public synchronized int getWindowEventCount() {
        long now = System.currentTimeMillis();
        pruneOldEvents(now);
        return scrollTimestamps.size();
    }

    /**
     * Returns total lifetime scroll events since the service started.
     */
    public long getTotalScrollCount() {
        return totalScrollCount;
    }

    // ========================= INTERNAL =========================

    private void pruneOldEvents(long now) {
        long cutoff = now - WINDOW_MS;
        while (!scrollTimestamps.isEmpty() && scrollTimestamps.getFirst() < cutoff) {
            scrollTimestamps.removeFirst();
        }
    }
}
