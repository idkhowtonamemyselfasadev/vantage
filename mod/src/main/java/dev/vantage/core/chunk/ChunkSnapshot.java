package dev.vantage.core.chunk;

import java.util.List;

import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * An immutable copy of what a scan reads of one chunk, taken on the client thread by {@link Chunks#scan} and handed to a worker.
 * The sections are copies ({@code LevelChunkSection.copy()}), not the live ones, and nothing here changes after construction: the
 * scanner must treat the array as read only. {@code minSectionY} is the section coordinate of {@code sections[0]} (-4 in the
 * overworld); {@code generation} is the chunk's data generation at the moment of the copy.
 */
public record ChunkSnapshot(int cx, int cz, int generation, int minSectionY, LevelChunkSection[] sections, List<BlockEntityInfo> blockEntities) {}
