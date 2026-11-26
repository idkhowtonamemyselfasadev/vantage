package dev.vantage.module.finder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.vantage.core.Category;
import dev.vantage.core.LeakLog;
import dev.vantage.core.Module;
import dev.vantage.core.Toasts;
import dev.vantage.core.notify.Notify;
import dev.vantage.core.notify.Sound;
import dev.vantage.core.util.Cooldown;
import dev.vantage.core.util.Fmt;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where somebody else is standing, read off the chunks that keep ticking. The server only simulates chunks near a player, but it
 * sends entity and block packets out to the full view distance. A mob that walks, a crop that grows or leaves that decay in the ring
 * outside your own simulation distance can only be ticking because another player is near it. Every such packet adds a weighted
 * signal to its chunk; signals decay with a 45 s half-life, spread over a square the size of the simulation distance, and the local
 * maximum of that field is the centre of the other player's simulated square. A mob standing in the ring without moving for 40 s
 * proves its chunk is NOT ticking and vetoes it. Overworld only, nothing below Y 0. Only reads packets, nothing is sent.
 */
public class SimulationRing extends Module {
    private static final int COLOR = 0xFFFFD43B;
    private static final double HALF_LIFE_S = 45.0, MIN_SCORE = 0.5, SHARE_PER_SOURCE = 10.0;
    private static final long CHUNK_SETTLE_MS = 3000, BELOW_ZERO_MS = 3000, TELEPORT_QUIET_MS = 2000, FROZEN_MS = 40_000, OWN_AREA_MS = 60_000,
            SPAWN_GRACE_MS = 2000, HIT_KEEP_MS = 15 * 60_000;

    public final NumberSetting sensitivity = add(new NumberSetting("Sensitivity", "5 needs about four busy mobs or a growing farm; 10 fires on much less", 5, 1, 10, 1));
    public final BoolSetting toast = add(new BoolSetting("Toast", "Card for each new hit", true));
    public final BoolSetting draw = add(new BoolSetting("Columns", "Draw a column over each hit chunk (the hit also lands on Base Finder's ground square)", true));
    public final NumberSetting range = add(new NumberSetting("Range", "Draw hits this many chunks away", 16, 4, 32, 1)).visibleIf(draw::on);

    /** Signal kinds with their weight and the cap one chunk can hold of them. */
    public enum Kind {
        MOVE(2.5, 10, 0), HEAD(2.5, 10, 0), SPAWN(2, 4, 1), REMOVE(1.5, 4, 1), STATE(0.5, 4, 0), EVENT(5, 10, 0),
        BLOCK(1.6, 4, 1), TICK(10, 10, 1), LIGHTNING(10, 10, 0), HURT(5, 10, 0), EQUIP(5, 10, 0);
        public final double weight; public final int cap, spill;
        Kind(double weight, int cap, int spill) { this.weight = weight; this.cap = cap; this.spill = spill; }
    }

    private static final class Sum { double value; long last; }
    private static final class ChunkSignal { final Map<Kind, Sum> perKind = new HashMap<>(); }

    public record Hit(int cx, int cz, double score, long time) {}

    private final Map<Long, ChunkSignal> chunks = new HashMap<>();
    private final Map<Object, double[]> shares = new HashMap<>();
    private final Map<Long, Long> chunkAge = new HashMap<>();
    private final Map<Integer, Long> spawnTime = new HashMap<>();
    private final Map<Integer, Long> lastInsideOwn = new HashMap<>();
    private final Map<Integer, long[]> lightning = new HashMap<>(); // id -> [spawn millis]
    private final Set<Long> passed = new HashSet<>();
    /** id -> {x, y, z, yaw, head, since}: for the frozen-mob veto. */
    private final Map<Integer, double[]> still = new HashMap<>();
    private final ArrayDeque<Hit> hits = new ArrayDeque<>();
    private long belowZeroUntil, quietUntil;
    private final Cooldown toastGate = Cooldown.wall(3000);
    private Vec3 lastPos;
    private long handledPos = Long.MIN_VALUE;
    private int tick;

