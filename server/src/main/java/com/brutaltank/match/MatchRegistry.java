package com.brutaltank.match;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * matchId -> {@link Match}. Per PLAN.md 2.2, this is the only structure
 * touched from multiple threads outside of a single match's own state (which
 * is itself guarded by that match's monitor) — a plain {@link ConcurrentHashMap}
 * is sufficient since entries are never mutated in place, only inserted/removed.
 */
public final class MatchRegistry {

    private final ConcurrentHashMap<String, Match> matches = new ConcurrentHashMap<>();

    public void put(Match match) {
        matches.put(match.matchId, match);
    }

    public Match get(String matchId) {
        return matchId == null ? null : matches.get(matchId);
    }

    public void remove(String matchId) {
        matches.remove(matchId);
    }

    public Collection<Match> all() {
        return matches.values();
    }
}
