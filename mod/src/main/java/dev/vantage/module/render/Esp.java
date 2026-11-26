package dev.vantage.module.render;

import dev.vantage.render.R3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared box/fill/tracer drawing for the ESP modules. */
final class Esp {
    static void draw(R3 r, AABB b, int color, String mode, int fillAlpha, float width, boolean tracer) {
        // width / fillAlpha are the module's own settings, relative to their defaults (2 and 20): the look is EspStyle's
        EspStyle.box(r, b, color, !mode.equals("Fill"), !mode.equals("Outline"), width, fillAlpha / 20f, true);
        if (tracer) EspStyle.tracer(r, b.getCenter(), color);
    }

    static Vec3 center(AABB b) {
        return b.getCenter();
    }

    private Esp() {}
}
