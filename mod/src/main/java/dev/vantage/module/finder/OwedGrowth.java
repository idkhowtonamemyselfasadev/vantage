package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.chunk.Chunks;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Land nobody has been near, and the gaps in it. A block that is owed a growth step (kelp under 25 with water above, a crop short of
 * ripe in the light, dirt next to grass, snow missing in a snowy biome...) and never got it proves no player simulated that chunk
 * lately. Every chunk with owed blocks clears the square around it. A chunk that nothing clears, with enough cleared land around
 * and cleared land on at least three sides, is where somebody has been keeping chunks ticking. The inverse of grown kelp: there the
 * growth proves a player, here the missing growth proves the absence of one, and the hole in that proof is the player.
 * Donut's Folia fork does not tick everything the vanilla way, so kelp, buds, cave vines and snow follow its own rules there.
 * Reads loaded chunks on the client thread, two milliseconds a tick.
 */
public class OwedGrowth extends Module {
    private static final int GROWTH_LIGHT = 9, MELT_LIGHT = 10, BAMBOO_LIMIT = 16, SHORT_COLUMN = 3, KELP_MAX_AGE = 25, ENCLOSED_SIDES = 3, LOADED_MARGIN = 3;
    private static final String DONUT_BRAND = "DonutFolia";

    public final NumberSetting radius = add(new NumberSetting("Simulation distance", "How far an owed chunk clears, in chunks", 4, 2, 16, 1));
    public final NumberSetting sensitivity = add(new NumberSetting("Sensitivity", "Owed blocks needed within 2r+2 chunks before a gap counts", 40, 1, 500, 1));
    public final BoolSetting proof = add(new BoolSetting("Needs grown proof", "Only a chunk with a few fully grown plants (kelp 24+, 3-tall cane or cactus, max crops) can be a gap. Off: any chunk without young growth counts, which lights up every dug-out area, desert and bare stone", true));
    public final BoolSetting tiles = add(new BoolSetting("Tiles", "Draw every flagged chunk as a tile at Y 63 (a gap also adds a line to Base Finder squares that something else flagged)", true));
    public final ColorSetting color = add(new ColorSetting("Color", "", 0xFFFF4040)).visibleIf(tiles::on);
    public final NumberSetting range = add(new NumberSetting("Range", "Draw tiles this many chunks away", 24, 4, 48, 1)).visibleIf(tiles::on);

    /** weight = owed blocks in the chunk; clears = weight > 0. */
    /** weight = owed blocks (young growth), grown = plants that provably ticked to their end (kelp 24+, 3-tall cane or cactus, max crops). */
    public record Rec(int weight, boolean clears, int grown) {
        public Rec(int weight, boolean clears) { this(weight, clears, 0); }
    }
    /** A gap needs this many grown plants: bare rock, sand or a dug-out hole has nothing to grow and proves nothing. */
    public static final int MIN_GROWN = 3;

    private final Map<Long, Rec> recs = new HashMap<>();
    private final Map<Long, Integer> dirty = new HashMap<>();
    private Set<Long> flagged = new HashSet<>();
    private volatile Set<Long> pendingFlag;
    private volatile boolean flagRunning;
    private boolean recsChanged = true;
    private ChunkPos flagAt = new ChunkPos(Integer.MIN_VALUE, Integer.MIN_VALUE);
    private static final java.util.concurrent.ExecutorService FLAG_THREAD = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Vantage owed growth");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private int tick;

    public OwedGrowth() {
        super("Owed Growth", "Chunks nobody has been near, from growth that is overdue, and the gaps in that land", Category.FINDER);
    }

    @Override
    public String info() {
        return flagged.size() + " · " + recs.size() + " scanned";
    }

    public Set<Long> flagged() {
        return new HashSet<>(flagged);
    }

    public Rec rec(int cx, int cz) {
        return recs.get(ChunkPos.asLong(cx, cz));
    }

    public int pending() {
        return Chunks.pending(this);
    }

    private boolean donut() {
        var c = mc.getConnection();
        return c != null && c.serverBrand() != null && c.serverBrand().contains(DONUT_BRAND);
    }

    // ---- the owed table ------------------------------------------------------------------------------------------------------

    private BlockState state(BlockPos p) {
        return mc.level.getBlockState(p);
    }

