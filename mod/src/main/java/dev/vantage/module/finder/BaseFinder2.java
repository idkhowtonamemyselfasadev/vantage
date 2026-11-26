package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Module;
import dev.vantage.core.Theme;
import dev.vantage.core.chunk.Chunks;
import dev.vantage.mixin.BaseSpawnerAccessor;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.ActionSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.Header;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Base Finder looks for blocks players place. This one looks at things the world generates and flags the ones
 * that were touched, which points at players even when nobody built anything:
 *
 * Geodes: worldgen puts a bud on ~35% of budding amethyst, so a geode with almost none was harvested, one with
 * nearly all of them covered sat in loaded chunks for a long time, and pistons or torches inside are never natural.
 * End portals: each frame has a 10% eye chance, so 5+ eyes or a lit portal means someone was there.
 * Desert temples: the pressure plate and the 9 TNT under the centre are gone once someone disarmed it.
 * Spawners: delay changed, torches around, or a triggered trial spawner.
 * Torches: deep underground and away from mineshafts and strongholds, the two places that have their own.
 */
public class BaseFinder2 extends Module {
    private enum Kind {
        GEODE("GEODE", 14), PORTAL("PORTAL", 10), TEMPLE("TEMPLE", 4), SPAWNER("SPAWNER", 1), TORCHES("TORCHES", 16);
        final String tag;
        final int radius;
        Kind(String tag, int radius) { this.tag = tag; this.radius = radius; }
    }

    private static final class Site {
        final Kind kind;
        final BlockPos first;
        final Set<BlockPos> points = new HashSet<>();
        String why;
        boolean pinged;
        Site(Kind kind, BlockPos first) { this.kind = kind; this.first = first; }
        BlockPos center() {
            long x = 0, y = 0, z = 0;
            for (BlockPos p : points) { x += p.getX(); y += p.getY(); z += p.getZ(); }
            int n = Math.max(1, points.size());
            return new BlockPos((int) (x / n), (int) (y / n), (int) (z / n));
        }
    }

    /** Fewer exposed budding blocks than this is too little to tell harvested from unlucky (0.65^10 = 1.3%). */
    private static final int GEODE_MIN_EXPOSED = 10;
    private static final int PORTAL_MIN_EYES = 5;
    private static final int TORCH_MIN_DEPTH = 12;
    /** Spawners tick down near any player, so the delay says nothing when that player could be you. */
    private static final int SPAWNER_SELF_RANGE = 18;

    private static final Set<Block> TORCH = Set.of(Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH);
    private static final Set<Block> FARM = Set.of(Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.OBSERVER, Blocks.HOPPER, Blocks.DISPENSER, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK);
    /** Mineshafts and strongholds place their own torches; a section next to any of these doesn't count. */
    private static final Set<Block> TORCH_STRUCTURE = Set.of(Blocks.RAIL, Blocks.COBWEB, Blocks.OAK_FENCE, Blocks.DARK_OAK_FENCE, Blocks.OAK_PLANKS, Blocks.DARK_OAK_PLANKS,
            Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.INFESTED_STONE_BRICKS, Blocks.IRON_BARS);
    private static final Set<Block> WANTED = Set.of(Blocks.BUDDING_AMETHYST, Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL, Blocks.BLUE_TERRACOTTA,
            Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH);

    private final Header looksFor = add(new Header("Looks for"));
    public final BoolSetting geodes = add(new BoolSetting("Geodes", "Harvested, farmed or lit amethyst geodes", true));
    public final BoolSetting grown = add(new BoolSetting("Grown geodes", "Geodes with nearly every budding block covered: their chunks stayed loaded for a long time", true));
    public final BoolSetting portals = add(new BoolSetting("End portals", "Lit portals or frames with 5+ eyes", true));
    public final BoolSetting temples = add(new BoolSetting("Desert temples", "Temples with the TNT trap taken out", true));
    public final BoolSetting spawners = add(new BoolSetting("Spawners", "Activated or torched spawners, triggered trial spawners", true));
    public final BoolSetting torches = add(new BoolSetting("Cave torches", "Torches deep underground, away from mineshafts and strongholds", true));
    public final NumberSetting minTorches = add(new NumberSetting("Min torches", "Torches in one spot before it counts", 3, 1, 20, 1));
    private final Header alerts = add(new Header("Alerts"));
    public final BoolSetting notify = add(new BoolSetting("Chat alert", "Message + sound for each find", true));
    public final BoolSetting waypoint = add(new BoolSetting("Auto waypoint", "Drop a waypoint on each find", false));
    private final Header display = add(new Header("Display"));
    public final BoolSetting show = add(new BoolSetting("Show in world", "Box + label on each find", true));
    public final NumberSetting maxDraw = add(new NumberSetting("Draw range", "Blocks around you to draw", 512, 32, 2048, 32));
    public final ColorSetting color = add(new ColorSetting("Color", "Marker colour", 0xFFAD879B));

