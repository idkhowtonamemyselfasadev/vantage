package dev.vantage.core.store;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.CRC32;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.vantage.core.Notices;
import dev.vantage.core.io.SafeFile;
import dev.vantage.core.log.Log;

/**
 * A set of chunks that can grow without limit (scanned area, protected sky, new and old chunks), kept as regions of 32 x 32 chunks,
 * each a 1,024-bit map of 16 longs. Membership is one map lookup and a bit test; 500,000 chunks side by side are about 490 regions.
 * Chunk coordinates may be negative. Used from one thread (the client thread).
 *
 * File: why it is not JSON. A region is 136 bytes (two ints and 16 longs) however full it is, and a JSON list of chunk numbers costs
 * 10 to 14 bytes per chunk and has to be parsed number by number; the bit maps are a fixed small size, load and save are one pass over
 * a byte array. {@link SafeFile} reads and writes text, so the binary payload is stored as Base64 (a third bigger, still small):
 * <pre>
 * VCHUNKS 1 &lt;regions&gt; &lt;crc32 of the payload, hex&gt;
 * &lt;base64 of: per region int rx, int rz, 16 x long bits&gt;
 * </pre>
 * The magic word and version let a later layout be told apart; the checksum and the length make a cut-off or damaged file count as
 * damaged, so the backup is used. Everything else about saving is {@link Store}: snapshot on the calling thread, writing on the
 * storage thread, once a second at most.
 *
 * <h3>Following a scope</h3>
 * A store made with {@link Stores#chunkSet} belongs to a {@link Scope} and follows it by itself: when the place changes (join, a
 * dimension change for a DIMENSION store, a module that was switched off is on again) the old set is handed to the writer and the
 * file of the new place is loaded; with no world, or while {@link #activeWhen} says no, nothing is loaded and nothing is written.
 * Every call on the store first looks whether the place changed (a few reference comparisons), and {@link Stores#tick()} does it
 * every client tick, so a module never calls {@link #load()} and needs no code in {@code onEnable}, {@code onJoinWorld} or
 * {@code onDimensionChange} for its store. {@link #onLoad} tells it when a new set arrived.
 *
 * <h3>Adopting old files</h3>
 * {@link #adopt} names where the module used to keep this data and how to read that format. When the store binds to a place whose new
 * file does not exist (not even as a backup or a write still queued), the old files for that place are found, read and COPIED into
 * the store, and the store saves its new file. The old file is never changed, moved or deleted: an older client version still uses it.
 * Adoption happens once: as soon as the new file exists it wins, and an old file that changes later is not looked at again.
 * Two old files for one place (two spellings of one server) are joined as a union. If any old file cannot be read, nothing is
 * adopted, the user is told once which file, the old file stays as it is and the store starts empty without writing a file: an empty
 * new file is written only when the module first saves something.
 */
public final class ChunkSetStore extends Store {
    private static final String MAGIC = "VCHUNKS";
    private static final int FORMAT = 1;
    private static final int WORDS = 16;
    private static final int REGION_BYTES = 8 + WORDS * 8;

    /** One chunk of the set. */
    @FunctionalInterface
    public interface ChunkVisitor {
        void accept(int cx, int cz);
    }

    private final Map<Long, long[]> regions = new HashMap<>();
    private long lastKey = Long.MIN_VALUE;
    private long[] lastBits;
    private int size;
    private Path bound;
    /** Null for a store that is told where its file is (the two public constructors); set for a store that follows a scope. */
    private final Follow follow;

    /** Reads one old file of a module and returns the chunks in it as packed keys ({@link #pack}). Throws when the file cannot be read or is not in the format. */
    @FunctionalInterface
    public interface OldReader {
        long[] read(Path file) throws IOException;
    }

    private record Old(String pattern, OldReader reader) {}

