package dev.vantage.module.cosmetic;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/** When a player pops a totem, a translucent copy of their shape rises and fades. Read from the totem event the server sends anyway. */
public class TotemSpirit extends Module {
    public final NumberSetting riseTime = add(new NumberSetting("Rise time", "Seconds until the spirit is gone", 2.4, 0.5, 6, 0.1));
    public final NumberSetting ascent = add(new NumberSetting("Ascent", "Blocks it rises", 1.6, 0.2, 4, 0.1));
    public final ModeSetting style = add(new ModeSetting("Style", "Soft glass body or a wire outline", "Glass", "Glass", "Wire"));
    public final NumberSetting opacity = add(new NumberSetting("Opacity", "How solid it starts", 0.65, 0.1, 1, 0.05));
    public final BoolSetting own = add(new BoolSetting("Own pops", "A spirit for your own totem too", false));
    public final BoolSetting throughWalls = add(new BoolSetting("Through walls", "Visible behind blocks", false));
    public static int spirits, drawn;
    private record Spirit(double x, double y, double z, boolean crouch, long t0) {}
    private final List<Spirit> live = new ArrayList<>();

    public TotemSpirit() {
        super("Totem Spirit", "A ghost rises from a player who pops a totem", Category.COSMETIC);
        // the totem event (35) the server broadcasts, read before the game plays it
        listen(dev.vantage.core.event.PacketInEvent.class, e -> {
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundEntityEventPacket p && p.getEventId() == 35 && mc.level != null) pop(p.getEntity(mc.level));
        });
    }

    public void pop(Entity e) {
        if (!(e instanceof LivingEntity le) || mc.player == null) return;
        if (e == mc.player && !own.on()) return;
        if (e.distanceToSqr(mc.player) > 9216) return;
        if (live.size() >= 12) live.remove(0);
        live.add(new Spirit(e.getX(), e.getY(), e.getZ(), le.isCrouching(), System.currentTimeMillis()));
        spirits++;
    }

    @Override
    public void onRender3D(R3 r) {
        if (live.isEmpty()) return;
        long now = System.currentTimeMillis();
        double life = riseTime.get() * 1000;
        live.removeIf(s -> now - s.t0 > life);
        boolean xray = throughWalls.on(), glass = style.is("Glass");
        for (Spirit s : live) {
            float t = (float) ((now - s.t0) / life);
            float fade = (float) Math.pow(1 - t, 1.2);
            int a = Math.round(255 * opacity.f() * fade);
            if (a < 3) continue;
            double y = s.y + ascent.get() * (1 - (1 - t) * (1 - t));
            double drop = s.crouch ? 0.2 : 0;
            AABB[] parts = {
                new AABB(s.x - 0.25, y + 1.5 - drop, s.z - 0.25, s.x + 0.25, y + 2 - drop, s.z + 0.25), // head
                new AABB(s.x - 0.25, y + 0.75, s.z - 0.125, s.x + 0.25, y + 1.5 - drop, s.z + 0.125), // body
                new AABB(s.x - 0.5, y + 0.8, s.z - 0.125, s.x - 0.25, y + 1.5 - drop, s.z + 0.125), // arms
                new AABB(s.x + 0.25, y + 0.8, s.z - 0.125, s.x + 0.5, y + 1.5 - drop, s.z + 0.125),
                new AABB(s.x - 0.25, y, s.z - 0.125, s.x, y + 0.75, s.z + 0.125), // legs
                new AABB(s.x, y, s.z - 0.125, s.x + 0.25, y + 0.75, s.z + 0.125)};
            int fill = (Math.round(a * 0.55f) << 24) | 0xCFE6FF, line = (a << 24) | 0xE8F3FF;
            for (AABB b : parts) {
                if (glass) { r.fill(b, fill, xray); r.box(b, (Math.round(a * 0.5f) << 24) | 0xE8F3FF, 1f, xray); }
                else r.box(b, line, 1.5f, xray);
            }
            drawn++;
        }
    }
}
