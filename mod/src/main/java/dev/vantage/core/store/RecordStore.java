package dev.vantage.core.store;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.vantage.core.Notices;
import dev.vantage.core.Vantage;
import dev.vantage.core.io.SafeFile;

/**
 * A list of records that grows (findings, waypoints, stash marks): a base file plus a journal. A change is one short line appended
 * to the journal, so it costs one small write however many records there are; when the journal passes a size, and at
 * {@link #flush()}, the journal is folded into a new base file.
 *
 * Files (all next to the base file {@code X}): {@code X} is {@code {"v":1,"records":[...]}} through {@link SafeFile} (so it has
 * {@code X.bak} and kept-aside copies); {@code X.journal} has one JSON object per line: {@code {"op":"a"|"u","r":{record}}} or
 * {@code {"op":"d","id":"..."}}; {@code X.journal.old} is the journal that the last fold folded in. It exists because the backup
 * ({@code X.bak}) is the base from before that fold: if the base is damaged, backup + old journal + journal give everything back.
 *
 * Replaying is idempotent (add and update are both "set this id", remove is "drop this id"), so a crash between writing the new base
 * and starting a new journal only replays changes the base already has. A last line cut off by a crash is dropped (and the journal
 * is cut back to the last whole line so the next append starts clean); any other unreadable line is skipped, a copy of the journal is
 * kept, and the user is told once.
 *
 * Records must be immutable once added (the fold serialises them on the storage thread), and {@code toJson}, {@code fromJson} and
 * {@code id} must not depend on anything that changes. All methods may be called from one thread at a time; they are synchronized.
 */
