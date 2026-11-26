package dev.vantage.module.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import dev.vantage.module.finder.StructureRules.Kind;
import org.junit.jupiter.api.Test;

/** Palettes and the chunk claim of generated structures (no world needed). */
class StructureRulesTest {
    private static long c(int x, int z) { return StructureRules.chunkKey(x, z); }

    @Test
    void cityPaletteIsTheRealOne() {
        for (String n : new String[] {"deepslate_bricks", "deepslate_tiles", "cracked_deepslate_bricks", "polished_deepslate", "chiseled_deepslate", "deepslate_tile_stairs",
                "deepslate_brick_wall", "gray_wool", "gray_carpet", "light_blue_wool", "cyan_carpet"})
            assertTrue(StructureRules.generated(Kind.CITY, n), n);
        // what a base is built from and the city never holds
        for (String n : new String[] {"white_concrete", "red_wool", "shulker_box", "obsidian", "deepslate", "cobbled_deepslate", "chest", "black_wool", "purple_carpet"})
            assertFalse(StructureRules.generated(Kind.CITY, n), n);
        assertTrue(StructureRules.marker(Kind.CITY, "reinforced_deepslate"));
        assertTrue(StructureRules.marker(Kind.CITY, "sculk_shrieker"));
        assertFalse(StructureRules.marker(Kind.CITY, "sculk"));
        assertTrue(StructureRules.sculk("sculk_catalyst"));
    }

    @Test
    void trialAndBastionPalettes() {
        assertTrue(StructureRules.generated(Kind.TRIAL, "red_concrete"));
        assertTrue(StructureRules.generated(Kind.TRIAL, "light_gray_stained_glass"));
        assertFalse(StructureRules.generated(Kind.TRIAL, "blue_concrete"));
        assertFalse(StructureRules.generated(Kind.TRIAL, "shulker_box"));
        assertTrue(StructureRules.marker(Kind.TRIAL, "trial_spawner"));
        assertTrue(StructureRules.build(Kind.TRIAL, "tuff_bricks"));
        assertTrue(StructureRules.generated(Kind.BASTION, "gold_block"));
        assertFalse(StructureRules.generated(Kind.BASTION, "netherite_block"));
        assertTrue(StructureRules.marker(Kind.BASTION, "gilded_blackstone"));
        assertTrue(StructureRules.anyGenerated("gray_wool"));
        assertFalse(StructureRules.anyGenerated("diamond_block"));
    }

    @Test
    void whereEachKindCanBe() {
        assertTrue(StructureRules.placeOk(Kind.CITY, -48, false));
        assertTrue(StructureRules.placeOk(Kind.CITY, -16, false));
        assertFalse(StructureRules.placeOk(Kind.CITY, 0, false), "a build above Y 0 is never a city");
        assertFalse(StructureRules.placeOk(Kind.CITY, 64, false));
        assertFalse(StructureRules.placeOk(Kind.TRIAL, -48, true), "no trial chamber in the Nether");
        assertTrue(StructureRules.placeOk(Kind.BASTION, 64, true));
        assertFalse(StructureRules.placeOk(Kind.BASTION, 64, false));
    }

    @Test
    void sculkNeedsCraftedDeepslateAndANumber() {
        assertTrue(StructureRules.cityMarkerChunk(true, 0, false));
        assertTrue(StructureRules.cityMarkerChunk(false, StructureRules.SCULK_MIN, true));
        assertFalse(StructureRules.cityMarkerChunk(false, StructureRules.SCULK_MIN - 1, true));
        assertFalse(StructureRules.cityMarkerChunk(false, 50, false), "deep dark sculk alone is not a city");
    }

    @Test
    void markerChunkClaimsItselfAndPaletteChunksReachingIt() {
        StructureRules r = StructureRules.create();
        r.update(Kind.CITY, c(0, 0), true, true);
        assertTrue(r.claimed(Kind.CITY, c(0, 0)));
        // a palette chunk two chunks away (one jump) is claimed, one with no palette is not even considered by the caller but cannot chain
        r.update(Kind.CITY, c(2, 0), false, true);
        assertTrue(r.claimed(Kind.CITY, c(2, 0)));
    }

    @Test
    void claimSpreadsAlongPaletteChunksUpToReach() {
        StructureRules r = StructureRules.create();
        r.update(Kind.CITY, c(0, 0), true, true);
        for (int x = 1; x <= StructureRules.REACH; x++) r.update(Kind.CITY, c(x, 0), false, true);
        for (int x = 1; x <= StructureRules.REACH; x++) assertTrue(r.claimed(Kind.CITY, c(x, 0)), "x " + x);
        r.update(Kind.CITY, c(StructureRules.REACH + 1, 0), false, true);
        assertFalse(r.claimed(Kind.CITY, c(StructureRules.REACH + 1, 0)), "farther than the reach");
    }

    @Test
    void gapOfTwoBridgesButGapOfThreeDoesNot() {
        StructureRules r = StructureRules.create();
        r.update(Kind.CITY, c(0, 0), true, true);
        r.update(Kind.CITY, c(2, 0), false, true);   // one empty chunk between: bridged
        r.update(Kind.CITY, c(5, 0), false, true);   // two empty chunks between (3 jump): not bridged
        assertTrue(r.claimed(Kind.CITY, c(2, 0)));
        assertFalse(r.claimed(Kind.CITY, c(5, 0)));
    }

