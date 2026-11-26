package dev.vantage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vantage.core.event.DimensionChangeEvent;
import dev.vantage.core.event.Events;
import dev.vantage.core.event.TickEvent;
import dev.vantage.core.event.WorldEvents;
import dev.vantage.core.event.WorldJoinEvent;
import dev.vantage.core.event.WorldLeaveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The module base class on the event bus: lifecycle, listeners that cannot leak, and faults that do not change the saved state. */
class ModuleTest {
    static class Probe extends Module {
        int ticks, enables, disables, joins, leaves, dimensions;
        boolean throwOnTick, throwOnEnable;

        Probe(String name) {
            super(name, "test module", Category.CLIENT);
        }

        @Override protected void onEnable() { enables++; if (throwOnEnable) throw new IllegalStateException("enable"); }
        @Override protected void onDisable() { disables++; }
        @Override public void onTick() { ticks++; if (throwOnTick) throw new IllegalStateException("tick"); }
        @Override public void onJoinWorld() { joins++; }
        @Override public void onLeaveWorld() { leaves++; }
        @Override public void onDimensionChange() { dimensions++; }
    }

    /** Overrides nothing: must not subscribe anything. */
    static class Idle extends Module {
        Idle() {
            super("Idle", "test module", Category.CLIENT);
        }
    }

    @BeforeEach
    void sink() {
        // the same wiring Modules.init() does, without building the 85 real modules
        Events.faultSink((owner, type, error) -> { if (owner instanceof Module m) m.fault(type.getSimpleName(), error); });
    }

    @AfterEach
    void noSink() {
        Events.faultSink(null);
    }

    @Test
    void hooksRunOnlyWhileTheModuleIsOn() {
        Probe p = new Probe("Hooks");
        TickEvent.fire();
        assertEquals(0, p.ticks);
        p.setEnabled(true);
        TickEvent.fire();
        TickEvent.fire();
        assertEquals(2, p.ticks);
        p.muted = true; // a rig mutes a module for a screenshot: on, but its hooks see it as off
        TickEvent.fire();
        assertEquals(2, p.ticks);
        p.muted = false;
        p.setEnabled(false);
        TickEvent.fire();
        assertEquals(2, p.ticks);
        assertEquals(1, p.enables);
        assertEquals(1, p.disables);
    }

    @Test
    void aThousandRoundsOfOnAndOffLeaveNoListener() {
        Probe p = new Probe("Rounds");
        int before = Events.liveCount();
        for (int i = 0; i < 1000; i++) {
            p.setEnabled(true);
            assertEquals(1, Events.liveCount(p), "one hook (onTick) is live while on");
            p.setEnabled(false);
            assertEquals(0, Events.liveCount(p));
        }
        assertEquals(before, Events.liveCount());
        assertEquals(1000, p.enables);
        assertEquals(1000, p.disables);
    }

    @Test
    void aModuleThatOverridesNothingSubscribesNothing() {
        Idle m = new Idle();
        m.setEnabled(true);
        assertEquals(0, Events.liveCount(m));
        m.setEnabled(false);
    }

    @Test
    void aThrowingHookSwitchesTheModuleOffForTheSessionButKeepsItsSavedState() {
        Probe p = new Probe("Faulty");
        p.setEnabled(true);
        p.throwOnTick = true;
        TickEvent.fire();
        assertFalse(p.on());
        assertFalse(p.isEnabled());
        assertTrue(p.faulted());
        assertTrue(Panic.persisted(p), "the config must still store it as on");
        assertEquals(1, p.disables, "onDisable ran so the module could clean up");
        assertEquals(0, Events.liveCount(p));
        TickEvent.fire();
        assertEquals(1, p.ticks, "no further calls after the fault");
        // switching it on by hand clears the fault
        p.throwOnTick = false;
        p.setEnabled(true);
        assertFalse(p.faulted());
        TickEvent.fire();
        assertEquals(2, p.ticks);
        p.setEnabled(false);
        assertFalse(Panic.persisted(p));
    }

