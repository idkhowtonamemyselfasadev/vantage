package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generated structures that are built out of blocks Base Finder scores as "somebody built this" (deepslate bricks, wool, concrete, glass).
 * The marker blocks (reinforced deepslate, trial spawners) sit in a few chunks only, so the 3x3 penalty around them leaves the rest of the
 * structure looking like a base. A structure here is claimed chunk by chunk: a chunk is claimed when a chain of chunks that hold the same
 * palette, each at most STEP chunks from the next, leads to a marker chunk no more than REACH chunks away. Claimed chunks do not count the
 * structure's own palette; blocks that never generate there (shulker boxes, obsidian, other colours) still count.
 * Pure logic on block names and chunk keys, so the unit tests need no world.
 */
public final class StructureRules {
    public enum Kind {
        CITY("ancient city", "deepslate bricks, tiles, gray and blue wool"),
        TRIAL("trial chamber", "concrete and glass"),
        BASTION("bastion remnant", "gold blocks"),
        MONUMENT("ocean monument", "gold blocks, sea lanterns, prismarine and sponge");

        public final String name, notCounted;
        Kind(String name, String notCounted) { this.name = name; this.notCounted = notCounted; }
    }

    /** A chain may jump this many chunks between two palette chunks (a cavern or a street without any), and ends at most REACH from the chunk asked about. */
    public static final int STEP = 2, REACH = 7;
    /** Ancient cities and trial chambers generate under Y 0 (cities from about -52 up to -17, chambers from -40 to -20); a section starting at or above this is never one. */
    public static final int MAX_SECTION_Y = -16;
    /** Sculk blocks in one chunk, with crafted deepslate, that say a city is here even when no reinforced deepslate or shrieker was loaded. */
    public static final int SCULK_MIN = 6;
    /** Distinct blocks of the trial chamber building palette in one section that make it a chamber section. */
    public static final int TRIAL_SIGNATURE_MIN = 3;
    /**
     * Monument wings hold little: a section with a single prismarine-family block already makes the chunk a palette chunk (the claim still needs a marker chain,
     * so a lone sea lantern far from any prismarine stays a base clue). Three distinct monument blocks including a sea lantern in one section mark a chunk
     * when the dark prismarine and sponge rooms were never loaded.
     */
    public static final int MONUMENT_SIGNATURE_MIN = 1, MONUMENT_MARKER_SIGNATURE = 3;

    /** Section signature for a kind: distinct build blocks in the section decide palette; a monument section with enough of them plus a lantern is a marker. */
    public static boolean paletteSection(Kind k, int distinctBuild) {
        return distinctBuild >= (k == Kind.MONUMENT ? MONUMENT_SIGNATURE_MIN : TRIAL_SIGNATURE_MIN);
    }

    public static boolean monumentMarkerSection(int distinctBuild, boolean seaLantern) {
        return seaLantern && distinctBuild >= MONUMENT_MARKER_SIGNATURE;
    }

    /** The released scoring's monument sign: water cells and monument building blocks a flooded section must hold (measured on one generated monument: 1400+ water, 375+ blocks). */
    public static final int MONUMENT_WATER_MIN = 800, MONUMENT_BLOCKS_MIN = 200;

    public static boolean monumentFlooded(int water) { return water >= MONUMENT_WATER_MIN; }

    /** A section that is monument by itself: flooded, a lot of prismarine, and all of prismarine, its bricks, dark prismarine and sea lanterns. */
    public static boolean monumentWaterMarker(int water, int blocks, boolean prismarine, boolean bricks, boolean dark, boolean seaLantern) {
        return monumentFlooded(water) && blocks >= MONUMENT_BLOCKS_MIN && prismarine && bricks && dark && seaLantern;
    }

    /** A shipwreck holds up to three chests, an ocean ruin fewer. */
    public static final int LOOSE_CONTAINERS_MAX = 3;

    /**
     * Is everything "built" in a chunk just a few plain containers? Chests and barrels score 1 each (2 deep underground), so storage points above two per
     * container mean shulkers or the like. Such a chunk has no base in it that could read as wrecked from its shape or from a wreck next door; loud signs
     * (a grief sign, fire, TNT, lava) still count by themselves.
     */
    public static boolean looseContainersOnly(int buildPoints, int storagePoints, int containers) {
        return containers >= 1 && containers <= LOOSE_CONTAINERS_MAX && storagePoints <= 2 * containers && buildPoints <= storagePoints;
    }

    // ---- block names (registry paths, no namespace) ----

