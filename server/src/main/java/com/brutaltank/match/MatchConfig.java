package com.brutaltank.match;

import com.brutaltank.protocol.Payloads;

/** Resolved per-match configuration, per PLAN.md 2.3/5 (M2 defaults: 3 rounds, up to 8 players). */
public record MatchConfig(int maxRounds, int maxPlayers, int botCount, Difficulty botDifficulty) {

    // 4 -> 3 (explicit user request, 2026-08-25).
    public static final int DEFAULT_MAX_ROUNDS = 3;
    public static final int DEFAULT_MAX_PLAYERS = 8;
    public static final int DEFAULT_BOT_COUNT = 0;
    public static final Difficulty DEFAULT_BOT_DIFFICULTY = Difficulty.MIXED;

    public static MatchConfig defaultConfig() {
        return new MatchConfig(DEFAULT_MAX_ROUNDS, DEFAULT_MAX_PLAYERS, DEFAULT_BOT_COUNT, DEFAULT_BOT_DIFFICULTY);
    }

    /** Applies any client-supplied overrides from {@code CreateMatch.matchConfig} on top of the defaults. */
    public static MatchConfig fromDto(Payloads.MatchConfigDto dto) {
        if (dto == null) {
            return defaultConfig();
        }
        int rounds = dto.maxRounds != null && dto.maxRounds > 0 ? dto.maxRounds : DEFAULT_MAX_ROUNDS;
        int players = dto.maxPlayers != null && dto.maxPlayers > 0
                ? Math.min(dto.maxPlayers, DEFAULT_MAX_PLAYERS)
                : DEFAULT_MAX_PLAYERS;
        // Always leaves room for at least the creator (a match can't be
        // 100% bots -- there'd be nobody to click Ready).
        int bots = dto.botCount != null ? Math.max(0, Math.min(dto.botCount, players - 1)) : DEFAULT_BOT_COUNT;
        Difficulty difficulty = Difficulty.fromString(dto.botDifficulty);
        return new MatchConfig(rounds, players, bots, difficulty);
    }
}
