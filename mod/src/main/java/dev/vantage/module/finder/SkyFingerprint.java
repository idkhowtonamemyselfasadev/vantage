package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.vantage.core.Category;
import dev.vantage.core.LeakLog;
import dev.vantage.core.Module;
import dev.vantage.core.Toasts;
import dev.vantage.core.notify.Level;
import dev.vantage.core.notify.Notify;
import dev.vantage.core.notify.Sound;
import dev.vantage.core.store.ChunkSetStore;
import dev.vantage.core.store.Scope;
import dev.vantage.core.store.Stores;
import dev.vantage.core.util.Cooldown;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The sky-light fingerprint. A chunk packet carries one sky-light array per section, and vanilla sends a dark section in two ways:
 * the "empty" mask (the engine never stored anything there) or a real 2048-byte array of zeros (the engine touched it once). Untouched
 * rock below the surface comes as the empty mask; rock the server's light engine had to visit, because someone dug or built in it,
 * comes as zeros. Below Y 48 that is a base signal. Two innocent reasons are checked before anything is said: the section was lit
 * earlier and went dark (a cave relit), or the section and its six neighbours hold no air at all (deep solid rock the engine brushed).
 * A zeroed section with neither excuse is a base; with an excuse it is a suspect, drawn but quiet. Your own footprint is written
 * off: every chunk you walk through plus two around is protected for good on this server. Only reads packets.
 */
public class SkyFingerprint extends Module {
    private static final int C_BASE = 0xFFD99A00, C_SUSPECT = 0xFFFFE97A;
    private static final int WORLD_LIMIT = 250_000, SIGNAL_TOP = 62, PROTECT_RADIUS = 2;
    private static final int FLAG_TURNED_DARK = 1, FLAG_AIRLESS = 2;

    public final BoolSetting columns = add(new BoolSetting("Columns", "Draw a column over each hit chunk (the hit also lands on Base Finder's ground square)", true));
    public final BoolSetting suspects = add(new BoolSetting("Show suspects", "Also draw zeroed sections that have an innocent explanation", true)).visibleIf(columns::on);
    public final BoolSetting toast = add(new BoolSetting("Toast", "Card for each new base hit", true));
    public final NumberSetting range = add(new NumberSetting("Range", "Draw hits this many chunks away", 16, 4, 32, 1)).visibleIf(columns::on);

    /** NORMAL has light, EMPTY came as the empty mask, ZEROED came as an array of zeros, MAX is all 15, UNKNOWN was never sent. */
    public enum Status { UNKNOWN, NORMAL, EMPTY, ZEROED, MAX }

    public record Hit(int cx, int cz, boolean base, int flags, long time) {}

    private final Map<Long, Status[]> snapshots = new HashMap<>();
    private final Map<Long, Integer> sentNotDark = new HashMap<>();
    private final Map<Long, Hit> hits = new HashMap<>();
    private final Set<Long> held = new HashSet<>();
    /**
     * Your own footprint: every chunk you walked through plus two around, per server and dimension. A chunk set that follows the place
     * you are in by itself (core/store), loaded while this module is on. The old file was one JSON array of chunk numbers per server and
     * dimension in sky-protected/; it is copied into the new file once and left where it is.
     */
    private final ChunkSetStore protect = Stores.chunkSet("sky-protected", Scope.DIMENSION).activeWhen(this::activeInWorld)
            .adopt("sky-protected/{server}_{dim}.json", ChunkSetStore::readJsonArray);
    private long lastChunk = Long.MIN_VALUE;
    private final Cooldown toastGate = Cooldown.wall(3000);

