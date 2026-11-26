package dev.vantage.module.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.vantage.core.Callouts;
import dev.vantage.core.Hud;
import dev.vantage.core.Ui;
import dev.vantage.core.hud.HudElements;
import dev.vantage.core.util.ChunkMath;
import dev.vantage.core.util.Colors;
import dev.vantage.core.util.Compass;
import dev.vantage.render.R3;
import dev.vantage.setting.BlockColorSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The block ESP: Search's incremental, time-boxed scan (palette pre-check per section, block updates keep the cache) with a colour
 * and an on/off per block type, Outline / Fill / Both with opacity and distance fade, an Exposed-only filter and grouping.
 * Grouping is what keeps a base readable: blocks near each other (chained within Group distance) become ONE box with one label
 * ("Chests x5"), or one "Base stuff x23" box over different kinds, or, as Veins, touching blocks of one kind become one outline.
 * Everything is worked out every half second from the capped, nearest-first hit list; a frame only draws the result.
 */
public class BlockEsp extends Search {
    public final ModeSetting colors = add(new ModeSetting("Colors", "By block: each type has its own colour (right click a block in the picker); Single: one colour", "By block", "By block", "Single"));
    public final NumberSetting fillOpacity = add(new NumberSetting("Fill opacity", "Fill strength for Fill and Both", 45, 10, 200, 5)).visibleIf(() -> !mode.is("Outline"));
    public final NumberSetting width = add(new NumberSetting("Line width", "Outline thickness", 1.5, 1, 5, 0.5));
    public final BoolSetting fade = add(new BoolSetting("Distance fade", "Far boxes get fainter", true));
    public final NumberSetting fadeStart = add(new NumberSetting("Fade start", "Blocks away where the fade begins", 64, 16, 256, 8)).visibleIf(fade::on);
    public final ModeSetting grouping = add(new ModeSetting("Grouping", "Off: one box per block. Veins: touching blocks of one kind share an outline. Kind: near blocks of one kind become one box. Everything: all near blocks become one box",
            "Kind", "Off", "Veins", "Kind", "Everything"));
    public final NumberSetting groupDistance = add(new NumberSetting("Group distance", "Blocks apart that still count as one group (chained)", 4, 1, 16, 1)).visibleIf(() -> grouping.is("Kind") || grouping.is("Everything"));
    public final BoolSetting containersTogether = add(new BoolSetting("Containers together", "Kind: chests, barrels, shulkers and ender chests group as one \"Storage\" kind", true)).visibleIf(() -> grouping.is("Kind"));
    public final BoolSetting members = add(new BoolSetting("Show members", "Faint boxes for every block inside a group box", false)).visibleIf(() -> grouping.is("Kind") || grouping.is("Everything"));
    public final ModeSetting labelMode = add(new ModeSetting("Labels", "Card only: no text in the world, aim at a group for its card. Short tag: just the count (\"x13\") close by. Full (old): the whole label",
            "Short tag", "Card only", "Short tag", "Full (old)"));
    public final NumberSetting labelRange = add(new NumberSetting("Label range", "Group labels show within this many blocks (0 = no labels). Short tags stop at 32", 48, 0, 256, 8)).visibleIf(() -> !labelMode.is("Card only"));
    public final BoolSetting card = add(new BoolSetting("Aim card", "Aim at a group (or the nearest one near the crosshair) for a card: what is in it, how far, where, and whether it is exposed. Middle click adds a waypoint", true));
    public final BoolSetting hudList = add(new BoolSetting("HUD list", "A small panel with the five nearest groups", false));
    public final BoolSetting exposed = add(new BoolSetting("Exposed only", "Skip blocks buried on all six sides", false));
    public final NumberSetting scanRange = add(new NumberSetting("Scan range", "Chunks around you that get scanned (0 = render distance)", 0, 0, 32, 1)).onChange(this::rescanAll);
    public final BlockColorSetting blockColors = add(new BlockColorSetting());
    private final BoolSetting migrated = add(new BoolSetting("Migrated v2", "", false)).visibleIf(() -> false);

