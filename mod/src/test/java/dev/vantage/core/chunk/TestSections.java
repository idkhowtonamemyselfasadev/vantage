package dev.vantage.core.chunk;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/** Real chunk sections and snapshots without a game: the block registry is started once, the biomes are a stand-in nobody reads. */
public final class TestSections {
    private static boolean started;

    private TestSections() {}

    public static synchronized void start() {
        if (started) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        started = true;
    }

    /** A section of air with the given blocks set; the key is {x, y, z} inside the section. */
    public static LevelChunkSection section(Map<List<Integer>, Block> blocks) {
        start();
        PalettedContainer<BlockState> states = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        IdMap<Holder<Biome>> biomeIds = new IdMap<>() {
            @Override public int getId(Holder<Biome> h) { return 0; }
            @Override public Holder<Biome> byId(int id) { return NO_BIOME; }
            @Override public int size() { return 1; }
            @Override public Iterator<Holder<Biome>> iterator() { return List.of(NO_BIOME).iterator(); }
        };
        PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(NO_BIOME, Strategy.createForBiomes(biomeIds));
        LevelChunkSection s = new LevelChunkSection(states, biomes);
        blocks.forEach((p, b) -> s.setBlockState(p.get(0), p.get(1), p.get(2), b.defaultBlockState(), false));
        return s;
    }

    private static final Holder<Biome> NO_BIOME = Holder.direct(null);

    /** A snapshot of the sections, copied the way the service copies them. minSectionY is -4 as in the overworld. */
    public static ChunkSnapshot snapshot(int cx, int cz, LevelChunkSection... sections) {
        LevelChunkSection[] copy = new LevelChunkSection[sections.length];
        for (int i = 0; i < sections.length; i++) copy[i] = sections[i].copy();
        return new ChunkSnapshot(cx, cz, 1, -4, copy, List.of());
    }
}