    public SimulationRing() {
        super("Simulation Ring", "Mobs and blocks ticking outside your own simulation range: another player keeps them running", Category.FINDER);
        // entity traffic from chunks outside your own simulation distance
        listen(dev.vantage.core.event.PacketHandledEvent.class, e -> {
            var k = e.packet;
            if (k instanceof net.minecraft.network.protocol.game.ClientboundMoveEntityPacket p) onMove(p);
            else if (k instanceof net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket p) onPositionSync(p);
            else if (k instanceof net.minecraft.network.protocol.game.ClientboundRotateHeadPacket p) onRotate(p);
            else if (k instanceof net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket p) onEntityData(p);
            else if (k instanceof net.minecraft.network.protocol.game.ClientboundEntityEventPacket p) onEntityEvent(p);
            else if (k instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket p) onAdd(p);
            else if (k instanceof net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket p) onEquipment(p);
            else if (k instanceof net.minecraft.network.protocol.game.ClientboundDamageEventPacket p) onDamage(p);
            else if (k instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) onTeleport();
        });
        // before the game handles them: a removed entity and a picked-up item are still in the world, so their place is known
        listen(dev.vantage.core.event.PacketInEvent.class, e -> {
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket p) onRemove(p);
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket p) onTake(p);
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket p && mc.level != null) blockChanged(p.getPos(), mc.level.getBlockState(p.getPos()), p.getBlockState());
        });
    }

    @Override
    public String info() {
        return hits.size() + " · ring " + ringStart() + ".." + view();
    }

    public List<Hit> hits() {
        return new ArrayList<>(hits);
    }

    /** The field score of a chunk right now, for tests. */
    public double scoreAt(int cx, int cz) {
        ChunkSignal cs = chunks.get(ChunkPos.asLong(cx, cz));
        return cs == null ? 0 : score(cs, System.currentTimeMillis());
    }

    // ---- geometry -----------------------------------------------------------------------------------------------------------

    private int view() {
        return mc.options.getEffectiveRenderDistance();
    }

    private int sim() {
        int s = mc.level == null ? 5 : mc.level.getServerSimulationDistance();
        return Math.max(1, Math.min(s > 0 ? s : 5, view() - 3));
    }

    private int ringStart() {
        return sim() + 2;
    }

    private boolean overworld() {
        return mc.level != null && mc.level.dimension() == Level.OVERWORLD;
    }

    private static int chebyshev(int ax, int az, int bx, int bz) {
        return Math.max(Math.abs(ax - bx), Math.abs(az - bz));
    }

    private boolean inRing(int cx, int cz, int spill) {
        ChunkPos me = mc.player.chunkPosition();
        int d = chebyshev(cx, cz, me.x, me.z);
        return d >= ringStart() + spill && d <= view() && mc.level.getChunkSource().hasChunk(cx, cz);
    }

    private boolean nearVisiblePlayer(int cx, int cz) {
        int r = sim() + 1;
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            ChunkPos c = p.chunkPosition();
            if (chebyshev(cx, cz, c.x, c.z) <= r) return true;
        }
        return false;
    }

    private boolean insideOwn(Entity e) {
        ChunkPos me = mc.player.chunkPosition(), c = e.chunkPosition();
        return chebyshev(c.x, c.z, me.x, me.z) <= sim();
    }

    // ---- ingestion -----------------------------------------------------------------------------------------------------------

    private boolean quiet(long now) {
        return mc.level == null || mc.player == null || !overworld() || belowZeroUntil > now || quietUntil > now || dev.vantage.core.util.Alone.now();
    }

    private boolean ignoredEntity(Entity e) {
        if (e == null || e instanceof Player || e.isRemoved()) return true;
        if (e.getVehicle() instanceof Player || e.hasPassenger(p -> p instanceof Player)) return true;
        if (e instanceof TamableAnimal t && t.isTame() && t.isOwnedBy(mc.player)) return true;
        if (e instanceof FallingBlockEntity f && f.getBlockState().is(Blocks.POINTED_DRIPSTONE)) return true;
        return false;
    }

    /** Why signals were taken or dropped, kept only in the test rig. */
    private static final boolean TRACE = Boolean.getBoolean("vantage.ui");
    public static final java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>();

    private void why(String s) {
        trace.add(s);
        if (trace.size() > 40) trace.removeFirst();
    }

    private void add(Kind kind, BlockPos where, Object source, long now) {
        if (where.getY() < 0 || quiet(now)) { if (TRACE) why(kind + " quiet/belowzero at " + where.toShortString()); return; }
        int cx = where.getX() >> 4, cz = where.getZ() >> 4;
        long ck = ChunkPos.asLong(cx, cz);
        String dim = dev.vantage.core.util.Dims.id(mc.level);
        if (dev.vantage.core.util.OwnTicks.spawnArea(mc, cx, cz) || dev.vantage.core.util.OwnTicks.ownedRecently(dim, cx, cz, now, sim() + 1)) { if (TRACE) why(kind + " own or spawn area " + cx + "," + cz); return; } // the server's spawn chunks and where you just were tick without anybody else
        if (!inRing(cx, cz, kind.spill) || passed.contains(ck) || nearVisiblePlayer(cx, cz)) { if (TRACE) why(kind + " not in ring " + cx + "," + cz + " passed " + passed.contains(ck)); return; }
        Long age = chunkAge.get(ck);
        if (age == null || now - age < CHUNK_SETTLE_MS) { if (TRACE) why(kind + " young chunk " + cx + "," + cz + " age " + age); return; }
        if (TRACE) why(kind + " counted " + cx + "," + cz);
        double[] share = shares.computeIfAbsent(source, s -> new double[] {SHARE_PER_SOURCE});
        double w = Math.min(kind.weight, share[0]); // one busy mob cannot carry a chunk over the line alone
        if (w <= 0) return;
        share[0] -= w;
        ChunkSignal cs = chunks.computeIfAbsent(ck, k -> new ChunkSignal());
        Sum s = cs.perKind.computeIfAbsent(kind, k -> new Sum());
        s.value = Math.min(kind.cap, s.value * decay(now - s.last) + w);
        s.last = now;
    }

    private void entitySignal(Kind kind, Entity e, long now) {
        if (ignoredEntity(e)) { if (TRACE) why(kind + " ignored entity " + (e == null ? "null" : e.getType().toShortString())); return; }
        Long own = lastInsideOwn.get(e.getId());
        if (own != null && now - own < OWN_AREA_MS) return; // it may have wandered out of your own square
        if (kind == Kind.STATE || kind == Kind.EQUIP) {
            Long sp = spawnTime.get(e.getId());
            if (sp != null && now - sp < SPAWN_GRACE_MS) return; // the data that comes with a spawn
        }
        add(kind, e.blockPosition(), e.getId(), now);
    }

    public void onMove(ClientboundMoveEntityPacket p) {
        if (mc.level == null || !p.hasPosition()) return;
        entitySignal(Kind.MOVE, p.getEntity(mc.level), System.currentTimeMillis());
    }

    public void onPositionSync(ClientboundEntityPositionSyncPacket p) {
        if (mc.level == null) return;
        entitySignal(Kind.MOVE, mc.level.getEntity(p.id()), System.currentTimeMillis());
    }

    public void onRotate(ClientboundRotateHeadPacket p) {
        if (mc.level == null) return;
        entitySignal(Kind.HEAD, p.getEntity(mc.level), System.currentTimeMillis());
    }

    public void onEntityData(ClientboundSetEntityDataPacket p) {
        if (mc.level == null) return;
        entitySignal(Kind.STATE, mc.level.getEntity(p.id()), System.currentTimeMillis());
    }

    public void onEntityEvent(ClientboundEntityEventPacket p) {
        if (mc.level == null) return;
        entitySignal(Kind.EVENT, p.getEntity(mc.level), System.currentTimeMillis());
    }

    public void onEquipment(ClientboundSetEquipmentPacket p) {
        if (mc.level == null) return;
        entitySignal(Kind.EQUIP, mc.level.getEntity(p.getEntity()), System.currentTimeMillis());
    }

    public void onAdd(ClientboundAddEntityPacket p) {
        if (mc.level == null) return;
        long now = System.currentTimeMillis();
        spawnTime.put(p.getId(), now);
        if (p.getType() == EntityType.LIGHTNING_BOLT) { lightning.put(p.getId(), new long[] {now}); return; }
        if (p.getType() == EntityType.PLAYER) return;
        Entity e = mc.level.getEntity(p.getId());
        if (e != null && ignoredEntity(e)) return;
        add(Kind.SPAWN, BlockPos.containing(p.getX(), p.getY(), p.getZ()), p.getId(), now);
    }

    public void onRemove(ClientboundRemoveEntitiesPacket p) {
        if (mc.level == null) return;
        long now = System.currentTimeMillis();
        for (int id : p.getEntityIds()) {
            long[] bolt = lightning.remove(id);
            Entity e = mc.level.getEntity(id);
            if (bolt != null) { if (e != null && now - bolt[0] < 3000) add(Kind.LIGHTNING, e.blockPosition(), id, now); continue; }
            if (e == null || ignoredEntity(e)) continue;
            Long own = lastInsideOwn.get(id);
            if (own != null && now - own < OWN_AREA_MS) continue;
            add(Kind.REMOVE, e.blockPosition(), id, now);
            spawnTime.remove(id);
            still.remove(id);
        }
    }

    public void onDamage(ClientboundDamageEventPacket p) {
        if (mc.level == null) return;
        DamageSource src = p.getSource(mc.level);
        if (src.is(DamageTypes.SWEET_BERRY_BUSH) || src.is(DamageTypes.STALAGMITE) || src.is(DamageTypes.FALLING_STALACTITE)) return;
        entitySignal(Kind.HURT, mc.level.getEntity(p.entityId()), System.currentTimeMillis());
    }

    /** A mob picking up an item: an entity event in all but name. */
    public void onTake(ClientboundTakeItemEntityPacket p) {
        if (mc.level == null) return;
        Entity collector = mc.level.getEntity(p.getPlayerId());
        if (collector == null || collector instanceof Player) return;
        entitySignal(Kind.EVENT, collector, System.currentTimeMillis());
    }

    /** Your own teleport: the ring moves with you, so the first packets after it mean nothing. */
    public void onTeleport() {
        quietUntil = System.currentTimeMillis() + TELEPORT_QUIET_MS;
    }

    private static boolean ignoredBlock(BlockState s) {
        return s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT) || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.BEE_NEST) || s.is(Blocks.VINE);
    }

    private static boolean growsFromAir(Block b) {
        return b == Blocks.SUGAR_CANE || b == Blocks.CACTUS || b == Blocks.BAMBOO || b == Blocks.MELON || b == Blocks.PUMPKIN || b == Blocks.SNOW
                || b == Blocks.FIRE || b == Blocks.BROWN_MUSHROOM || b == Blocks.RED_MUSHROOM || b == Blocks.CHORUS_PLANT || b == Blocks.CHORUS_FLOWER;
    }

    /** A change only random ticking makes: crops ageing, saplings turning into trees, leaves decaying, grass spreading, copper weathering... */
    static boolean tickChange(BlockState old, BlockState now) {
        Block ob = old.getBlock(), nb = now.getBlock();
        if (ob instanceof CropBlock c && nb == ob && c.getMaxAge() > 0 && now.getValue(CropBlock.AGE) > old.getValue(CropBlock.AGE)) return true;
        if (ob instanceof StemBlock && nb == ob && now.getValue(StemBlock.AGE) > old.getValue(StemBlock.AGE)) return true;
        if (ob instanceof SaplingBlock && (nb instanceof LeavesBlock || nb.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS))) return true;
        if (ob instanceof LeavesBlock && !old.getValue(LeavesBlock.PERSISTENT) && now.isAir()) return true;
        if (ob == Blocks.DIRT && (nb == Blocks.GRASS_BLOCK || nb == Blocks.MYCELIUM)) return true;
        if (ob == Blocks.FARMLAND && nb == Blocks.FARMLAND && !now.getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE).equals(old.getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE))) return true;
        if (ob != nb && ob instanceof WeatheringCopper && nb instanceof WeatheringCopper) return true; // weathering stage
        if (!old.getFluidState().isEmpty() && !now.getFluidState().isEmpty() && old.getFluidState().getAmount() != now.getFluidState().getAmount()) return true;
        if (old.isAir() && growsFromAir(nb)) return true;
        return false;
    }

    /** Single block packets, with the old block still known. */
    public void blockChanged(BlockPos pos, BlockState old, BlockState now) {
        if (mc.level == null) return;
        handledPos = pos.asLong();
        if (ignoredBlock(now) || ignoredBlock(old)) return;
        add(tickChange(old, now) ? Kind.TICK : Kind.BLOCK, pos, pos.asLong(), System.currentTimeMillis());
    }

    @Override
    public void onBlockUpdate(BlockPos pos, BlockState state) {
        if (pos.asLong() == handledPos) { handledPos = Long.MIN_VALUE; return; } // the single-block packet was scored above
        if (mc.level == null || ignoredBlock(state)) return;
        add(Kind.BLOCK, pos, pos.asLong(), System.currentTimeMillis());
    }

    @Override
    public void onChunkData(LevelChunk chunk) {
        chunkAge.put(chunk.getPos().toLong(), System.currentTimeMillis());
    }

    @Override
    public void onChunkUnload(int cx, int cz) {
        long k = ChunkPos.asLong(cx, cz);
        chunkAge.remove(k);
        chunks.remove(k);
    }

    // ---- scoring ------------------------------------------------------------------------------------------------------------

    private static double decay(long ms) {
        return Math.exp(-ms / 1000.0 / HALF_LIFE_S * Math.log(2));
    }

    private static double score(ChunkSignal cs, long now) {
        double s = 0;
        for (var e : cs.perKind.entrySet()) s += Math.min(e.getKey().cap, e.getValue().value) * decay(now - e.getValue().last);
        return s;
    }

    /** Pathfinding mobs in the ring that have not moved, turned or changed in 40 s: their chunk is not ticking. */
    private Set<Long> frozenChunks(long now) {
        Set<Long> out = new HashSet<>();
        Set<Integer> alive = new HashSet<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof PathfinderMob m) || e instanceof Shulker || m.isNoAi() || m.isSleeping() || e.isPassenger() || e.isVehicle()) continue;
            ChunkPos c = e.chunkPosition();
            if (!inRing(c.x, c.z, 0)) continue;
            alive.add(e.getId());
            double[] was = still.get(e.getId());
            double[] cur = {e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getYHeadRot(), now};
            if (was == null || Math.abs(was[0] - cur[0]) > 1e-4 || Math.abs(was[1] - cur[1]) > 1e-4 || Math.abs(was[2] - cur[2]) > 1e-4
                    || Math.abs(was[3] - cur[3]) > 1e-3 || Math.abs(was[4] - cur[4]) > 1e-3) { still.put(e.getId(), cur); continue; }
            if (now - was[5] >= FROZEN_MS) out.add(c.toLong());
        }
        still.keySet().retainAll(alive);
        return out;
    }

    private void evaluate(long now) {
        if (dev.vantage.core.util.Alone.now()) { chunks.clear(); hits.clear(); return; } // singleplayer: nobody else can keep a chunk ticking
        int sim = sim();
        Map<Long, Double> raw = new HashMap<>(), field = new HashMap<>();
        for (var it = chunks.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            double sc = score(e.getValue(), now);
            if (sc < 0.05) { it.remove(); continue; }
            if (sc < MIN_SCORE) continue;
            int cx = ChunkPos.getX(e.getKey()), cz = ChunkPos.getZ(e.getKey());
            for (int dx = -sim; dx <= sim; dx++) for (int dz = -sim; dz <= sim; dz++) {
                long k = ChunkPos.asLong(cx + dx, cz + dz);
                double dist = Math.sqrt(dx * dx + dz * dz);
                raw.merge(k, sc, Double::sum);
                field.merge(k, sc * (1.0 - 0.3 * dist / (sim * 1.4143 + 1.0)), Double::sum);
            }
        }
        if (raw.isEmpty()) return;
        for (long frozen : frozenChunks(now)) {
            field.remove(frozen);
            int cx = ChunkPos.getX(frozen), cz = ChunkPos.getZ(frozen);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) field.computeIfPresent(ChunkPos.asLong(cx + dx, cz + dz), (k, v) -> v * 0.5);
        }
        double threshold = 8.0 * Math.pow(2.0, (5 - sensitivity.get()) / 2.5);
        for (var e : raw.entrySet()) {
            long k = e.getKey();
            if (e.getValue() < threshold) continue;
            Double mine = field.get(k);
            if (mine == null) continue;
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            boolean peak = true;
            for (int dx = -sim; dx <= sim && peak; dx++) for (int dz = -sim; dz <= sim; dz++) {
                Double o = field.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (o != null && o > mine) { peak = false; break; }
            }
            if (!peak) continue;
            int spacing = Math.max(4, 2 * sim);
            if (hits.stream().anyMatch(h -> chebyshev(h.cx(), h.cz(), cx, cz) <= spacing)) continue;
            hits.addLast(new Hit(cx, cz, e.getValue(), now));
            if (hits.size() > 256) hits.removeFirst();
            spotted(cx, cz, e.getValue());
        }
    }

    private void spotted(int cx, int cz, double score) {
        int x = (cx << 4) + 8, z = (cz << 4) + 8;
        LeakLog.add("RING", x, 64, z, "simulated chunk, score " + Fmt.fixed(score, 1));
        Notify.post(this, dev.vantage.core.notify.Level.WARNING, "ring:" + cx + "," + cz, 30_000, "Simulation Ring: someone keeps chunk " + cx + " " + cz + " ticking (" + x + " " + z + ")", new Sound(1.3f));
        if (toast.on() && toastGate.tryUse()) {
            Toasts.warn("Simulation Ring", "Someone near " + x + " " + z);
        }
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        Vec3 pos = mc.player.position();
        if (lastPos != null && pos.distanceToSqr(lastPos) > 64) quietUntil = now + TELEPORT_QUIET_MS; // a jump over 8 blocks in one tick
        lastPos = pos;
        dev.vantage.core.util.OwnTicks.note(mc, dev.vantage.core.util.Dims.id(mc.level), now);
        if (mc.player.getY() < 0) belowZeroUntil = now + BELOW_ZERO_MS;
        passed.add(mc.player.chunkPosition().toLong());
        if (++tick % 20 == 0) {
            for (Entity e : mc.level.entitiesForRendering()) if (insideOwn(e)) lastInsideOwn.put(e.getId(), now);
            lastInsideOwn.values().removeIf(t -> now - t > OWN_AREA_MS);
            spawnTime.values().removeIf(t -> now - t > 60_000);
            shares.clear(); // each source gets a fresh share every second: a mob that keeps moving keeps counting, just not all at once
        }
        if (tick % 5 == 0) evaluate(now);
        hits.removeIf(h -> now - h.time() > HIT_KEEP_MS);
    }

    private void reset() {
        chunks.clear(); shares.clear(); chunkAge.clear(); spawnTime.clear(); lastInsideOwn.clear(); lightning.clear();
        passed.clear(); still.clear(); hits.clear();
        lastPos = null; handledPos = Long.MIN_VALUE;
        quietUntil = System.currentTimeMillis() + TELEPORT_QUIET_MS;
    }

    /** Chunks loaded before the switch count as settled; only ones that arrive from now on wait their 3 s. */
    private void settleLoaded() {
        if (mc.level == null || mc.player == null) return;
        long then = System.currentTimeMillis() - CHUNK_SETTLE_MS;
        int r = view() + 1;
        ChunkPos me = mc.player.chunkPosition();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (mc.level.getChunkSource().hasChunk(me.x + dx, me.z + dz)) chunkAge.putIfAbsent(ChunkPos.asLong(me.x + dx, me.z + dz), then);
        }
    }

    @Override protected void onEnable() { reset(); settleLoaded(); }
    @Override public void onDisable() { reset(); }
    @Override
    public void onJoinWorld() {
        startWorld();
    }

    @Override
    public void onLeaveWorld() {
        startWorld();
    }

    @Override
    public void onDimensionChange() {
        startWorld();
    }

    private void startWorld() {
        dev.vantage.core.util.OwnTicks.reset();
        reset();
        settleLoaded();
    }

    @Override
    public void onRender3D(R3 r) {
        if (!draw.on() || mc.level == null) return;
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z, rng = range.i();
        double minY = mc.level.getMinY(), maxY = mc.level.getMaxY() + 1;
        for (Hit h : hits) {
            if (chebyshev(h.cx(), h.cz(), pcx, pcz) > rng) continue;
            double x0 = h.cx() << 4, z0 = h.cz() << 4;
            AABB col = new AABB(x0, minY, z0, x0 + 16, maxY, z0 + 16);
            r.column(col, COLOR | 0xFF000000, EspStyle.WEIGHT, EspStyle.fill(COLOR, true, 1f), x0 + 8, z0 + 8);
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x0 + 8, (int) z0 + 8);
            r.label(new Vec3(x0 + 8, y + 3, z0 + 8), String.format("Someone simulates this  %.0f", h.score()), COLOR);
        }
    }

    @Override
    public int labelClass() {
        return LABEL_SUMMARY;
    }
}
