package dev.vantage.core;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.util.Dims;
import net.minecraft.client.Minecraft;

/**
 * Find Log (beta): everything Base Finder and Stash Finder marked this session, newest last. Memory only, never saved, never sent.
 * Fed by {@code FindEvents.marked}; recording only happens while the Find Log module is on (and so only while beta is unlocked).
 */
public final class FindLog {
    private FindLog() {}

    public record Entry(String kind, String detail, double x, double y, double z, String dim, long time) {
        /** Blocks from the player to the mark right now, or -1 when there is no player or it is another dimension. */
        public int distance() {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || !Dims.id(mc.level).equals(dim)) return -1;
            return (int) Math.round(Math.sqrt(mc.player.distanceToSqr(x, y, z)));
        }

        /** "x 120 y -40 z 83". */
        public String coords() {
            return "x " + (int) Math.floor(x) + " y " + (int) Math.floor(y) + " z " + (int) Math.floor(z);
        }
    }

    private static final int MAX = 500;
    private static final List<Entry> ENTRIES = new ArrayList<>();

    /** Records a mark; the same kind within 8 blocks of an earlier one is not a new find. */
    public static synchronized boolean add(String kind, String detail, double x, double y, double z) {
        var mc = Minecraft.getInstance();
        String dim = mc.level == null ? "?" : Dims.id(mc.level);
        for (Entry e : ENTRIES) if (e.kind.equals(kind) && e.dim.equals(dim) && Math.abs(e.x - x) < 8 && Math.abs(e.y - y) < 8 && Math.abs(e.z - z) < 8) return false;
        ENTRIES.add(new Entry(kind, detail == null ? "" : detail, x, y, z, dim, System.currentTimeMillis()));
        if (ENTRIES.size() > MAX) ENTRIES.remove(0);
        return true;
    }

    public static synchronized List<Entry> list() {
        return new ArrayList<>(ENTRIES);
    }

    public static synchronized int count(String kind) {
        int n = 0;
        for (Entry e : ENTRIES) if (kind == null || e.kind.equals(kind)) n++;
        return n;
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    /** "12s", "4m", "1h 5m": how long ago. */
    public static String ago(long time) {
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        return s / 3600 + "h " + (s % 3600) / 60 + "m";
    }
}
