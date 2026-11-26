package dev.vantage.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import dev.vantage.core.Notices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BUGS.md B-198: a load must not wait for the writer thread on the caller's thread. The writer is held by a latch, so a load that waits
 * for it can never return; each test runs the load on another thread and gives it five seconds.
 */
class StoreLoadWaitTest {
    @TempDir
    Path dir;

    private final long[] now = {0};
    private final CountDownLatch hold = new CountDownLatch(1);
    private final ExecutorService caller = Executors.newSingleThreadExecutor();

    @BeforeEach
    void holdTheWriter() {
        Notices.clear("Saved data");
        CountDownLatch busy = new CountDownLatch(1);
        Stores.submit(() -> {
            busy.countDown();
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(busy.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void releaseTheWriter() {
        hold.countDown();
        caller.shutdownNow();
    }

    private <T> T onAnotherThread(java.util.concurrent.Callable<T> call) throws Exception {
        Future<T> f = caller.submit(call);
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the call waited for the writer thread", e);
        }
    }

    private ChunkSetStore chunks(Path f) {
        return new ChunkSetStore("sky", () -> f, () -> now[0]);
    }

    private static Set<Long> contentsOf(ChunkSetStore s) {
        Set<Long> out = new HashSet<>();
        s.forEach((x, z) -> out.add(ChunkSetStore.pack(x, z)));
        return out;
    }

    @Test
    void aSaveThatIsStillQueuedIsReadFromMemoryByALoadOfTheSameFile() throws Exception {
        Path f = dir.resolve("sky.vcs");
        ChunkSetStore a = chunks(f);
        assertFalse(a.load()); // binds the store to the file; there is none yet
        a.add(3, 4);
        a.add(-70, 12);
        a.requestNow(); // handed to the writer, which is held: nothing is on disk
        assertFalse(Files.exists(f));
        ChunkSetStore b = chunks(f);
        assertTrue(onAnotherThread(b::load));
        assertEquals(Set.of(ChunkSetStore.pack(3, 4), ChunkSetStore.pack(-70, 12)), contentsOf(b)); // what was saved, not the empty file
        assertTrue(Notices.texts("Saved data").isEmpty());
        hold.countDown();
        a.flush();
        assertTrue(Files.exists(f)); // and the save still happens
    }

    @Test
    void theSameStoreReloadingItsOwnQueuedSaveGetsItBackWithoutWaiting() throws Exception {
        Path f = dir.resolve("sky.vcs");
        ChunkSetStore a = chunks(f);
        a.load();
        a.add(1, 1);
        assertTrue(onAnotherThread(a::load)); // module off and on again: the change is not lost and the load does not wait
        assertEquals(Set.of(ChunkSetStore.pack(1, 1)), contentsOf(a));
    }

    @Test
    void aLoadForAnotherFileDoesNotWaitForTheSaveOfTheFirstAndLoadsItsOwnContent() throws Exception {
        Path[] now = {dir.resolve("overworld.vcs")};
        ChunkSetStore a = new ChunkSetStore("sky", () -> now[0], () -> this.now[0]);
        a.load();
        a.add(5, 5); // the overworld's chunk, not saved yet (dirty)
        ChunkSetStore other = chunks(dir.resolve("nether.vcs"));
        other.add(9, 9);
        other.flushRequest(); // queued behind the held writer
        now[0] = dir.resolve("nether.vcs"); // dimension change: the same store object, another file
        assertTrue(onAnotherThread(a::load));
        assertEquals(Set.of(ChunkSetStore.pack(9, 9)), contentsOf(a));
        hold.countDown();
        a.flush();
        other.flush();
        ChunkSetStore back = chunks(dir.resolve("overworld.vcs"));
        assertTrue(back.load());
        assertEquals(Set.of(ChunkSetStore.pack(5, 5)), contentsOf(back)); // the overworld's change reached its own file
    }

    @Test
    void aJsonStoreServesAQueuedSaveToo() throws Exception {
        Path f = dir.resolve("data.json");
        AtomicInteger value = new AtomicInteger(7), loaded = new AtomicInteger(-1);
        JsonStore a = new JsonStore("test", () -> f, 1, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("value", value.get());
            return o;
        }, (root, v) -> loaded.set(root.getAsJsonObject().get("value").getAsInt()), () -> now[0]);
        a.load();
        a.markDirty();
        a.requestNow();
        assertFalse(Files.exists(f));
        JsonStore b = new JsonStore("test", () -> f, 1, JsonObject::new, (root, v) -> loaded.set(root.getAsJsonObject().get("value").getAsInt()), () -> now[0]);
        assertTrue(onAnotherThread(b::load));
        assertEquals(7, loaded.get());
    }

    @Test
    void aJournalStoreStillWaitsForAJobOnItsOwnFileButNotForAnotherFile() throws Exception {
        Path base = dir.resolve("finds.json");
        RecordStore<String> a = new RecordStore<>("finds", () -> base, 1 << 20, r -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", r);
            return o;
        }, o -> o.get("id").getAsString(), r -> r, () -> now[0]);
        a.load();
        a.add("one");
        a.requestNow(); // a journal append for this file, behind the held writer
        RecordStore<String> otherFile = new RecordStore<>("other", () -> dir.resolve("other.json"), 1 << 20, r -> new JsonObject(), o -> "", r -> r, () -> now[0]);
        assertFalse(onAnotherThread(otherFile::load)); // nothing on disk, and it did not wait
        Future<Boolean> sameFile = caller.submit(a::load);
        assertThrows(TimeoutException.class, () -> sameFile.get(300, TimeUnit.MILLISECONDS)); // the journal cannot be served from memory: this one waits, bounded by Stores.FLUSH_WAIT_SECONDS
        hold.countDown();
        assertTrue(sameFile.get(5, TimeUnit.SECONDS));
        assertEquals(1, a.size());
    }
}