    private final List<Site> sites = new ArrayList<>();
    private final Set<BlockPos> spawnersSeen = new HashSet<>();
    /** Temples sit on chunk borders; chunks waiting for a neighbour, with the tick to look again. */
    private final Map<Long, Integer> retry = new HashMap<>();
    private int ticks;

    public BaseFinder2() {
        super("Base Finder 2", "Natural structures players touched: harvested geodes, lit portals, disarmed temples, cave torches", Category.FINDER);
        add(new Header("Finds"));
        add(new ActionSetting("List finds", "Print the nearest 10 finds in chat", () -> {
            List<Site> l = new ArrayList<>(found());
            if (l.isEmpty()) Chat.info("no activity found yet");
            BlockPos me = mc.player.blockPosition();
            l.sort((a, b) -> Double.compare(a.center().distSqr(me), b.center().distSqr(me)));
            for (int i = 0; i < Math.min(10, l.size()); i++) {
                Site s = l.get(i);
                BlockPos c = s.center();
                Chat.info(s.kind.tag + "  " + Chat.coords(c.getX(), c.getY(), c.getZ()) + "  " + s.why);
            }
        }));
        add(new ActionSetting("Clear finds", "Forget all finds", () -> { reset(); rescan(); Chat.info("activity finds cleared"); }));
    }

    /** Where players used a natural structure, as clues for Base Finder's zones. */
    public List<BlockPos> touched() {
        List<BlockPos> l = new ArrayList<>();
        for (Site s : found()) {
            if (s.kind == Kind.GEODE && s.why.startsWith("grown out") && !s.why.contains(",")) continue; // a geode nobody touched, left loaded: growth, not a visit
            l.add(s.center());
        }
        return l;
    }

    /** One line per find, for the screenshot test log. */
    public List<String> lines() {
        List<String> l = new ArrayList<>();
        for (Site s : found()) l.add(s.kind.tag + " " + s.center().toShortString() + " " + s.why);
        return l;
    }

    private List<Site> found() {
        List<Site> l = new ArrayList<>();
        for (Site s : sites) if (s.why != null) l.add(s);
        return l;
    }

    private void reset() {
        sites.clear();
        spawnersSeen.clear();
        retry.clear();
    }

