package dev.vantage.module.finder;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Is this a generated village? The client is not told, but every village meeting point has a bell, and village streets are dirt paths
 * (smooth sandstone in the desert). A bell with streets around it is read as a village; a lone bell is not. A player who copies both
 * is read as one too, so everything this switches off is small: the village's own furniture and its own villagers' chatter.
 */
public final class Villages {
    /** How far from its bell a village reaches, in chunks. */
    public static final int RADIUS = 5;
    /** Street blocks needed around the bell (the bell's chunk and the 8 around it). */
    public static final int STREET_MIN = 12;
    /** A find with more containers than this is not village furniture. */
    public static final int STORAGE_MAX = 3;
    /** A sound this far under the ground or deeper comes from under the village, not from it. */
    public static final int UNDER = 6;

    /** Blocks (of the ones Base Finder counts) that the village structure templates place. */
    private static final Set<Block> PLACES = Set.of(Blocks.FARMLAND, Blocks.CRAFTING_TABLE, Blocks.GRINDSTONE, Blocks.SMITHING_TABLE,
            Blocks.WHITE_STAINED_GLASS_PANE, Blocks.YELLOW_STAINED_GLASS_PANE, Blocks.ORANGE_STAINED_GLASS_PANE,
            Blocks.WHITE_GLAZED_TERRACOTTA, Blocks.YELLOW_GLAZED_TERRACOTTA, Blocks.ORANGE_GLAZED_TERRACOTTA, Blocks.LIME_GLAZED_TERRACOTTA, Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA,
            Blocks.WHITE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIGHT_GRAY_WOOL,
            Blocks.WHITE_CARPET, Blocks.YELLOW_CARPET, Blocks.ORANGE_CARPET, Blocks.RED_CARPET, Blocks.GREEN_CARPET, Blocks.BLUE_CARPET, Blocks.PURPLE_CARPET, Blocks.LIME_CARPET);

    public static boolean places(Block b) {
        return PLACES.contains(b);
    }

    /** Pure rule: a bell with this many street blocks around it. */
    public static boolean village(boolean bell, int streets) {
        return bell && streets >= STREET_MIN;
    }

    private static LevelChunk loaded(Level level, int cx, int cz) {
        return level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c ? c : null;
    }

    /** The meeting point of a village in this chunk. */
    public static boolean centre(Level level, int cx, int cz) {
        LevelChunk c = loaded(level, cx, cz);
        if (c == null) return false;
        BlockPos bell = null;
        for (BlockEntity be : c.getBlockEntities().values()) if (be instanceof BellBlockEntity) { bell = be.getBlockPos(); break; }
        if (bell == null) return false;
        int[] streets = {0};
        int mid = level.getSectionIndex(bell.getY());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            LevelChunk near = loaded(level, cx + dx, cz + dz);
            if (near == null) continue;
            for (int si = Math.max(0, mid - 1); si <= Math.min(near.getSections().length - 1, mid + 1); si++) {
                LevelChunkSection s = near.getSections()[si];
                if (s.hasOnlyAir() || !s.getStates().maybeHas(st -> st.is(Blocks.DIRT_PATH) || st.is(Blocks.SMOOTH_SANDSTONE))) continue;
                s.getStates().count((st, n) -> { if (st.is(Blocks.DIRT_PATH) || st.is(Blocks.SMOOTH_SANDSTONE)) streets[0] += n; });
            }
        }
        return village(true, streets[0]);
    }

    /** Is a village's meeting point within reach of this chunk? Only loaded chunks can answer. */
    public static boolean near(Level level, int cx, int cz) {
        for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++) if (centre(level, cx + dx, cz + dz)) return true;
        return false;
    }

    /** A sound from a village's own ground level: near a meeting point and not deep under the surface. */
    public static boolean surfaceOf(Level level, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), cx = bx >> 4, cz = bz >> 4;
        if (loaded(level, cx, cz) == null) return false;
        int ground = Integer.MAX_VALUE, ox = cx << 4, oz = cz << 4;
        for (int[] o : new int[][] {{0, 0}, {15, 0}, {0, 15}, {15, 15}, {8, 8}}) ground = Math.min(ground, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ox + o[0], oz + o[1]));
        return y >= ground - UNDER && near(level, cx, cz);
    }

    private Villages() {}
}
