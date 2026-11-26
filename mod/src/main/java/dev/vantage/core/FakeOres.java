package dev.vantage.core;

import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Spots servers that fill the rock with fake ores (Paper anti-xray engine-mode 2): one section holding more rare ore than
 * worldgen ever puts there. From then on hidden-list blocks only count where they touch air, because the fakes are only
 * placed in blocks nobody can see. Exposed blocks are real, so this only throws bad data away.
 */
public final class FakeOres implements dev.vantage.core.event.Events.Owner {
    /** Paper's fake list as ore families. Real rock has one or two of them in a section, the fakes have them all. */
    private static final List<Set<Block>> FAMILIES = List.of(Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
            Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE), Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
            Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE), Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE));
    private static final int FAMILY_MIN = 20, FAMILIES_NEEDED = 5;
    /** Debris comes in veins of a few blocks, so this many in one section can't be real. */
    private static final int DEBRIS_LIMIT = 24;
    private static final int STRIKES = 2;

    private static int strikes;
    private static boolean detected;
    /** Highest seen, for the test log: how far real rock stays from the limits. */
    public static int maxFamilies, maxDebris;

    public static boolean detected() {
        return detected;
    }

    /** Called while scanning. Reads the section's palette counts, no block by block walk. */
    public static void look(LevelChunkSection s) {
        if (detected) return;
        int[] family = new int[FAMILIES.size()];
        int[] debris = new int[1];
        s.getStates().count((st, c) -> {
            Block b = st.getBlock();
            if (b == Blocks.ANCIENT_DEBRIS) debris[0] += c;
            for (int i = 0; i < family.length; i++) if (FAMILIES.get(i).contains(b)) family[i] += c;
        });
        int kinds = 0;
        for (int n : family) if (n >= FAMILY_MIN) kinds++;
        maxFamilies = Math.max(maxFamilies, kinds);
        maxDebris = Math.max(maxDebris, debris[0]);
        if ((kinds < FAMILIES_NEEDED && debris[0] < DEBRIS_LIMIT) || ++strikes < STRIKES) return;
        detected = true;
        Vantage.LOG.info("Server fakes ores, only exposed blocks count now");
        Toasts.warn("Fake ores", "This server fakes ores. Only exposed blocks count now");
    }

    public static boolean exposed(BlockPos p) {
        var level = Minecraft.getInstance().level;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            BlockState s = level.getBlockState(m.setWithOffset(p, d));
            // void air = a chunk that isn't loaded yet, which says nothing about this block
            if (!s.isSolidRender() && !s.is(Blocks.VOID_AIR)) return true;
        }
        return false;
    }

    /** Rig only: as if the server's ores had (or had not) been seen to be fake. */
    public static void forceForTest(boolean fake) {
        detected = fake;
        strikes = fake ? STRIKES : 0;
    }

    public static void reset() {
        strikes = 0;
        detected = false;
    }

    private static final FakeOres OWNER = new FakeOres();
    private static final dev.vantage.core.event.Events.Group GROUP = new dev.vantage.core.event.Events.Group(OWNER);

    private FakeOres() {}

    @Override
    public String ownerName() {
        return "fake ore detector";
    }

    /** Before every module, so none of them reads a verdict that belongs to the world that is gone. */
    @Override
    public int ownerRank() {
        return 0;
    }

    /**
     * Called once at start-up. Anti-xray is a setting of a world (Paper configures it per world), so what was learned about one is
     * dropped when the client leaves it or changes dimension, not only when it goes back to the title screen: the Nether of a server
     * need not fake ores because its overworld does. The next world is judged again from its own sections.
     */
    public static void init() {
        if (GROUP.declared() > 0) return;
        GROUP.listen(dev.vantage.core.event.WorldLeaveEvent.class, 100, e -> reset());
        GROUP.listen(dev.vantage.core.event.DimensionChangeEvent.class, 100, e -> reset());
        GROUP.attach();
    }
}