    private void rescan() {
        if (mc.level == null || mc.player == null) return;
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos pc = mc.player.chunkPosition();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) enqueue(pc.x + x, pc.z + z);
    }

    @Override
    protected void onEnable() {
        rescan();
    }

    @Override
    public void onLeaveWorld() {
        reset();
    }

    @Override
    public void onDimensionChange() {
        reset();
    }

    @Override
    public String info() {
        int n = found().size();
        return n == 0 ? null : String.valueOf(n);
    }

    /** Read the delay right when the chunk arrives, before our own client ticks it down. */
    @Override
    public void onChunkData(LevelChunk chunk) {
        scanSpawners(chunk, true);
        enqueue(chunk.getPos().x, chunk.getPos().z); // every data packet: a chunk that changed gets scanned again; the queue keeps one waiting scan per chunk, so there is no backlog
    }

    /**
     * One scan job per chunk in the chunk service: inside the shared budget, a chunk waits once, dropped when it unloads or changes
     * first. A chunk with no data yet is not queued: its first data queues it.
     */
    private void enqueue(int cx, int cz) {
        if (Chunks.generation(cx, cz) < 1) return;
        Chunks.submit(this, "scan", cx, cz, budget -> {
            if (mc.level != null && mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c) {
                scanSpawners(c, false);
                scan(c);
            }
            return true;
        });
    }

    @Override
    public void onTick() {
        ticks++;
        if (!retry.isEmpty()) {
            var it = retry.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getValue() > ticks) continue;
                enqueue(ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey()));
                it.remove();
            }
        }
    }

    private Site site(Kind kind, BlockPos p) {
        int r2 = kind.radius * kind.radius;
        for (Site s : sites) if (s.kind == kind && s.first.distSqr(p) <= r2) return s;
        Site s = new Site(kind, p);
        sites.add(s);
        return s;
    }

    /** Palette first, so a section without anything interesting costs almost nothing. */
    private static Set<Block> blocksIn(LevelChunkSection s) {
        Set<Block> in = new HashSet<>();
        // the predicate never matches, so true means a global palette that can't be listed: walk it instead
        boolean global = s.getStates().maybeHas(st -> { in.add(st.getBlock()); return false; });
        if (global) for (int i = 0; i < 4096; i++) in.add(s.getBlockState(i & 15, i >> 8, (i >> 4) & 15).getBlock());
        return in;
    }

    private static boolean anyOf(Set<Block> in, Set<Block> wanted) {
        for (Block b : wanted) if (in.contains(b)) return true;
        return false;
    }

    private void scan(LevelChunk chunk) {
        LevelChunkSection[] secs = chunk.getSections();
        List<Set<Block>> in = new ArrayList<>(secs.length);
        for (LevelChunkSection s : secs) in.add(s.hasOnlyAir() ? Set.of() : blocksIn(s));
        Set<Site> touched = new HashSet<>();
        // a re-scan starts this chunk's evidence from scratch: a torch, frame or budding block that is gone in the newer data must not stay
        int ccx = chunk.getPos().x, ccz = chunk.getPos().z;
        for (Site s : sites) {
            if (s.kind == Kind.SPAWNER) continue; // spawners are read by scanSpawners
            if (s.points.removeIf(q -> (q.getX() >> 4) == ccx && (q.getZ() >> 4) == ccz)) touched.add(s);
        }
        int ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        for (int si = 0; si < secs.length; si++) {
            if (!anyOf(in.get(si), WANTED)) continue;
            boolean torchOk = torches.on(); // the mineshaft / stronghold veto is local now: see nearStructure() (a player tunnel beside a generated mineshaft still counts)
            int oy = mc.level.getSectionYFromSectionIndex(si) << 4;
            for (int i = 0; i < 4096; i++) {
                int lx = i & 15, ly = i >> 8, lz = (i >> 4) & 15;
                BlockState st = secs[si].getBlockState(lx, ly, lz);
                Block b = st.getBlock();
                if (!WANTED.contains(b)) continue;
                BlockPos p = new BlockPos(ox + lx, oy + ly, oz + lz);
                Kind kind = null;
                if (b == Blocks.BUDDING_AMETHYST) { if (geodes.on()) kind = Kind.GEODE; }
                else if (b == Blocks.END_PORTAL_FRAME) { if (portals.on()) kind = Kind.PORTAL; }
                else if (b == Blocks.END_PORTAL) { if (portals.on() && mc.level.dimension() == Level.OVERWORLD) kind = Kind.PORTAL; }
                else if (b == Blocks.BLUE_TERRACOTTA) { if (temples.on() && templeCentre(p)) kind = Kind.TEMPLE; }
                else if (torchOk && p.getY() < mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, p.getX(), p.getZ()) - TORCH_MIN_DEPTH && !nearStructure(p)) kind = Kind.TORCHES;
                if (kind == null) continue;
                Site s = site(kind, p);
                s.points.add(p);
                touched.add(s);
            }
        }
        // a geode's verdict depends on the other geodes (do they show buds?), so one scanned again judges them all again
        if (touched.stream().anyMatch(t -> t.kind == Kind.GEODE)) for (Site o : sites) if (o.kind == Kind.GEODE) touched.add(o);
        for (Site s : touched) {
            if (s.points.isEmpty()) { s.why = null; continue; } // emptied: nothing to judge, nothing drawn
            String why = switch (s.kind) {
                case GEODE -> judgeGeode(s);
                case PORTAL -> judgePortal(s);
                case TEMPLE -> judgeTemple(s, chunk);
                case TORCHES -> s.points.size() >= minTorches.i() ? s.points.size() + " torches underground" : null;
                case SPAWNER -> s.why;
            };
            report(s, why);
        }
    }

    /** A torch on or beside the wood, rails, cobwebs, bricks or bars a generated mineshaft or stronghold is made of is the structure's, not a player's. */
    private boolean nearStructure(BlockPos p) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        boolean fence = false, planks = false;
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
            q.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
            Block b = mc.level.getBlockState(q).getBlock();
            // only what a generated structure has and a base rarely does: rails, cobwebs, mossy/cracked/infested bricks, iron bars; plain stone bricks and planks are common base walls
            if (b == Blocks.RAIL || b == Blocks.COBWEB || b == Blocks.MOSSY_STONE_BRICKS || b == Blocks.CRACKED_STONE_BRICKS || b == Blocks.INFESTED_STONE_BRICKS || b == Blocks.IRON_BARS) return true;
            if (b == Blocks.OAK_FENCE || b == Blocks.DARK_OAK_FENCE) fence = true;
            else if (b == Blocks.OAK_PLANKS || b == Blocks.DARK_OAK_PLANKS) planks = true;
        }
        return fence && planks; // the mineshaft support pattern: a fence post beside its planks
    }

    /** A player building in range changes single blocks, not whole chunks: a placed or removed torch queues its chunk for a scan again (one waiting scan per chunk, so no backlog). */
    @Override
    public void onBlockUpdate(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (!torches.on() || mc.level == null) return;
        Block b = state.getBlock();
        boolean placed = WANTED.contains(b) && b != Blocks.BUDDING_AMETHYST;
        // a removed torch arrives as air (or anything else): only a position that is one of our torch points matters
        boolean removed = !placed && sites.stream().anyMatch(s -> s.kind == Kind.TORCHES && s.points.contains(pos));
        if (placed || removed) enqueue(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** The temple floor has blue terracotta in its patterns too; only the centre one has orange on all four diagonals. */
    private boolean templeCentre(BlockPos p) {
        for (int dx = -1; dx <= 1; dx += 2) for (int dz = -1; dz <= 1; dz += 2) {
            if (!mc.level.getBlockState(p.offset(dx, 0, dz)).is(Blocks.ORANGE_TERRACOTTA)) return false;
        }
        return true;
    }

    private boolean loaded(BlockPos p) {
        return mc.level.hasChunk(p.getX() >> 4, p.getZ() >> 4);
    }

    private String judgeGeode(Site s) {
        int exposed = 0, buds = 0, xa = Integer.MAX_VALUE, ya = xa, za = xa, xb = Integer.MIN_VALUE, yb = xb, zb = xb;
        for (BlockPos p : s.points) {
            xa = Math.min(xa, p.getX()); ya = Math.min(ya, p.getY()); za = Math.min(za, p.getZ());
            xb = Math.max(xb, p.getX()); yb = Math.max(yb, p.getY()); zb = Math.max(zb, p.getZ());
            boolean open = false, bud = false;
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (!loaded(q)) continue;
                BlockState st = mc.level.getBlockState(q);
                if (st.getBlock() instanceof AmethystClusterBlock && st.getValue(AmethystClusterBlock.FACING) == d) bud = true;
                else if (st.isAir() || st.is(Blocks.WATER)) open = true;
            }
            if (bud) { buds++; exposed++; } else if (open) exposed++;
        }
        int farm = 0, lit = 0;
        for (BlockPos q : BlockPos.betweenClosed(xa - 2, ya - 2, za - 2, xb + 2, yb + 2, zb + 2)) {
            Block b = mc.level.getBlockState(q).getBlock();
            if (FARM.contains(b)) farm++;
            else if (TORCH.contains(b)) lit++;
        }
        List<String> why = new ArrayList<>();
        if (farm > 0) why.add("farm: " + farm + " redstone blocks");
        if (lit > 0) why.add(lit + " torches inside");
        if (exposed >= GEODE_MIN_EXPOSED && !dev.vantage.core.util.Alone.now()) { // alone, nobody else harvested or tended it: worldgen's own count of buds
            if (buds <= exposed * 0.06 && naturalBudsSeen(s)) why.add("harvested: " + buds + " buds on " + exposed + " budding");
            else if (grown.on() && buds >= exposed * 0.8) why.add("grown out: " + buds + " buds on " + exposed + " budding");
        }
        return why.isEmpty() ? null : String.join(", ", why);
    }

    /** A geode with no buds proves nothing about harvesting unless another geode here does have them: then worldgen's buds are being loaded and this one lost its own. */
    private boolean naturalBudsSeen(Site self) {
        for (Site o : sites) {
            if (o == self || o.kind != Kind.GEODE || o.points.isEmpty() || o.center().distSqr(self.center()) > 96 * 96) continue; // neighbours only
            int exposed = 0, buds = 0;
            for (BlockPos p : o.points) {
                boolean open = false, bud = false;
                for (Direction d : Direction.values()) {
                    BlockPos q = p.relative(d);
                    if (!loaded(q)) continue;
                    BlockState st = mc.level.getBlockState(q);
                    if (st.getBlock() instanceof AmethystClusterBlock && st.getValue(AmethystClusterBlock.FACING) == d) bud = true;
                    else if (st.isAir() || st.is(Blocks.WATER)) open = true;
                }
                if (bud) { buds++; exposed++; } else if (open) exposed++;
            }
            if (exposed >= GEODE_MIN_EXPOSED && buds >= exposed * 0.15) return true;
        }
        return false;
    }

    private String judgePortal(Site s) {
        int eyes = 0;
        boolean lit = false;
        for (BlockPos p : s.points) {
            BlockState st = mc.level.getBlockState(p);
            if (st.is(Blocks.END_PORTAL)) lit = true;
            else if (st.is(Blocks.END_PORTAL_FRAME) && st.getValue(EndPortalFrameBlock.HAS_EYE)) eyes++;
        }
        if (lit) return "portal lit";
        return eyes >= PORTAL_MIN_EYES ? eyes + " eyes in the frame" : null;
    }

    /** Plate 11 and TNT 13 blocks under the centre, TNT in a 3x3. */
    private String judgeTemple(Site s, LevelChunk chunk) {
        BlockPos c = s.first;
        for (int dx = -1; dx <= 1; dx += 2) for (int dz = -1; dz <= 1; dz += 2) {
            if (!loaded(c.offset(dx, 0, dz))) {
                retry.put(chunk.getPos().toLong(), ticks + 100);
                return null;
            }
        }
        boolean plate = mc.level.getBlockState(c.below(11)).is(Blocks.STONE_PRESSURE_PLATE);
        int tnt = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (mc.level.getBlockState(c.offset(dx, -13, dz)).is(Blocks.TNT)) tnt++;
        }
        if (plate && tnt == 9) return null;
        return "trap touched: plate " + (plate ? "there" : "gone") + ", " + tnt + "/9 TNT";
    }

    /** fresh = the chunk just arrived: read the delay now, but it isn't in the world yet, so torches wait for the queue. */
    private void scanSpawners(LevelChunk chunk, boolean fresh) {
        if (!spawners.on() || mc.player == null) return;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            boolean trial = be.getBlockState().getBlock() instanceof TrialSpawnerBlock;
            if (!(be instanceof SpawnerBlockEntity) && !trial) continue;
            BlockPos pos = be.getBlockPos().immutable();
            String why = null;
            if (spawnersSeen.add(pos)) {
                if (be instanceof SpawnerBlockEntity s) {
                    int delay = ((BaseSpawnerAccessor) s.getSpawner()).vantage$spawnDelay();
                    if (delay != 20 && mc.player.blockPosition().distSqr(pos) > SPAWNER_SELF_RANGE * SPAWNER_SELF_RANGE) why = "spawner delay " + delay;
                } else {
                    TrialSpawnerState st = be.getBlockState().getValue(TrialSpawnerBlock.STATE);
                    if (st != TrialSpawnerState.INACTIVE && st != TrialSpawnerState.WAITING_FOR_PLAYERS) why = "trial spawner " + st.getSerializedName();
                }
            }
            if (why == null && !fresh && lightAround(pos)) why = "torches around spawner";
            if (why == null) continue;
            Site site = site(Kind.SPAWNER, pos);
            site.points.add(pos);
            if (site.why == null) report(site, why);
        }
    }

    private boolean lightAround(BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, -1, -2), p.offset(2, 2, 2))) {
            if (TORCH.contains(mc.level.getBlockState(q).getBlock())) return true;
        }
        return false;
    }

    private void report(Site s, String why) {
        s.why = why;
        if (why == null || s.pinged) return;
        s.pinged = true;
        BlockPos c = s.center();
        if (notify.on()) {
            int dist = (int) Math.sqrt(mc.player.blockPosition().distSqr(c));
            Chat.alert(Component.literal(s.kind.tag + " ").withColor(color.argb()).append(Component.literal(
                    Chat.coords(c.getX(), c.getY(), c.getZ()) + "  " + dist + "m  " + why).withColor(Theme.TEXT)), 1.4f);
        }
        if (waypoint.on()) Waypoints.get().add(s.kind.tag.charAt(0) + s.kind.tag.substring(1).toLowerCase(), c.getX(), c.getY(), c.getZ(), color.argb());
    }

    @Override
    public void onRender3D(R3 r) {
        if (!show.on()) return;
        double max2 = maxDraw.get() * maxDraw.get();
        int c = color.argb();
        for (Site s : sites) {
            if (s.why == null) continue;
            BlockPos p = s.center();
            Vec3 m = Vec3.atCenterOf(p);
            if (m.distanceToSqr(r.cam) > max2) continue;
            double h = Math.max(1, Math.min(s.kind.radius, 6));
            AABB b = new AABB(m.x - h, m.y - h, m.z - h, m.x + h, m.y + h, m.z + h);
            EspStyle.box(r, b, c, true, true, EspStyle.STD, 1f, true);
            r.label(m.add(0, h + 1, 0), s.kind.tag + "  " + s.why, c);
        }
    }

    @Override
    public int labelClass() {
        return LABEL_EVIDENCE;
    }

    @Override
    public boolean groupsBoxes() {
        return true;
    }
}
