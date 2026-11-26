package dev.vantage.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventsTest {
    /** Each test uses its own event classes, so tests cannot see each other's listeners. */
    static final class Ping { int n; }
    static final class Pong {}
    static final class Order {}
    static final class Boom {}
    static final class Hot { long sum; }
    static final class Mid {}

    record Who(String ownerName, int ownerRank) implements Events.Owner {}

    /** The sink is global, and other test classes install their own: every test here starts and ends without one. */
    @BeforeEach
    @AfterEach
    void noSink() {
        Events.faultSink(null);
    }

    @Test
    void aListenerHearsItsEventOnlyWhileItsGroupIsAttached() {
        Who w = new Who("a", 0);
        int[] heard = {0};
        Events.Group g = new Events.Group(w).listen(Ping.class, e -> heard[0] += e.n);
        Ping p = new Ping();
        p.n = 5;
        Events.channel(Ping.class).fire(p);
        assertEquals(0, heard[0], "not attached yet");
        g.attach();
        Events.channel(Ping.class).fire(p);
        Events.fire(p);
        assertEquals(10, heard[0]);
        g.detach();
        Events.fire(p);
        assertEquals(10, heard[0], "detached: silent again");
    }

    @Test
    void enablingAndDisablingAThousandTimesLeavesNoListenerBehind() {
        Who w = new Who("module", 0);
        Events.Group g = new Events.Group(w).listen(Ping.class, e -> {}).listen(Pong.class, e -> {}).listen(Pong.class, 5, e -> {});
        int before = Events.liveCount();
        for (int i = 0; i < 1000; i++) {
            g.attach();
            assertEquals(3, Events.liveCount(w));
            g.attach(); // a second attach must not double anything
            assertEquals(3, Events.liveCount(w));
            g.detach();
            assertEquals(0, Events.liveCount(w));
            g.detach();
        }
        assertEquals(before, Events.liveCount());
        assertEquals(0, Events.liveCount(w));
        assertFalse(g.attached());
    }

    @Test
    void orderIsPriorityThenOwnerRankThenDeclaration() {
        List<String> seen = new ArrayList<>();
        Who first = new Who("first", 1), second = new Who("second", 2);
        // attached in the "wrong" order on purpose: the result must not depend on it
        Events.Group b = new Events.Group(second).listen(Order.class, e -> seen.add("second-1")).listen(Order.class, 10, e -> seen.add("second-high"));
        Events.Group a = new Events.Group(first).listen(Order.class, e -> seen.add("first-1")).listen(Order.class, e -> seen.add("first-2"));
        b.attach();
        a.attach();
        Events.fire(new Order());
        assertEquals(List.of("second-high", "first-1", "first-2", "second-1"), seen);
        a.detach();
        b.detach();
    }

    @Test
    void aThrowingListenerGoesToTheSinkAndTheOthersStillRun() {
        Who bad = new Who("bad", 0), good = new Who("good", 1);
        List<String> faults = new ArrayList<>();
        int[] ran = {0};
        Events.faultSink((owner, type, error) -> faults.add(owner.ownerName() + ":" + type.getSimpleName() + ":" + error.getMessage()));
        Events.Group g1 = new Events.Group(bad).listen(Boom.class, e -> { throw new IllegalStateException("x"); });
        Events.Group g2 = new Events.Group(good).listen(Boom.class, e -> ran[0]++);
        g1.attach();
        g2.attach();
        Events.fire(new Boom());
        assertEquals(List.of("bad:Boom:x"), faults);
        assertEquals(1, ran[0]);
        g1.detach();
        g2.detach();
    }

    @Test
    void withoutASinkAnErrorIsNotSwallowed() {
        Who bad = new Who("bad", 0);
        Events.Group g = new Events.Group(bad).listen(Boom.class, e -> { throw new java.io.IOException("disk"); });
        g.attach();
        try {
            assertThrows(IllegalStateException.class, () -> Events.fire(new Boom()));
        } finally {
            g.detach();
        }
    }

    @Test
    void aListenerDetachedInTheMiddleOfAPassIsSkippedInThatPass() {
        Who a = new Who("a", 0), b = new Who("b", 1);
        int[] ran = {0};
        Events.Group gb = new Events.Group(b).listen(Mid.class, e -> ran[0]++);
        Events.Group ga = new Events.Group(a).listen(Mid.class, e -> gb.detach());
        ga.attach();
        gb.attach();
        Events.fire(new Mid());
        assertEquals(0, ran[0], "b was switched off by a earlier in the same pass");
        ga.detach();
    }

    @Test
    void aListenerDeclaredWhileAttachedIsLiveAtOnce() {
        Who w = new Who("late", 0);
        int[] ran = {0};
        Events.Group g = new Events.Group(w);
        g.attach();
        g.listen(Pong.class, e -> ran[0]++);
        Events.fire(new Pong());
        assertEquals(1, ran[0]);
        g.detach();
        assertEquals(0, Events.liveCount(w));
    }

    @Test
    void firingAllocatesNothing() {
        Who w = new Who("hot", 0);
        Events.Group g = new Events.Group(w).listen(Hot.class, e -> e.sum += 1).listen(Hot.class, e -> e.sum += 2).listen(Hot.class, 3, e -> e.sum += 3);
        g.attach();
        Events.Channel<Hot> c = Events.channel(Hot.class);
        Hot h = new Hot();
        for (int i = 0; i < 200_000; i++) c.fire(h); // warm up, so the JIT has compiled the loop
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 1_000_000; i++) c.fire(h);
        long allocated = mx.getThreadAllocatedBytes(id) - before;
        g.detach();
        assertEquals(1_200_000L * 6, h.sum);
        // 1,000,000 events x 3 listeners. One object per event would be at least 16 MB; the measuring call itself costs a few bytes.
        assertTrue(allocated < 4096, "firing allocated " + allocated + " bytes over 1,000,000 events");
    }
}