    /** One drawn thing: a lone block (count 1) or a group. */
    public record Group(String label, int count, AABB box, Vec3 labelAt, int color, List<BlockPos> pos, boolean outline, Map<String, Integer> kinds,
                        String title, Map<String, Integer> kindColors, Map<String, String> kindNames) {}

    private volatile List<Group> groups = List.of();
    /**
     * The prepared outline of every merged group, built in {@link #setGroups} (the only place that writes {@code groups}) so a frame
     * never rebuilds a face set. Keyed by the group object itself: a new group list means new keys, so none can be stale.
     */
    private Map<Group, R3.Outline> prepared = new java.util.IdentityHashMap<>();
    /** Lone blocks bucketed by colour: each bucket's prepared outline, rebuilt only when the bucket's blocks differ from the last frame's. */
    private final R3.OutlineCache bucketCache = new R3.OutlineCache();
    private List<BlockPos> built;
    private final Map<Block, String> idCache = new HashMap<>();
    private final Map<String, String> nameCache = new HashMap<>();

    static {
        HudElements.declare("blockesp", "Block ESP", "Top Left", BlockEsp.class);
    }

    public BlockEsp() {
        super("Block ESP", "Outline chosen blocks through walls: a colour per block, groups for bases and veins",
                List.of("chest", "trapped_chest", "barrel", "ender_chest", "shulker_box", "spawner", "trial_spawner", "beacon",
                        "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore", "ancient_debris",
                        "nether_portal", "end_portal", "end_portal_frame", "respawn_anchor", "enchanting_table"));
        // Search's own colour mode stays saved so an old config can migrate: Custom there meant one colour
        colorMode.set("Block");
        colorMode.visibleIf(() -> false);
        color.visibleIf(() -> colors.is("Single"));
        mode.set("Outline");
        range.visibleIf(() -> true);
        blocks.withColors(blockColors)
                .preset("Ores", id -> id.endsWith("_ore") || id.equals("ancient_debris") || id.equals("gilded_blackstone"))
                .preset("Valuables", id -> id.contains("diamond") || id.contains("emerald") || id.equals("ancient_debris") || id.equals("netherite_block")
                        || id.equals("gold_block") || id.equals("raw_gold_block") || id.endsWith("gold_ore") || id.equals("beacon") || id.equals("enchanting_table")
                        || id.equals("respawn_anchor") || id.equals("end_portal_frame"))
                .preset("Spawners & Storage", id -> id.endsWith("spawner") || id.equals("vault") || id.equals("chest") || id.equals("trapped_chest")
                        || id.equals("barrel") || id.equals("ender_chest") || id.endsWith("shulker_box"))
                .preset("Redstone", id -> id.endsWith("redstone_ore") || java.util.Set.of("redstone_block", "observer", "piston", "sticky_piston", "dispenser", "dropper",
                        "hopper", "repeater", "comparator", "tnt", "redstone_lamp", "daylight_detector", "target", "lever", "note_block", "crafter").contains(id));
    }

    @Override
    public String info() {
        int n = 0;
        for (Group g : groups) n += g.count();
        return n + " shown";
    }

    /** What the last pass drew: groups of two or more and lone blocks. For the rig. */
    public List<Group> groups() {
        return groups;
    }

    public int nearestSize() {
        return nearest.size();
    }

    public int shown() {
        int n = 0;
        for (Group g : groups) n += g.count();
        return n;
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        setGroups(List.of());
        bucketCache.clear();
        built = null;
        aimedNow = null;
        heldAim = null;
        tags = List.of();
    }

    @Override
    protected int scanChunks() {
        int rd = super.scanChunks();
        return scanRange.i() > 0 ? Math.min(rd, scanRange.i()) : rd;
    }

    // ---- colours ----------------------------------------------------------------------------------------------------

    private String id(Block b) {
        return idCache.computeIfAbsent(b, k -> {
            var key = BuiltInRegistries.BLOCK.getKey(k);
            return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
        });
    }

