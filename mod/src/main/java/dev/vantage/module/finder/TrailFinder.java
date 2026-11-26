package dev.vantage.module.finder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.ChunkTracker;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Theme;
import dev.vantage.core.hud.HudElements;
import dev.vantage.core.util.Compass;
import dev.vantage.core.util.Dist;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Trails: a chain of OLD chunks running through an area of NEW chunks is where someone travelled before.
 * Groups connected old chunks, keeps the long thin ones surrounded by new terrain, and reports which way they run.
 */
public class TrailFinder extends Module {
    public record Trail(List<Long> chunks, double angleDeg, int nearX, int nearZ, int farX, int farZ) {}

    public final NumberSetting minLength = add(new NumberSetting("Min chunks", "Old chunks in a row before it counts as a trail", 5, 3, 40, 1));
    public final NumberSetting newRatio = add(new NumberSetting("New around", "How much of the area around it must be new (%)", 50, 10, 100, 5));
    public final BoolSetting notify = add(new BoolSetting("Chat alert", "Message + sound when a trail shows up", true));
    public final BoolSetting hud = add(new BoolSetting("HUD hint", "Show direction of the nearest trail on screen", true));
    public final dev.vantage.setting.NumberSetting hintSize = add(new dev.vantage.setting.NumberSetting("Hint size", "Size of the HUD hint (1 = as before)", 1.0, 0.8, 2.5, 0.1)).visibleIf(hud::on);
    /** Rig: width and height of the last hint drawn. */
    public static float hintW, hintH;
    public final ModeSetting corner = add(new ModeSetting("HUD corner", "Where the hint goes", "Top Left", Hud.CORNERS)).visibleIf(hud::on);
    public final ColorSetting color = add(new ColorSetting("Color", "Trail colour", 0xFF78A8A8));

    private List<Trail> trails = List.of();
    private final Set<Long> reported = new HashSet<>();
    private int timer, lastVersion = -1;

    static {
        HudElements.declare("trail", "Trail", "Top Left", TrailFinder.class);
    }

    public TrailFinder() {
        super("Trail Finder", "Finds paths of old chunks through new terrain", Category.FINDER);
    }

    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    private void forgetWorld() {
        dev.vantage.core.util.OwnTicks.reset();
        trails = List.of();
        reported.clear();
    }

    @Override
    public String info() {
        return trails.isEmpty() ? null : String.valueOf(trails.size());
    }

    /** Rig only: shows these trails and stops scanning, so the HUD hint can be measured. */
    public void pinForTest(List<Trail> t) {
        trails = t == null ? List.of() : t;
        pinned = t != null;
    }

    private boolean pinned;

    /** The old chunks that are not explained by the server's spawn or the user's own presence. */
    public static Set<Long> strangers(Set<Long> old, java.util.function.Predicate<Long> own) {
        Set<Long> out = new HashSet<>();
        for (long k : old) if (!own.test(k)) out.add(k);
        return out;
    }

