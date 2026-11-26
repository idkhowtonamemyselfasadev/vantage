package dev.vantage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Stable ids, and modules that need or exclude each other. */
class ModuleLinksTest {
    static class Engine extends Module {
        Engine() { super("Test Engine", "needed by Car", Category.CLIENT); }
    }

    static class Car extends Module {
        Car() {
            super("Test Car", "needs Engine", Category.CLIENT);
            requires(Engine.class);
        }
    }

    static class Boat extends Module {
        Boat() {
            super("Test Boat", "cannot run with Car", Category.CLIENT);
            conflictsWith(Car.class);
        }
    }

    static class Renamed extends Module {
        Renamed() { super("test-old-name", "Test Brand New Name", "its display name changed after release", Category.CLIENT); }
    }

    static Engine engine;
    static Car car;
    static Boat boat;

    @BeforeAll
    static void register() {
        engine = Modules.add(new Engine());
        car = Modules.add(new Car());
        boat = Modules.add(new Boat());
        Modules.validate();
    }

    @Test
    void idsAreMadeFromTheName() {
        assertEquals("storage-esp", Ids.of("Storage ESP"));
        assertEquals("base-finder-2", Ids.of("Base Finder 2"));
        assertEquals("3d-hand", Ids.of("3D Hand"));
        assertEquals("scan-budget-ms", Ids.of("Scan budget (ms)"));
        assertEquals("a-b", Ids.of("  A -- b!  "));
        assertTrue(Ids.valid("storage-esp"));
        assertFalse(Ids.valid("Storage ESP"));
        assertFalse(Ids.valid("-x"));
        assertFalse(Ids.valid("x--y"));
        assertThrows(IllegalArgumentException.class, () -> Ids.of("???"));
        assertEquals("test-car", car.id);
        assertEquals(car, Modules.byId("test-car"));
    }

    @Test
    void aRenamedModuleKeepsItsId() {
        Renamed r = new Renamed();
        assertEquals("test-old-name", r.id);
        assertEquals("Test Brand New Name", r.name);
    }

    @Test
    void twoModulesCannotShareAnId() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Modules.add(new Engine()));
        assertTrue(e.getMessage().contains("test-engine"), e.getMessage());
    }

    @Test
    void switchingOnSwitchesTheRequiredModuleOnFirstAndSwitchingThatOffTakesTheDependentDown() {
        engine.setEnabled(false);
        car.setEnabled(false);
        boat.setEnabled(false);
        car.setEnabled(true);
        assertTrue(engine.isEnabled(), "Engine came on with Car");
        assertTrue(car.isEnabled());
        engine.setEnabled(false);
        assertFalse(car.isEnabled(), "Car cannot stay on without Engine");
        // the other direction: switching the dependent off leaves the required one alone
        car.setEnabled(true);
        car.setEnabled(false);
        assertTrue(engine.isEnabled());
        engine.setEnabled(false);
    }

    @Test
    void conflictingModulesSwitchEachOtherOffWhicheverSideDeclaredIt() {
        car.setEnabled(false);
        boat.setEnabled(false);
        boat.setEnabled(true);
        car.setEnabled(true); // Car did not declare the conflict, Boat did
        assertFalse(boat.isEnabled());
        assertTrue(car.isEnabled());
        boat.setEnabled(true);
        assertFalse(car.isEnabled());
        assertTrue(boat.isEnabled());
        boat.setEnabled(false);
        engine.setEnabled(false);
    }

    @Test
    void aFaultInARequiredModuleTakesTheDependentDownToo() {
        car.setEnabled(true);
        engine.fault("test", new IllegalStateException("x"));
        assertFalse(engine.isEnabled());
        assertFalse(car.isEnabled());
        assertNotNull(Modules.byId("test-engine"));
    }

    static class LoopA extends Module {
        LoopA() { super("Test Loop A", "requires B", Category.CLIENT); requires(LoopB.class); }
    }

    static class LoopB extends Module {
        LoopB() { super("Test Loop B", "requires A", Category.CLIENT); requires(LoopA.class); }
    }

    @Test
    void aRequiresLoopIsRejectedAtStart() {
        LoopA a = new LoopA();
        LoopB b = new LoopB();
        // these two stay in the shared registry afterwards; no other test calls validate() again
        assertThrows(IllegalStateException.class, () -> { Modules.add(a); Modules.add(b); Modules.validate(); });
    }
}