public final class RecordStore<T> extends Store {
    private static final int FORMAT = 1;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** The base is readable when it is one complete JSON object; checked by reading tokens, without building 500,000 records as a tree. */
    private static final Predicate<String> BASE_VALID = s -> {
        try (JsonReader r = new JsonReader(new StringReader(s))) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) return false;
            r.skipValue();
            return r.peek() == JsonToken.END_DOCUMENT;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    };

    private final long foldBytes;
    private final Function<T, JsonObject> toJson;
    private final Function<JsonObject, T> fromJson;
    private final Function<T, String> id;
    private final LinkedHashMap<String, T> records = new LinkedHashMap<>();
    private final List<String> queued = new ArrayList<>();
    /** Lines whose append failed, kept in order until one succeeds. Storage thread only. */
    private final List<String> backlog = new ArrayList<>();
    private long journalBytes;
    private volatile boolean foldWanted;
    private Path bound;

    public RecordStore(String name, Supplier<Path> base, long foldBytes, Function<T, JsonObject> toJson, Function<JsonObject, T> fromJson, Function<T, String> id) {
        this(name, base, foldBytes, toJson, fromJson, id, System::currentTimeMillis);
    }

    public RecordStore(String name, Supplier<Path> base, long foldBytes, Function<T, JsonObject> toJson, Function<JsonObject, T> fromJson, Function<T, String> id,
            LongSupplier clock) {
        super(name, base, clock);
        this.foldBytes = foldBytes;
        this.toJson = toJson;
        this.fromJson = fromJson;
        this.id = id;
    }

    static Path journalOf(Path base) {
        return base.resolveSibling(base.getFileName() + ".journal");
    }

    static Path oldJournalOf(Path base) {
        return base.resolveSibling(base.getFileName() + ".journal.old");
    }

    // ---- the records ----

    public synchronized void add(T record) {
        records.put(id.apply(record), record);
        change("a", record);
    }

    /** Replaces the record with the same id (and adds it when there is none). */
    public synchronized void update(T record) {
        records.put(id.apply(record), record);
        change("u", record);
    }

    /** Removes the record with this id; returns it, or null when there was none (nothing is written then). */
    public synchronized T remove(String recordId) {
        T old = records.remove(recordId);
        if (old == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("op", "d");
        o.addProperty("id", recordId);
        enqueue(o.toString());
        return old;
    }

    public synchronized T get(String recordId) {
        return records.get(recordId);
    }

    public synchronized int size() {
        return records.size();
    }

    /** Calls the consumer for every record, oldest first. Do not change the store from inside it. */
    public synchronized void forEach(Consumer<T> each) {
        records.values().forEach(each);
    }

    public synchronized List<T> all() {
        return new ArrayList<>(records.values());
    }

    public synchronized void clear() {
        List<String> ids = new ArrayList<>(records.keySet());
        for (String i : ids) remove(i);
    }

    private void change(String op, T record) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.add("r", toJson.apply(record));
        enqueue(o.toString());
    }

    private void enqueue(String line) {
        queued.add(line);
        journalBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
        if (journalBytes >= foldBytes) foldWanted = true;
        markDirty();
    }

    // ---- loading ----

    /** Replaces the records with what the files hold. Returns true when there was anything on disk to load from. */
    public synchronized boolean load() {
        Path base = path.get();
        if (base == null) return false;
        if (bound != null) handOver();
        waitFor(base); // a journal cannot be served from memory: wait, but only for jobs that write this very file
        bound = base;
        records.clear();
        queued.clear();
        journalBytes = 0;
        foldWanted = false;
        SafeFile.Read r = SafeFile.read(base, BASE_VALID);
        report(base, r);
        int unreadable = 0;
        if (r.ok()) unreadable += readBase(r.text());
        boolean onDisk = r.ok();
        if (!r.ok() || r.fromBackup()) onDisk |= replay(oldJournalOf(base), false);
        onDisk |= replay(journalOf(base), true);
        if (unreadable > 0) {
            Notices.add("Saved data", unreadable + " record(s) in " + base.getFileName() + " could not be read and were skipped." + keptCopy(base));
        }
        return onDisk;
    }

    private int readBase(String text) {
        int bad = 0;
        try (JsonReader jr = new JsonReader(new StringReader(text))) {
            jr.beginObject();
            while (jr.hasNext()) {
                if (!jr.nextName().equals("records")) {
                    jr.skipValue();
                    continue;
                }
                jr.beginArray();
                while (jr.hasNext()) {
                    try {
                        T rec = fromJson.apply(JsonParser.parseReader(jr).getAsJsonObject());
                        records.put(id.apply(rec), rec);
                    } catch (RuntimeException e) {
                        bad++;
                    }
                }
                jr.endArray();
            }
        } catch (IOException | RuntimeException e) {
            Notices.add("Saved data", name + ": the list of records could not be read to the end (" + e.getMessage() + "); what was read before that is kept.");
        }
        return bad;
    }

    /** Applies the lines of one journal file; returns true when the file existed. {@code live} journals are cut back to a clean end. */
    private boolean replay(Path file, boolean live) {
        if (!Files.isRegularFile(file)) return false;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            Notices.add("Saved data", file.getFileName() + " could not be read (" + e.getMessage() + "), so the changes in it are missing.");
            return true;
        }
        int pos = 0;
        int lineNo = 0;
        int bad = 0;
        int firstBad = 0;
        long cleanEnd = bytes.length;
        boolean needNewline = false;
        while (pos < bytes.length) {
            int nl = pos;
            while (nl < bytes.length && bytes[nl] != '\n') nl++;
            boolean complete = nl < bytes.length;
            String line = new String(bytes, pos, nl - pos, StandardCharsets.UTF_8);
            lineNo++;
            boolean ok = line.isBlank() || apply(line);
            if (!ok && !complete) {
                cleanEnd = pos; // a crash in the middle of an append: this change was never finished
                Vantage.LOG.warn("{}: the last line of the journal was cut off and is dropped", name);
            } else if (!ok) {
                if (bad++ == 0) firstBad = lineNo;
            } else if (!complete) {
                needNewline = true;
            }
            pos = nl + 1;
        }
        if (live) {
            journalBytes = cleanEnd;
            tidyEnd(file, cleanEnd, needNewline);
        }
        if (bad > 0) {
            Notices.add("Saved data", bad + " unreadable line(s) in " + file.getFileName() + " were skipped (the first is line " + firstBad + ")." + keptCopy(file));
        }
        return true;
    }

    private void tidyEnd(Path file, long cleanEnd, boolean needNewline) {
        try {
            if (cleanEnd < Files.size(file)) {
                try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    ch.truncate(cleanEnd);
                }
            } else if (needNewline) {
                Files.writeString(file, "\n", StandardOpenOption.APPEND);
                journalBytes++;
            }
        } catch (IOException e) {
            Vantage.LOG.warn("{}: the journal could not be cleaned up at its end", name, e);
        }
    }

    private boolean apply(String line) {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            switch (o.get("op").getAsString()) {
                case "a", "u" -> {
                    T rec = fromJson.apply(o.getAsJsonObject("r"));
                    records.put(id.apply(rec), rec);
                }
                case "d" -> records.remove(o.get("id").getAsString());
                default -> {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String keptCopy(Path file) {
        try {
            Path copy = file.resolveSibling(file.getFileName() + ".unread-" + LocalDateTime.now().format(STAMP));
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            return " A copy was kept as " + copy.getFileName() + ".";
        } catch (IOException e) {
            return "";
        }
    }

    // ---- saving ----

    @Override
    boolean needsRequest() {
        return dirty() || foldWanted || journalBytes > 0;
    }

    @Override
    void flushRequest() {
        synchronized (this) {
            if (journalBytes > 0) foldWanted = true; // leaving: the journal goes into the base file
        }
        super.flushRequest();
    }

    @Override
    synchronized void request() {
        Path base = bound != null ? bound : path.get();
        if (base == null) {
            stayDirty();
            return;
        }
        if (!queued.isEmpty()) {
            List<String> batch = new ArrayList<>(queued);
            queued.clear();
            submitInOrder(base, () -> append(base, batch));
        }
        if (foldWanted) {
            foldWanted = false;
            journalBytes = 0;
            List<T> snapshot = new ArrayList<>(records.values());
            submitInOrder(base, () -> fold(base, snapshot));
        }
    }

    private void append(Path base, List<String> batch) throws IOException {
        backlog.addAll(batch);
        StringBuilder sb = new StringBuilder();
        for (String l : backlog) sb.append(l).append('\n');
        Path journal = journalOf(base);
        Path dir = journal.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        try (FileChannel ch = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buf = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(false); // one force per batch: a second's worth of changes
        }
        backlog.clear();
    }

    private void fold(Path base, List<T> snapshot) throws IOException {
        StringBuilder sb = new StringBuilder(snapshot.size() * 160 + 32);
        sb.append("{\"v\":").append(FORMAT).append(",\"records\":[");
        boolean first = true;
        for (T rec : snapshot) {
            if (!first) sb.append(',');
            first = false;
            sb.append(toJson.apply(rec));
        }
        sb.append("]}");
        try {
            SafeFile.write(base, sb.toString(), BASE_VALID);
        } catch (IOException e) {
            foldWanted = true; // the old base and the journal are untouched; fold again at the next request
            throw e;
        }
        backlog.clear(); // everything queued before this fold is in the snapshot
        Path journal = journalOf(base);
        if (Files.exists(journal)) Files.move(journal, oldJournalOf(base), StandardCopyOption.REPLACE_EXISTING);
    }
}
