package dev.vantage.module.render;

/**
 * The plain arithmetic of Free Look, kept free of game classes so it can be unit tested: angle wrap and clamp, the mouse turn, and
 * where an orbit camera sits. Angles are degrees in Minecraft's convention (yaw 0 looks along +z, yaw 90 along -x, pitch +90 straight down).
 */
public final class FreeLookMath {
    /** Degrees per mouse unit: the same factor vanilla's {@code Entity.turn} uses. */
    public static final float TURN = 0.15f;

    private FreeLookMath() {}

    /** Wraps an angle into [-180, 180). */
    public static float wrapDegrees(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** Pitch limited to straight up and straight down. */
    public static float clampPitch(float pitch) {
        return Math.max(-90f, Math.min(90f, pitch));
    }

    /** New yaw after a mouse move of {@code dx} units (wrapped). */
    public static float turnYaw(float yaw, double dx) {
        return wrapDegrees(yaw + (float) dx * TURN);
    }

    /** New pitch after a mouse move of {@code dy} units (clamped to +-90). */
    public static float turnPitch(float pitch, double dy) {
        return clampPitch(pitch + (float) dy * TURN);
    }

    /** The direction a camera with this yaw and pitch looks in (unit vector), the same as vanilla's {@code Vec3.directionFromRotation}. */
    public static double[] look(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double cp = Math.cos(p);
        return new double[] {-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp};
    }

    /** Where the camera sits: {@code distance} blocks behind the pivot (the player's eyes) along the view direction. */
    public static double[] orbit(double px, double py, double pz, float yaw, float pitch, double distance) {
        double[] l = look(yaw, pitch);
        return new double[] {px - l[0] * distance, py - l[1] * distance, pz - l[2] * distance};
    }
}