    private static final Set<String> CITY_DEEPSLATE = Set.of("deepslate_bricks", "cracked_deepslate_bricks", "deepslate_tiles", "cracked_deepslate_tiles", "polished_deepslate",
            "chiseled_deepslate", "deepslate_brick_stairs", "deepslate_brick_slab", "deepslate_brick_wall", "deepslate_tile_stairs", "deepslate_tile_slab", "deepslate_tile_wall",
            "polished_deepslate_stairs", "polished_deepslate_slab", "polished_deepslate_wall");
    /** Wool and carpet colours the ancient city structure files hold (counted from the 1.21.11 data pack). */
    private static final Set<String> CITY_WOOL = Set.of("gray_wool", "gray_carpet", "light_blue_wool", "light_blue_carpet", "blue_wool", "blue_carpet", "cyan_wool", "cyan_carpet");
    private static final Set<String> CITY_MARKER = Set.of("reinforced_deepslate", "sculk_shrieker");
    private static final Set<String> SCULK = Set.of("sculk", "sculk_vein", "sculk_catalyst", "sculk_sensor");

    /** What a trial chamber is built from: tuff bricks and waxed copper. */
    private static final Set<String> TRIAL_BUILD = Set.of("tuff_bricks", "polished_tuff", "chiseled_tuff_bricks", "chiseled_tuff", "waxed_copper_block", "waxed_oxidized_copper",
            "waxed_oxidized_cut_copper", "waxed_copper_grate", "waxed_copper_bulb", "waxed_oxidized_copper_grate", "waxed_chiseled_copper");
    /** Scored blocks the trial chamber files hold (red and white concrete, three glass colours, the red glazed terracotta). */
    private static final Set<String> TRIAL_SCORED = Set.of("red_concrete", "white_concrete", "light_gray_stained_glass", "white_stained_glass", "black_stained_glass", "red_glazed_terracotta");
    private static final Set<String> TRIAL_MARKER = Set.of("trial_spawner", "vault");

    /** Bastion remnants (Nether): gold blocks are the one scored block in their files; gilded blackstone only generates there. */
    private static final Set<String> BASTION_BUILD = Set.of("polished_blackstone_bricks", "cracked_polished_blackstone_bricks", "chiseled_polished_blackstone", "blackstone",
            "gilded_blackstone", "polished_blackstone_brick_stairs", "polished_blackstone_brick_slab", "blackstone_stairs", "blackstone_slab");
    private static final Set<String> BASTION_MARKER = Set.of("gilded_blackstone");

    /** Ocean monument: dark prismarine and wet sponge are its markers; gold (the core) and sea lanterns are the scored blocks it generates. */
    private static final Set<String> MONUMENT_MARKER = Set.of("dark_prismarine", "wet_sponge");
    private static final Set<String> MONUMENT_BUILD = Set.of("prismarine", "prismarine_bricks", "dark_prismarine", "sea_lantern", "prismarine_brick_stairs", "prismarine_brick_slab", "prismarine_slab", "prismarine_stairs");
    /** Monuments sit on the sea floor between about Y 30 and 62 (their top rooms reach about Y 60). */
    public static final int MONUMENT_MIN_SECTION_Y = 16, MONUMENT_MAX_SECTION_Y = 48;

    private StructureRules() {}

    /** The blocks of this kind's palette that Base Finder would score, or false. */
    public static boolean generated(Kind k, String path) {
        return switch (k) {
            case CITY -> CITY_DEEPSLATE.contains(path) || CITY_WOOL.contains(path);
            case TRIAL -> TRIAL_SCORED.contains(path);
            case BASTION -> path.equals("gold_block");
            case MONUMENT -> path.equals("gold_block") || path.equals("sea_lantern") || path.equals("wet_sponge") || path.equals("prismarine_bricks") || path.equals("dark_prismarine");
        };
    }

    /** Marker blocks that tell this kind's chunk by themselves (the city's sculk needs a count, see cityMarkerChunk). */
    public static boolean marker(Kind k, String path) {
        return switch (k) {
            case CITY -> CITY_MARKER.contains(path);
            case TRIAL -> TRIAL_MARKER.contains(path);
            case BASTION -> BASTION_MARKER.contains(path);
            case MONUMENT -> MONUMENT_MARKER.contains(path);
        };
    }

    /** Blocks the structure is built from, for the signature of a section. */
    public static boolean build(Kind k, String path) {
        return switch (k) {
            case CITY -> false;
            case TRIAL -> TRIAL_BUILD.contains(path);
            case BASTION -> BASTION_BUILD.contains(path);
            case MONUMENT -> MONUMENT_BUILD.contains(path);
        };
    }

    /** Where the kind can be: cities and chambers in the Overworld under Y 0, bastions in the Nether. */
    public static boolean placeOk(Kind k, int sectionY, boolean ceiling) {
        if (k == Kind.MONUMENT) return !ceiling && sectionY >= MONUMENT_MIN_SECTION_Y && sectionY <= MONUMENT_MAX_SECTION_Y;
        return k == Kind.BASTION ? ceiling : !ceiling && sectionY <= MAX_SECTION_Y;
    }

