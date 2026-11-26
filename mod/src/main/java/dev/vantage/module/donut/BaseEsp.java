package dev.vantage.module.donut;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Toasts;
import dev.vantage.core.Ui;
import dev.vantage.module.finder.BaseFinder;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Groups storage and machinery into clusters and shows each one: a box round it, the containers lit up and a card
 * with what's in it. Chests count 3, hoppers 4, so a small farm or a real base stands out from a lone chest.
 */
public class BaseEsp extends Module {
    private static final int BOX = 0xFFD8DCE6, BLOCK = 0xFFF0A020;
    /** Blocks this close belong to the same group. */
    private static final int LINK = 6;
    private static final int INTACT_MIN = 6;
    /** Most of a group this big hanging in the air means the floor was taken out from under it. */
    private static final int FLOAT_MIN = 4;
    private static final long LOOK_MS = 5_000;

    public final NumberSetting minBlocks = add(new NumberSetting("Min Blocks", "Sets the minimum storage or machinery score. Chests count as 3, hoppers as 4.", 24, 3, 200, 1));
    public final NumberSetting radius = add(new NumberSetting("Cluster Radius", "Sets the maximum group width, height or depth (blocks).", 24, 4, 48, 1));
    public final NumberSetting range = add(new NumberSetting("Range", "Sets the maximum distance to show detected groups (blocks).", 308, 32, 512, 4));
    public final NumberSetting maxPanels = add(new NumberSetting("Max Panels", "Cards for the nearest groups when Base Finder's Declutter is off (or Base Finder is off). With Declutter on, Base Finder's card and tags describe the base.", 8, 0, 8, 1));
    public final BoolSetting belowZero = add(new BoolSetting("Below Y 0", "Only groups under Y 0", true));
    public final BoolSetting box = add(new BoolSetting("Box", "Outlines each detected storage or machinery group.", true));
    public final BoolSetting notify = add(new BoolSetting("Notify", "Shows a notification once per detected group.", true));
    public final ModeSetting litContainers = add(new ModeSetting("Lit containers", "With Base Finder's Declutter on: which groups get their containers outlined (one merged outline, within 48 blocks).", "Focus", "Focus", "All", "Off"));
    public final NumberSetting containerFill = add(new NumberSetting("Container fill", "Fill opacity of lit containers. 0 = outline only.", 0, 0, 60, 5));

    /** For the rig, from the last frame: fill quads Base ESP added, group boxes left out because you stand in them, cards drawn and where. */
    public static int fillQuads, boxesSkippedInside, cardsDrawn;
    public static List<float[]> lastCards = List.of();
    /** The merged container outline of each group (minus what Storage ESP already outlines), worked out when the groups change. */
    private record Lit(List<int[]> edges, List<BlockPos> blocks) {}
    private java.util.Map<Group, Lit> lit = new java.util.IdentityHashMap<>(), litBefore = new java.util.IdentityHashMap<>();

    public record Group(AABB box, List<BlockPos> storage, int containers, int hoppers, int machines, int redstone, int score, String state, String evidence) {}
    /** Blocks in a group's box. Walking the box costs more than the rest of the scan, so it's kept for a few seconds. */
    private record Look(long time, int redstone, int water) {}

    private List<Group> groups = List.of();
    private final Set<Long> told = new HashSet<>();
    private final java.util.Map<Long, Look> looks = new java.util.HashMap<>();
    private int timer;

    public BaseEsp() {
        super("Base ESP", "Highlights storage and redstone clusters below Y 0", Category.DONUT);
    }

    public List<Group> groups() {
        return groups;
    }

    @Override
    public String info() {
        return groups.isEmpty() ? null : String.valueOf(groups.size());
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
        groups = List.of();
        told.clear();
        looks.clear();
    }

