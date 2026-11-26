package dev.vantage.core.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

/**
 * What keeps chunks ticking that is not somebody else: the server's own spawn area, and the chunks around where the user has been.
 * A chunk the user's own presence ticked looks "old" later, and mobs moving in it just after the user left look like another
 * player's simulation. Finders that read other players off ticking use this to leave those chunks out.
 */
public final class OwnTicks {
    /** Chunks within spawn range of the world spawn that the server keeps loaded for everyone. */
    public static final int SPAWN_RADIUS = 6;
    /** How long after the user left a spot its chunks may still tick (ms). */
    public static final long RECENT_MS = 8_000;

    private static final Map<String, Set<Long>> SIM = new HashMap<>();
    private static final Map<String, Map<Long, Long>> RECENT = new HashMap<>();
    private static long lastKey = Long.MIN_VALUE;

    private static int simRadius(Minecraft mc) {
        int s = mc.level == null ? 5 : mc.level.getServerSimulationDistance();
        return Math.max(3, Math.min(s > 0 ? s : 5, 12)) + 1;
    }

    /** Call every tick (cheap): learns where the user is. */
    public static void note(Minecraft mc, String dim, long now) {
        if (mc.player == null || mc.level == null) return;
        ChunkPos me = mc.player.chunkPosition();
        long k = me.toLong();
        RECENT.computeIfAbsent(dim, d -> new HashMap<>()).put(k, now);
        if (k == lastKey) return;
        lastKey = k;
        int r = simRadius(mc);
        Set<Long> sim = SIM.computeIfAbsent(dim, d -> new HashSet<>());
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) sim.add(ChunkPos.asLong(me.x + dx, me.z + dz));
        RECENT.get(dim).values().removeIf(t -> now - t > RECENT_MS * 4);
    }

    /** The user's presence ticked this chunk at some point this session. */
    public static boolean ownedEver(String dim, int cx, int cz) {
        Set<Long> s = SIM.get(dim);
        return s != null && s.contains(ChunkPos.asLong(cx, cz));
    }

    /** The user was within simulation range of this chunk in the last few seconds. */
    public static boolean ownedRecently(Minecraft mc, String dim, int cx, int cz, long now) {
        return ownedRecently(dim, cx, cz, now, simRadius(mc));
    }

    /** Same with the caller's own reach in chunks (the Simulation Ring uses its own simulation distance + 1). */
    public static boolean ownedRecently(String dim, int cx, int cz, long now, int r) {
        Map<Long, Long> m = RECENT.get(dim);
        if (m == null) return false;
        for (var e : m.entrySet()) {
            if (now - e.getValue() > RECENT_MS) continue;
            if (Math.abs(ChunkPos.getX(e.getKey()) - cx) <= r && Math.abs(ChunkPos.getZ(e.getKey()) - cz) <= r) return true;
        }
        return false;
    }

    /** The world spawn's own always-loaded area. */
    public static boolean spawnArea(Minecraft mc, int cx, int cz) {
        if (mc.level == null) return false;
        var pos = mc.level.getLevelData().getRespawnData().pos();
        return Math.abs((pos.getX() >> 4) - cx) <= SPAWN_RADIUS && Math.abs((pos.getZ() >> 4) - cz) <= SPAWN_RADIUS;
    }

    /** New world: forget. */
    public static void reset() {
        SIM.clear();
        RECENT.clear();
        lastKey = Long.MIN_VALUE;
    }

    private OwnTicks() {}
}