    private int colorOf(Block b) {
        if (colors.is("Single")) return color.argb();
        String id = id(b);
        int c = blockColors.colorOr0(id);
        return c != 0 ? c : defaultColor(id, b);
    }

    /** Sensible colours by name: ores in their metal, storage in warm wood, spawners purple, redstone red. */
    public static int defaultColor(String id, Block b) {
        if (id.contains("diamond")) return 0xFF4AEDD9;
        if (id.contains("emerald")) return 0xFF17DD62;
        if (id.contains("gold")) return 0xFFFFD84A;
        if (id.contains("iron")) return 0xFFD8AF93;
        if (id.contains("copper")) return 0xFFE77C56;
        if (id.contains("coal")) return 0xFF8A8F96;
        if (id.contains("lapis")) return 0xFF3F6BE8;
        if (id.contains("redstone") || id.equals("tnt")) return 0xFFFF3A3A;
        if (id.contains("quartz")) return 0xFFEDE6DC;
        if (id.contains("ancient_debris") || id.contains("netherite")) return 0xFFC48A6A;
        if (id.contains("spawner")) return 0xFF9A3CFF;
        if (id.equals("trapped_chest")) return 0xFFE06040;
        if (id.equals("chest")) return 0xFFE0A040;
        if (id.equals("barrel")) return 0xFFB07848;
        if (id.contains("shulker")) return 0xFFD070D0;
        if (id.equals("ender_chest")) return 0xFF9060E0;
        if (id.contains("portal") && !id.contains("frame")) return 0xFFB060FF;
        if (id.contains("end_portal_frame")) return 0xFF8CD7A0;
        if (id.equals("beacon")) return 0xFF7FFFF0;
        if (id.contains("anchor")) return 0xFFFF7F50;
        if (id.endsWith("_bed")) return 0xFFFF6B6B;
        if (id.contains("vault")) return 0xFF6AE0FF;
        if (id.equals("hopper") || id.equals("dropper") || id.equals("dispenser") || id.equals("observer") || id.contains("piston")
                || id.equals("comparator") || id.equals("repeater") || id.equals("crafter")) return 0xFFFF5A5A;
        int c = b.defaultMapColor().col;
        return c == 0 ? 0xFFD3DCD4 : 0xFF000000 | c;
    }

    private static boolean container(String id) {
        return id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel") || id.equals("ender_chest") || id.endsWith("shulker_box");
    }

    // ---- scan filter: runs on the tick, nearest first, time-boxed ---------------------------------------------------------

