package dev.vantage.module.render;

import dev.vantage.core.Category;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Theme;
import dev.vantage.core.Ui;
import dev.vantage.core.hud.HudElements;
import dev.vantage.module.finder.Waypoints;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Top-down dots around you, turning with your view: forward is always up. */
public class Radar extends Module {
    public final NumberSetting size = add(new NumberSetting("Size", "Radar size in GUI pixels", 90, 50, 200, 5));
    public final NumberSetting range = add(new NumberSetting("Range", "Blocks from centre to edge", 64, 16, 256, 8));
    public final ModeSetting corner = add(new ModeSetting("Corner", "Where it sits", "Top Left", Hud.CORNERS));
    public final BoolSetting rotate = add(new BoolSetting("Rotate", "Turn with your view (off = north up)", true));
    public final BoolSetting players = add(new BoolSetting("Players", "", true));
    public final BoolSetting names = add(new BoolSetting("Names", "Player names next to their dot", true)).visibleIf(players::on);
    public final BoolSetting hostiles = add(new BoolSetting("Hostiles", "", true));
    public final BoolSetting passives = add(new BoolSetting("Passives", "", false));
    public final BoolSetting items = add(new BoolSetting("Items", "", false));
    public final BoolSetting waypoints = add(new BoolSetting("Waypoints", "", true));

    static {
        HudElements.declare("radar", "Radar", "Top Left", Radar.class);
    }

    public Radar() {
        super("Radar", "Mini radar with players, mobs and waypoints", Category.RENDER);
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        int s = size.i();
        String cn = corner.get();
        float k = Ui.scale();
        float[] at = Hud.slot("radar", cn, s, s);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        float cx = x + s / 2f, cy = y + s / 2f, rad = s / 2f;
        // a round disc with a soft drop, a ring at half range and faint cross hairlines, all inside the circle
        Ui.rr(g, x - 1, y + 1.5f, s + 2, s + 2, rad + 1, 0x38000000, 0);
        Ui.rr(g, x, y, s, s, rad, Ui.BG, Ui.BORDER);
        float in = rad * 0.7071f;
        Ui.vline(g, cx, cy - in, 2 * in, Ui.LINE);
        Ui.hline(g, cx - in, cy, 2 * in, Ui.LINE);
        Ui.rr(g, cx - rad / 2, cy - rad / 2, rad, rad, rad / 2, 0, Ui.LINE);

        float partial = delta.getGameTimeDeltaPartialTick(true);
        Vec3 me = mc.player.getPosition(partial);
        double yaw = Math.toRadians(rotate.on() ? mc.player.getViewYRot(partial) : 180);
        double scale = (s / 2.0 - 3) / range.get();
        // north marker on the rim; with the view up, your heading is always up, so the rim shows where north went
        dot(g, cx, cy, s, scale, yaw, 0, -range.get() * 0.92, "N", Theme.TEXT_DIM, true);

        if (waypoints.on()) {
            Waypoints wm = Waypoints.get();
            if (wm.on()) {
                for (Waypoints.Waypoint w : wm.list()) {
                    if (!w.visible) continue;
                    Vec3 p = wm.shownPos(w);
                    if (p != null) dot(g, cx, cy, s, scale, yaw, p.x - me.x, p.z - me.z, null, 0xFF000000 | w.color, true);
                }
            }
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            int col;
            String label = null;
            if (e instanceof Player p) {
                if (!players.on()) continue;
                col = 0xFFEEF3EE;
                if (names.on()) label = dev.vantage.core.Streamer.name(p.getName().getString());
            } else if (e instanceof Enemy || e.getType().getCategory() == MobCategory.MONSTER) {
                if (!hostiles.on()) continue;
                col = Theme.RED;
            } else if (e instanceof LivingEntity) {
                if (!passives.on()) continue;
                col = Theme.GREEN;
            } else if (e instanceof ItemEntity) {
                if (!items.on()) continue;
                col = Theme.YELLOW;
            } else continue;
            Vec3 p = e.getPosition(partial);
            dot(g, cx, cy, s, scale, yaw, p.x - me.x, p.z - me.z, label, col, false);
        }
        // you: a halo and a dot in the accent, and a small heading wedge at the rim (up when the radar turns with you)
        int accent = Theme.accent();
        Ui.rr(g, cx - 4, cy - 4, 8, 8, 4, Ui.fade(accent, 0.25f), 0);
        Ui.rr(g, cx - 2, cy - 2, 4, 4, 2, accent, 0);
        double head = rotate.on() ? 0 : Math.toRadians(mc.player.getViewYRot(partial)) - Math.PI;
        float hx = cx + (float) Math.sin(head) * (rad - 5), hy = cy - (float) Math.cos(head) * (rad - 5);
        Ui.rr(g, hx - 1.5f, hy - 1.5f, 3, 3, 1.5f, accent, 0);
        Ui.end(g);
    }

    private void dot(GuiGraphics g, float cx, float cy, int s, double scale, double yaw, double dx, double dz,
                     String text, int col, boolean clamp) {
        // screen right = player's right, screen up = player's forward (MC: yaw 0 faces +z)
        double rx = -dx * Math.cos(yaw) - dz * Math.sin(yaw);
        double fw = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
        double sx = rx * scale, sy = -fw * scale;
        double lim = s / 2.0 - 4, len = Math.sqrt(sx * sx + sy * sy);
        if (len > lim) {
            if (!clamp) return;
            sx *= lim / len;
            sy *= lim / len;
        }
        float px = cx + (float) sx, py = cy + (float) sy;
        if (text != null && text.length() == 1 && col == Theme.TEXT_DIM) {
            Ui.center(g, text, px, py - 4, col, 0.8f, false);
            return;
        }
        Ui.rr(g, px - 2.2f, py - 2.2f, 4.4f, 4.4f, 2.2f, 0x90000000, 0);
        Ui.rr(g, px - 1.5f, py - 1.5f, 3, 3, 1.5f, col, 0);
        if (text != null) Ui.text(g, text, px + 4, py - 4, col, 0.7f, false);
    }
}
