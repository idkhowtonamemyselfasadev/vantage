package dev.vantage.module.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.vantage.core.util.ChunkMath;
import dev.vantage.core.util.Compass;
import dev.vantage.core.util.Dist;
import dev.vantage.core.util.Fmt;

/**
 * The game-free part of {@link ServiceDemo}: which block counts for which mode, the nearest found chunks, and the text lines. Kept
 * apart so it is unit tested without a Minecraft instance (a module class cannot be constructed in a test).
 */
final class ServiceDemoMath {
    static final List<String> MODES = List.of("Chest", "Barrel", "Spawner", "Shulker box");

    /** A found chunk with its distance (blocks, from the player to the chunk's middle) and the compass word towards it. */
    record Hit(int cx, int cz, double dist, String dir) {}

    private ServiceDemoMath() {}

    /** Whether a block (its registry path, e.g. "trapped_chest") is what the mode looks for. */
    static boolean matches(String mode, String blockPath) {
        return switch (mode) {
            case "Chest" -> blockPath.equals("chest") || blockPath.equals("trapped_chest");
            case "Barrel" -> blockPath.equals("barrel");
            case "Spawner" -> blockPath.equals("spawner");
            case "Shulker box" -> blockPath.endsWith("shulker_box");
            default -> false;
        };
    }

    /** The {@code limit} chunks of the set nearest to (px, pz), nearest first; ties go to the lower x, then the lower z. */
    static List<Hit> nearest(long[] keys, int count, double px, double pz, int limit) {
        List<Hit> all = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int cx = ChunkMath.keyX(keys[i]), cz = ChunkMath.keyZ(keys[i]);
            double dx = ChunkMath.centre(cx) - px, dz = ChunkMath.centre(cz) - pz;
            all.add(new Hit(cx, cz, Dist.dist2(dx, dz), Compass.name(dx, dz)));
        }
        all.sort(Comparator.comparingDouble(Hit::dist).thenComparingInt(Hit::cx).thenComparingInt(Hit::cz));
        return all.subList(0, Math.min(limit, all.size()));
    }

    static String countLine(int count) {
        return "Demo: " + count + (count == 1 ? " chunk" : " chunks");
    }

    /** "45 m NE", the second HUD line and the tail of a list line. */
    static String where(Hit h) {
        return Fmt.distance(h.dist) + " " + h.dir;
    }

    static String listLine(int rank, Hit h) {
        return rank + ". chunk " + h.cx + ", " + h.cz + ": " + where(h);
    }
}