    @Override
    protected List<BlockPos> filter(List<BlockPos> all) {
        boolean ex = exposed.on();
        if (!ex && blockColors.offSet().isEmpty() && scanRange.i() == 0) return all;
        var eye = mc.player.blockPosition();
        all.sort(Comparator.comparingDouble(p -> p.distSqr(eye)));
        int reach = scanChunks(), pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
        List<BlockPos> out = new ArrayList<>();
        long t0 = System.nanoTime();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        int i = 0;
        for (BlockPos p : all) {
            if ((++i & 63) == 0 && System.nanoTime() - t0 > 4_000_000) break;
            if (Math.max(Math.abs((p.getX() >> 4) - pcx), Math.abs((p.getZ() >> 4) - pcz)) > reach) continue;
            BlockState s = mc.level.getBlockState(p);
            if (!blocks.contains(s.getBlock()) || blockColors.off(id(s.getBlock()))) continue;
            if (ex) {
                boolean open = false;
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    n.setWithOffset(p, d);
                    if (!mc.level.getBlockState(n).isSolidRender()) { open = true; break; }
                }
                if (!open) continue;
            }
            out.add(p);
            if (out.size() >= max.i()) break;
        }
        return out;
    }

    // ---- grouping ---------------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        super.onTick();
        if (!migrated.on()) {
            migrated.set(true);
            if (colorMode.is("Custom")) colors.set("Single"); // the old Block ESP drew everything in one colour
        }
        boolean mid = mc.screen == null && mc.mouseHandler.isMiddlePressed();
        if (mid && !middleWas && card.on()) waypointAimed();
        middleWas = mid;
        if (nearest == built || mc.level == null) return;
        built = nearest;
        setGroups(build(built));
    }

    private void setGroups(List<Group> gs) {
        Map<Group, R3.Outline> p = new java.util.IdentityHashMap<>();
        for (Group g : gs) if (g.count() > 1 && g.outline()) p.put(g, R3.prepare(g.pos()));
        prepared = p;
        groups = gs;
    }

    private String kindKey(String id) {
        if (grouping.is("Everything")) return "*";
        if (grouping.is("Kind") && containersTogether.on() && container(id)) return "container";
        return id;
    }

    private String nameOf(Block b) {
        return nameCache.computeIfAbsent(id(b), k -> b.getName().getString());
    }

    private static String noun(String name, int n) {
        String l = name.toLowerCase(Locale.ROOT);
        return n <= 1 || l.endsWith("s") ? l : l.endsWith("x") ? l + "es" : l + "s";
    }

    private static int find(int[] up, int i) {
        while (up[i] != i) { up[i] = up[up[i]]; i = up[i]; }
        return i;
    }

    private List<Group> build(List<BlockPos> pos) {
        int n = pos.size();
        String[] ids = new String[n], keys = new String[n];
        Block[] bl = new Block[n];
        int[] up = new int[n];
        for (int i = 0; i < n; i++) {
            bl[i] = mc.level.getBlockState(pos.get(i)).getBlock();
            ids[i] = id(bl[i]);
            keys[i] = kindKey(ids[i]);
            up[i] = i;
        }
        boolean off = grouping.is("Off");
        if (!off) {
            double link = grouping.is("Veins") ? 1.8 : groupDistance.get();
            int cell = Math.max(1, (int) Math.ceil(link));
            Map<Long, List<Integer>> grid = new HashMap<>();
            for (int i = 0; i < n; i++) {
                BlockPos p = pos.get(i);
                grid.computeIfAbsent(BlockPos.asLong(Math.floorDiv(p.getX(), cell), Math.floorDiv(p.getY(), cell), Math.floorDiv(p.getZ(), cell)), k -> new ArrayList<>()).add(i);
            }
            double l2 = link * link;
            for (int i = 0; i < n; i++) {
                BlockPos p = pos.get(i);
                int cx = Math.floorDiv(p.getX(), cell), cy = Math.floorDiv(p.getY(), cell), cz = Math.floorDiv(p.getZ(), cell);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    List<Integer> l = grid.get(BlockPos.asLong(cx + dx, cy + dy, cz + dz));
                    if (l == null) continue;
                    for (int j : l) {
                        if (j <= i || !keys[j].equals(keys[i]) || pos.get(j).distSqr(p) > l2) continue;
                        int a = find(up, i), b = find(up, j);
                        if (a != b) up[a] = b;
                    }
                }
            }
        }
        Map<Integer, List<Integer>> sets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) sets.computeIfAbsent(find(up, i), k -> new ArrayList<>()).add(i);
        boolean outline = grouping.is("Veins");
        List<Group> out = new ArrayList<>();
        for (List<Integer> m : sets.values()) {
            List<BlockPos> ps = new ArrayList<>(m.size());
            Map<String, Integer> kinds = new HashMap<>();
            Map<String, Block> sample = new HashMap<>();
            double x0 = 1e9, y0 = 1e9, z0 = 1e9, x1 = -1e9, y1 = -1e9, z1 = -1e9;
            for (int i : m) {
                BlockPos p = pos.get(i);
                ps.add(p);
                kinds.merge(ids[i], 1, Integer::sum);
                sample.put(ids[i], bl[i]);
                x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
                x1 = Math.max(x1, p.getX() + 1); y1 = Math.max(y1, p.getY() + 1); z1 = Math.max(z1, p.getZ() + 1);
            }
            String top = null;
            for (var e : kinds.entrySet()) if (top == null || e.getValue() > kinds.get(top) || (e.getValue().equals(kinds.get(top)) && e.getKey().compareTo(top) < 0)) top = e.getKey();
            int col = colorOf(sample.get(top));
            AABB box = new AABB(x0, y0, z0, x1, y1, z1);
            String label = null;
            if (m.size() > 1) {
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(kinds.entrySet());
                sorted.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
                if (kinds.size() == 1) label = cap(noun(nameOf(sample.get(top)), m.size())) + " x" + m.size();
                else {
                    StringBuilder sb = new StringBuilder(grouping.is("Everything") ? "Base stuff" : "Storage").append(" x").append(m.size()).append(" (");
                    int shown = 0;
                    for (var e : sorted) {
                        if (shown == 3) { sb.append(", +").append(sorted.size() - 3).append(" more"); break; }
                        sb.append(shown++ > 0 ? ", " : "").append(e.getValue()).append(' ').append(noun(nameOf(sample.get(e.getKey())), e.getValue()));
                    }
                    label = sb.append(')').toString();
                }
            }
            Vec3 at = new Vec3((x0 + x1) / 2, y1 + 0.35, (z0 + z1) / 2);
            Map<String, Integer> kc = new HashMap<>();
            Map<String, String> kn = new HashMap<>();
            for (String k : kinds.keySet()) { kc.put(k, colorOf(sample.get(k))); kn.put(k, nameOf(sample.get(k))); }
            String title = label != null ? (label.indexOf(" (") > 0 ? label.substring(0, label.indexOf(" (")) : label) : cap(nameOf(sample.get(top)));
            out.add(new Group(label, m.size(), box, at, col, ps, outline, kinds, title, kc, kn));
        }
        return out;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------------

    @Override
    public void onRender3D(R3 r) {
        List<Group> gs = groups;
        if (gs.isEmpty()) return;
        boolean outlineOn = !mode.is("Fill"), fillOn = !mode.is("Outline");
        int fa = fillOpacity.i();
        float w = width.get().floatValue();
        Vec3 cam = r.cam;
        // lone blocks are bucketed by colour (and fade step) so touching ones of a kind still read as one outline
        Map<Integer, List<BlockPos>> singles = new HashMap<>();
        Map<Integer, Float> stepAlpha = new HashMap<>();
        List<Group> labelled = new ArrayList<>();
        for (Group g : gs) {
            Vec3 c = g.box().getCenter();
            double d = Math.sqrt(g.box().distanceToSqr(cam));
            float f = fade.on() ? (float) Math.max(0.3, Math.min(1, 1 - (d - fadeStart.get()) / 128.0)) : 1f;
            int col = Colors.fade(g.color(), f);
            if (g.count() == 1) {
                int key = Colors.fade(g.color(), Math.round(f * 8) / 8f);
                singles.computeIfAbsent(key, k -> new ArrayList<>()).add(g.pos().get(0));
                stepAlpha.put(key, Math.round(f * 8) / 8f);
            } else if (g.outline()) {
                R3.Outline o = prepared.get(g);
                if (o == null) o = R3.prepare(g.pos());
                r.outline(o, col, w, true, fillOn ? Math.round(fa * f) : 0, outlineOn);
            } else {
                AABB b = g.box().inflate(0.1);
                if (fillOn) r.fill(b, Colors.fade(col, fa / 255f), true);
                if (outlineOn || !fillOn) r.box(b, col, w + 0.5f, true);
                if (members.on()) for (BlockPos p : g.pos()) r.box(new AABB(p), Colors.fade(col, 0.3f), 1f, true);
            }
            double lr = labelMode.is("Short tag") ? Math.min(32, labelRange.get()) : labelRange.get();
            if (!labelMode.is("Card only") && g.count() > 1 && g.label() != null && labelRange.i() > 0 && d <= lr && g != aimedNow) labelled.add(g);
            if (tracers.on()) r.tracer(c, col, 1.1f);
        }
        for (var e : singles.entrySet()) {
            r.outline(bucketCache.get(e.getKey(), e.getValue()), e.getKey(), w, true, fillOn ? Math.round(fa * stepAlpha.get(e.getKey())) : 0, outlineOn || !fillOn);
        }
        bucketCache.endFrame();
        // labels: biggest group first, nudged up when one sits right above another, hidden after three nudges
        labelled.sort((a, b) -> b.count() - a.count());
        List<Vec3> placed = new ArrayList<>();
        List<String> tagsDrawn = new ArrayList<>();
        int shownLabels = 0;
        for (Group g : labelled) {
            if (shownLabels >= 40) break;
            Vec3 at = g.labelAt();
            int tries = 0;
            while (tries < 4 && overlaps(placed, at)) { at = at.add(0, 1.1, 0); tries++; }
            if (tries == 4) continue;
            placed.add(at);
            shownLabels++;
            double d = at.distanceTo(cam);
            float scale = (float) Math.max(0.65, Math.min(1.3, 1.45 - d / labelRange.get() * 0.8));
            float f = (float) Math.max(0.45, Math.min(1, 1.6 - d / labelRange.get() * 1.2));
            String text = tagText(g);
            tagsDrawn.add(text);
            r.label(at, text, Colors.fade(0xFF000000 | g.color(), f), scale);
        }
        tags = tagsDrawn;
    }

    /** What a world label says in the current Labels mode: "x13" for Short tag, the whole label for Full (old). */
    public String tagText(Group g) {
        return labelMode.is("Short tag") ? "x" + g.count() : g.label();
    }

    private volatile List<String> tags = List.of();
    private volatile Group aimedNow;
    private Group heldAim;
    private long heldAt;
    private boolean middleWas;

    /** For the rig: the label texts drawn in the world last frame. */
    public List<String> tagsDrawn() { return tags; }
    /** For the rig: the group the card is on right now, or null. */
    public Group aimed() { return aimedNow; }
    public static final double CONE = 0.05;
    /** Last card as placed by Callouts, for the rig. */
    public static final String CARD_ID = "blockesp:card";

    /** The group the crosshair is on: its box when the look ray goes through it (nearest first), else the closest one within a small cone. */
    private Group pickAim() {
        var cam = mc.gameRenderer.getMainCamera();
        Vec3 eye = cam.position();
        var f = cam.forwardVector();
        Vec3 fwd = new Vec3(f.x(), f.y(), f.z());
        Vec3 far = eye.add(fwd.scale(256));
        Group best = null;
        double bestMiss = Double.MAX_VALUE, bestDist = Double.MAX_VALUE;
        for (Group g : groups) {
            double miss, dist;
            var hit = g.box().inflate(0.15).clip(eye, far);
            if (hit.isPresent()) { miss = 0; dist = hit.get().distanceTo(eye); }
            else {
                Vec3 c = g.box().getCenter().subtract(eye);
                double along = c.dot(fwd);
                if (along <= 0) continue;
                miss = c.subtract(fwd.scale(along)).length() / along;
                dist = along;
            }
            if (miss > CONE) continue;
            if (miss == 0 && bestMiss == 0 ? dist < bestDist : miss < bestMiss) { best = g; bestMiss = miss; bestDist = dist; }
        }
        return best;
    }

    /** Middle click on the aimed group: a waypoint at its middle, named like its card. */
    public boolean waypointAimed() {
        Group g = aimedNow;
        if (g == null) return false;
        Vec3 c = g.box().getCenter();
        dev.vantage.module.finder.Waypoints.get().add(g.title(), (int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z), g.color());
        return true;
    }

    // ---- the card ------------------------------------------------------------------------------------------------------------

    private static final float CARD_W = 214, ROW = 14;

    private static boolean ore(String id) {
        return id.endsWith("_ore") || id.equals("ancient_debris") || id.equals("gilded_blackstone");
    }

    private static boolean storageLike(Group g) {
        for (String k : g.kinds().keySet()) if (container(k) || k.endsWith("spawner") || k.equals("vault")) return true;
        return false;
    }

    /** True when some block of the group touches air or anything see-through. */
    private boolean exposedAny(Group g) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (BlockPos p : g.pos())
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                n.setWithOffset(p, d);
                if (!mc.level.getBlockState(n).isSolidRender()) return true;
            }
        return false;
    }

    private List<Map.Entry<String, Integer>> sortedKinds(Group g) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(g.kinds().entrySet());
        l.sort((a, b) -> !a.getValue().equals(b.getValue()) ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        return l;
    }

    /** Everything the card prints, as lines, so the rig can read it and the layout can size itself. Row types: kind rows are "kind|id|text|count". */
    public record CardInfo(List<String[]> kinds, String ore, String where, String note, int more) {}

    public CardInfo cardInfo(Group g) {
        List<String[]> rows = new ArrayList<>();
        var sorted = sortedKinds(g);
        for (int i = 0; i < sorted.size() && i < 5; i++) {
            var e = sorted.get(i);
            rows.add(new String[] {e.getKey(), noun(g.kindNames().get(e.getKey()), e.getValue()), String.valueOf(e.getValue())});
        }
        String oreLine = null;
        boolean allOre = !g.kinds().isEmpty() && g.kinds().keySet().stream().allMatch(BlockEsp::ore);
        if (allOre) {
            int total = 0;
            for (Group o : groups) for (var e : o.kinds().entrySet()) if (g.kinds().containsKey(e.getKey())) total += e.getValue();
            oreLine = "Vein of " + g.count() + " · " + total + " in range";
        }
        Vec3 c = g.box().getCenter();
        var eye = mc.player.position();
        double dx = c.x - eye.x, dz = c.z - eye.z;
        int dist = (int) Math.round(Math.sqrt(g.box().distanceToSqr(mc.gameRenderer.getMainCamera().position())));
        String where = dist + "m " + Compass.name(dx, dz) + " · Y " + (int) Math.floor(c.y) + " · chunk " + (ChunkMath.chunk(c.x)) + ", " + (ChunkMath.chunk(c.z));
        String note = null;
        if (storageLike(g)) note = exposedAny(g) ? "Open to air" : "Not exposed: seen through walls";
        return new CardInfo(rows, oreLine, where, note, Math.max(0, sorted.size() - 5));
    }

    private float cardHeight(CardInfo ci) {
        float h = 34 + ci.kinds().size() * ROW + (ci.more() > 0 ? ROW : 0) + 4;
        if (ci.ore() != null) h += ROW;
        h += 16; // where
        if (ci.note() != null) h += 14;
        return h + 17; // hint
    }

    private void drawCard(GuiGraphics gg, Group grp, CardInfo ci, float x, float y, float w, float h) {
        int col = grp.color();
        Ui.card(gg, x, y, w, h, 0);
        // the header strip: a faint wash of the group's colour with a solid hairline under it, flat
        Ui.rr(gg, x + 1, y + 1, w - 2, 26, 5, Ui.fade(col, 0.16f), 0);
        Ui.rect(gg, x + 1, y + 26, w - 2, 2, col);
        String t = Ui.fit(grp.title(), w - 66, 1.1f);
        Ui.text(gg, t, x + 10, y + 8, Ui.TEXT, 1.1f, false);
        Ui.right(gg, ci.where().substring(0, ci.where().indexOf('m') + 1), x + w - 9, y + 9, Ui.SUB, 0.9f, false);
        float ry = y + 35;
        for (String[] r : ci.kinds()) {
            int kc = grp.kindColors().getOrDefault(r[0], col);
            Ui.rr(gg, x + 11, ry + 2.5f, 5, 5, 2.5f, kc, 0);
            Ui.text(gg, Ui.fit(cap(r[1]), w - 70, 0.95f), x + 23, ry, Ui.TEXT, 0.95f, false);
            Ui.right(gg, r[2], x + w - 10, ry, Ui.SUB, 0.95f, false);
            ry += ROW;
        }
        if (ci.more() > 0) { Ui.text(gg, "+" + ci.more() + " more kinds", x + 23, ry, Ui.FAINT, 0.85f, false); ry += ROW; }
        ry += 2;
        Ui.hline(gg, x + 10, ry, w - 20, Ui.LINE);
        ry += 6;
        if (ci.ore() != null) { Ui.text(gg, ci.ore(), x + 10, ry, col, 0.9f, false); ry += ROW; }
        Ui.text(gg, Ui.fit(ci.where(), w - 20, 0.87f), x + 10, ry, Ui.SUB, 0.87f, false);
        ry += 16;
        if (ci.note() != null) { Ui.text(gg, Ui.fit(ci.note(), w - 20, 0.87f), x + 10, ry - 2, ci.note().startsWith("Open") ? Ui.GREEN : Ui.YELLOW, 0.87f, false); ry += 14; }
        Ui.text(gg, "Middle click: waypoint", x + 10, y + h - 15, Ui.FAINT, 0.8f, false);
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (mc.level == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        Group a = card.on() && !Hud.hidden() && mc.screen == null ? pickAim() : null;
        if (a != null) { heldAim = a; heldAt = now; }
        else if (heldAim != null && now - heldAt < 250 && groups.contains(heldAim) && card.on() && !Hud.hidden()) a = heldAim;
        else heldAim = null;
        aimedNow = a;
        if (a != null) {
            CardInfo ci = cardInfo(a);
            float h = cardHeight(ci);
            float[] at = Ui.project(a.box().getCenter());
            float ax = at == null ? Ui.W() / 2 : at[0], ay = at == null ? Ui.H() / 2 : at[1];
            ax = Math.max(40, Math.min(Ui.W() - 40, ax));
            ay = Math.max(40, Math.min(Ui.H() - 40, ay));
            Group fa = a;
            Callouts.submit(CARD_ID, ax, ay, 900, a.color(), List.of(new Callouts.Form(Callouts.FULL, CARD_W, h)),
                    (gg, kind, x, y, w, hh) -> drawCard(gg, fa, ci, x, y, w, hh));
        }
        if (hudList.on() && !groups.isEmpty()) drawHud(g);
    }

    private void drawHud(GuiGraphics g) {
        var eye = mc.gameRenderer.getMainCamera().position();
        List<Group> sorted = new ArrayList<>(groups);
        sorted.sort(Comparator.comparingDouble(o -> o.box().distanceToSqr(eye)));
        final float w = 220, row = 22;
        int rows = Math.min(5, sorted.size());
        boolean more = sorted.size() > rows;
        float h = 31 + rows * row + (more ? 16 : 3);
        float[] at = Hud.slot("blockesp", "Top Left", w, h);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        Ui.panel(g, x, y, w, h);
        Ui.text(g, "Block ESP", x + 9, y + 7, Ui.TITLE, 1.1f, false);
        String near = sorted.size() + (sorted.size() == 1 ? " group" : " groups");
        float pw = Ui.width(near, 0.95f, false) + 12;
        Ui.rr(g, x + w - pw - 9, y + 6, pw, 16, 4, 0x30808CFF, 0);
        Ui.text(g, near, x + w - pw - 3, y + 10, Ui.TITLE, 0.95f, false);
        for (int i = 0; i < rows; i++) {
            Group o = sorted.get(i);
            float ry = y + 30 + i * row;
            Ui.rr(g, x + 11, ry + 4, 5, 5, 2.5f, o.color(), 0);
            Ui.text(g, Ui.fit(o.title(), w - 80, 0.95f), x + 23, ry + 1, Ui.TEXT, 0.95f, false);
            Ui.right(g, (int) Math.round(Math.sqrt(o.box().distanceToSqr(eye))) + "m", x + w - 10, ry + 1, Ui.SUB, 0.95f, false);
        }
        if (more) {
            Ui.hline(g, x + 9, y + h - 21, w - 18, Ui.LINE);
            Ui.text(g, "+" + (sorted.size() - rows) + " more groups", x + 9, y + h - 14, Ui.FAINT, 0.85f, false);
        }
        Ui.end(g);
    }

    private static boolean overlaps(List<Vec3> placed, Vec3 at) {
        for (Vec3 p : placed) if (Math.abs(p.x - at.x) < 2.6 && Math.abs(p.z - at.z) < 2.6 && Math.abs(p.y - at.y) < 1.0) return true;
        return false;
    }
}
