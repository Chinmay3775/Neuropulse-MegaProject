package com.neuropulse.app.utils;

import java.util.Random;

/**
 * Provides randomized intervention content for blocking overlays and alerts.
 * Content rotates to avoid repetition and keep users engaged.
 */
public class InterventionContentProvider {

    private static final Random random = new Random();
    private static int lastBreathingIndex = -1;
    private static int lastQuoteIndex = -1;
    private static int lastSuggestionIndex = -1;
    private static int lastChallengeIndex = -1;

    // ================= BREATHING EXERCISES =================
    private static final String[][] BREATHING_EXERCISES = {
            {"Box Breathing", "Inhale 4s → Hold 4s → Exhale 4s → Hold 4s", "Repeat 4 cycles for calm focus"},
            {"4-7-8 Technique", "Inhale 4s → Hold 7s → Exhale 8s", "Promotes deep relaxation"},
            {"Deep Belly Breathing", "Breathe into your belly for 5s → Exhale slowly for 7s", "Reduces anxiety and stress"},
            {"Alternate Nostril", "Close right nostril, inhale left → Switch → Exhale right", "Balances your nervous system"},
            {"Energizing Breath", "3 quick inhales through nose → Long exhale through mouth", "Boosts alertness naturally"}
    };

    // ================= MOTIVATIONAL QUOTES =================
    private static final String[] QUOTES = {
            "\"The secret of getting ahead is getting started.\" — Mark Twain",
            "\"Your time is limited, don't waste it living someone else's life.\" — Steve Jobs",
            "\"Almost everything will work again if you unplug it for a few minutes… including you.\" — Anne Lamott",
            "\"The best way to predict the future is to create it.\" — Peter Drucker",
            "\"Don't compare your chapter 1 to someone else's chapter 20.\" — Unknown",
            "\"You don't have to be great to start, but you have to start to be great.\" — Zig Ziglar",
            "\"Technology is a useful servant but a dangerous master.\" — Christian Lous Lange",
            "\"The real voyage of discovery consists not in seeking new landscapes, but in having new eyes.\" — Marcel Proust",
            "\"Disconnect to reconnect.\" — Unknown",
            "\"Be where your feet are.\" — Scott O'Neil",
            "\"The mind is everything. What you think, you become.\" — Buddha",
            "\"Happiness is not something ready-made. It comes from your own actions.\" — Dalai Lama"
    };

    // ================= PRODUCTIVITY SUGGESTIONS =================
    private static final String[] SUGGESTIONS = {
            "📚 Read a chapter of a book you've been meaning to finish",
            "🚶 Take a 5-minute walk outside — fresh air resets your brain",
            "💧 Drink a full glass of water — hydration improves focus",
            "📝 Write down 3 things you're grateful for today",
            "🧹 Tidy up your desk or workspace for 5 minutes",
            "📞 Call a friend or family member you haven't spoken to in a while",
            "🎵 Listen to your favorite song with your eyes closed",
            "🌱 Water a plant or step into nature for a moment",
            "🧘 Do a 2-minute body scan meditation",
            "✏️ Sketch something — anything — for 5 minutes",
            "📖 Learn one new word or fun fact",
            "🎯 Write down your top 3 priorities for today"
    };

    // ================= MICRO CHALLENGES =================
    private static final String[] CHALLENGES = {
            "💪 Do 10 push-ups right now!",
            "🦵 Hold a wall sit for 30 seconds",
            "🤸 Do 15 jumping jacks",
            "🧘 Hold a plank for 30 seconds",
            "👀 Look at something 20 feet away for 20 seconds (20-20 rule)",
            "🙆 Do 10 neck rolls — 5 each direction",
            "✋ Stretch your fingers and wrists for 30 seconds",
            "🦶 Stand up and touch your toes 5 times",
            "😤 Take 5 deep breaths — in through nose, out through mouth",
            "🏃 Walk to the farthest room in your house and back"
    };

    // ================= PUBLIC API =================

    public static String[] getBreathingExercise() {
        lastBreathingIndex = getNextIndex(lastBreathingIndex, BREATHING_EXERCISES.length);
        return BREATHING_EXERCISES[lastBreathingIndex];
    }

    public static String getMotivationalQuote() {
        lastQuoteIndex = getNextIndex(lastQuoteIndex, QUOTES.length);
        return QUOTES[lastQuoteIndex];
    }

    public static String getProductivitySuggestion() {
        lastSuggestionIndex = getNextIndex(lastSuggestionIndex, SUGGESTIONS.length);
        return SUGGESTIONS[lastSuggestionIndex];
    }

    public static String getMicroChallenge() {
        lastChallengeIndex = getNextIndex(lastChallengeIndex, CHALLENGES.length);
        return CHALLENGES[lastChallengeIndex];
    }

    /**
     * Returns a mixed intervention bundle suitable for a blocking overlay.
     * [0] = breathing exercise title, [1] = breathing instructions,
     * [2] = motivational quote, [3] = suggestion
     */
    public static String[] getInterventionBundle() {
        String[] breathing = getBreathingExercise();
        return new String[]{
                breathing[0],
                breathing[1] + "\n" + breathing[2],
                getMotivationalQuote(),
                getProductivitySuggestion()
        };
    }

    // ================= HELPERS =================
    private static int getNextIndex(int lastIndex, int size) {
        int next;
        do {
            next = random.nextInt(size);
        } while (next == lastIndex && size > 1);
        return next;
    }
}
