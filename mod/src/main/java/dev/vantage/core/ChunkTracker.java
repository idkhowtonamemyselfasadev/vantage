package dev.vantage.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.vantage.core.util.Dims;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * New/old chunk detection by liquid flow, shared by New Chunks and Trail Finder.
 *
 * Flowing liquid on arrival alone does not prove a visit: when a freshly generated chunk is first made tickable the server runs one
 * step of every marked spring and aquifer edge before it sends the chunk, so new land often arrives with a little flowing liquid.
 * What takes time is a flow that has run on. A chunk is OLD when it arrives with liquid at least two steps from where it started and
 * with no flowing liquid still hanging over air (a flow in mid-fall is one the chunk has not been ticked long enough to finish).
 * Liquid that starts or goes on flowing while we watch makes a chunk NEW. Everything else stays unknown.
 */
public final class ChunkTracker {
    private static final Map<String, Set<Long>> NEW = new HashMap<>();
    private static final Map<String, Set<Long>> OLD = new HashMap<>();
    private static int version;

    public static int version() {
        return version;
    }

    public static String dim() {
        return Dims.id(Minecraft.getInstance().level);
    }

    public static Set<Long> newChunks() {
        return NEW.computeIfAbsent(dim(), k -> new HashSet<>());
    }

    public static Set<Long> oldChunks() {
        return OLD.computeIfAbsent(dim(), k -> new HashSet<>());
    }

    public static boolean isNew(int cx, int cz) {
        return newChunks().contains(ChunkPos.asLong(cx, cz));
    }

    public static boolean isOld(int cx, int cz) {
        return oldChunks().contains(ChunkPos.asLong(cx, cz));
    }

    public static void onBlock(BlockPos pos, BlockState state) {
        FluidState f = state.getFluidState();
        if (f.isEmpty() || f.isSource()) return;
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        // Only flow that spreads from a neighbouring liquid counts (rules out buckets being emptied by players
        // as best we can: a lone flowing block with no liquid next to it is ignored).
        boolean fed = false;
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            if (!level.getFluidState(pos.relative(d)).isEmpty()) { fed = true; break; }
        }
        if (!fed) return;
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (oldChunks().contains(key)) return;
        if (newChunks().add(key)) version++;
    }

    public static void onChunkData(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        if (newChunks().contains(key) || oldChunks().contains(key)) return;
        if (arrivedOld(chunk)) {
            oldChunks().add(key);
            version++;
        }
    }

    /** One flowing block of an arriving chunk, as the rule sees it. */
    public static boolean ranFar(int amount, boolean falling, int dropOff) {
        return !falling && amount <= 8 - 2 * dropOff;
    }

    private static boolean arrivedOld(LevelChunk chunk) {
        boolean nether = chunk.getLevel().dimension() == Level.NETHER, far = false;
        LevelChunkSection[] secs = chunk.getSections();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        for (int si = 0; si < secs.length; si++) {
            LevelChunkSection sec = secs[si];
            // palette check only, no walk over 4096 blocks
            if (sec.hasOnlyAir() || !sec.getStates().maybeHas(ChunkTracker::flowing)) continue;
            // the palette says maybe (a big "global" palette always does): confirm block by block
            int base = chunk.getSectionYFromSectionIndex(si) << 4;
            for (int i = 0; i < 4096; i++) {
                BlockState s = sec.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                if (!flowing(s)) continue;
                int y = base + (i >> 8);
                below.set(chunk.getPos().getMinBlockX() + (i & 15), y - 1, chunk.getPos().getMinBlockZ() + ((i >> 4) & 15));
                if (y > chunk.getMinY() && chunk.getBlockState(below).isAir()) return false; // still falling: not ticked for long
                FluidState f = s.getFluidState();
                boolean falling = f.hasProperty(FlowingFluid.FALLING) && f.getValue(FlowingFluid.FALLING);
                far |= ranFar(f.getAmount(), falling, f.getType().isSame(Fluids.LAVA) && !nether ? 2 : 1);
            }
        }
        return far;
    }

    private static boolean flowing(BlockState s) {
        FluidState f = s.getFluidState();
        return !f.isEmpty() && !f.isSource();
    }

    public static void clearAll() {
        NEW.clear();
        OLD.clear();
        version++;
    }

    private ChunkTracker() {}
}
