package dev.vantage.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.event.Events;
import org.junit.jupiter.api.Test;

/** The one place a command reaches the server: the gap, the queue, the slash, and no world. */
class ServerCommandsTest {
    private static final Events.Owner OWNER = new Events.Owner() {
        @Override public String ownerName() { return "Test module"; }
        @Override public int ownerRank() { return 1; }
    };

    private long now = 10_000;
    private boolean joined = true;
    private final List<String> wire = new ArrayList<>();
    private final ServerCommands gate = new ServerCommands(() -> now, wire::add, () -> joined);

    @Test
    void theFirstCommandGoesOutAtOnce() {
        assertTrue(gate.sendNow(OWNER, "rtp east"));
        assertEquals(List.of("rtp east"), wire);
        assertEquals(0, gate.waiting());
    }

    @Test
    void aLeadingSlashIsTakenOff() {
        gate.sendNow(OWNER, "/tpa Bob");
        now += 1000;
        gate.sendNow(OWNER, "  //home  ");
        assertEquals(List.of("tpa Bob", "home"), wire);
    }

    @Test
    void nothingIsSentWithoutAWorldOrWithoutText() {
        joined = false;
        assertFalse(gate.sendNow(OWNER, "rtp"));
        joined = true;
        assertFalse(gate.sendNow(OWNER, "/"));
        assertFalse(gate.sendNow(OWNER, "   "));
        assertFalse(gate.sendNow(OWNER, null));
        assertTrue(wire.isEmpty());
    }

    @Test
    void aSecondCommandWithinTheGapWaitsInOrder() {
        gate.sendNow(OWNER, "one");
        now += 100;
        gate.sendNow(OWNER, "two");
        gate.sendNow(OWNER, "three");
        assertEquals(List.of("one"), wire);
        assertEquals(2, gate.waiting());
        now += 149;
        gate.drain();
        assertEquals(List.of("one"), wire, "249 ms since the first: still too soon");
        now += 1;
        gate.drain();
        assertEquals(List.of("one", "two"), wire);
        gate.drain();
        assertEquals(List.of("one", "two"), wire, "one per gap, even when asked twice at the same moment");
        now += 250;
        gate.drain();
        assertEquals(List.of("one", "two", "three"), wire);
        assertEquals(0, gate.waiting());
    }

    @Test
    void aNewCommandDoesNotJumpTheQueue() {
        gate.sendNow(OWNER, "one");
        gate.sendNow(OWNER, "two");
        now += 1000;
        gate.sendNow(OWNER, "three");
        assertEquals(List.of("one"), wire, "two is still waiting, so three waits behind it");
        gate.drain();
        now += 250;
        gate.drain();
        assertEquals(List.of("one", "two", "three"), wire);
    }

    @Test
    void theGapBetweenAnyTwoSentCommandsIsAtLeast250Milliseconds() {
        List<Long> times = new ArrayList<>();
        ServerCommands g = new ServerCommands(() -> now, t -> times.add(now), () -> true);
        for (int i = 0; i < 20; i++) g.sendNow(OWNER, "c" + i);
        for (int step = 0; step < 2000 && g.waiting() > 0; step++) {
            now += 50; // one tick
            g.drain();
        }
        assertEquals(20, times.size());
        for (int i = 1; i < times.size(); i++) assertTrue(times.get(i) - times.get(i - 1) >= ServerCommands.MIN_GAP_MS);
    }

    @Test
    void leavingTheWorldDropsTheQueue() {
        gate.sendNow(OWNER, "one");
        gate.sendNow(OWNER, "two");
        gate.drop();
        now += 1000;
        gate.drain();
        assertEquals(List.of("one"), wire);
        gate.sendNow(OWNER, "two"); // goes out at once: nothing is waiting any more
        gate.sendNow(OWNER, "three");
        assertEquals(1, gate.waiting());
        joined = false;
        now += 1000;
        gate.drain();
        assertEquals(0, gate.waiting(), "a queue whose world is gone is dropped by the next drain too");
        assertEquals(List.of("one", "two"), wire);
    }
}
