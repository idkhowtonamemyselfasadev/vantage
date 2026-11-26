package dev.vantage.module.finder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;

/**
 * Respawn anchors in loaded chunks: highlight, a tracer to each one, arrow to the nearest and a chat ping per group. Min group keeps
 * only anchors that sit in a group of at least that many, where anchors within Group range blocks of each other (any direction, chained)
 * belong together: that is what an anchor farm or a base's anchor row looks like, a lone anchor in a cave is noise. 1 shows every anchor.
 */
public class AnchorFinder extends ArrowFinder {
    public final NumberSetting minGroup = add(new NumberSetting("Min group", "Only flag anchors in a group of at least this many. 1 = every anchor", 2, 1, 16, 1));
    public final NumberSetting groupRange = add(new NumberSetting("Group range", "Anchors this many blocks apart (chained) count as one group. 1 = touching", 3, 1, 16, 1));

    public AnchorFinder() {
        super("Anchor Finder", "Highlights respawn anchors with tracers", List.of("respawn_anchor"),
                "Respawn anchor", "ANCHOR", "anchor", "anchors", 8, 0xFFB05CFF);
        tracers.set(true);
    }

    @Override
    public String info() {
        return minGroup.i() > 1 ? String.valueOf(nearest.size()) : super.info();
    }

    /** Groups anchors within Group range of each other (chained) and keeps the groups big enough. */
    @Override
    protected List<BlockPos> filter(List<BlockPos> all) {
        return groups(all, minGroup.i(), groupRange.i());
    }

    /** Anchors that sit in a group of at least {@code need}, two anchors at most {@code range} blocks apart on any axis being linked. */
    public static List<BlockPos> groups(List<BlockPos> all, int need, int range) {
        if (need <= 1 || all.size() < need) return need <= 1 ? all : List.of();
        Set<BlockPos> left = new HashSet<>(all);
        List<BlockPos> out = new ArrayList<>();
        while (!left.isEmpty()) {
            BlockPos seed = left.iterator().next();
            List<BlockPos> group = new ArrayList<>();
            ArrayDeque<BlockPos> todo = new ArrayDeque<>();
            todo.add(seed);
            left.remove(seed);
            while (!todo.isEmpty()) {
                BlockPos p = todo.poll();
                group.add(p);
                for (var it = left.iterator(); it.hasNext();) {
                    BlockPos q = it.next();
                    if (Math.abs(q.getX() - p.getX()) <= range && Math.abs(q.getY() - p.getY()) <= range && Math.abs(q.getZ() - p.getZ()) <= range) { it.remove(); todo.add(q); }
                }
            }
            if (group.size() >= need) out.addAll(group);
        }
        return out;
    }
}