    /** What a scoped store keeps besides the set. */
    private static final class Follow {
        final Scope scope;
        final Supplier<Path> root;
        final Supplier<Scope.Place> places;
        final String name;
        final List<Old> adoptions = new ArrayList<>();
        BooleanSupplier active = () -> true;
        Consumer<Boolean> onLoad = fromFile -> {};
        Scope.Place lastPlace;
        boolean lastOn;
        String boundId;
        boolean busy;
        boolean lastHad;

        Follow(String name, Scope scope, Supplier<Path> root, Supplier<Scope.Place> places) {
            this.name = name;
            this.scope = scope;
            this.root = root;
            this.places = places;
        }

        Path path(Scope.Place place) {
            return Scope.path(root.get(), scope, place, name, EXT);
        }
    }

    static final String EXT = ".chunks";
    /** Old files that were reported as unreadable in this session, so the user hears about each one once. */
    private static final Set<Path> WARNED = new HashSet<>();

    public ChunkSetStore(String name, Supplier<Path> path) {
        this(name, path, System::currentTimeMillis);
    }

    public ChunkSetStore(String name, Supplier<Path> path, LongSupplier clock) {
        super(name, path, clock);
        this.follow = null;
    }

    private ChunkSetStore(String name, Follow follow, LongSupplier clock) {
        super(name, () -> follow.path(follow.places.get()), clock);
        this.follow = follow;
    }

    /** A store that follows {@code scope}; {@code root} is the config folder, {@code places} says where the player is (null: no world). */
    static ChunkSetStore scoped(String name, Scope scope, Supplier<Path> root, Supplier<Scope.Place> places, LongSupplier clock) {
        return new ChunkSetStore(name, new Follow(name, scope, root, places), clock);
    }

    private Follow scoped() {
        if (follow == null) throw new IllegalStateException(name + " is not bound to a scope");
        return follow;
    }

    /** The store loads only while this is true (a module passes {@code this::activeInWorld}); while it is false the set is empty and nothing is written. */
    public ChunkSetStore activeWhen(BooleanSupplier active) {
        scoped().active = active;
        return this;
    }

    /** Called after every load for a new place, with true when a file (or an adoption) supplied chunks. Runs on the client thread; the set is already in place. */
    public ChunkSetStore onLoad(Consumer<Boolean> onLoad) {
        scoped().onLoad = onLoad;
        return this;
    }

    /**
     * Declares where this data used to be kept: a path under the config folder with {@code {server}} and {@code {dim}} for the old
     * spellings (see {@link OldFiles}), and the reader of that format. Can be called more than once; the files of all declarations are joined.
     */
    public ChunkSetStore adopt(String pattern, OldReader reader) {
        scoped().adoptions.add(new Old(pattern, reader));
        return this;
    }

    @Override
    void followScope() {
        if (follow != null) followNow();
    }

    private static long regionKey(int cx, int cz) {
        return (cx >> 5 & 0xFFFFFFFFL) | ((long) (cz >> 5) << 32);
    }

    private static int bitIndex(int cx, int cz) {
        return (cz & 31) << 5 | (cx & 31);
    }

    private long[] bitsOf(int cx, int cz, boolean create) {
        long key = regionKey(cx, cz);
        if (key == lastKey && lastBits != null) return lastBits;
        long[] bits = regions.get(key);
        if (bits == null) {
            if (!create) return null;
            bits = new long[WORDS];
            regions.put(key, bits);
        }
        lastKey = key;
        lastBits = bits;
        return bits;
    }

