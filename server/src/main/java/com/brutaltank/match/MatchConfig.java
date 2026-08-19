package com.brutaltank.match;

import com.brutaltank.protocol.Payloads;

/** Resolved per-match configuration, per PLAN.md 2.3/5 (M2 defaults: 4 rounds, up to 8 players). */
public record MatchConfig(int maxRounds, int maxPlayers) {

    public static final int DEFAULT_MAX_ROUNDS = 4;
    public static final int DEFAULT_MAX_PLAYERS = 8;

    public static MatchConfig defaultConfig() {
        return new MatchConfig(DEFAULT_MAX_ROUNDS, DEFAULT_MAX_PLAYERS);
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
        return new MatchConfig(rounds, players);
    }
}