    private static int weight(BlockEntity be) {
        if (be instanceof HopperBlockEntity) return 4;
        if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity) return 3;
        if (be instanceof DispenserBlockEntity || be instanceof CrafterBlockEntity || be instanceof ComparatorBlockEntity) return 2;
        if (be instanceof AbstractFurnaceBlockEntity || be instanceof BrewingStandBlockEntity) return 2;
        return 0;
    }

    private static final class Build {
        int x0, y0, z0, x1, y1, z1, containers, hoppers, machines, score;
        final List<BlockPos> storage = new ArrayList<>();

        Build(BlockPos p) {
            x0 = x1 = p.getX(); y0 = y1 = p.getY(); z0 = z1 = p.getZ();
        }

        boolean fits(BlockPos p, int max) {
            return Math.max(x1, p.getX()) - Math.min(x0, p.getX()) <= max && Math.max(y1, p.getY()) - Math.min(y0, p.getY()) <= max
                    && Math.max(z1, p.getZ()) - Math.min(z0, p.getZ()) <= max;
        }

        boolean near(BlockPos p) {
            for (BlockPos q : storage) if (q.distManhattan(p) <= LINK) return true;
            return false;
        }

        void add(BlockPos p, BlockEntity be, int w) {
            x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
            score += w;
            if (be instanceof HopperBlockEntity) hoppers++;
            else if (w == 3) containers++;
            else machines++;
            storage.add(p);
        }
    }

    @Override
    public void onTick() {
        if (--timer > 0) return;
        timer = 20;
        int r = mc.options.getEffectiveRenderDistance() + 1, rangeSq = range.i() * range.i();
        Vec3 me = mc.player.position();
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
        List<BlockEntity> found = new ArrayList<>();
        for (int cx = pcx - r; cx <= pcx + r; cx++) for (int cz = pcz - r; cz <= pcz + r; cz++) {
            if (!(mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) continue;
            if (dev.vantage.module.finder.BaseFinder.endCity(chunk)) continue;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                BlockPos p = be.getBlockPos();
                if (weight(be) == 0 || (belowZero.on() && p.getY() >= 0) || p.distToCenterSqr(me) > rangeSq) continue;
                found.add(be);
            }
        }
        found.sort(Comparator.comparingLong(b -> b.getBlockPos().asLong()));
        List<Build> builds = new ArrayList<>();
        for (BlockEntity be : found) {
            BlockPos p = be.getBlockPos();
            Build into = null;
            // a group grows through neighbours, so a stray chest nearby can't cut a base in half
            for (Build b : builds) if (b.near(p) && b.fits(p, radius.i())) { into = b; break; }
            if (into == null) builds.add(into = new Build(p));
            into.add(p, be, weight(be));
        }
        BaseFinder finder = Modules.get(BaseFinder.class);
        List<Group> out = new ArrayList<>();
        for (Build b : builds) {
            if (b.score < minBlocks.i()) continue;
            int mx = (b.x0 + b.x1) / 2, mz = (b.z0 + b.z1) / 2;
            String state = "Unclear", evidence = "Not enough clear condition evidence";
            String grief = finder.griefAt(mx, mz);
            int standing = supported(b.storage);
            boolean farm = finder.farmAt(mx, mz); // Base Finder v2: a working farm hangs its collectors on hoppers and carries water on purpose
            if (grief != null) { state = "Griefed"; evidence = grief; }
            else if (!farm && b.storage.size() >= FLOAT_MIN && standing * 2 < b.storage.size()) { state = "Griefed"; evidence = "Containers left floating"; }
            else if (finder.liveAt(mx, mz)) { state = "Active"; evidence = "Someone was here just now"; }
            long key = BlockPos.asLong(mx >> 4, b.y0 >> 4, mz >> 4);
            Look look = look(key, b);
            if (state.equals("Unclear")) {
                if (look.water > 0 && !farm) evidence = "Water exposure; damage unconfirmed";
                else if (b.storage.size() >= INTACT_MIN && standing * 10 >= b.storage.size() * 9) {
                    state = "Likely intact";
                    evidence = "Connected layout, supported blocks";
                }
            }
            AABB a = new AABB(b.x0, b.y0, b.z0, b.x1 + 1, b.y1 + 1, b.z1 + 1).inflate(0.6);
            Group grp = new Group(a, b.storage, b.containers, b.hoppers, b.machines, look.redstone, b.score + look.redstone, state, evidence);
            out.add(grp);
            // with Declutter on, Base Finder has already pinged a base that sits on its flagged chunks
            if (notify.on() && told.add(key) && !(BaseFinder.declutter() && finder.viewNear(mx >> 4, mz >> 4))) {
                Toasts.ok("Base ESP", state + " - " + count(grp) + " at " + mx + " " + b.y0 + " " + mz);
            }
        }
        out.sort(Comparator.comparingDouble(g -> g.box().getCenter().distanceToSqr(me)));
        groups = out;
        litBefore = lit;
        lit = new java.util.IdentityHashMap<>();
        for (Group grp : out) lit.put(grp, makeLit(grp));
    }

    private Lit makeLit(Group grp) {
        var se = Modules.get(dev.vantage.module.render.StorageEsp.class);
        Set<Long> skip = se != null && se.on() ? se.positions() : Set.of();
        Set<Long> set = new HashSet<>();
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos p : grp.storage()) if (!skip.contains(p.asLong()) && set.add(p.asLong())) blocks.add(p);
        return new Lit(R3.edges(set), blocks);
    }

    private Lit litFor(Group grp) {
        Lit l = lit.get(grp);
        if (l == null) l = litBefore.get(grp);
        if (l == null) lit.put(grp, l = makeLit(grp));
        return l;
    }

    private static int stateColor(Group grp) {
        return grp.state().equals("Active") || grp.state().equals("Likely intact") ? Ui.GREEN : grp.state().equals("Griefed") ? Ui.GREY : Ui.SUB;
    }

    private static double boxDist(AABB b, Vec3 p) {
        double dx = Math.max(0, Math.max(b.minX - p.x, p.x - b.maxX)), dy = Math.max(0, Math.max(b.minY - p.y, p.y - b.maxY)), dz = Math.max(0, Math.max(b.minZ - p.z, p.z - b.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Declutter: only the base Base Finder focuses gets its group boxes; containers get one merged outline, and only close by. */
    private void drawDeclutter(R3 r) {
        var site = Modules.get(BaseFinder.class).focusSite();
        List<Group> focusGroups = site == null ? List.of() : site.groups();
        int skipped = 0;
        if (box.on()) for (Group grp : focusGroups) {
            if (grp.box().inflate(2).contains(r.cam)) { skipped++; continue; }
            EspStyle.box(r, grp.box(), stateColor(grp) | 0xFF000000, true);
        }
        boxesSkippedInside = skipped;
        List<Group> shown = litContainers.is("Off") ? List.of() : litContainers.is("All") ? groups : focusGroups;
        for (Group grp : shown) {
            if (boxDist(grp.box(), r.cam) > 48) continue;
            Lit l = litFor(grp);
            r.edgeLines(l.edges(), BLOCK | 0xFF000000, EspStyle.WEIGHT, true);
            if (containerFill.i() > 0) r.outline(l.blocks(), BLOCK | 0xFF000000, EspStyle.WEIGHT, true, EspStyle.fillAlpha(true, containerFill.i() / 40f), false, true);
        }
    }

    private Look look(long key, Build b) {
        long now = System.currentTimeMillis();
        Look old = looks.get(key);
        if (old != null && now - old.time < LOOK_MS) return old;
        int redstone = 0, water = 0;
        for (BlockPos p : BlockPos.betweenClosed(b.x0, b.y0, b.z0, b.x1, b.y1, b.z1)) {
            var st = mc.level.getBlockState(p);
            if (BaseFinder.redstone(st.getBlock())) redstone++;
            else if (!st.getFluidState().isEmpty() && st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && !st.getFluidState().isSource()) water++;
        }
        Look l = new Look(now, redstone, water);
        looks.put(key, l);
        return l;
    }

    /** Containers with something under them: griefed bases leave them floating. */
    private int supported(List<BlockPos> storage) {
        int n = 0;
        for (BlockPos p : storage) if (!mc.level.getBlockState(p.below()).isAir()) n++;
        return n;
    }

    private static String count(Group g) {
        List<String> parts = new ArrayList<>();
        if (g.redstone() > 0) parts.add(g.redstone() + " redstone");
        if (g.containers() > 0) parts.add(g.containers() + " containers");
        if (g.hoppers() > 0) parts.add(g.hoppers() + " hoppers");
        if (g.machines() > 0) parts.add(g.machines() + " machines");
        return String.join(", ", parts);
    }

    @Override
    public void onRender3D(R3 r) {
        int q0 = r.quadCount();
        if (BaseFinder.declutter()) drawDeclutter(r);
        else drawAll(r);
        fillQuads = r.quadCount() - q0;
    }

    private void drawAll(R3 r) {
        boxesSkippedInside = 0;
        for (Group g : groups) {
            r.kind(null);
            if (box.on()) EspStyle.box(r, g.box(), BOX | 0xFF000000, true);
            r.kind("container");
            for (BlockPos p : g.storage()) {
                AABB a = new AABB(p);
                EspStyle.box(r, a, BLOCK | 0xFF000000, true, true, EspStyle.STD, 1f, true);
            }
        }
    }

    /** When each group's card first and last showed, so a card can fade in instead of popping. */
    private final java.util.Map<Long, long[]> shown = new java.util.HashMap<>();

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (dev.vantage.core.Hud.hidden()) return;
        long now = System.currentTimeMillis();
        if (BaseFinder.declutter()) { cardsDrawn = 0; lastCards = List.of(); return; } // Base Finder's card and tags describe the base
        if (!Modules.get(BaseFinder.class).on()) { alone(now); return; }
        Ui.begin(g);
        placed.clear();
        int n = 0;
        for (Group grp : groups) {
            if (n >= maxPanels.i()) break;
            Vec3 c = grp.box().getCenter();
            float[] at = Ui.project(c);
            if (at == null) continue;
            n++;
            double dist = Math.sqrt(c.distanceToSqr(mc.player.position()));
            card(g, grp, at[0], at[1], (int) dist, cardAlpha(grp, now, dist));
        }
        if (now % 5000 < 20) shown.values().removeIf(v -> now - v[1] > 10_000);
        Ui.end(g);
        lastCards = new ArrayList<>(placed);
        cardsDrawn = n;
    }

    /** How far a group's card has faded in (and out with distance). */
    private float cardAlpha(Group grp, long now, double dist) {
        long key = BlockPos.asLong((int) grp.box().minX, (int) grp.box().minY, (int) grp.box().minZ);
        long[] seen = shown.get(key);
        if (seen == null) shown.put(key, seen = new long[] {now, now});
        else if (now - seen[1] > 500) seen[0] = now;
        seen[1] = now;
        return Ui.ease((now - seen[0]) / 300f) * dev.vantage.module.render.EspStyle.fade(dist);
    }

    /** Base ESP without Base Finder (rig pictures): its cards go through Callouts, so they never overlap or pile up at the edge. */
    private void alone(long now) {
        int n = 0;
        float W = Ui.W(), H = Ui.H();
        for (Group grp : groups) {
            if (n >= maxPanels.i()) break;
            Vec3 c = grp.box().getCenter();
            float[] at = Ui.project(c);
            if (at == null || at[0] < 24 || at[0] > W - 24 || at[1] < 24 || at[1] > H - 24) continue;
            n++;
            double dist = Math.sqrt(c.distanceToSqr(mc.player.position()));
            float a = cardAlpha(grp, now, dist);
            int col = stateColor(grp);
            String id = "esp:" + BlockPos.asLong((int) grp.box().minX, (int) grp.box().minY, (int) grp.box().minZ);
            dev.vantage.core.Callouts.submit(id, at[0], at[1], (float) -dist, col, List.of(new dev.vantage.core.Callouts.Form(dev.vantage.core.Callouts.ESP, 176, 92)),
                    (gg, kind, x, y, w, h) -> { Ui.alpha *= a; body(gg, grp, x, y, w, h, (int) dist, col); });
        }
        if (now % 5000 < 20) shown.values().removeIf(v -> now - v[1] > 10_000);
        lastCards = List.of();
        cardsDrawn = n;
    }

    /** Cards drawn this frame, nearest first, so a later one can step out of their way. */
    private final List<float[]> placed = new ArrayList<>();

    /** The card sits up and to the right of the group, a hairline leads from its corner to the group's centre, and it fades with distance like the box. */
    private void card(GuiGraphics g, Group grp, float px, float py, int dist, float a) {
        float w = 176, h = 92;
        float x = px + 18, y = py - 30 - 6 * (1 - a);
        x = Math.max(4, Math.min(x, Ui.W() - w - 4));
        y = Math.max(4, Math.min(y, Ui.H() - h - 4));
        // a card that would cover an earlier one moves: above it first, then below, then beside, so cards at the screen edge stack instead of piling up
        float[][] tries = {{0, 0}, {0, -(h + 6)}, {0, h + 6}, {-(w + 8), 0}, {w + 8, 0}, {0, -2 * (h + 6)}, {0, 2 * (h + 6)}};
        for (float[] t : tries) {
            float tx = Math.max(4, Math.min(x + t[0], Ui.W() - w - 4)), ty = Math.max(4, Math.min(y + t[1], Ui.H() - h - 4));
            if (free(tx, ty, w, h)) { x = tx; y = ty; break; }
        }
        placed.add(new float[] {x, y, w, h});
        Ui.alpha = a;
        int col = grp.state().equals("Active") || grp.state().equals("Likely intact") ? Ui.GREEN : grp.state().equals("Griefed") ? Ui.GREY : Ui.SUB;
        // the leader: from the group's centre to the card's nearest corner, with a small dot where it starts
        float ax = px < x ? x : px > x + w ? x + w : px, ay = py < y ? y : py > y + h ? y + h : py;
        leader(g, px, py, ax, ay, Ui.fade(Ui.BORDER, 1.6f));
        Ui.rr(g, px - 2, py - 2, 4, 4, 2, Ui.fade(col, 0.8f), 0);
        body(g, grp, x, y, w, h, dist, col);
        Ui.alpha = 1f;
    }

    /** The card itself at x, y. */
    private void body(GuiGraphics g, Group grp, float x, float y, float w, float h, int dist, int col) {
        Ui.card(g, x, y, w, h, col);
        Ui.text(g, "BASE ESP", x + 11, y + 6, Ui.FAINT, 0.8f, false);
        Ui.right(g, dist + "m", x + w - 9, y + 6, Ui.SUB, 0.85f, false);
        Ui.hline(g, x + 11, y + 19, w - 20, Ui.LINE);
        Ui.text(g, grp.state(), x + 11, y + 28, col, 1.1f, false);
        Ui.right(g, grp.score() + " score", x + w - 9, y + 30, Ui.SUB, 0.85f, false);
        Ui.text(g, Ui.fit(count(grp), w - 20, 0.85f), x + 11, y + 53, Ui.SUB, 0.85f, false);
        Ui.text(g, Ui.fit(grp.evidence(), w - 20, 0.85f), x + 11, y + 74, Ui.SUB, 0.85f, false);
    }

    private boolean free(float x, float y, float w, float h) {
        for (float[] r : placed) if (x < r[0] + r[2] + 4 && x + w + 4 > r[0] && y < r[1] + r[3] + 4 && y + h + 4 > r[1]) return false;
        return true;
    }

    /** A thin line between two screen points, drawn as short overlapping squares so it works at any angle. */
    private static void leader(GuiGraphics g, float x1, float y1, float x2, float y2, int color) {
        // one rotated fill; the old dot-every-two-pixels loop was hundreds of draw elements per card and the HUD's biggest cost
        Ui.line(g, x1, y1, x2, y2, 1, color);
    }

    @Override
    public boolean groupsBoxes() {
        return true;
    }
}
