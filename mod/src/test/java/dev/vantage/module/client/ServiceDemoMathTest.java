package dev.vantage.module.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.vantage.core.util.ChunkMath;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ServiceDemoMathTest {
    @BeforeAll
    static void boot() { // ChunkMath reaches into the game's chunk classes, which need the registries (the same set-up ChunkMathTest uses)
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static long[] keys(int... xz) {
        long[] k = new long[xz.length / 2];
        for (int i = 0; i < k.length; i++) k[i] = ChunkMath.key(xz[2 * i], xz[2 * i + 1]);
        return k;
    }

    @Test
    void everyModeMatchesItsBlocksOnly() {
        assertTrue(ServiceDemoMath.matches("Chest", "chest"));
        assertTrue(ServiceDemoMath.matches("Chest", "trapped_chest"));
        assertFalse(ServiceDemoMath.matches("Chest", "ender_chest"));
        assertTrue(ServiceDemoMath.matches("Barrel", "barrel"));
        assertTrue(ServiceDemoMath.matches("Spawner", "spawner"));
        assertTrue(ServiceDemoMath.matches("Shulker box", "shulker_box"));
        assertTrue(ServiceDemoMath.matches("Shulker box", "red_shulker_box"));
        assertFalse(ServiceDemoMath.matches("Barrel", "chest"));
        assertFalse(ServiceDemoMath.matches("nonsense", "chest"));
    }

    @Test
    void nearestComesFirstAndTheListIsCutAtTheLimit() {
        long[] k = keys(5, 0, 1, 0, -3, 0, 0, 4);
        List<ServiceDemoMath.Hit> hits = ServiceDemoMath.nearest(k, 4, 8, 8, 3); // the player stands in the middle of chunk (0, 0)
        assertEquals(3, hits.size());
        assertEquals(1, hits.get(0).cx()); // 16 blocks east
        assertEquals(-3, hits.get(1).cx()); // 48 blocks west
        assertEquals(0, hits.get(2).cx()); // 64 blocks south; the chunk 80 blocks east is cut off
        assertEquals(4, hits.get(2).cz());
    }

    @Test
    void anEmptySetGivesAnEmptyList() {
        assertTrue(ServiceDemoMath.nearest(new long[0], 0, 0, 0, 5).isEmpty());
    }

    @Test
    void theCountOnlyReadsTheFirstCountKeys() {
        long[] k = keys(1, 0, 9, 9);
        assertEquals(1, ServiceDemoMath.nearest(k, 1, 8, 8, 5).size());
    }

    @Test
    void equalDistancesAreOrderedByCoordinates() {
        List<ServiceDemoMath.Hit> hits = ServiceDemoMath.nearest(keys(1, 0, -1, 0), 2, 8, 8, 2);
        assertEquals(-1, hits.get(0).cx());
        assertEquals(hits.get(0).dist(), hits.get(1).dist(), 1e-9);
    }

    @Test
    void textsUseTheSharedFormatters() {
        ServiceDemoMath.Hit east = ServiceDemoMath.nearest(keys(3, 0), 1, 8, 8, 1).get(0); // 48 blocks east, 0 north-south
        assertEquals("48 m E", ServiceDemoMath.where(east));
        assertEquals("1. chunk 3, 0: 48 m E", ServiceDemoMath.listLine(1, east));
        assertEquals("Demo: 1 chunk", ServiceDemoMath.countLine(1));
        assertEquals("Demo: 0 chunks", ServiceDemoMath.countLine(0));
        assertEquals("Demo: 12 chunks", ServiceDemoMath.countLine(12));
    }

    @Test
    void aFarChunkReadsInKilometres() {
        ServiceDemoMath.Hit far = ServiceDemoMath.nearest(keys(0, -250), 1, 8, 8, 1).get(0);
        assertEquals("4.0 km N", ServiceDemoMath.where(far));
    }
}