    /** Chunk as Minecraft packs it ({@code ChunkPos.asLong}): x in the low 32 bits, z in the high 32. */
    public static long pack(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    public boolean add(long packed) {
        return add((int) packed, (int) (packed >> 32));
    }

    public boolean remove(long packed) {
        return remove((int) packed, (int) (packed >> 32));
    }

    public boolean contains(long packed) {
        return contains((int) packed, (int) (packed >> 32));
    }

    /** Returns true when the chunk was not in the set before. */
    public boolean add(int cx, int cz) {
        followScope();
        if (!put(cx, cz)) return false;
        markDirty();
        return true;
    }

    private boolean put(int cx, int cz) {
        long[] bits = bitsOf(cx, cz, true);
        int i = bitIndex(cx, cz);
        long mask = 1L << (i & 63);
        if ((bits[i >> 6] & mask) != 0) return false;
        bits[i >> 6] |= mask;
        size++;
        return true;
    }

    /** Returns true when the chunk was in the set. */
    public boolean remove(int cx, int cz) {
        followScope();
        long[] bits = bitsOf(cx, cz, false);
        if (bits == null) return false;
        int i = bitIndex(cx, cz);
        long mask = 1L << (i & 63);
        if ((bits[i >> 6] & mask) == 0) return false;
        bits[i >> 6] &= ~mask;
        size--;
        boolean empty = true;
        for (long w : bits) if (w != 0) { empty = false; break; }
        if (empty) {
            regions.remove(regionKey(cx, cz));
            lastKey = Long.MIN_VALUE;
            lastBits = null;
        }
        markDirty();
        return true;
    }

    public boolean contains(int cx, int cz) {
        followScope();
        long[] bits = bitsOf(cx, cz, false);
        if (bits == null) return false;
        int i = bitIndex(cx, cz);
        return (bits[i >> 6] & 1L << (i & 63)) != 0;
    }

    public int size() {
        followScope();
        return size;
    }

    public int regionCount() {
        followScope();
        return regions.size();
    }

    public void forEach(ChunkVisitor visitor) {
        followScope();
        for (Map.Entry<Long, long[]> e : regions.entrySet()) {
            int rx = (int) (long) e.getKey();
            int rz = (int) (e.getKey() >> 32);
            long[] bits = e.getValue();
            for (int w = 0; w < WORDS; w++) {
                long word = bits[w];
                while (word != 0) {
                    int b = Long.numberOfTrailingZeros(word);
                    word &= word - 1;
                    int i = w << 6 | b;
                    visitor.accept(rx << 5 | (i & 31), rz << 5 | (i >> 5));
                }
            }
        }
    }

    /**
     * The chunks of the set as a new array of packed keys (the layout of {@link #pack}, which is what {@code R3.tileSet} and
     * {@code ChunkMath.key} use), in no particular order: it costs one pass over the bit maps and sorting would add n log n for nothing
     * the callers use. The array is a copy; the store may change afterwards.
     */
    public long[] keys() {
        followScope();
        long[] out = new long[size];
        int[] n = {0};
        forEach((cx, cz) -> out[n[0]++] = pack(cx, cz));
        return out;
    }

    public void clear() {
        followScope();
        if (size == 0 && regions.isEmpty()) return;
        regions.clear();
        lastKey = Long.MIN_VALUE;
        lastBits = null;
        size = 0;
        markDirty();
    }

    // ---- file ----

    private static final Predicate<String> VALID = s -> decode(s) != null;

    /** The payload of a file's text, or null when it is not a complete file of this format. */
    private static byte[] decode(String text) {
        try {
            int nl = text.indexOf('\n');
            if (nl < 0) return null;
            String[] head = text.substring(0, nl).split(" ");
            if (head.length != 4 || !head[0].equals(MAGIC) || Integer.parseInt(head[1]) != FORMAT) return null;
            byte[] payload = Base64.getDecoder().decode(text.substring(nl + 1).trim());
            if (payload.length != Integer.parseInt(head[2]) * REGION_BYTES) return null;
            CRC32 crc = new CRC32();
            crc.update(payload);
            return crc.getValue() == Long.parseLong(head[3], 16) ? payload : null;
        } catch (IllegalArgumentException e) {
            return null; // bad number or bad Base64: a damaged file
        }
    }

    /**
     * Replaces the set with what the file holds. Returns true when something was loaded; false means the set is empty and the file is left alone.
     * A scoped store loads the file of the place it is in now (again, even if it was loaded already).
     */
    public boolean load() {
        if (follow != null) {
            follow.lastPlace = null;
            follow.lastOn = false;
            follow.boundId = null;
            follow.lastHad = false;
            followNow();
            return follow.lastHad;
        }
        Path p = path.get();
        if (p == null) return false;
        if (bound != null) handOver(); // the old state goes to the writer, in the background; nothing here waits for it
        return loadFrom(p, null);
    }

    private void clearMemory() {
        regions.clear();
        lastKey = Long.MIN_VALUE;
        lastBits = null;
        size = 0;
    }

    private boolean loadFrom(Path p, Scope.Place place) {
        bound = p;
        clearMemory();
        if (follow != null && !follow.adoptions.isEmpty() && !existsNew(p)) return adoptOld(place);
        SafeFile.Read r = read(p, VALID); // a save of this file that is still queued is read from memory, not from the older file
        report(p, r);
        if (!r.ok()) return false;
        ByteBuffer buf = ByteBuffer.wrap(decode(r.text()));
        while (buf.hasRemaining()) {
            int rx = buf.getInt();
            int rz = buf.getInt();
            long[] bits = new long[WORDS];
            for (int w = 0; w < WORDS; w++) {
                bits[w] = buf.getLong();
                size += Long.bitCount(bits[w]);
            }
            regions.put((rx & 0xFFFFFFFFL) | ((long) rz << 32), bits);
        }
        return true;
    }

    /** The new file is there, or its backup, or a save of it is still on its way to the disk: then the new place has its own data and wins. */
    private static boolean existsNew(Path p) {
        return Stores.pending(p) != null || Files.exists(p) || Files.exists(SafeFile.backupOf(p));
    }

    /** The place changed (or the store was switched on or off): hand over the old set, load the new one. */
    private void followNow() {
        Follow f = follow;
        if (f.busy) return; // a callback of ours asked for the set
        boolean on = f.active.getAsBoolean();
        Scope.Place p = on ? f.places.get() : null;
        if (p == f.lastPlace && on == f.lastOn) return;
        f.lastPlace = p;
        f.lastOn = on;
        String id = on ? f.scope.identity(p) : null;
        if (Objects.equals(id, f.boundId)) return;
        f.busy = true;
        try {
            if (bound != null) handOver(); // the old place's changes go to the writer, to the file they belong to
            clearDirty(); // what was changed without a place belongs to nothing and must not be written into this one
            clearMemory();
            bound = null;
            f.boundId = id;
            if (id == null) return;
            f.lastHad = loadFrom(f.path(p), p);
            f.onLoad.accept(f.lastHad);
        } finally {
            f.busy = false;
        }
    }

    /** Reads every old file for this place and copies what is in them into the set. All or nothing: one unreadable file means nothing is adopted. */
    private boolean adoptOld(Scope.Place place) {
        Path root = follow.root.get();
        Map<Path, long[]> found = new LinkedHashMap<>();
        for (Old o : follow.adoptions) {
            for (Path old : OldFiles.find(root, o.pattern(), place)) {
                if (found.containsKey(old)) continue;
                try {
                    found.put(old, o.reader().read(old));
                } catch (IOException | RuntimeException e) {
                    warnUnreadable(old, e);
                    return false;
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<Path, long[]> e : found.entrySet()) {
            for (long k : e.getValue()) put((int) k, (int) (k >> 32));
            names.add(e.getKey().getFileName().toString());
            if (e.getValue().length > 0) Log.core("store").info("{}: adopted {} entries from {}", name, e.getValue().length, e.getKey().getFileName());
        }
        if (names.size() > 1) Log.core("store").info("{}: adopted the union of {} old files ({}), {} chunks in all", name, names.size(), String.join(", ", names), size);
        if (size == 0) return false; // nothing to copy: no file is written until the module saves something
        markDirty();
        return true;
    }

    private void warnUnreadable(Path old, Exception e) {
        synchronized (WARNED) {
            if (!WARNED.add(old.toAbsolutePath().normalize())) return;
        }
        Notices.add("Saved data", old.getFileName() + " could not be read (" + e.getClass().getSimpleName() + "), so " + name
                + " was not taken over from it. The file is left as it is and " + name + " starts empty.");
    }

    /**
     * The chunks of an old file that is one JSON array of packed chunk numbers ({@code ChunkPos.asLong}), the way the sky-protected file
     * was written: {@code [4294967296,-1,0]}. Throws when the file is empty, is not an array, or holds anything but whole numbers.
     */
    public static long[] readJsonArray(Path file) throws IOException {
        String text = Files.readString(file);
        if (text.isBlank()) throw new IOException(file.getFileName() + " is empty");
        JsonArray a = JsonParser.parseString(text).getAsJsonArray(); // not an array: IllegalStateException, not JSON: JsonParseException
        long[] out = new long[a.size()];
        for (int i = 0; i < out.length; i++) {
            JsonElement e = a.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalStateException("entry " + i + " is not a number");
            out[i] = e.getAsLong();
        }
        return out;
    }

    /** The chunks of a file in this class's own format, as packed keys. For a module that moved from its own file to this format. Throws when the file is not one. */
    public static long[] readFile(Path file) throws IOException {
        byte[] payload = decode(Files.readString(file));
        if (payload == null) throw new IOException(file.getFileName() + " is not a chunk set file");
        ByteBuffer buf = ByteBuffer.wrap(payload);
        List<Long> out = new ArrayList<>();
        while (buf.hasRemaining()) {
            int rx = buf.getInt();
            int rz = buf.getInt();
            for (int w = 0; w < WORDS; w++) {
                long word = buf.getLong();
                while (word != 0) {
                    int b = Long.numberOfTrailingZeros(word);
                    word &= word - 1;
                    int i = w << 6 | b;
                    out.add(pack(rx << 5 | (i & 31), rz << 5 | (i >> 5)));
                }
            }
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    @Override
    void request() {
        Path p = bound != null ? bound : follow != null ? null : path.get(); // a scoped store never writes before it has loaded: its set may be only a part of the file
        if (p == null) {
            stayDirty();
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(regions.size() * REGION_BYTES);
        for (Map.Entry<Long, long[]> e : regions.entrySet()) {
            buf.putInt((int) (long) e.getKey());
            buf.putInt((int) (e.getKey() >> 32));
            for (long w : e.getValue()) buf.putLong(w);
        }
        byte[] payload = buf.array();
        int count = regions.size();
        submitFile(p, () -> {
            CRC32 crc = new CRC32();
            crc.update(payload);
            return MAGIC + " " + FORMAT + " " + count + " " + Long.toHexString(crc.getValue()) + "\n" + Base64.getEncoder().encodeToString(payload) + "\n";
        }, VALID);
    }

    // ---- for a person ----

    /** A readable summary: how many chunks and regions, the area they span, and the fullest regions. */
    public String summary() {
        followScope();
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(": ").append(size).append(" chunks in ").append(regions.size()).append(" regions\n");
        if (size == 0) return sb.toString();
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        forEach((cx, cz) -> {
            box[0] = Math.min(box[0], cx);
            box[1] = Math.min(box[1], cz);
            box[2] = Math.max(box[2], cx);
            box[3] = Math.max(box[3], cz);
        });
        sb.append("  spans chunks x ").append(box[0]).append("..").append(box[2]).append(", z ").append(box[1]).append("..").append(box[3]).append('\n');
        List<Map.Entry<Long, long[]>> list = new ArrayList<>(regions.entrySet());
        list.sort((a, b) -> Integer.compare(count(b.getValue()), count(a.getValue())));
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            long k = list.get(i).getKey();
            sb.append("  region ").append((int) k).append(',').append((int) (k >> 32)).append(": ").append(count(list.get(i).getValue())).append(" of 1024\n");
        }
        return sb.toString();
    }

    private static int count(long[] bits) {
        int n = 0;
        for (long w : bits) n += Long.bitCount(w);
        return n;
    }

    public void dump(PrintStream out) {
        out.print(summary());
    }
}
