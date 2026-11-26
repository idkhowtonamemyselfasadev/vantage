package dev.vantage.module.finder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * One pile of containers lying across a chunk border. Containers (chest, trapped chest, barrel, shulker box) belong to one pile when
 * they are linked by steps of at most {@link #LINK} blocks. A pile whose chunks each hold too few to be a stash, but which holds enough
 * as a whole, is scored once, by the chunk holding most of it (a tie: the smaller chunk key), so every chunk of it agrees on which.
 */
public final class ChunkPile {
    /** Largest gap (on every axis) between two containers of the same pile. */
    public static final int LINK = 4;
    /** A pile is not followed past this many containers. */
    private static final int MAX = 256;

    /** One pile: its containers by chunk key. */
    public record Pile(Map<Long, List<BlockPos>> byChunk) {
        public List<BlockPos> outside(long chunk) {
            List<BlockPos> out = new ArrayList<>();
            byChunk.forEach((k, l) -> { if (k != chunk) out.addAll(l); });
            return out;
        }
    }

    /**
     * The piles that hold containers of the home chunk and cross a border. {@code containers} gives a chunk's containers (empty when it
     * has none or is not loaded).
     */
    public static List<Pile> crossing(long home, LongFunction<List<BlockPos>> containers) {
        Map<Long, List<BlockPos>> seenChunks = new java.util.HashMap<>();
        LongFunction<List<BlockPos>> of = k -> seenChunks.computeIfAbsent(k, containers::apply);
        Set<BlockPos> done = new HashSet<>();
        List<Pile> out = new ArrayList<>();
        for (BlockPos start : of.apply(home)) {
            if (!done.add(start)) continue;
            Map<Long, List<BlockPos>> pile = new LinkedHashMap<>();
            ArrayDeque<BlockPos> todo = new ArrayDeque<>();
            todo.add(start);
            int n = 0;
            while (!todo.isEmpty() && n < MAX) {
                BlockPos p = todo.poll();
                n++;
                pile.computeIfAbsent(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), k -> new ArrayList<>()).add(p);
                for (int cx = (p.getX() - LINK) >> 4; cx <= (p.getX() + LINK) >> 4; cx++) {
                    for (int cz = (p.getZ() - LINK) >> 4; cz <= (p.getZ() + LINK) >> 4; cz++) {
                        for (BlockPos o : of.apply(ChunkPos.asLong(cx, cz))) {
                            if (Math.abs(o.getX() - p.getX()) <= LINK && Math.abs(o.getY() - p.getY()) <= LINK && Math.abs(o.getZ() - p.getZ()) <= LINK && done.add(o)) todo.add(o);
                        }
                    }
                }
            }
            if (pile.size() > 1) out.add(new Pile(pile));
        }
        return out;
    }

    /**
     * The chunk that scores a split pile, or null when the pile needs no help: some chunk of it is a stash on its own ({@code min} or
     * more), or all of it together is not. {@code weight} counts one container (a shulker box may count double).
     */
    public static Long owner(Pile pile, LongFunction<List<BlockPos>> containers, java.util.function.ToIntFunction<BlockPos> weight, int min) {
        int total = 0, most = 0;
        Long owner = null;
        for (var e : pile.byChunk().entrySet()) {
            int whole = 0, here = 0;
            for (BlockPos p : containers.apply(e.getKey())) whole += weight.applyAsInt(p);
            if (whole >= min) return null;
            for (BlockPos p : e.getValue()) here += weight.applyAsInt(p);
            total += here;
            if (here > most || here == most && owner != null && e.getKey() < owner) { most = here; owner = e.getKey(); }
        }
        return total >= min ? owner : null;
    }

    public static boolean container(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity;
    }

    /** The containers of the loaded chunks, as {@link #crossing} reads them; {@code counts} picks which. */
    public static LongFunction<List<BlockPos>> in(Level level, java.util.function.Predicate<BlockEntity> counts) {
        Map<Long, List<BlockPos>> cache = new java.util.HashMap<>();
        return k -> cache.computeIfAbsent(k, key -> {
            if (!(level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false) instanceof LevelChunk c)) return List.of();
            List<BlockPos> out = new ArrayList<>();
            for (BlockEntity be : c.getBlockEntities().values()) if (container(be) && counts.test(be)) out.add(be.getBlockPos());
            out.sort(null); // the answer never depends on the map's order
            return out;
        });
    }

    private ChunkPile() {}
}