    @Test
    void switchingAFaultedModuleOffByHandReallySavesItAsOff() {
        Probe p = new Probe("FaultyOff");
        p.setEnabled(true);
        p.throwOnTick = true;
        TickEvent.fire();
        assertTrue(Panic.persisted(p));
        p.setEnabled(false);
        assertFalse(p.faulted());
        assertFalse(Panic.persisted(p));
    }

    @Test
    void anOnEnableThatThrowsLeavesTheModuleOffNotHalfOn() {
        Probe p = new Probe("BadStart");
        p.throwOnEnable = true;
        p.setEnabled(true);
        assertFalse(p.isEnabled());
        assertTrue(p.faulted());
        assertEquals(0, Events.liveCount(p));
        TickEvent.fire();
        assertEquals(0, p.ticks);
    }

    @Test
    void loadingAConfigThatSaysOffSwitchesOffAModuleThatWasOnInCode() {
        Probe p = new Probe("Loaded");
        p.setEnabled(true);
        p.loadEnabled(false);
        assertFalse(p.isEnabled());
        assertEquals(1, p.disables, "onDisable must run (it did not before: BUGS.md)");
        assertEquals(0, Events.liveCount(p));
    }

    @Test
    void aRegisteredModuleHearsJoinLeaveAndDimensionChangeEvenWhileOff() {
        Probe p = new Probe("World");
        WorldJoinEvent.fire(null);
        WorldLeaveEvent.fire();
        DimensionChangeEvent.fire(null);
        assertEquals(0, p.joins + p.leaves + p.dimensions, "not registered yet");
        p.registered(1);
        assertFalse(p.isEnabled());
        WorldJoinEvent.fire(null);
        assertEquals(1, p.joins);
        assertEquals(0, p.leaves + p.dimensions, "a join is not a leave or a dimension change");
        WorldLeaveEvent.fire();
        assertEquals(1, p.leaves);
        assertEquals(0, p.dimensions);
        DimensionChangeEvent.fire(null);
        assertEquals(1, p.dimensions);
        assertEquals(1, p.joins, "each event reaches the module exactly once");
        assertEquals(1, p.leaves);
        p.setEnabled(true);
        WorldJoinEvent.fire(null);
        WorldLeaveEvent.fire();
        DimensionChangeEvent.fire(null);
        p.setEnabled(false);
        assertEquals(2, p.joins);
        assertEquals(2, p.leaves);
        assertEquals(2, p.dimensions);
    }

    @Test
    void aModuleThatOverridesNoWorldHookSubscribesNothing() {
        Idle m = new Idle();
        m.registered(1);
        assertEquals(0, Events.liveCount(m));
        m.setEnabled(true);
        assertEquals(0, Events.liveCount(m));
    }

    @Test
    void aWorldChangeFiresExactlyItsOwnEvent() {
        Probe p = new Probe("Choice");
        p.registered(1);
        WorldEvents.fire(false, true, null); // no world -> world
        assertEquals(1, p.joins);
        assertEquals(0, p.leaves + p.dimensions);
        WorldEvents.fire(true, true, null); // world -> another world
        assertEquals(1, p.dimensions);
        assertEquals(1, p.joins);
        assertEquals(0, p.leaves);
        WorldEvents.fire(true, false, null); // world -> no world
        assertEquals(1, p.leaves);
        assertEquals(1, p.joins);
        assertEquals(1, p.dimensions);
    }

    @Test
    void theLoggerIsOneObjectEvenWhenSeveralThreadsAskFirst() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 50; round++) {
                Probe p = new Probe("Log race " + round);
                var start = new java.util.concurrent.CountDownLatch(1);
                java.util.List<java.util.concurrent.Future<Object>> got = new java.util.ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    got.add(pool.submit(() -> {
                        start.await();
                        return p.log();
                    }));
                }
                start.countDown();
                Object first = got.get(0).get(5, java.util.concurrent.TimeUnit.SECONDS);
                for (var f : got) org.junit.jupiter.api.Assertions.assertSame(first, f.get(5, java.util.concurrent.TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertSame(first, p.log());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