    public SkyFingerprint() {
        super("Sky Fingerprint", "Rock the light engine touched: zeroed sky light below Y 48 where nobody could have been", Category.FINDER);
        // light data arrives with a chunk and in its own packet (the hook used to be two lines in a mixin)
        listen(dev.vantage.core.event.PacketHandledEvent.class, e -> {
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket p) onLight(p.getX(), p.getZ(), p.getLightData());
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundLightUpdatePacket p) onLight(p.getX(), p.getZ(), p.getLightData());
        });
    }

    @Override
    public String info() {
        long bases = hits.values().stream().filter(Hit::base).count();
        return bases + "/" + hits.size();
    }

    public List<Hit> hits() {
        return new ArrayList<>(hits.values());
    }

    public int protectedCount() {
        return protect.size();
    }

    /** True when the chunk is part of your own footprint on this server and dimension (for the in-game storage check). */
    public boolean protects(int cx, int cz) {
        return protect.contains(cx, cz);
    }

    /** The raw per-section statuses of a chunk, for the test log. */
    public Status[] snapshot(int cx, int cz) {
        return snapshots.get(ChunkPos.asLong(cx, cz));
    }

    // ---- classification ----------------------------------------------------------------------------------------------------

    public static Status classify(byte[] a) {
        if (a == null || a.length != 2048) return Status.UNKNOWN;
        boolean zero = true, max = true;
        for (byte b : a) {
            if (b != 0) zero = false;
            if (b != (byte) 0xFF) max = false;
            if (!zero && !max) return Status.NORMAL;
        }
        return zero ? Status.ZEROED : Status.MAX;
    }

    /** Applies one packet's sky masks to a snapshot (index 0 is the section below the world). */
    public static void apply(ClientboundLightUpdatePacketData d, Status[] out) {
        BitSet mask = d.getSkyYMask(), empty = d.getEmptySkyYMask();
        List<byte[]> arrays = d.getSkyUpdates();
        int next = 0;
        for (int i = 0; i < out.length; i++) {
            if (mask.get(i)) { if (next < arrays.size()) out[i] = classify(arrays.get(next++)); }
            else if (empty.get(i)) out[i] = Status.EMPTY;
        }
    }

    /** Any zeroed section whose top is below Y 62. */
    public static boolean baseSignal(Status[] sky, int minSectionY) {
        for (int i = 0; i < sky.length; i++) {
            if (sky[i] == Status.ZEROED && (((minSectionY - 1 + i) << 4) + 15) < SIGNAL_TOP) return true;
        }
        return false;
    }

    private static int notDarkBits(Status[] sky) {
        int bits = 0;
        for (int i = 0; i < sky.length; i++) if (sky[i] == Status.NORMAL || sky[i] == Status.MAX || sky[i] == Status.EMPTY) bits |= 1 << i;
        return bits;
    }

    /**
     * 0 = no innocent explanation for any zeroed section below the top: a real hit. Otherwise the excuses that applied.
     * turnedDark: the section was once sent lit or empty. airless: the section and its face neighbours hold no air at all.
     */
    public static int suspicion(Status[] sky, int minSectionY, int notDarkBefore, java.util.function.IntPredicate airlessAround) {
        int flags = 0;
        for (int i = 0; i < sky.length; i++) {
            if (sky[i] != Status.ZEROED) continue;
            int sectionY = minSectionY - 1 + i;
            if ((sectionY << 4) + 15 >= SIGNAL_TOP) continue;
            int explained = 0;
            if ((notDarkBefore & (1 << i)) != 0) explained |= FLAG_TURNED_DARK;
            // on a fill server every deep section and its neighbours are "airless" by construction: a zeroed one is the base shape, not an excuse
            if (!DeepLeaks.fillServer() && airlessAround.test(sectionY)) explained |= FLAG_AIRLESS;
            if (explained == 0) return 0;
            flags |= explained;
        }
        return flags;
    }

    private boolean airlessAround(int cx, int sy, int cz) {
        int[][] offs = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offs) if (!airless(cx + o[0], sy + o[1], cz + o[2])) return false;
        return true;
    }

    private boolean airless(int cx, int sy, int cz) {
        LevelChunk ch = mc.level.getChunkSource().getChunk(cx, cz, false);
        if (ch == null) return false; // unknown counts as not airless
        int idx = ch.getSectionIndexFromSectionY(sy);
        if (idx < 0 || idx >= ch.getSections().length) return true;
        LevelChunkSection s = ch.getSections()[idx];
        if (s.hasOnlyAir()) return false;
        if (!s.getStates().maybeHas(BlockState::isAir)) return true;
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) if (s.getBlockState(x, y, z).isAir()) return false;
        return true;
    }

    // ---- packets ----------------------------------------------------------------------------------------------------------

    /** Light packets seen since start, for the test log. */
    public static int packets;

    public void onLight(int cx, int cz, ClientboundLightUpdatePacketData data) {
        packets++;
        if (mc.level == null || mc.player == null) return;
        if (Math.abs(cx) * 16 > WORLD_LIMIT || Math.abs(cz) * 16 > WORLD_LIMIT) return;
        long key = ChunkPos.asLong(cx, cz);
        int minSection = mc.level.getMinSectionY();
        Status[] snap = snapshots.computeIfAbsent(key, k -> {
            Status[] s = new Status[mc.level.getSectionsCount() + 2];
            java.util.Arrays.fill(s, Status.UNKNOWN);
            return s;
        });
        int notDarkBefore = sentNotDark.getOrDefault(key, 0);
        apply(data, snap);
        sentNotDark.put(key, notDarkBefore | notDarkBits(snap));
        if (!baseSignal(snap, minSection)) return;
        if (protect.contains(key)) return;
        if (mc.player.getY() < 0) { held.add(key); return; } // judged later, when a packet comes while you are above Y 0
        held.remove(key);
        int s = suspicion(snap, minSection, notDarkBefore, sy -> airlessAround(cx, sy, cz));
        boolean base = s == 0;
        Hit old = hits.get(key);
        if (old != null && old.base()) return; // a base stays a base
        hits.put(key, new Hit(cx, cz, base, s, System.currentTimeMillis()));
        if (old != null && !base) return;
        String excuse = base ? "" : ((s & FLAG_TURNED_DARK) != 0 ? " turned dark" : "") + ((s & FLAG_AIRLESS) != 0 ? " airless" : "");
        LeakLog.add(base ? "SKY-BASE" : "SKY-SUSPECT", (cx << 4) + 8, 0, (cz << 4) + 8, "zeroed sky light below Y 48" + excuse);
        if (!base) return;
        Notify.post(this, Level.WARNING, "sky:" + cx + "," + cz, 30_000, "Sky fingerprint: the light engine touched rock at chunk " + cx + " " + cz
                + " (" + ((cx << 4) + 8) + " " + ((cz << 4) + 8) + ")", new Sound(1.4f));
        if (toast.on() && toastGate.tryUse()) {
            Toasts.warn("Sky Fingerprint", "Touched rock at " + ((cx << 4) + 8) + " " + ((cz << 4) + 8));
        }
    }

    // ---- own footprint -------------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        long now = mc.player.chunkPosition().toLong();
        if (now == lastChunk) return;
        lastChunk = now;
        Hit h = hits.get(now);
        if ((h != null && h.base()) || held.contains(now)) return; // walking into a hit does not launder it
        int cx = ChunkPos.getX(now), cz = ChunkPos.getZ(now);
        for (int dx = -PROTECT_RADIUS; dx <= PROTECT_RADIUS; dx++) for (int dz = -PROTECT_RADIUS; dz <= PROTECT_RADIUS; dz++) {
            long k = ChunkPos.asLong(cx + dx, cz + dz);
            protect.add(k); // the store saves what changed, at most once a second, on its own thread
            Hit o = hits.get(k);
            if (o != null && !o.base()) hits.remove(k); // a suspect you walked through is your own doing
        }
    }

    private void reset() {
        snapshots.clear();
        sentNotDark.clear();
        hits.clear();
        held.clear();
        lastChunk = Long.MIN_VALUE;
    }

    @Override protected void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    @Override
    public void onLeaveWorld() {
        reset();
    }

    @Override
    public void onDimensionChange() {
        reset();
    }

    @Override
    public void onChunkUnload(int cx, int cz) {
        long k = ChunkPos.asLong(cx, cz);
        snapshots.remove(k); // the hit stays, the raw data does not
    }

    @Override
    public void onRender3D(R3 r) {
        if (!columns.on() || mc.level == null) return;
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z, rng = range.i();
        double minY = mc.level.getMinY(), maxY = mc.level.getMaxY() + 1;
        for (Hit h : hits.values()) {
            if (!h.base() && !suspects.on()) continue;
            if (Math.max(Math.abs(h.cx() - pcx), Math.abs(h.cz() - pcz)) > rng) continue;
            int c = h.base() ? C_BASE : C_SUSPECT;
            double x0 = h.cx() << 4, z0 = h.cz() << 4;
            AABB col = new AABB(x0, minY, z0, x0 + 16, maxY, z0 + 16);
            r.column(col, c | 0xFF000000, EspStyle.WEIGHT, EspStyle.fill(c, true, 1f), x0 + 8, z0 + 8);
            r.label(new Vec3(x0 + 8, mc.player.getEyeY() + 2, z0 + 8), h.base() ? "Sky fingerprint" : "Sky fingerprint (suspect)", c);
        }
    }

    @Override
    public int labelClass() {
        return LABEL_SUMMARY;
    }
}