    private int light(BlockPos p) {
        int l = Math.max(mc.level.getBrightness(LightLayer.BLOCK, p), mc.level.getBrightness(LightLayer.SKY, p));
        if (l < 15 && mc.level.dimensionType().hasSkyLight() && mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()) <= p.getY()) l = 15;
        return l;
    }

    /** One snow layer is fine; a full fluid smothers; otherwise anything opaque enough to block the light off a grass top. */
    private boolean smothers(BlockPos above) {
        BlockState a = state(above);
        if (a.is(Blocks.SNOW) && a.getValue(SnowLayerBlock.LAYERS) == 1) return false;
        if (a.getFluidState().getAmount() == 8) return true;
        return a.getLightBlock() >= 15;
    }

    private int stack(BlockPos p, Block b) {
        int n = 1;
        while (state(p.below(n)).is(b)) n++;
        return n;
    }

    private int kelpColumn(BlockPos p) {
        int n = 1;
        while (state(p.below(n)).is(Blocks.KELP_PLANT) || state(p.below(n)).is(Blocks.KELP)) n++;
        return n;
    }

    /** True when this block is owed a tick it never got. */
    boolean owed(BlockPos p, BlockState s, boolean donut) {
        Block b = s.getBlock();
        if (b == Blocks.KELP) {
            boolean water = mc.level.getFluidState(p.above()).is(FluidTags.WATER);
            if (donut) return water && kelpColumn(p) < SHORT_COLUMN;
            return s.getValue(KelpBlock.AGE) < KELP_MAX_AGE && water;
        }
        if (b == Blocks.CAVE_VINES) return !donut && s.getValue(CaveVinesBlock.AGE) < 25 && state(p.below()).isAir();
        if (b == Blocks.WEEPING_VINES) return s.getValue(WeepingVinesBlock.AGE) < 25 && state(p.below()).isAir();
        if (b == Blocks.BAMBOO) {
            if (s.getValue(BambooStalkBlock.STAGE) != 0 || !state(p.above()).isAir()) return false;
            return stack(p, Blocks.BAMBOO) < BAMBOO_LIMIT && light(p.above()) >= GROWTH_LIGHT;
        }
        if (b == Blocks.SUGAR_CANE) return state(p.above()).isAir() && stack(p, Blocks.SUGAR_CANE) < 3;
        if (b == Blocks.CACTUS) return state(p.above()).isAir() && (s.getValue(CactusBlock.AGE) < 15 || stack(p, Blocks.CACTUS) < 3);
        if (b == Blocks.COCOA) return s.getValue(CocoaBlock.AGE) < 2;
        if (b == Blocks.SWEET_BERRY_BUSH) return s.getValue(SweetBerryBushBlock.AGE) < 3 && light(p.above()) >= GROWTH_LIGHT;
        if (b == Blocks.NETHER_WART) return s.getValue(NetherWartBlock.AGE) < 3;
        if (b instanceof CropBlock c) return !c.isMaxAge(s) && light(p) >= GROWTH_LIGHT;
        if (b instanceof StemBlock) return s.getValue(StemBlock.AGE) < 7 && light(p) >= GROWTH_LIGHT;
        if (b == Blocks.BUDDING_AMETHYST) {
            if (donut) return false;
            for (Direction d : Direction.values()) {
                BlockState n = state(p.relative(d));
                FluidState f = n.getFluidState();
                if (n.isAir() || (f.isSource() && f.is(FluidTags.WATER))) return true;
            }
            return false;
        }
        if (b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.LARGE_AMETHYST_BUD) {
            if (donut) return true;
            if (!s.hasProperty(BlockStateProperties.FACING)) return false;
            return state(p.relative(s.getValue(BlockStateProperties.FACING).getOpposite())).is(Blocks.BUDDING_AMETHYST);
        }
        if (b == Blocks.VINE) {
            boolean side = s.getValue(VineBlock.NORTH) || s.getValue(VineBlock.EAST) || s.getValue(VineBlock.SOUTH) || s.getValue(VineBlock.WEST);
            return state(p.below()).isAir() && side;
        }
        if (b instanceof LeavesBlock) return s.getValue(LeavesBlock.DISTANCE) == 7 && !s.getValue(LeavesBlock.PERSISTENT);
        if (b == Blocks.GRASS_BLOCK || b == Blocks.MYCELIUM) return smothers(p.above());
        if (b == Blocks.DIRT) {
            if (smothers(p.above()) || state(p.above()).getFluidState().is(FluidTags.WATER)) return false;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 3; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos q = p.offset(dx, dy, dz);
                BlockState n = state(q);
                if ((n.is(Blocks.GRASS_BLOCK) || n.is(Blocks.MYCELIUM)) && !smothers(q.above()) && light(q.above()) >= GROWTH_LIGHT) return true;
            }
            return false;
        }
        if (b == Blocks.MANGROVE_PROPAGULE && s.getValue(MangrovePropaguleBlock.HANGING)) return s.getValue(MangrovePropaguleBlock.AGE) < 4;
        return false;
    }

    /** True when this plant reached a state only ticks can produce (world gen never places it so). */
    boolean grown(BlockPos p, BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.KELP) return s.getValue(KelpBlock.AGE) >= 24; // world gen: 20..23
        if (b == Blocks.SUGAR_CANE) return state(p.above()).isAir() && stack(p, Blocks.SUGAR_CANE) >= 3;
        if (b == Blocks.CACTUS) return state(p.above()).isAir() && stack(p, Blocks.CACTUS) >= 3;
        if (b instanceof CropBlock c) return c.isMaxAge(s);
        return false;
    }

    /** Blocks worth a look at all; a palette without any of them skips the section. */
    private static boolean candidate(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.KELP || b == Blocks.CAVE_VINES || b == Blocks.WEEPING_VINES || b == Blocks.BAMBOO || b == Blocks.SUGAR_CANE || b == Blocks.CACTUS
                || b == Blocks.COCOA || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.NETHER_WART || b instanceof CropBlock || b instanceof StemBlock
                || b == Blocks.BUDDING_AMETHYST || b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.LARGE_AMETHYST_BUD
                || b == Blocks.VINE || b instanceof LeavesBlock || b == Blocks.GRASS_BLOCK || b == Blocks.MYCELIUM || b == Blocks.DIRT || b == Blocks.MANGROVE_PROPAGULE;
    }

    /** No light source within a diamond of radius 5 bright enough to melt here. */
    private boolean calmLight(BlockPos p) {
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            if (!mc.level.hasChunk((p.getX() + dx) >> 4, (p.getZ() + dz) >> 4)) return false;
        }
        for (int dx = -5; dx <= 5; dx++) for (int dy = -5; dy <= 5; dy++) for (int dz = -5; dz <= 5; dz++) {
            int dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            if (dist > 5) continue;
            BlockPos q = p.offset(dx, dy, dz);
            if (state(q).getLightEmission() - dist >= MELT_LIGHT) return false;
        }
        return true;
    }

    private boolean canHoldSnow(BlockPos below) {
        BlockState b = state(below);
        if (b.is(BlockTags.SNOW_LAYER_CANNOT_SURVIVE_ON)) return false;
        if (b.is(BlockTags.SNOW_LAYER_CAN_SURVIVE_ON)) return true;
        if (b.is(Blocks.SNOW) && b.getValue(SnowLayerBlock.LAYERS) == 8) return true;
        return b.isFaceSturdy(mc.level, below, Direction.UP);
    }

    /** Water whose surface is at sea level is the open sea or a shore: it says nothing about anyone clearing land, so it owes no ice that counts. */
    public static boolean openSeaLevelWater(int waterY, int sea) {
        return waterY == sea - 1;
    }

    /** Snow and ice the biome owes a column, checked on the surface only. */
    private int surfaceOwed(LevelChunk chunk, boolean donut) {
        int n = 0;
        int sea = mc.level.getSeaLevel();
        ChunkPos cp = chunk.getPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
            BlockPos surface = new BlockPos(cp.getMinBlockX() + x, top, cp.getMinBlockZ() + z);
            BlockPos under = surface.below();
            BlockState us = state(under);
            Biome biome = mc.level.getBiome(surface).value();
            if (state(surface).isAir() && biome.getPrecipitationAt(surface, sea) == Biome.Precipitation.SNOW && canHoldSnow(under)) {
                if (donut && state(under.below()).is(Blocks.DEEPSLATE)) continue;
                if (calmLight(surface)) n++;
            } else if (us.is(Blocks.WATER) && !biome.warmEnoughToRain(under, sea) && !openSeaLevelWater(under.getY(), sea)) {
                boolean sides = false, loaded = true;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos q = under.relative(d);
                    if (!mc.level.hasChunk(q.getX() >> 4, q.getZ() >> 4)) loaded = false;
                    else if (!state(q).is(Blocks.WATER)) sides = true;
                }
                if (loaded && sides && calmLight(under)) n++;
            }
        }
        return n;
    }

    private Rec scan(LevelChunk chunk) {
        boolean donut = donut();
        int weight = 0, grown = 0;
        LevelChunkSection[] secs = chunk.getSections();
        ChunkPos cp = chunk.getPos();
        for (int si = 0; si < secs.length; si++) {
            LevelChunkSection s = secs[si];
            if (s.hasOnlyAir() || !s.getStates().maybeHas(OwedGrowth::candidate)) continue;
            int y0 = mc.level.getSectionYFromSectionIndex(si) << 4;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState st = s.getBlockState(x, y, z);
                if (!candidate(st)) continue;
                p.set(cp.getMinBlockX() + x, y0 + y, cp.getMinBlockZ() + z);
                if (owed(p, st, donut)) weight++;
                else if (grown(p, st)) grown++;
            }
        }
        if (mc.level.dimensionType().hasSkyLight()) weight += surfaceOwed(chunk, donut);
        return new Rec(weight, weight > 0, grown);
    }

    // ---- flagging -------------------------------------------------------------------------------------------------------------

    private boolean loaded(long k) {
        return mc.level.getChunkSource().hasChunk(ChunkPos.getX(k), ChunkPos.getZ(k));
    }

    /** For each of the four directions: a clearing chunk in the strip reaching R out and R to each side. */
    static int coveredSides(Map<Long, Rec> recs, long k, int R) {
        int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k), n = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            boolean hit = false;
            for (int out = 1; out <= R && !hit; out++) for (int side = -R; side <= R; side++) {
                Rec rec = recs.get(ChunkPos.asLong(cx + d[0] * out + d[1] * side, cz + d[1] * out + d[0] * side));
                if (rec != null && rec.clears()) { hit = true; break; }
            }
            if (hit) n++;
        }
        return n;
    }

    /** The chunks nothing clears, with enough owed blocks around and cleared land on three sides. Pure, for the tests. */
    public static Set<Long> flag(Map<Long, Rec> recs, int r, int sensitivity, java.util.function.LongPredicate loaded) {
        return flag(recs, r, sensitivity, MIN_GROWN, loaded);
    }

    public static Set<Long> flag(Map<Long, Rec> recs, int r, int sensitivity, int minGrown, java.util.function.LongPredicate loaded) {
        int R = 2 * r + 2;
        Map<Long, Integer> coverage = new HashMap<>(), support = new HashMap<>();
        for (var e : recs.entrySet()) {
            if (!e.getValue().clears()) continue;
            int cx = ChunkPos.getX(e.getKey()), cz = ChunkPos.getZ(e.getKey());
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) coverage.merge(ChunkPos.asLong(cx + dx, cz + dz), 1, Integer::sum);
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) support.merge(ChunkPos.asLong(cx + dx, cz + dz), e.getValue().weight(), Integer::sum);
        }
        Set<Long> out = new HashSet<>();
        for (var e : recs.entrySet()) {
            long k = e.getKey();
            if (e.getValue().grown() < minGrown) continue;
            if (!loaded.test(k) || coverage.getOrDefault(k, 0) > 0) continue;
            if (support.getOrDefault(k, 0) < sensitivity) continue;
            if (coveredSides(recs, k, R) < ENCLOSED_SIDES) continue;
            out.add(k);
        }
        return out;
    }

    // ---- scheduling ---------------------------------------------------------------------------------------------------------

    /** One scan job per chunk in the chunk service: it runs inside the shared budget, a chunk waits once, and the job is dropped when the chunk unloads or changes first. */
    private void enqueue(long k) {
        int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
        Chunks.submit(this, "scan", cx, cz, budget -> {
            if (mc.level != null && mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c) {
                recs.put(k, scan(c));
                recsChanged = true;
            }
            return true;
        });
    }

    private void queueAround() {
        if (mc.player == null) return;
        int r = mc.options.getEffectiveRenderDistance() + LOADED_MARGIN;
        ChunkPos me = mc.player.chunkPosition();
        List<Long> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Chunks.generation(me.x + dx, me.z + dz) >= 1) list.add(ChunkPos.asLong(me.x + dx, me.z + dz));
        }
        list.sort(java.util.Comparator.comparingInt(k -> {
            int dx = ChunkPos.getX(k) - me.x, dz = ChunkPos.getZ(k) - me.z;
            return dx * dx + dz * dz;
        }));
        list.forEach(this::enqueue);
    }

    @Override
    public void onChunkData(LevelChunk chunk) {
        enqueue(chunk.getPos().toLong());
    }

    @Override
    public void onBlockUpdate(BlockPos pos, BlockState state) {
        dirty.put(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), 20);
    }

    @Override
    public void onSectionBurst(net.minecraft.core.SectionPos sp, int count, BlockPos first, BlockState firstState) {
        dirty.put(ChunkPos.asLong(sp.x(), sp.z()), 20);
    }

    @Override
    public void onChunkUnload(int cx, int cz) {
        dirty.remove(ChunkPos.asLong(cx, cz));
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        for (var it = dirty.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            if (e.getValue() > 1) { e.setValue(e.getValue() - 1); continue; }
            it.remove();
            enqueue(e.getKey());
        }
        Set<Long> done = pendingFlag;
        if (done != null) { pendingFlag = null; flagged = done; }
        // the flag pass walks every record times a (2r+1)^2 and a (4r+5)^2 square: 20-60 ms on the render thread in the owner's
        // stutter log, growing as records piled up. It runs on its own thread over a snapshot now, every 3 s, only when something changed.
        if (++tick % 60 == 0 && !flagRunning && (recsChanged || !mc.player.chunkPosition().equals(flagAt))) {
            int r = radius.i(), keep = mc.options.getEffectiveRenderDistance() + 2 * r + 2;
            ChunkPos me = mc.player.chunkPosition();
            recs.keySet().removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - me.x), Math.abs(ChunkPos.getZ(k) - me.z)) > keep);
            Map<Long, Rec> snap = new HashMap<>(recs);
            Set<Long> loadedNow = new HashSet<>();
            for (long k : snap.keySet()) if (loaded(k)) loadedNow.add(k);
            int sens = sensitivity.i(), min = proof.on() ? MIN_GROWN : 0;
            recsChanged = false;
            flagAt = me;
            flagRunning = true;
            FLAG_THREAD.execute(() -> {
                try { pendingFlag = flag(snap, r, sens, min, loadedNow::contains); }
                catch (Throwable t) { log().once("flag-pass-failed", t, () -> "flag pass failed (later failures are only counted)"); }
                finally { flagRunning = false; }
            });
        }
    }

    private void reset() {
        recs.clear(); dirty.clear(); flagged = new HashSet<>();
    }

    @Override
    protected void onEnable() {
        reset();
        if (mc.level != null) queueAround();
    }

    @Override public void onDisable() { reset(); }
    @Override
    public void onJoinWorld() {
        startWorld();
    }

    @Override
    public void onLeaveWorld() {
        reset();
    }

    @Override
    public void onDimensionChange() {
        startWorld();
    }

    private void startWorld() {
        reset();
        if (mc.level != null) queueAround();
    }

    @Override
    public void onRender3D(R3 r) {
        if (!tiles.on() || mc.level == null) return;
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z, rng = range.i();
        int c = color.argb() & 0xFFFFFF;
        double y = 63.05;
        for (long k : flagged) {
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) > rng) continue;
            double x0 = cx << 4, z0 = cz << 4;
            EspStyle.zone(r, x0, z0, x0 + 16, z0 + 16, y, c | 0xFF000000, true, true, true);
            // one label per 3x3 group of tiles, else a field of gaps turns into a wall of text
            boolean lead = true;
            for (int dx = -1; dx <= 1 && lead; dx++) for (int dz = -1; dz <= 1; dz++) {
                if ((dx < 0 || (dx == 0 && dz < 0)) && flagged.contains(ChunkPos.asLong(cx + dx, cz + dz))) { lead = false; break; }
            }
            if (lead) r.label(new Vec3(x0 + 8, y + 1.5, z0 + 8), "Gap in the quiet land", color.argb());
        }
    }

    @Override
    public int labelClass() {
        return LABEL_EVIDENCE;
    }
}
