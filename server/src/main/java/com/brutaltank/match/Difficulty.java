package com.brutaltank.match;

/**
 * Bot skill tier, chosen (per match, applied to every bot) on {@code
 * CreateMatch.matchConfig.botDifficulty}. {@link #MIXED} isn't a tier of its
 * own — {@link BotProfile#forDifficulty} resolves it to a randomly-picked
 * concrete tier per bot, so a "Mixed" match has genuinely varied bots rather
 * than one uniform blended skill level.
 */
public enum Difficulty {
    EASY, MEDIUM, HARD, MIXED;

    /** Parses a client-supplied string, defaulting to MIXED for null/unknown values. */
    public static Difficulty fromString(String s) {
        if (s == null) {
            return MIXED;
        }
        try {
            return Difficulty.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MIXED;
        }
    }
}
