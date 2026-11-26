package dev.vantage.core.util;

import net.minecraft.util.Mth;

/**
 * Eight-way compass words and arrow angles. Words are {@code N NE E SE S SW W NW}. Each word covers 45 degrees centred on its
 * direction; the boundary between two words belongs to the one that is clockwise from yaw 0 going up (exactly 22.5 is SW, exactly
 * -22.5 is S), because the old copies used {@code round(yaw / 45)}, which rounds halves up.
 */
public final class Compass {
    private static final String[] WORDS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    /** Compass word of a yaw in degrees; any value works (720 is 0), NaN reads as "S". 0 = S, 90 = W, 180 = N, -90 = E. */
    public static String name(double yaw) {
        return WORDS[Math.floorMod(Math.round(yaw / 45.0), 8)];
    }

    /** Compass word of the direction from a point to another, given as the offset (dx, dz); (0, 0) reads as "S". */
    public static String name(double dx, double dz) {
        return name(Dist.bearing(dx, dz));
    }

    /** Angle in degrees to turn an arrow that points up on the screen (positive is the way the old HUD arrow turned: bearing minus view yaw) so that it points at a target with this bearing when the viewer looks along viewYaw; -180 to 180. */
    public static double arrowAngle(double bearing, double viewYaw) {
        return Mth.wrapDegrees(bearing - viewYaw);
    }

    private Compass() {}
}
