package dev.vantage.core.util;

import net.minecraft.util.Mth;

/**
 * Distances and bearings in blocks and degrees. Distances are exact doubles; rounding for display belongs to {@link Fmt}, so a
 * 12.6 block distance is no longer "12" in one module and "13" in another. Bearings use the Minecraft yaw convention: 0 looks
 * south (+z), 90 looks west (-x), -90 looks east, 180 and -180 look north.
 */
public final class Dist {
    /** Horizontal distance in blocks from (dx, dz) to the origin. */
    public static double dist2(double dx, double dz) {
        return Math.sqrt(dist2Sq(dx, dz));
    }

    /** Squared horizontal distance of (dx, dz). */
    public static double dist2Sq(double dx, double dz) {
        return dx * dx + dz * dz;
    }

    /** Horizontal distance in blocks between two points. */
    public static double dist2(double x1, double z1, double x2, double z2) {
        return dist2(x2 - x1, z2 - z1);
    }

    /** Straight-line distance in blocks of (dx, dy, dz). */
    public static double dist3(double dx, double dy, double dz) {
        return Math.sqrt(dist3Sq(dx, dy, dz));
    }

    /** Squared straight-line distance of (dx, dy, dz). */
    public static double dist3Sq(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
    }

    /** Straight-line distance in blocks between two points. */
    public static double dist3(double x1, double y1, double z1, double x2, double y2, double z2) {
        return dist3(x2 - x1, y2 - y1, z2 - z1);
    }

    /** Squared distance from a point to a box (min and max corners, either order of faces not needed); 0 inside or on the surface. */
    public static double toBoxSq(double x, double y, double z, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX - x, 0), x - maxX), dy = Math.max(Math.max(minY - y, 0), y - maxY), dz = Math.max(Math.max(minZ - z, 0), z - maxZ);
        return dist3Sq(dx, dy, dz);
    }

    /** Distance in blocks from a point to a box; 0 inside or on the surface, the face distance beside a face, the diagonal past a corner. */
    public static double toBox(double x, double y, double z, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return Math.sqrt(toBoxSq(x, y, z, minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** Bearing in degrees (-180 to 180, yaw convention, 0 = south) of the direction (dx, dz); 0 for (0, 0). */
    public static double bearing(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Bearing in degrees (-180 to 180, yaw convention) from one point to another. */
    public static double bearing(double fromX, double fromZ, double toX, double toZ) {
        return bearing(toX - fromX, toZ - fromZ);
    }

    /** Difference between two yaws as the shortest turn, -180 to 180 (from minus to, wrapped). */
    public static double angleDiff(double from, double to) {
        return Mth.wrapDegrees(to - from);
    }

    private Dist() {}
}
