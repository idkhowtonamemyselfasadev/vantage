package dev.vantage.core.chunk;

import java.util.HashSet;
import java.util.Set;

/** A hand-driven {@link Rules} for the queue tests: the test says which owners are on and which chunks are loaded and at what generation. */
final class TestRules implements Rules {
    final Set<Object> off = new HashSet<>();
    final Set<Long> loaded = new HashSet<>();
    int generation = 0, world = 0;
    Throwable failure;

    TestRules load(int cx, int cz) {
        loaded.add(ChunkIndex.key(cx, cz));
        return this;
    }

    @Override
    public boolean ownerOn(Object owner) {
        return !off.contains(owner);
    }

    @Override
    public boolean loaded(long chunkKey) {
        return loaded.contains(chunkKey);
    }

    @Override
    public int generation(long chunkKey) {
        return loaded.contains(chunkKey) ? generation : ChunkIndex.NONE;
    }

    @Override
    public int world() {
        return world;
    }

    @Override
    public void failed(Object owner, String kind, Throwable error) {
        failure = error;
    }
}
