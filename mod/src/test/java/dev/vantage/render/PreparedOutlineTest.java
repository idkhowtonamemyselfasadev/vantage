package dev.vantage.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** R3.prepare: the faces and edges of a block set, worked out once, must be exactly what the per-frame method used to work out. */
class PreparedOutlineTest {
    @BeforeAll
    static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<BlockPos> blocks(int... xyz) {
        List<BlockPos> l = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) l.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return l;
    }

    private static Set<Long> keys(List<BlockPos> l) {
        Set<Long> s = new HashSet<>();
        for (var p : l) s.add(p.asLong());
        return s;
    }

    /** Faces counted the slow way: a face of a block counts when the block next to it is not in the set. */
    private static int facesOf(List<BlockPos> l) {
        Set<Long> s = keys(l);
        int n = 0;
        for (long k : s) {
            BlockPos p = BlockPos.of(k);
            for (var d : net.minecraft.core.Direction.values()) if (!s.contains(p.relative(d).asLong())) n++;
        }
        return n;
    }

    private static Set<List<Integer>> edgeSet(List<int[]> e) {
        Set<List<Integer>> s = new HashSet<>();
        for (int[] a : e) s.add(List.of(a[0], a[1], a[2], a[3], a[4]));
        return s;
    }

    private static Set<List<Integer>> edgeSet(R3.Outline o) {
        Set<List<Integer>> s = new HashSet<>();
        for (int i = 0; i < o.edges.length; i += 5) s.add(List.of(o.edges[i], o.edges[i + 1], o.edges[i + 2], o.edges[i + 3], o.edges[i + 4]));
        return s;
    }

    private static void sameAsOldWay(List<BlockPos> l) {
        R3.Outline o = R3.prepare(l);
        List<int[]> old = R3.edges(keys(l));
        assertEquals(old.size(), o.edgeCount());
        assertEquals(edgeSet(old), edgeSet(o));
        assertEquals(facesOf(l), o.faces);
        assertEquals(keys(l).size(), o.blocks());
    }

    @Test
    void oneBlockHasSixFacesAndTwelveEdges() {
        R3.Outline o = R3.prepare(blocks(3, 64, -5));
        assertEquals(6, o.faces);
        assertEquals(12, o.edgeCount());
        sameAsOldWay(blocks(3, 64, -5));
    }

    @Test
    void twoTouchingBlocksHaveTenFaces() {
        List<BlockPos> l = blocks(0, 0, 0, 1, 0, 0);
        R3.Outline o = R3.prepare(l);
        assertEquals(10, o.faces);
        assertEquals(12, o.edgeCount()); // the four long edges join, so a 2x1x1 bar has 12 edges
        sameAsOldWay(l);
    }

    @Test
    void lShapeAndCube() {
        sameAsOldWay(blocks(0, 0, 0, 1, 0, 0, 2, 0, 0, 0, 1, 0, 0, 2, 0));
        List<BlockPos> cube = blocks(0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0, 0, 0, 1, 1, 0, 1, 0, 1, 1, 1, 1, 1);
        sameAsOldWay(cube);
        assertEquals(24, R3.prepare(cube).faces);
        assertEquals(12, R3.prepare(cube).edgeCount());
    }

    @Test
    void separateAndNegativeBlocks() {
        sameAsOldWay(blocks(-10, -60, -10, 5, 70, 5, 6, 70, 5, 6, 71, 5));
    }

    @Test
    void orderAndRepeatsDoNotMatter() {
        List<BlockPos> a = blocks(0, 0, 0, 1, 0, 0, 2, 0, 0, 0, 1, 0, 0, 2, 0, 5, 5, 5);
        List<BlockPos> b = new ArrayList<>(a);
        Collections.reverse(b);
        b.add(new BlockPos(5, 5, 5));
        R3.Outline x = R3.prepare(a), y = R3.prepare(b);
        assertTrue(java.util.Arrays.equals(x.pos, y.pos));
        assertTrue(java.util.Arrays.equals(x.exposed, y.exposed));
        assertTrue(java.util.Arrays.equals(x.edges, y.edges));
        assertEquals(x.low, y.low);
    }

    @Test
    void emptyGivesNothing() {
        R3.Outline o = R3.prepare(List.of());
        assertEquals(0, o.faces);
        assertEquals(0, o.blocks());
        assertEquals(0, o.edgeCount());
    }

    @Test
    void cacheRebuildsOnlyWhenBlocksChange() {
        R3.OutlineCache c = new R3.OutlineCache();
        R3.Outline a = c.get(1, blocks(0, 0, 0, 1, 0, 0));
        assertSame(a, c.get(1, blocks(0, 0, 0, 1, 0, 0)));
        assertNotSame(a, c.get(1, blocks(0, 0, 0)));
        c.endFrame();
        c.get(2, blocks(0, 0, 0));
        c.endFrame(); // key 1 was not asked for in that frame
        R3.Outline again = c.get(1, blocks(0, 0, 0));
        assertEquals(6, again.faces);
    }
}
