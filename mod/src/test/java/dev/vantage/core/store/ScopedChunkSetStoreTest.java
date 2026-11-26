package dev.vantage.core.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import dev.vantage.core.Notices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedChunkSetStoreTest {
    @TempDir
    Path dir;

    private final AtomicReference<Scope.Place> where = new AtomicReference<>();
    private final long[] now = {0};
    private static final String OLD_PATTERN = "sky-protected/{server}_{dim}.json";

    @BeforeEach
    void clearNotices() {
        Notices.clear("Saved data");
    }

    private static Scope.Place at(String server, String dim) {
        return Scope.place(server, null, null, dim);
    }

    private ChunkSetStore store(Scope scope) {
        return ChunkSetStore.scoped("sky-protected", scope, () -> dir, where::get, () -> now[0]);
    }

    private ChunkSetStore adopting(Scope scope) {
        return store(scope).adopt(OLD_PATTERN, ChunkSetStore::readJsonArray);
    }

    private static Set<Long> contentsOf(ChunkSetStore s) {
        Set<Long> out = new HashSet<>();
        s.forEach((x, z) -> out.add(ChunkSetStore.pack(x, z)));
        return out;
    }

    private static String json(long... keys) {
        return Arrays.toString(keys).replace(" ", "");
    }

    private Path old(String name, String text) throws IOException {
        Path f = dir.resolve("sky-protected").resolve(name);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_000_000_000_000L));
        return f;
    }

    private Path fileOf(Scope scope, Scope.Place p) {
        return Scope.path(dir, scope, p, "sky-protected", ".chunks");
    }

    private List<Path> allFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void switchingScopeHandsOverTheOldSetAndLoadsTheNew() throws IOException {
        Scope.Place overworld = at("play.example.org", "minecraft:overworld");
        Scope.Place nether = at("play.example.org", "minecraft:the_nether");
        ChunkSetStore s = store(Scope.DIMENSION);
        where.set(overworld);
        s.add(1, 1);
        s.add(2, 2);
        where.set(nether);
        assertEquals(0, s.size(), "the nether has its own set");
        s.add(9, 9);
        where.set(overworld);
        assertEquals(Set.of(ChunkSetStore.pack(1, 1), ChunkSetStore.pack(2, 2)), contentsOf(s), "back in the overworld: its own chunks, loaded again");
        assertFalse(s.contains(9, 9));
        Stores.flush();
        assertTrue(Files.exists(fileOf(Scope.DIMENSION, overworld)));
        assertTrue(Files.exists(fileOf(Scope.DIMENSION, nether)), "the old set was flushed when the place changed");
        ChunkSetStore check = store(Scope.DIMENSION);
        where.set(nether);
        assertEquals(Set.of(ChunkSetStore.pack(9, 9)), contentsOf(check));
    }

    @Test
    void aServerStoreDoesNotLoadAgainOnADimensionChange() {
        ChunkSetStore s = store(Scope.SERVER);
        List<Boolean> loads = new ArrayList<>();
        s.onLoad(loads::add);
        where.set(at("play.example.org", "minecraft:overworld"));
        s.add(1, 1);
        where.set(at("play.example.org", "minecraft:the_nether"));
        assertTrue(s.contains(1, 1));
        assertEquals(1, loads.size());
        where.set(at("other.example.org", "minecraft:overworld"));
        assertEquals(0, s.size());
        assertEquals(2, loads.size());
    }

    @Test
    void aGlobalStoreIgnoresTheWorld() {
        ChunkSetStore s = store(Scope.GLOBAL);
        s.add(4, 4);
        where.set(at("play.example.org", "minecraft:overworld"));
        assertTrue(s.contains(4, 4));
        where.set(null);
        assertTrue(s.contains(4, 4));
        s.flush();
        assertTrue(Files.exists(dir.resolve("data/sky-protected.chunks")));
    }

    @Test
    void withNoWorldNothingIsLoadedOrWritten() throws IOException {
        ChunkSetStore s = store(Scope.DIMENSION);
        where.set(at("play.example.org", "minecraft:overworld"));
        s.add(1, 1);
        s.flush();
        where.set(null);
        assertEquals(0, s.size());
        s.add(5, 5); // a late event after leaving: kept in memory only
        Stores.flush();
        assertEquals(1, allFiles().size(), "only the overworld file exists, nothing under 'unknown' or any other name");
        where.set(at("play.example.org", "minecraft:overworld"));
        assertEquals(Set.of(ChunkSetStore.pack(1, 1)), contentsOf(s), "the late change did not leak into the next place");
        Stores.flush();
        assertEquals(Set.of(ChunkSetStore.pack(1, 1)), contentsOf(store(Scope.DIMENSION)));
    }

    @Test
    void anInactiveStoreLoadsNothingAndWritesNothingAndLoadsWhenItBecomesActive() throws IOException {
        AtomicBoolean active = new AtomicBoolean(false);
        Scope.Place p = at("play.example.org", "minecraft:overworld");
        where.set(p);
        ChunkSetStore first = store(Scope.DIMENSION);
        first.add(1, 1);
        first.add(2, 2);
        first.flush();
        byte[] before = Files.readAllBytes(fileOf(Scope.DIMENSION, p));
        ChunkSetStore s = store(Scope.DIMENSION).activeWhen(active::get);
        assertEquals(0, s.size());
        s.add(7, 7); // a module that is off and still gets asked: must not replace the file with its one chunk
        Stores.flush();
        assertArrayEquals(before, Files.readAllBytes(fileOf(Scope.DIMENSION, p)));
        active.set(true);
        assertEquals(Set.of(ChunkSetStore.pack(1, 1), ChunkSetStore.pack(2, 2)), contentsOf(s));
        active.set(false);
        assertEquals(0, s.size());
    }

    @Test
    void adoptionCopiesAndLeavesTheOldFileByteForByteAndTimeForTime() throws IOException {
        Scope.Place p = at("play.example.org", "minecraft:overworld");
        Path oldFile = old("play.example.org_minecraft_overworld.json", json(ChunkSetStore.pack(3, -4), ChunkSetStore.pack(-1, 0), ChunkSetStore.pack(100000, 100000)));
        byte[] bytes = Files.readAllBytes(oldFile);
        FileTime time = Files.getLastModifiedTime(oldFile);
        where.set(p);
        ChunkSetStore s = adopting(Scope.DIMENSION);
        assertEquals(Set.of(ChunkSetStore.pack(3, -4), ChunkSetStore.pack(-1, 0), ChunkSetStore.pack(100000, 100000)), contentsOf(s));
        Stores.flush();
        assertTrue(Files.exists(fileOf(Scope.DIMENSION, p)), "the new file is written");
        assertArrayEquals(bytes, Files.readAllBytes(oldFile));
        assertEquals(time, Files.getLastModifiedTime(oldFile));
        assertEquals(0, Notices.texts("Saved data").size());
        where.set(null);
        where.set(p);
        assertEquals(3, s.size(), "it comes back from the new file");
    }

    @Test
    void adoptionHappensOnceAndTheNewFileWinsEvenIfTheOldOneChangesLater() throws IOException {
        Scope.Place p = at("play.example.org", "minecraft:overworld");
        Path oldFile = old("play.example.org_minecraft_overworld.json", json(ChunkSetStore.pack(1, 1)));
        where.set(p);
        ChunkSetStore s = adopting(Scope.DIMENSION);
        assertEquals(1, s.size());
        s.add(2, 2);
        Stores.flush();
        Files.writeString(oldFile, json(ChunkSetStore.pack(1, 1), ChunkSetStore.pack(50, 50), ChunkSetStore.pack(60, 60)));
        ChunkSetStore later = adopting(Scope.DIMENSION);
        assertEquals(Set.of(ChunkSetStore.pack(1, 1), ChunkSetStore.pack(2, 2)), contentsOf(later), "what the new file holds, not what the old one holds now");
        ChunkSetStore again = adopting(Scope.DIMENSION);
        assertTrue(again.load());
        assertEquals(2, again.size());
    }

    @Test
    void aNewerClientsFileIsNotOverwrittenByAnAdoption() throws IOException {
        Scope.Place p = at("play.example.org", "minecraft:overworld");
        old("play.example.org_minecraft_overworld.json", json(ChunkSetStore.pack(1, 1)));
        where.set(p);
        ChunkSetStore plain = store(Scope.DIMENSION);
        plain.add(40, 40);
        plain.add(41, 41);
        plain.flush();
        Path file = fileOf(Scope.DIMENSION, p);
        byte[] before = Files.readAllBytes(file);
        FileTime time = Files.getLastModifiedTime(file);
        ChunkSetStore s = adopting(Scope.DIMENSION);
        assertEquals(Set.of(ChunkSetStore.pack(40, 40), ChunkSetStore.pack(41, 41)), contentsOf(s));
        Stores.flush();
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(time, Files.getLastModifiedTime(file));
    }

    @Test
    void aBackupOfTheNewFileAlsoCountsAsTheNewFileExisting() throws IOException {
        Scope.Place p = at("play.example.org", "minecraft:overworld");
        old("play.example.org_minecraft_overworld.json", json(ChunkSetStore.pack(1, 1)));
        where.set(p);
        ChunkSetStore plain = store(Scope.DIMENSION);
        plain.add(40, 40);
        plain.flush();
        plain.add(41, 41);
        plain.flush();
        Path file = fileOf(Scope.DIMENSION, p);
        Files.delete(file); // the file is gone, its .bak (the first save) is not
        ChunkSetStore s = adopting(Scope.DIMENSION);
        assertEquals(Set.of(ChunkSetStore.pack(40, 40)), contentsOf(s), "the backup wins over the old file");
    }

    @Test
    void anUnreadableOldFileAdoptsNothingWritesNothingAndWarnsOnce() throws IOException {
        Scope.Place p = at("play.example.org", "minecraft:overworld");
        Path oldFile = old("play.example.org_minecraft_overworld.json", "[12,34,");
        byte[] bytes = Files.readAllBytes(oldFile);
        FileTime time = Files.getLastModifiedTime(oldFile);
        where.set(p);
        ChunkSetStore s = adopting(Scope.DIMENSION);
        assertEquals(0, s.size());
        Stores.flush();
        assertEquals(List.of(oldFile), allFiles(), "no file was written anywhere");
        assertArrayEquals(bytes, Files.readAllBytes(oldFile));
        assertEquals(time, Files.getLastModifiedTime(oldFile));
        List<String> warnings = Notices.texts("Saved data");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("play.example.org_minecraft_overworld.json"), warnings.get(0));
        where.set(null);
        where.set(p);
        assertEquals(0, s.size());
        assertEquals(1, Notices.texts("Saved data").size(), "the same file is reported once");
        s.add(1, 1);
        Stores.flush();
        assertTrue(Files.exists(fileOf(Scope.DIMENSION, p)), "the first thing the module saves creates the file");
        assertArrayEquals(bytes, Files.readAllBytes(oldFile));
    }

    @Test
    void anOldFileThatIsNotAnArrayOrHoldsTextIsUnreadable() throws IOException {
        for (String bad : new String[] {"", "{\"a\":1}", "[1,\"x\"]", "[1,[2]]", "not json at all", "\u0000\u0000"}) {
            Notices.clear("Saved data");
            Path d = Files.createTempDirectory(dir, "bad");
            Path f = d.resolve("x.json");
            Files.writeString(f, bad);
            assertThrows(Exception.class, () -> ChunkSetStore.readJsonArray(f), bad);
        }
    }

    @Test
    void twoOldSpellingsOfOneServerAreJoinedAsAUnion() throws IOException {
        Scope.Place p = at("Play.Example.org", "minecraft:overworld");
        Path a = old("Play.Example.org_minecraft_overworld.json", json(ChunkSetStore.pack(1, 1), ChunkSetStore.pack(2, 2)));
        Path b = old("play.example.org_25565_minecraft_overworld.json", json(ChunkSetStore.pack(2, 2), ChunkSetStore.pack(3, 3)));
        byte[] ba = Files.readAllBytes(a);
        byte[] bb = Files.readAllBytes(b);
        where.set(p);
        ChunkSetStore s = adopting(Scope.DIMENSION);
        assertEquals(Set.of(ChunkSetStore.pack(1, 1), ChunkSetStore.pack(2, 2), ChunkSetStore.pack(3, 3)), contentsOf(s));
        Stores.flush();
        assertTrue(Files.exists(fileOf(Scope.DIMENSION, p)));
        assertArrayEquals(ba, Files.readAllBytes(a));
        assertArrayEquals(bb, Files.readAllBytes(b));
    }

    @Test
    void oneUnreadableOfTwoOldFilesAdoptsNeitherSoNothingIsStrandedBehindANewFile() throws IOException {
        Scope.Place p = at("play.example.org", "minecraft:overworld");
        old("play.example.org_minecraft_overworld.json", json(ChunkSetStore.pack(1, 1)));
        Path bad = old("play.example.org_25565_minecraft_overworld.json", "oops");
        where.set(p);
        ChunkSetStore s = adopting(Scope.DIMENSION);
        assertEquals(0, s.size());
        Stores.flush();
        assertEquals(2, allFiles().size());
        assertEquals(1, Notices.texts("Saved data").size());
        assertTrue(Notices.texts("Saved data").get(0).contains(bad.getFileName().toString()));
    }

    @Test
    void anOldFileWithNothingInItWritesNoNewFile() throws IOException {
        Scope.Place p = at("play.example.org", "minecraft:overworld");
        old("play.example.org_minecraft_overworld.json", "[]");
        where.set(p);
        ChunkSetStore s = adopting(Scope.DIMENSION);
        assertEquals(0, s.size());
        Stores.flush();
        assertEquals(1, allFiles().size());
    }

    @Test
    void anotherServerOrDimensionDoesNotAdoptSomeoneElsesFile() throws IOException {
        old("other.example.org_minecraft_overworld.json", json(ChunkSetStore.pack(1, 1)));
        old("play.example.org_minecraft_the_nether.json", json(ChunkSetStore.pack(2, 2)));
        old("play.example.org_25566_minecraft_overworld.json", json(ChunkSetStore.pack(3, 3)));
        where.set(at("play.example.org", "minecraft:overworld"));
        ChunkSetStore s = adopting(Scope.DIMENSION);
        assertEquals(0, s.size());
        where.set(at("play.example.org", "minecraft:the_nether"));
        assertEquals(Set.of(ChunkSetStore.pack(2, 2)), contentsOf(s));
    }

    @Test
    void aSinglePlayerWorldAdoptsTheFileUnderItsDisplayName() throws IOException {
        old("sp-My_World_minecraft_overworld.json", json(ChunkSetStore.pack(8, 8)));
        where.set(Scope.place(null, "folder-name", "My World", "minecraft:overworld"));
        ChunkSetStore s = adopting(Scope.DIMENSION);
        assertEquals(Set.of(ChunkSetStore.pack(8, 8)), contentsOf(s));
        Stores.flush();
        assertTrue(Files.exists(dir.resolve("data/sky-protected/sp-folder-name/minecraft_overworld.chunks")));
    }

    @Test
    void theChunkSetFileOfAnEarlierVersionIsReadBackAsAdoptionSource() throws IOException {
        ChunkSetStore plain = new ChunkSetStore("demo", () -> dir.resolve("service-demo/play.example.org/minecraft_overworld.chunks"));
        plain.add(5, -5);
        plain.add(-70, 3);
        plain.flush();
        where.set(at("play.example.org", "minecraft:overworld"));
        ChunkSetStore s = ChunkSetStore.scoped("service-demo", Scope.DIMENSION, () -> dir, where::get, () -> now[0]).adopt("service-demo/{server}/{dim}.chunks", ChunkSetStore::readFile);
        assertEquals(Set.of(ChunkSetStore.pack(5, -5), ChunkSetStore.pack(-70, 3)), contentsOf(s));
    }

    @Test
    void anOldReaderMayThrowAnyRuntimeProblemAndStillOnlyWarns() throws IOException {
        old("play.example.org_minecraft_overworld.json", "[1]");
        where.set(at("play.example.org", "minecraft:overworld"));
        ChunkSetStore s = store(Scope.DIMENSION).adopt(OLD_PATTERN, f -> {
            throw new IllegalStateException("surprise");
        });
        assertEquals(0, s.size());
        assertEquals(1, Notices.texts("Saved data").size());
    }

    @Test
    void jsonOldReaderReadsWhatTheOldWriterWrote() throws IOException {
        Path f = old("sample.json", "[4294967296,-1,0,-4294967296,9223372036854775807]");
        assertArrayEquals(new long[] {4294967296L, -1, 0, -4294967296L, Long.MAX_VALUE}, ChunkSetStore.readJsonArray(f));
        assertEquals(1, ChunkSetStore.readJsonArray(old("one.json", "[ 7 ]")).length);
        assertEquals(0, ChunkSetStore.readJsonArray(old("none.json", "[]")).length);
        long k = ChunkSetStore.pack(-3, 9);
        assertEquals(-3, (int) k);
        assertEquals(9, (int) (k >> 32));
    }

    @Test
    void anUnscopedStoreRefusesTheScopeCalls() {
        ChunkSetStore plain = new ChunkSetStore("x", () -> dir.resolve("x.chunks"));
        assertThrows(IllegalStateException.class, () -> plain.adopt("a", f -> new long[0]));
    }
}