    @Override
    public void onTick() {
        dev.vantage.core.util.OwnTicks.note(mc, ChunkTracker.dim(), System.currentTimeMillis());
        if (pinned) return;
        if (--timer > 0) return;
        timer = 40;
        if (ChunkTracker.version() == lastVersion) return;
        lastVersion = ChunkTracker.version();
        String dim = ChunkTracker.dim();
        // an old chunk is only a sign of somebody else when it is not the world spawn, and not a chunk the user's own presence ticked earlier
        Set<Long> old = strangers(ChunkTracker.oldChunks(), k -> dev.vantage.core.util.OwnTicks.spawnArea(mc, ChunkPos.getX(k), ChunkPos.getZ(k)) || dev.vantage.core.util.OwnTicks.ownedEver(dim, ChunkPos.getX(k), ChunkPos.getZ(k)));
        Set<Long> nw = ChunkTracker.newChunks();
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
        int range = mc.options.getEffectiveRenderDistance() + 8;
        Set<Long> seen = new HashSet<>();
        List<Trail> out = new ArrayList<>();
        for (long start : old) {
            if (seen.contains(start)) continue;
            int sx = ChunkPos.getX(start), sz = ChunkPos.getZ(start);
            if (Math.abs(sx - pcx) > range || Math.abs(sz - pcz) > range) continue;
            // flood fill the connected old chunks (8-neighbourhood)
            List<Long> comp = new ArrayList<>();
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(start);
            seen.add(start);
            while (!q.isEmpty() && comp.size() < 2000) {
                long k = q.poll();
                comp.add(k);
                int x = ChunkPos.getX(k), z = ChunkPos.getZ(k);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    long n = ChunkPos.asLong(x + dx, z + dz);
                    if (old.contains(n) && seen.add(n)) q.add(n);
                }
            }
            if (comp.size() < minLength.i()) continue;
            Set<Long> compSet = new HashSet<>(comp);
            int newN = 0, known = 0;
            for (long k : comp) {
                int x = ChunkPos.getX(k), z = ChunkPos.getZ(k);
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    long n = ChunkPos.asLong(x + dx, z + dz);
                    if (compSet.contains(n)) continue;
                    if (nw.contains(n)) { newN++; known++; } else if (old.contains(n)) known++;
                }
            }
            if (known == 0 || newN * 100.0 / known < newRatio.get()) continue;
            Trail t = shape(comp, pcx, pcz);
            if (t != null) out.add(t);
        }
        trails = out;
        for (Trail t : out) {
            long id = t.chunks().get(0);
            if (!reported.add(id)) continue;
            if (notify.on() && !dev.vantage.core.util.Alone.now()) { // alone, the chunks "walked" are the order the world was generated in
                Chat.alert(Component.literal("TRAIL ").withColor(color.argb()).append(Component.literal(
                        t.chunks().size() + " chunks running " + Compass.name(t.angleDeg()) + "-" + Compass.name(t.angleDeg() + 180)
                                + ", nearest " + t.nearX() + " " + t.nearZ()).withColor(Theme.TEXT)), 1.2f);
            }
        }
    }

    /** Principal axis of the chunk cloud = direction of travel. Blobs (no clear axis) are not trails. */
    private Trail shape(List<Long> comp, int pcx, int pcz) {
        double mx = 0, mz = 0;
        for (long k : comp) { mx += ChunkPos.getX(k); mz += ChunkPos.getZ(k); }
        mx /= comp.size(); mz /= comp.size();
        double sxx = 0, szz = 0, sxz = 0;
        for (long k : comp) {
            double dx = ChunkPos.getX(k) - mx, dz = ChunkPos.getZ(k) - mz;
            sxx += dx * dx; szz += dz * dz; sxz += dx * dz;
        }
        double tr = sxx + szz, det = sxx * szz - sxz * sxz;
        double l1 = tr / 2 + Math.sqrt(Math.max(0, tr * tr / 4 - det)), l2 = tr / 2 - Math.sqrt(Math.max(0, tr * tr / 4 - det));
        if (l2 > 0 && l1 / l2 < 3) return null; // too round: a base or a village, not a road
        double ang = 0.5 * Math.atan2(2 * sxz, sxx - szz); // radians, in x/z space
        double ux = Math.cos(ang), uz = Math.sin(ang);
        long near = comp.get(0), far = comp.get(0);
        double best = Double.MAX_VALUE, proj = -Double.MAX_VALUE;
        for (long k : comp) {
            double dx = ChunkPos.getX(k) - pcx, dz = ChunkPos.getZ(k) - pcz;
            double d = dx * dx + dz * dz;
            if (d < best) { best = d; near = k; }
            double p = dx * ux + dz * uz;
            if (Math.abs(p) > proj) { proj = Math.abs(p); far = k; }
        }
        // MC yaw style bearing: 0 = south (+z), 90 = west
        double deg = Dist.bearing(ux, uz);
        return new Trail(comp, deg, ChunkPos.getX(near) * 16 + 8, ChunkPos.getZ(near) * 16 + 8,
                ChunkPos.getX(far) * 16 + 8, ChunkPos.getZ(far) * 16 + 8);
    }

    @Override
    public void onRender3D(R3 r) {
        double y = Math.floor(mc.player.getY()) + 0.05;
        int c = color.argb();
        for (Trail t : trails) {
            for (long k : t.chunks()) {
                double x0 = ChunkPos.getX(k) * 16, z0 = ChunkPos.getZ(k) * 16;
                EspStyle.zone(r, x0, z0, x0 + 16, z0 + 16, y, c | 0xFF000000, false, true, true);
            }
            r.label(new Vec3(t.farX(), y + 3, t.farZ()), "TRAIL " + Compass.name(t.angleDeg()) + "/" + Compass.name(t.angleDeg() + 180), c);
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (!hud.on() || trails.isEmpty()) return;
        Trail best = null;
        double bd = Double.MAX_VALUE;
        for (Trail t : trails) {
            double d = mc.player.distanceToSqr(t.nearX(), mc.player.getY(), t.nearZ());
            if (d < bd) { bd = d; best = t; }
        }
        double dx = best.nearX() - mc.player.getX(), dz = best.nearZ() - mc.player.getZ();
        double bearing = Dist.bearing(dx, dz);
        String s = "trail " + Compass.name(best.angleDeg()) + "/" + Compass.name(best.angleDeg() + 180) + "  " + (int) Math.sqrt(bd) + "m " + Compass.name(bearing);
        float k = dev.vantage.core.Ui.scale(), z = hintSize.f(), w = dev.vantage.core.Ui.width(s, 0.9f * z, false) + 20 * z, h = 20 * z;
        hintW = w; hintH = h;
        float[] at = Hud.slot("trail", corner.get(), w, h);
        if (at == null) return;
        dev.vantage.core.Ui.begin(g);
        float x = at[0], y = at[1];
        dev.vantage.core.Ui.rr(g, x, y, w, h, 7 * z, dev.vantage.core.Ui.GLASS, dev.vantage.core.Ui.BORDER);
        dev.vantage.core.Ui.text(g, s, x + 10 * z, y + 6 * z, color.argb(), 0.9f * z, false);
        dev.vantage.core.Ui.end(g);
    }
}
