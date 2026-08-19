package com.brutaltank.lobby;

import com.brutaltank.match.Match;
import com.brutaltank.match.MatchRegistry;
import com.brutaltank.net.MessageSink;
import com.brutaltank.net.PlayerSession;
import com.brutaltank.protocol.Payloads;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the lobby flow (create/join/capacity/ready -> start) through the
 * real {@link LobbyManager} + {@link MatchRegistry} routing layer, per the
 * M2 task spec, using an in-memory {@link MessageSink} double instead of a
 * live socket.
 */
class LobbyManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduledExecutorService scheduler;
    private LobbyManager lobbyManager;
    private MatchRegistry registry;

    /** In-memory MessageSink recording sent frames, paired with its own PlayerSession. */
    private static final class Client {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final MessageSink sink = new MessageSink() {
            @Override
            public void send(String json) {
                messages.add(json);
            }

            @Override
            public boolean isOpen() {
                return true;
            }
        };
        final PlayerSession session = new PlayerSession(sink);

        JsonNode last(String type) {
            JsonNode found = null;
            for (String m : messages) {
                try {
                    JsonNode node = MAPPER.readTree(m);
                    if (type.equals(node.path("type").asText())) {
                        found = node.path("payload");
                    }
                } catch (Exception ignored) {
                }
            }
            return found;
        }

        long countOf(String type) {
            long c = 0;
            for (String m : messages) {
                try {
                    JsonNode node = MAPPER.readTree(m);
                    if (type.equals(node.path("type").asText())) {
                        c++;
                    }
                } catch (Exception ignored) {
                }
            }
            return c;
        }
    }

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        registry = new MatchRegistry();
        lobbyManager = new LobbyManager(registry, MAPPER, scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static Payloads.CreateMatch createMatch(String name) {
        Payloads.CreateMatch cm = new Payloads.CreateMatch();
        cm.displayName = name;
        return cm;
    }

    private static Payloads.JoinMatch joinMatch(String matchId, String name) {
        Payloads.JoinMatch jm = new Payloads.JoinMatch();
        jm.matchId = matchId;
        jm.displayName = name;
        return jm;
    }

    @Test
    @Timeout(5)
    void createMatchRepliesWithMatchCreatedAndRegistersMatch() {
        Client host = new Client();
        lobbyManager.handleCreateMatch(host.session, host.sink, "r1", createMatch("Dan"));

        JsonNode payload = host.last("MatchCreated");
        assertNotNull(payload);
        String matchId = payload.get("matchId").asText();
        assertNotNull(payload.get("playerToken").asText());
        assertEquals(host.session.playerId, payload.get("playerId").asText());
        assertNotNull(registry.get(matchId));

        // LobbyUpdate broadcast to the (sole) member too.
        JsonNode lobby = host.last("LobbyUpdate");
        assertNotNull(lobby);
        assertEquals(1, lobby.get("players").size());
        assertTrue(lobby.get("players").get(0).get("isHost").asBoolean());
    }

    @Test
    @Timeout(5)
    void joinMatchRepliesWithMatchJoinedAndBroadcastsLobbyUpdate() {
        Client host = new Client();
        lobbyManager.handleCreateMatch(host.session, host.sink, "r1", createMatch("Dan"));
        String matchId = host.session.currentMatchId;

        Client joiner = new Client();
        lobbyManager.handleJoinMatch(joiner.session, joiner.sink, "r2", joinMatch(matchId, "Riley"));

        JsonNode joinedPayload = joiner.last("MatchJoined");
        assertNotNull(joinedPayload);
        assertEquals(matchId, joinedPayload.get("matchId").asText());
        assertNotNull(joinedPayload.get("playerToken").asText());

        // Both host and joiner should see a LobbyUpdate reflecting 2 players.
        JsonNode hostLobby = host.last("LobbyUpdate");
        JsonNode joinerLobby = joiner.last("LobbyUpdate");
        assertEquals(2, hostLobby.get("players").size());
        assertEquals(2, joinerLobby.get("players").size());
    }

    @Test
    @Timeout(5)
    void joinNonexistentMatchGetsErrorMsg() {
        Client joiner = new Client();
        lobbyManager.handleJoinMatch(joiner.session, joiner.sink, "r1", joinMatch("m-does-not-exist", "Riley"));

        JsonNode error = joiner.last("ErrorMsg");
        assertNotNull(error);
        assertEquals("MATCH_NOT_FOUND", error.get("code").asText());
    }

    @Test
    @Timeout(5)
    void joinFullMatchIsRejected() {
        Client host = new Client();
        lobbyManager.handleCreateMatch(host.session, host.sink, "r1", createMatch("P0"));
        String matchId = host.session.currentMatchId;

        // Fill to capacity (max 8): host is #1, add 7 more to reach 8.
        for (int i = 1; i < 8; i++) {
            Client c = new Client();
            lobbyManager.handleJoinMatch(c.session, c.sink, "r" + i, joinMatch(matchId, "P" + i));
            assertNotNull(c.last("MatchJoined"), "player " + i + " should have joined");
        }

        Client overflow = new Client();
        lobbyManager.handleJoinMatch(overflow.session, overflow.sink, "r-of", joinMatch(matchId, "Overflow"));
        JsonNode error = overflow.last("ErrorMsg");
        assertNotNull(error);
        assertEquals("MATCH_FULL", error.get("code").asText());
    }

    @Test
    @Timeout(5)
    void allReadyWithTwoPlusPlayersStartsTheMatch() {
        Client host = new Client();
        lobbyManager.handleCreateMatch(host.session, host.sink, "r1", createMatch("Dan"));
        String matchId = host.session.currentMatchId;

        Client joiner = new Client();
        lobbyManager.handleJoinMatch(joiner.session, joiner.sink, "r2", joinMatch(matchId, "Riley"));

        lobbyManager.handleSetReady(host.session, "r3", host.sink, true);
        // Only one of two ready: match must not have started yet.
        assertEquals(0, host.countOf("MatchStarted"));

        lobbyManager.handleSetReady(joiner.session, "r4", joiner.sink, true);

        assertEquals(1, host.countOf("MatchStarted"));
        assertEquals(1, joiner.countOf("MatchStarted"));
        assertEquals(1, host.countOf("MatchStateSync"));
        assertEquals(1, host.countOf("TurnStarted"));

        Match match = registry.get(matchId);
        assertEquals(Match.Status.IN_PROGRESS, match.status());
    }

    @Test
    @Timeout(5)
    void readyWithOnlyOnePlayerDoesNotStartMatch() {
        Client host = new Client();
        lobbyManager.handleCreateMatch(host.session, host.sink, "r1", createMatch("Solo"));
        lobbyManager.handleSetReady(host.session, "r2", host.sink, true);

        assertEquals(0, host.countOf("MatchStarted"));
        assertEquals(Match.Status.WAITING, registry.get(host.session.currentMatchId).status());
    }

    @Test
    @Timeout(5)
    void leaveMatchDuringLobbyRemovesPlayerAndBroadcastsUpdate() {
        Client host = new Client();
        lobbyManager.handleCreateMatch(host.session, host.sink, "r1", createMatch("Dan"));
        String matchId = host.session.currentMatchId;

        Client joiner = new Client();
        lobbyManager.handleJoinMatch(joiner.session, joiner.sink, "r2", joinMatch(matchId, "Riley"));

        lobbyManager.handleLeaveMatch(joiner.session);

        JsonNode hostLobby = host.last("LobbyUpdate");
        assertEquals(1, hostLobby.get("players").size());
        assertFalse(host.session.playerId == null);
    }
}
