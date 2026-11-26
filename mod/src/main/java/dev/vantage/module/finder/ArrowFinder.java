package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Theme;
import dev.vantage.core.util.Dist;
import dev.vantage.module.render.Search;
import dev.vantage.setting.BoolSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * A Search that also points at the nearest hit: an arrow at the top of the screen with distance and coords,
 * plus one chat ping per group of hits. Amethyst Finder and Debris Finder are built on it.
 */
public abstract class ArrowFinder extends Search {
    public final BoolSetting arrow = add(new BoolSetting("Arrow", "Arrow at the top of the screen pointing to the nearest one", true));
    public final BoolSetting alert = add(new BoolSetting("Chat alert", "Message + sound for each new group", true));

    private final List<BlockPos> announced = new ArrayList<>();
    private final String label, alertTag, one, many;
    private final int group;

    protected ArrowFinder(String name, String description, List<String> blocks, String label, String alertTag,
                          String one, String many, int group, int color) {
        super(name, description, blocks, Category.FINDER);
        this.label = label;
        this.alertTag = alertTag;
        this.one = one;
        this.many = many;
        this.group = group;
        colorMode.set("Custom");
        this.color.set(color);
        mode.set("Both");
    }

    @Override
    protected void forgetWorld() {
        super.forgetWorld();
        announced.clear();
    }

    protected BlockPos closest() {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        Vec3 me = mc.player.position();
        for (BlockPos p : nearest) {
            double d = p.distToCenterSqr(me);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    @Override
    public void onTick() {
        super.onTick();
        if (!alert.on()) return;
        int g2 = group * group;
        for (BlockPos p : nearest) {
            boolean known = false;
            for (BlockPos a : announced) if (a.distSqr(p) < g2) { known = true; break; }
            if (known) continue;
            announced.add(p);
            int n = 0;
            for (BlockPos q : nearest) if (q.distSqr(p) < g2) n++;
            if (burstGroups == 0) burstSince = System.currentTimeMillis();
            burstGroups++;
            burstClusters += n;
            double d = p.distToCenterSqr(mc.player.position());
            if (burstNear == null || d < burstNearD) { burstNear = p; burstNearD = d; burstNearN = n; }
        }
        // a pass over a new area finds many groups at once (a world is full of geodes): one line per burst, the nearest group named
        if (burstGroups > 0 && System.currentTimeMillis() - burstSince > 2500) {
            BlockPos p = burstNear;
            String where = Chat.coords(p.getX(), p.getY(), p.getZ()) + "  " + (int) Math.sqrt(burstNearD) + "m";
            Chat.alert(Component.literal(alertTag + " ").withColor(color.argb()).append(Component.literal(burstGroups == 1
                    ? burstNearN + " " + (burstNearN == 1 ? one : many) + "  " + where
                    : burstClusters + " " + many + " in " + burstGroups + " groups, nearest " + where).withColor(Theme.TEXT)), 1.6f);
            burstGroups = 0; burstClusters = 0; burstNear = null;
        }
    }

    private int burstGroups, burstClusters, burstNearN;
    private long burstSince;
    private double burstNearD;
    private BlockPos burstNear;

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (!arrow.on()) return;
        BlockPos t = closest();
        if (t == null) return;
        float partial = delta.getGameTimeDeltaPartialTick(true);
        Vec3 me = mc.player.getPosition(partial);
        double dx = t.getX() + 0.5 - me.x, dz = t.getZ() + 0.5 - me.z, dy = t.getY() + 0.5 - mc.player.getEyeY();
        double bearing = Dist.bearing(dx, dz);
        float rel = (float) (bearing - mc.player.getViewYRot(partial));
        int col = color.argb();
        dev.vantage.core.Ui.begin(g);
        float cx = dev.vantage.core.Ui.W() / 2, cy = 40 + slot() * 54;
        int dist = (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
        String h = Math.abs(dy) < 2 ? "" : dy > 0 ? "  up " + (int) dy : "  down " + (int) -dy;
        String s = label + "  " + dist + "m" + h;
        String c = Chat.coords(t.getX(), t.getY(), t.getZ());
        float w = Math.max(dev.vantage.core.Ui.width(s, 0.95f, true), dev.vantage.core.Ui.width(c, 0.85f, false)) + 24;
        dev.vantage.core.Ui.rr(g, cx - w / 2, cy - 16, w, 46, 7, dev.vantage.core.Ui.GLASS, dev.vantage.core.Ui.BORDER);
        dev.vantage.core.Callouts.reserve(cx - w / 2, cy - 16, w, 46);
        g.pose().pushMatrix();
        g.pose().translate(cx, cy - 4);
        g.pose().rotate((float) Math.toRadians(rel));
        for (int i = 0; i <= 5; i++) g.fill(-i, -9 + i, i + 1, -8 + i, col);
        g.fill(-1, -4, 2, 6, col);
        g.pose().popMatrix();
        dev.vantage.core.Ui.center(g, s, cx, cy + 7, col, 0.95f, true);
        dev.vantage.core.Ui.center(g, c, cx, cy + 19, dev.vantage.core.Ui.SUB, 0.85f, false);
        dev.vantage.core.Ui.end(g);
    }

    /** Several arrow finders on at once stack downwards instead of drawing over each other. */
    private int slot() {
        int i = 0;
        for (var m : dev.vantage.core.Modules.all()) {
            if (m == this) return i;
            if (m instanceof ArrowFinder af && af.on() && af.arrow.on() && af.closest() != null) i++;
        }
        return i;
    }
}
