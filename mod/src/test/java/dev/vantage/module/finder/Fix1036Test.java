package dev.vantage.module.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

import com.google.gson.JsonParser;
import dev.vantage.core.ChunkTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Fix1036Test {
    /** A row of containers along x at height y. */
    private static List<BlockPos> row(int x, int n, int y, int z) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new BlockPos(x + i, y, z));
        return out;
    }

    private static LongFunction<List<BlockPos>> world(List<BlockPos> all) {
        Map<Long, List<BlockPos>> m = new HashMap<>();
        for (BlockPos p : all) m.computeIfAbsent(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), k -> new ArrayList<>()).add(p);
        return k -> m.getOrDefault(k, List.of());
    }

    private static final long WEST = ChunkPos.asLong(0, 0), EAST = ChunkPos.asLong(1, 0);

    private static Long owner(List<BlockPos> all, long from) {
        var w = world(all);
        var piles = ChunkPile.crossing(from, w);
        return piles.isEmpty() ? null : ChunkPile.owner(piles.get(0), w, p -> 1, 12);
    }

    @Test
    void aSplitStashHasOneOwnerFromBothSides() {
        var six = row(10, 12, -50, 3); // x 10..21: six on each side
        assertEquals(WEST < EAST ? WEST : EAST, owner(six, WEST));
        assertEquals(owner(six, WEST), owner(six, EAST));
        var uneven = row(8, 12, -50, 3); // eight and four: the eight
        assertEquals(WEST, owner(uneven, WEST));
        assertEquals(WEST, owner(uneven, EAST));
        var one = row(15, 12, -50, 3); // one and eleven
        assertEquals(EAST, owner(one, WEST));
        assertEquals(6, ChunkPile.crossing(WEST, world(six)).get(0).outside(WEST).size());
    }

    @Test
    void pilesThatNeedNoHelpHaveNoOwner() {
        assertNull(owner(row(11, 10, -50, 3), WEST), "ten in all");
        var big = row(4, 12, -50, 3);
        big.addAll(row(16, 3, -50, 3));
        assertNull(owner(big, WEST), "twelve on one side already is a stash");
        var apart = row(5, 6, -50, 3); // x 5..10
        apart.addAll(row(16, 6, -50, 3)); // x 16..21: a gap of six
        assertTrue(ChunkPile.crossing(WEST, world(apart)).isEmpty(), "two rooms, not one pile");
        var heights = row(10, 6, -50, 3);
        heights.addAll(row(16, 6, -40, 3));
        assertTrue(ChunkPile.crossing(WEST, world(heights)).isEmpty(), "ten blocks above one another");
    }

    @Test
    void aPileOverAChunkCornerCountsAllFour() {
        var all = new ArrayList<BlockPos>();
        for (int[] c : new int[][] {{14, 14}, {16, 14}, {14, 16}, {16, 16}}) all.addAll(row(c[0], 2, -50, c[1]));
        all.addAll(row(14, 2, -49, 14));
        all.addAll(row(16, 2, -49, 16));
        var w = world(all);
        for (var from : new long[] {WEST, EAST, ChunkPos.asLong(0, 1), ChunkPos.asLong(1, 1)}) {
            var piles = ChunkPile.crossing(from, w);
            assertEquals(4, piles.get(0).byChunk().size());
            assertEquals(Math.min(WEST, ChunkPos.asLong(1, 1)), ChunkPile.owner(piles.get(0), w, p -> 1, 12));
        }
    }

    @Test
    void kelpTheUserWatchedGrowStaysTheirsAfterARestart(@TempDir Path dir) throws Exception {
        var first = new KelpMemory(() -> "srv", () -> dir);
        var w = new KelpWatch(first.in(() -> "minecraft:overworld"));
        assertEquals(1, w.count(7, 1, true, true), "grown before the user came");
        assertEquals(1, w.count(7, 5, true, true), "four grew beside the user");
        first.flush();
        // the game is restarted: new objects, same folder
        var again = new KelpMemory(() -> "srv", () -> dir);
        var after = new KelpWatch(again.in(() -> "minecraft:overworld"));
        assertEquals(1, after.count(7, 5, true, true), "still only the one that was there first");
        after.unloaded(7);
        assertEquals(3, after.count(7, 7, true, true), "two grew while the user was away: they count");
        assertEquals(5, after.count(8, 5, true, true), "another chunk: nothing remembered");
        assertEquals(5, new KelpWatch(again.in(() -> "minecraft:the_nether")).count(7, 5, true, true), "another dimension");
        assertEquals(5, new KelpWatch(new KelpMemory(() -> "other", () -> dir).in(() -> "minecraft:overworld")).count(7, 5, true, true), "another server");
    }

    @Test
    void oldKelpMemoryIsDropped() {
        var m = new KelpMemory(() -> "srv", () -> Path.of("unused"));
        m.read(JsonParser.parseString("{\"o|1\":[3,1000],\"o|2\":[3,900],\"o|3\":[0,1000],\"bad\":[1,1000]}").getAsJsonObject(), 1000);
        assertEquals("{\"o|1\":[3,1000]}", m.write().toString());
    }

    @Test
    void switchingTheFinderOffForgetsNothingTheUserGrew() {
        var w = new KelpWatch();
        w.count(1, 1, true, true);
        w.count(1, 4, true, true);
        w.unloadAll();
        assertEquals(3, w.count(1, 6, true, true), "two grew while it was off");
    }

    @Test
    void onlyAFlowThatRanOnMarksAChunkOld() {
        assertTrue(!ChunkTracker.ranFar(7, false, 1), "water one step from its spring: what generation itself leaves");
        assertTrue(ChunkTracker.ranFar(6, false, 1), "two steps");
        assertTrue(!ChunkTracker.ranFar(6, false, 2), "overworld lava one step");
        assertTrue(ChunkTracker.ranFar(4, false, 2), "overworld lava two steps");
        assertTrue(!ChunkTracker.ranFar(8, true, 1), "falling liquid says nothing");
    }

    @Test
    void aBellAloneIsNoVillage() {
        assertTrue(Villages.village(true, 40));
        assertTrue(!Villages.village(true, 3) && !Villages.village(false, 40));
    }
}
