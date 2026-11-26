package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What a pile of containers is for. Plenty of chests alone proves nothing: farms, sorters, grinders and shops all have them.
 *
 * First the jobs a pile can have, any one of which explains it: signs on the chests (shop), a spawner in the chunk (grinder), hoppers
 * and comparators (sorter), hoppers feeding most of it (farm), workstations and beds around it (base storage). A stash is what's left,
 * and only if it's kept out of the way: each sign has odds, they combine as 1 - (1-a)(1-b), and 50% is the line. An open pile under
 * the sky stays below it on purpose. Contents aren't sent to the client, so nobody can see if it's full or empty.
 */
public final class StashLogic {
    public static final String STASH = "Stash";
    public record Verdict(String kind, int pct, int storage, List<String> why) {}

    private static final int MANY = 12, HUGE = 27, BURIED = 4, CLOSE = 2;
    private static final int MIN_STORAGE = 4, COMPARATORS = 4, WORKSTATIONS = 3;

    private static boolean sign(BlockState s) {
        return s.is(BlockTags.ALL_SIGNS);
    }

    /** Null when the chunk holds too little storage to say anything. hidden: containers only the chunk data admits to. */
    public static Verdict judge(Level level, LevelChunk chunk, int surface, int hidden) {
        return judge(level, chunk.getBlockEntities().values(), surface, hidden, false);
    }

    /** The same judgement over any set of block entities: a pile across a chunk border brings its containers and machines from the chunk next door. */
    public static Verdict judge(Level level, java.util.Collection<BlockEntity> entities, int surface, int hidden, boolean split) {
        String where = split ? " containers across a chunk border" : " containers in one chunk";
        List<BlockPos> storage = new ArrayList<>();
        int shulkers = 0, comparators = 0, workstations = 0;
        List<BlockPos> spawners = new ArrayList<>(), hoppers = new ArrayList<>();
        for (BlockEntity be : entities) {
            if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity) storage.add(be.getBlockPos());
            if (be instanceof ShulkerBoxBlockEntity) shulkers++;
            if (be instanceof ComparatorBlockEntity) comparators++;
            if (be instanceof SpawnerBlockEntity) spawners.add(be.getBlockPos());
            if (be instanceof HopperBlockEntity) hoppers.add(be.getBlockPos());
            if (be instanceof AbstractFurnaceBlockEntity || be instanceof BedBlockEntity || be instanceof EnchantingTableBlockEntity || be instanceof BrewingStandBlockEntity) workstations++;
        }
        int n = storage.size() + hidden;
        if (n < MIN_STORAGE) return null;
        int fed = 0, signs = 0, close = 0, underSky = 0, buried = 0;
        for (BlockPos p : storage) {
            boolean hopper = false, labelled = false;
            for (Direction d : Direction.values()) {
                BlockState s = level.getBlockState(p.relative(d));
                hopper |= s.is(Blocks.HOPPER);
                labelled |= sign(s);
            }
            if (hopper) fed++;
            if (labelled) signs++;
            for (BlockPos o : storage) {
                if (o != p && Math.abs(o.getX() - p.getX()) <= CLOSE && Math.abs(o.getY() - p.getY()) <= CLOSE && Math.abs(o.getZ() - p.getZ()) <= CLOSE) { close++; break; }
            }
            if (p.getY() + 1 >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ())) underSky++;
            if (p.getY() < surface - BURIED) buried++;
        }
        int seen = Math.max(1, storage.size());
        if (signs * 10 >= seen * 3) return new Verdict("Shop", 0, n, List.of(signs + " of " + seen + " containers have signs on them"));
        if (grinder(spawners, hoppers)) return new Verdict("Grinder", 0, n, List.of("a spawner and a hopper in the chunk: looks like a mob grinder"));
        if (comparators >= COMPARATORS && fed * 4 >= seen) return new Verdict("Sorter", 0, n, List.of("hoppers and " + comparators + " comparators next to these: looks like a sorter"));
        if (fed * 2 >= seen) return new Verdict("Farm storage", 0, n, List.of("hoppers run into " + fed + " of " + seen + " containers"));
        if (workstations >= WORKSTATIONS) return new Verdict("Base storage", 0, n, List.of(workstations + " workstations and beds around it: looks lived in"));

        List<String> why = new ArrayList<>();
        double miss = 1;
        if (n >= HUGE) { miss *= 1 - 0.40; why.add(n + where); }
        else if (n >= MANY) { miss *= 1 - 0.30; why.add(n + where); }
        if (fed == 0) { miss *= 1 - 0.15; why.add("nothing feeds them (no hopper): filled by hand, or empty?"); }
        if (close * 10 >= seen * 7) { miss *= 1 - 0.10; why.add("packed together"); }
        if (buried * 2 >= seen) { miss *= 1 - 0.35; why.add("below the surface"); }
        else if (underSky * 2 < seen) { miss *= 1 - 0.15; why.add("kept under cover"); }
        if (shulkers >= 2) { miss *= 1 - 0.30; why.add(shulkers + " shulkers (often packed loot)"); }
        if (hidden > 0) { miss *= 1 - 0.50; why.add(hidden + " hidden from you by the server"); }
        int pct = (int) Math.round((1 - miss) * 100);
        return new Verdict(pct >= 50 ? STASH : "Storage", pct, n, why);
    }

    /** Pure rule: a mob grinder is a spawner with a hopper catching its drops close by. A spawner alone is a dungeon or a mineshaft's, and hoppers under chests are storage plumbing. */
    public static boolean grinder(List<BlockPos> spawners, List<BlockPos> hoppers) {
        for (BlockPos s : spawners) for (BlockPos h : hoppers) {
            if (Math.abs(s.getX() - h.getX()) <= SPAWNER_HOPPER && Math.abs(s.getZ() - h.getZ()) <= SPAWNER_HOPPER && Math.abs(s.getY() - h.getY()) <= SPAWNER_HOPPER + 4) return true;
        }
        return false;
    }

    private static final int SPAWNER_HOPPER = 12;

    private StashLogic() {}
}