    public static boolean anyGenerated(String path) {
        for (Kind k : Kind.values()) if (generated(k, path)) return true;
        return false;
    }

    public static boolean sculk(String path) { return SCULK.contains(path); }

    /** A city chunk by its own blocks: a marker block, or sculk in numbers next to crafted deepslate. */
    public static boolean cityMarkerChunk(boolean markerBlock, int sculkBlocks, boolean paletteHere) {
        return markerBlock || paletteHere && sculkBlocks >= SCULK_MIN;
    }

    // ---- the claim ----

    private final List<Set<Long>> marks = newSets(), pals = newSets();

    private static List<Set<Long>> newSets() {
        List<Set<Long>> s = new ArrayList<>();
        for (int i = 0; i < Kind.values().length; i++) s.add(new HashSet<>());
        return s;
    }

    public static StructureRules create() { return new StructureRules(); }

    public void clear() {
        for (Set<Long> s : marks) s.clear();
        for (Set<Long> s : pals) s.clear();
    }

    private static long key(int x, int z) { return ((long) x & 0xFFFFFFFFL) | ((long) z << 32); }
    private static int cx(long k) { return (int) k; }
    private static int cz(long k) { return (int) (k >> 32); }

    /**
     * Records what the chunk holds (a marker block of this kind, the kind's palette). Returns the chunks (other than this one) whose claim changed, so
     * the caller can score them again.
     */
    public List<Long> update(Kind kind, long chunk, boolean mark, boolean pal) {
        int i = kind.ordinal();
        boolean was = marks.get(i).contains(chunk), wasPal = pals.get(i).contains(chunk);
        if (was == mark && wasPal == pal) return List.of();
        List<Long> near = nearby(i, chunk);
        boolean[] before = new boolean[near.size()];
        for (int n = 0; n < before.length; n++) before[n] = claimed(kind, near.get(n));
        if (mark) marks.get(i).add(chunk); else marks.get(i).remove(chunk);
        if (pal) pals.get(i).add(chunk); else pals.get(i).remove(chunk);
        List<Long> out = new ArrayList<>();
        for (int n = 0; n < before.length; n++) if (before[n] != claimed(kind, near.get(n))) out.add(near.get(n));
        return out;
    }

    /** Known chunks of this kind within REACH of the chunk, not the chunk itself. */
    private List<Long> nearby(int i, long chunk) {
        List<Long> out = new ArrayList<>();
        int x = cx(chunk), z = cz(chunk);
        for (Set<Long> s : List.of(pals.get(i), marks.get(i))) for (long k : s) {
            if (k != chunk && Math.max(Math.abs(cx(k) - x), Math.abs(cz(k) - z)) <= REACH && !out.contains(k)) out.add(k);
        }
        return out;
    }

    public boolean hasMarker(Kind kind, long chunk) { return marks.get(kind.ordinal()).contains(chunk); }

    /** The chunk is a marker chunk itself, or reaches one through palette chunks (jumps of at most STEP, never farther than REACH from the chunk). */
    public boolean claimed(Kind kind, long chunk) {
        int i = kind.ordinal();
        if (marks.get(i).isEmpty()) return false;
        if (marks.get(i).contains(chunk)) return true;
        int x0 = cx(chunk), z0 = cz(chunk);
        Set<Long> seen = new HashSet<>();
        List<Long> todo = new ArrayList<>();
        todo.add(chunk);
        seen.add(chunk);
        while (!todo.isEmpty()) {
            long at = todo.remove(todo.size() - 1);
            for (int dx = -STEP; dx <= STEP; dx++) for (int dz = -STEP; dz <= STEP; dz++) {
                int nx = cx(at) + dx, nz = cz(at) + dz;
                if ((dx | dz) == 0 || Math.max(Math.abs(nx - x0), Math.abs(nz - z0)) > REACH) continue;
                long nk = key(nx, nz);
                if (marks.get(i).contains(nk)) return true;
                if (pals.get(i).contains(nk) && seen.add(nk)) todo.add(nk);
            }
        }
        return false;
    }

    /** Which kinds claim this chunk, in enum order. */
    public List<Kind> claims(long chunk) {
        List<Kind> out = new ArrayList<>();
        for (Kind k : Kind.values()) if (claimed(k, chunk)) out.add(k);
        return out;
    }

    /** Every chunk of this kind that is known, for a rescan when the Skip structures switch changes. */
    public List<Long> known() {
        Set<Long> all = new HashSet<>();
        for (int i = 0; i < marks.size(); i++) { all.addAll(marks.get(i)); all.addAll(pals.get(i)); }
        return new ArrayList<>(all);
    }

    public static long chunkKey(int x, int z) { return key(x, z); }
    public static int chunkX(long k) { return cx(k); }
    public static int chunkZ(long k) { return cz(k); }
}
