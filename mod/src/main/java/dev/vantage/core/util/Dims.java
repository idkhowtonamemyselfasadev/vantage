package dev.vantage.core.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Dimension ids and the overworld/nether scale. A dimension id is the full identifier text ({@code minecraft:overworld}); the
 * short form without the namespace is accepted where noted.
 *
 * <p>Nether rule (one for the whole code base): the conversion is exact ({@code / 8.0} and {@code * 8.0}) and rounds nothing.
 * Whoever needs a block floors the result, which is what the game's portal linking does ({@code BlockPos.containing}); whoever
 * prints a number rounds it for display. The two copies this replaces used exactly that split (the HUD printed the exact value with
 * {@code %.0f}, the waypoints used the exact value and added 0.5 to land mid-block), so neither changes. The block forms
 * ({@link #overworldToNetherBlock}) floor, so -1 is -1 and -8 is -1 and -9 is -2, the same as the portal.
 *
 * <p>"No world" has one answer, {@link #NONE}, the empty string. Callers whose old answer was written into a file name keep
 * their own spelling (see the survey, D3), because a changed file name strands the user's saved data.
 */
public final class Dims {
    /** The nether coordinate scale of the overworld: 8 overworld blocks are 1 nether block. */
    public static final double NETHER_SCALE = 8.0;
    /** Dimension id when there is no world loaded: the empty string. */
    public static final String NONE = "";
    /** Full id of the overworld. */
    public static final String OVERWORLD = "minecraft:overworld";
    /** Full id of the nether. */
    public static final String NETHER = "minecraft:the_nether";
    /** Full id of the end. */
    public static final String END = "minecraft:the_end";

    /** Id of a dimension key as text ({@code minecraft:overworld}); {@link #NONE} for null. */
    public static String id(ResourceKey<Level> dimension) {
        return dimension == null ? NONE : dimension.identifier().toString();
    }

    /** Id of the dimension of a level; {@link #NONE} when the level is null (no world). */
    public static String id(Level level) {
        return level == null ? NONE : id(level.dimension());
    }

    /** True for {@code minecraft:the_nether} and for the short {@code the_nether}. */
    public static boolean isNether(String id) {
        return NETHER.equals(id) || "the_nether".equals(id);
    }

    /** True for {@code minecraft:overworld} and for the short {@code overworld}. */
    public static boolean isOverworld(String id) {
        return OVERWORLD.equals(id) || "overworld".equals(id);
    }

    /** Exact nether coordinate of an overworld coordinate: divided by 8, no rounding. */
    public static double overworldToNether(double coord) {
        return coord / NETHER_SCALE;
    }

    /** Exact overworld coordinate of a nether coordinate: times 8, no rounding. */
    public static double netherToOverworld(double coord) {
        return coord * NETHER_SCALE;
    }

    /** Nether block of an overworld block: floor division by 8 (the game's portal rule), so -1 and -8 give -1, -9 gives -2. */
    public static int overworldToNetherBlock(int block) {
        return Math.floorDiv(block, 8);
    }

    /** Lowest overworld block of a nether block: times 8 (the nether block covers that block and the next 7). */
    public static int netherToOverworldBlock(int block) {
        return block * 8;
    }

    /** The id as part of a file name: letters, digits, dot, dash and underscore stay, everything else (the colon) becomes an underscore. */
    public static String fileKey(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Dims() {}
}