    @Test
    void palettelessChunkOfAnotherKindNeverClaims() {
        StructureRules r = StructureRules.create();
        r.update(Kind.CITY, c(0, 0), true, true);
        r.update(Kind.TRIAL, c(1, 0), false, true);
        assertFalse(r.claimed(Kind.TRIAL, c(1, 0)), "a city marker says nothing about trial chambers");
        assertEquals(java.util.List.of(Kind.CITY), r.claims(c(0, 0)));
    }

    @Test
    void updateReportsChunksWhoseClaimChanged() {
        StructureRules r = StructureRules.create();
        r.update(Kind.CITY, c(3, 0), false, true);
        r.update(Kind.CITY, c(4, 0), false, true);
        assertFalse(r.claimed(Kind.CITY, c(3, 0)));
        Set<Long> flipped = Set.copyOf(r.update(Kind.CITY, c(2, 0), true, true)); // the marker turns up last
        assertTrue(flipped.contains(c(3, 0)) && flipped.contains(c(4, 0)), "" + flipped);
        assertEquals(java.util.List.of(), r.update(Kind.CITY, c(2, 0), true, true), "nothing changed, nothing to rescan");
        Set<Long> back = Set.copyOf(r.update(Kind.CITY, c(2, 0), false, false)); // and it goes away again
        assertTrue(back.contains(c(3, 0)) && back.contains(c(4, 0)));
        assertFalse(r.claimed(Kind.CITY, c(3, 0)));
    }

    @Test
    void negativeChunkCoordinatesKeepTheirKeys() {
        long k = c(-5, -9);
        assertEquals(-5, StructureRules.chunkX(k));
        assertEquals(-9, StructureRules.chunkZ(k));
        StructureRules r = StructureRules.create();
        r.update(Kind.TRIAL, c(-3, -3), true, true);
        r.update(Kind.TRIAL, c(-5, -4), false, true);
        assertTrue(r.claimed(Kind.TRIAL, c(-5, -4)));
        assertEquals(((long) -5 & 0xFFFFFFFFL) | (((long) -9 & 0xFFFFFFFFL) << 32), k, "the layout ChunkPos.asLong uses");
    }

    @Test
    void oceanMonumentClaimsItsGoldAndLanterns() {
        assertTrue(StructureRules.generated(Kind.MONUMENT, "gold_block") && StructureRules.generated(Kind.MONUMENT, "sea_lantern"));
        assertTrue(StructureRules.marker(Kind.MONUMENT, "wet_sponge") && StructureRules.marker(Kind.MONUMENT, "dark_prismarine"));
        assertFalse(StructureRules.marker(Kind.MONUMENT, "gold_block"), "gold alone is no marker: a player's gold stack must not claim itself");
        assertTrue(StructureRules.placeOk(Kind.MONUMENT, 32, false));
        assertFalse(StructureRules.placeOk(Kind.MONUMENT, -48, false), "no monument under the sea floor");
        assertFalse(StructureRules.placeOk(Kind.MONUMENT, 32, true), "none in the Nether");
        StructureRules r = StructureRules.create();
        r.update(Kind.MONUMENT, c(0, 0), true, true);
        r.update(Kind.MONUMENT, c(2, 0), false, true);
        assertTrue(r.claimed(Kind.MONUMENT, c(2, 0)), "a palette chunk next to a marker chunk is part of the monument");
        assertFalse(r.claimed(Kind.MONUMENT, c(30, 30)), "a gold block far from any monument is a player's");
        assertFalse(r.claimed(Kind.TRIAL, c(2, 0)));
    }

    @Test
    void monumentWingsAndUnloadedCoreStillClaim() {
        assertTrue(StructureRules.paletteSection(Kind.MONUMENT, 1));
        assertFalse(StructureRules.paletteSection(Kind.TRIAL, 2));
        assertTrue(StructureRules.monumentMarkerSection(3, true));
        assertFalse(StructureRules.monumentMarkerSection(2, true));
        assertFalse(StructureRules.monumentMarkerSection(4, false), "prismarine without a lantern marks nothing");
        StructureRules r = StructureRules.create();
        r.update(Kind.MONUMENT, c(0, 0), true, true);
        for (int x = 2; x <= 6; x += 2) r.update(Kind.MONUMENT, c(x, 0), false, true);
        assertTrue(r.claimed(Kind.MONUMENT, c(6, 0)), "a chain of five chunks from a marker reaches the far wing");
        assertFalse(r.claimed(Kind.MONUMENT, c(6, 12)), "gold and lanterns far from any prismarine palette still count");
    }

    @Test
    void aFewLooseContainersAreNoWreckableBase() {
        assertTrue(StructureRules.looseContainersOnly(1, 1, 1), "one shipwreck chest");
        assertTrue(StructureRules.looseContainersOnly(2, 2, 1), "the same chest underground");
        assertTrue(StructureRules.looseContainersOnly(6, 6, 3), "three chests");
        assertFalse(StructureRules.looseContainersOnly(4, 4, 4), "four containers");
        assertFalse(StructureRules.looseContainersOnly(8, 8, 1), "a shulker is packed loot, not a loose chest");
        assertFalse(StructureRules.looseContainersOnly(14, 2, 2), "anything else built beside the chests");
        assertFalse(StructureRules.looseContainersOnly(10, 0, 0), "built blocks and no containers");
    }
}
