package dev.vantage.core;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Raw numbers on what a server really sends, before any module's filters: which leaks exist at all. Reads incoming chunk packets
 * only, sends nothing. One line every 30 s in config/vantage/leaks/<server>-probe.log.
 *
 * ghost = in a section's palette, nowhere in its blocks. lit = light above 0 inside a solid opaque non-glowing block.
 * dropped = block entity listed in the packet that the client didn't keep. At most one chunk per 10 ms, so bursts are sampled.
 */
public final class LeakProbe {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long EVERY_MS = 30_000, GAP_MS = 10;

    private static long chunks, skipped, sections, global, ghostSections, deepSections, deepOneBlock, deepAir, deepCells;
    private static long lightSections, litCells, skyZero, entries, dropped, storageEntries, deepEntries;
    private static final long[] LEVELS = new long[16];
    private static final Map<String, Integer> GHOSTS = new HashMap<>();
    /** Sections holding one single block, by height: below 0, 0-31, 32-63, 64 and up. */
    private static final long[] BAND_ALL = new long[4], BAND_ONE = new long[4];
    private static final Map<String, Integer> FILL = new HashMap<>();
    private static long last, lastWrite;

    public static void onChunk(ClientboundLevelChunkWithLightPacket p, LevelChunk c) {
        long now = System.currentTimeMillis();
        if (now - last < GAP_MS) { skipped++; return; }
        last = now;
        chunks++;
        var level = Minecraft.getInstance().level;
        LevelChunkSection[] secs = c.getSections();
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection s = secs[i];
            if (s.hasOnlyAir()) continue;
            sections++;
            var states = s.getStates();
            int y = level.getSectionYFromSectionIndex(i) << 4;
            Set<BlockState> inArray = new HashSet<>();
            long[] air = new long[1];
            states.count((st, n) -> { inArray.add(st); if (st.isAir()) air[0] += n; });
            if (y < 0) {
                deepSections++;
                deepCells += 4096;
                deepAir += air[0];
                if (inArray.size() == 1) deepOneBlock++;
            }
            int band = y < 0 ? 0 : y < 32 ? 1 : y < 64 ? 2 : 3;
            BAND_ALL[band]++;
            if (inArray.size() == 1) {
                BAND_ONE[band]++;
                FILL.merge(BuiltInRegistries.BLOCK.getKey(inArray.iterator().next().getBlock()).getPath(), 1, Integer::sum);
            }
            if (states.maybeHas(st -> false)) { global++; continue; } // a global palette has no entries to compare
            List<BlockState> listed = new ArrayList<>();
            states.maybeHas(st -> { listed.add(st); return false; });
            boolean ghost = false;
            for (BlockState st : listed) {
                if (inArray.contains(st) || st.isAir()) continue;
                ghost = true;
                GHOSTS.merge(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(), 1, Integer::sum);
            }
            if (ghost) ghostSections++;
        }
        var light = p.getLightData();
        BitSet mask = light.getBlockYMask();
        List<byte[]> arrays = light.getBlockUpdates();
        int n = 0;
        for (int i = mask.nextSetBit(0); i >= 0 && n < arrays.size(); i = mask.nextSetBit(i + 1), n++) {
            int idx = i - 1; // light data starts one section below the world
            lightSections++;
            if (idx < 0 || idx >= secs.length) continue;
            DataLayer d = new DataLayer(arrays.get(n).clone());
            LevelChunkSection s = secs[idx];
            for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                int l = d.get(bx, by, bz);
                if (l == 0) continue;
                BlockState st = s.getBlockState(bx, by, bz);
                if (st.getLightEmission() != 0 || !st.canOcclude() || st.getLightBlock() < 15) continue;
                litCells++;
                LEVELS[l]++;
            }
        }
        BitSet sky = light.getSkyYMask();
        List<byte[]> skies = light.getSkyUpdates();
        n = 0;
        for (int i = sky.nextSetBit(0); i >= 0 && n < skies.size(); i = sky.nextSetBit(i + 1), n++) {
            boolean zero = true;
            for (byte b : skies.get(n)) if (b != 0) { zero = false; break; }
            if (zero) skyZero++;
        }
        p.getChunkData().getBlockEntitiesTagsConsumer(p.getX(), p.getZ()).accept((pos, type, tag) -> {
            entries++;
            if (c.getBlockEntity(pos) == null) dropped++;
            if (pos.getY() < 0) deepEntries++;
            String id = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type));
            if (id.contains("chest") || id.contains("barrel") || id.contains("shulker")) storageEntries++;
        });
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastWrite < EVERY_MS || chunks == 0) return;
        lastWrite = now;
        List<Map.Entry<String, Integer>> top = new ArrayList<>(GHOSTS.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder ghosts = new StringBuilder();
        for (int i = 0; i < Math.min(12, top.size()); i++) ghosts.append(top.get(i).getKey()).append('=').append(top.get(i).getValue()).append(' ');
        StringBuilder lv = new StringBuilder();
        for (int i = 1; i < 16; i++) if (LEVELS[i] > 0) lv.append('L').append(i).append('=').append(LEVELS[i]).append(' ');
        String line = TIME.format(LocalTime.now()) + " " + ChunkTracker.dim() + " chunks=" + chunks + " skipped=" + skipped + " sections=" + sections + " global=" + global
                + " ghostSections=" + ghostSections + " fakeOres=" + FakeOres.detected() + " lit=" + litCells + " lightSections=" + lightSections + " skyZero=" + skyZero
                + " entities=" + entries + " storage=" + storageEntries + " dropped=" + dropped + " entitiesBelow0=" + deepEntries
                + " deepSections=" + deepSections + " deepOneBlock=" + deepOneBlock + " deepAirPct=" + (deepCells == 0 ? 0 : deepAir * 100 / deepCells)
                + " oneBlock<0=" + BAND_ONE[0] + "/" + BAND_ALL[0] + " 0-31=" + BAND_ONE[1] + "/" + BAND_ALL[1] + " 32-63=" + BAND_ONE[2] + "/" + BAND_ALL[2] + " 64+=" + BAND_ONE[3] + "/" + BAND_ALL[3]
                + " fill" + FILL
                + " levels[" + lv.toString().trim() + "] ghosts[" + ghosts.toString().trim() + "]";
        dev.vantage.core.log.DataLogs.appendServer("leaks", "-probe.log", line);
        chunks = skipped = sections = global = ghostSections = deepSections = deepOneBlock = deepAir = deepCells = 0;
        lightSections = litCells = skyZero = entries = dropped = storageEntries = deepEntries = 0;
        java.util.Arrays.fill(LEVELS, 0);
        GHOSTS.clear();
        FILL.clear();
        java.util.Arrays.fill(BAND_ALL, 0);
        java.util.Arrays.fill(BAND_ONE, 0);
    }

    private LeakProbe() {}
}
