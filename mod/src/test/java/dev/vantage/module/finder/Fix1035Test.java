package dev.vantage.module.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class Fix1035Test {
    private static void add(List<BaseLogic.Clue> to, BaseLogic.Kind k, int n, int cx) {
        for (int i = 0; i < n; i++) to.add(new BaseLogic.Clue(k, cx, i % 2, cx * 16 + 8, -10, (i % 2) * 16 + 8));
    }

    private static int pct(List<BaseLogic.Clue> clues, boolean v2) {
        var z = BaseLogic.zones(clues, List.of(), 8, v2);
        return z.isEmpty() ? 0 : z.get(0).pct();
    }

    /** The clue sets of the two zones as the 1.0.34 route pass logged them: 12 empty barrels against a stash with shulker boxes. */
    @Test
    void aStashWithShulkersOutranksABarrelRoomWithRicherSurroundings() {
        for (boolean v2 : new boolean[] {false, true}) {
            var decoy = new ArrayList<BaseLogic.Clue>();
            add(decoy, BaseLogic.BUILT, 1, 0);
            add(decoy, BaseLogic.TOUCHED, 2, 0);
            add(decoy, BaseLogic.KELP, 1, 0);
            var stash = new ArrayList<BaseLogic.Clue>();
            add(stash, BaseLogic.BUILT, 2, 0);
            add(stash, BaseLogic.TOUCHED, 1, 0);
            assertTrue(pct(stash, v2) > pct(decoy, v2), "used structures count once: " + pct(stash, v2) + " vs " + pct(decoy, v2));
            add(stash, BaseLogic.SHULKERS, 1, 0);
            assertTrue(pct(stash, v2) >= pct(decoy, v2) + 8, "shulkers: " + pct(stash, v2) + " vs " + pct(decoy, v2));
        }
    }

    @Test
    void usedStructuresStillAddUpWithoutAnAnchor() {
        var natural = new ArrayList<BaseLogic.Clue>();
        add(natural, BaseLogic.TOUCHED, 3, 0);
        add(natural, BaseLogic.TUNNEL, 1, 0);
        // 1 - (1 - .35 * .875)(1 - .35 * .5) = 0.43
        assertEquals(43, pct(natural, false));
    }

    @Test
    void shulkersRaiseAZoneButNeverMakeOne() {
        for (boolean v2 : new boolean[] {false, true}) {
            var two = new ArrayList<BaseLogic.Clue>();
            add(two, BaseLogic.BUILT, 1, 0);
            add(two, BaseLogic.SHULKERS, 1, 0);
            assertTrue(BaseLogic.zones(two, List.of(), 8, v2).isEmpty());
            add(two, BaseLogic.TOUCHED, 1, 0);
            assertFalse(BaseLogic.zones(two, List.of(), 8, v2).isEmpty());
            assertTrue(BaseLogic.zones(two, List.of(), 8, v2, Set.copyOf(two)).get(0).pct() <= 50, "weak shulkers (one player in the world) anchor nothing");
        }
    }

    @Test
    void kelpThatGrowsUnderTheUsersNoseDoesNotCount() {
        var w = new KelpWatch();
        assertEquals(1, w.count(1, 1, true, true), "grown at first sight");
        assertEquals(1, w.count(1, 5, true, true), "four more while the user stood there");
        assertEquals(1, w.count(1, 5, true, true));
        w.unloaded(1);
        assertEquals(3, w.count(1, 7, true, true), "two grew while the chunk was not loaded: somebody else, or nobody");
        assertEquals(3, w.count(1, 8, true, true));
    }

    @Test
    void otherPeoplesKelpStillCounts() {
        var w = new KelpWatch();
        assertEquals(4, w.count(1, 4, true, false), "grown before the user came");
        assertEquals(6, w.count(1, 6, true, false), "grows beyond the user's simulation distance");
        assertEquals(0, w.count(2, 0, false, true));
        assertEquals(3, w.count(2, 3, true, true), "a chunk that held no kelp: somebody planted it");
        assertEquals(1, w.count(2, 1, true, true), "harvested");
        w.clear();
        assertEquals(9, w.count(1, 9, true, true), "a new world starts again");
    }

    @Test
    void releasedScoringNeedsAFloodedMonumentSign() {
        assertTrue(StructureRules.monumentWaterMarker(1488, 838, true, true, true, true), "the core section of the measured monument");
        assertFalse(StructureRules.monumentWaterMarker(0, 838, true, true, true, true), "a dry prismarine build");
        assertFalse(StructureRules.monumentWaterMarker(1488, 60, true, true, true, true), "a small underwater decoration");
        assertFalse(StructureRules.monumentWaterMarker(1488, 838, true, true, false, true), "no dark prismarine");
        assertFalse(StructureRules.monumentWaterMarker(1488, 838, true, true, true, false), "no sea lantern");
        assertTrue(StructureRules.monumentFlooded(800));
        assertFalse(StructureRules.monumentFlooded(799));
    }

    @Test
    void aMobHalfInAWallIsNotBuried() {
        assertTrue(DeepLeaks.buried(true, 0), "in a filled section");
        assertTrue(DeepLeaks.buried(false, 6));
        assertFalse(DeepLeaks.buried(false, 4), "a villager in a house wall");
        assertFalse(DeepLeaks.buried(false, 5));
    }
}
