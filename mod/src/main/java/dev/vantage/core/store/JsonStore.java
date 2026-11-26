package dev.vantage.core.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vantage.core.Notices;
import dev.vantage.core.io.SafeFile;

/**
 * One JSON document in one file, on {@link SafeFile}: temp file, backup of the last good version, a damaged file kept aside.
 *
 * The owner gives a path (asked at load and, for a store that was never loaded, at save time; a loaded store keeps saving to the file
 * it loaded from, so a change that was not saved yet never lands in the next server's file), a version number, {@code write} and
 * {@code read}. {@code write} runs on the thread that asks for the save (the client thread) and must return a tree that nothing changes
 * afterwards: the writer thread turns it into text. {@code read} gets the parsed document and the version the file had (1 when
 * the file has none), so a store can migrate old files.
 *
 * The document is normally an object and gets a {@code "v"} field (not given to {@code read}). A store whose file must stay as it
 * was before (a bare array, or an object whose keys are data) calls {@link #unversioned()}: nothing is added, the version is 1.
 *
 * Load rule: a damaged file is kept aside and the backup is used; when neither works the store starts empty ({@link #load()} returns
 * false, the owner clears its state) and the file is left alone until something changes. Every problem goes to {@link Notices}.
 */
public final class JsonStore extends Store {
    private static final Predicate<String> DOCUMENT = s -> {
        try {
            JsonElement e = JsonParser.parseString(s);
            return e.isJsonObject() || e.isJsonArray();
        } catch (RuntimeException ex) {
            return false;
        }
    };

    private final int version;
    private final Supplier<? extends JsonElement> write;
    private final BiConsumer<JsonElement, Integer> read;
    private boolean versioned = true;
    private Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private Path bound;

    public JsonStore(String name, Supplier<Path> path, int version, Supplier<? extends JsonElement> write, BiConsumer<JsonElement, Integer> read) {
        this(name, path, version, write, read, System::currentTimeMillis);
    }

    public JsonStore(String name, Supplier<Path> path, int version, Supplier<? extends JsonElement> write, BiConsumer<JsonElement, Integer> read, LongSupplier clock) {
        super(name, path, clock);
        this.version = version;
        this.write = write;
        this.read = read;
    }

    /** Pretty-printed file (for the files a person might open). */
    public JsonStore pretty() {
        gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        return this;
    }

    /** No {@code "v"} field is written or removed; every file counts as version 1. */
    public JsonStore unversioned() {
        versioned = false;
        return this;
    }

    /**
     * Reads the file into the owner through {@code read}. Returns true when something was loaded. False means the file was
     * missing or unusable: the owner starts empty. A change that was not saved yet for another file is handed to the writer first.
     */
    public boolean load() {
        Path p = path.get();
        if (p == null) return false;
        if (bound != null) handOver(); // the old state goes to the writer, in the background; nothing here waits for it
        bound = p;
        SafeFile.Read r = read(p, DOCUMENT); // a save of this file that is still queued is read from memory, not from the older file
        report(p, r);
        if (!r.ok()) return false;
        JsonElement root = JsonParser.parseString(r.text());
        int fileVersion = 1;
        if (versioned && root.isJsonObject()) {
            JsonObject o = root.getAsJsonObject();
            JsonElement v = o.get("v");
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) fileVersion = v.getAsInt();
            o.remove("v");
        }
        try {
            read.accept(root, fileVersion);
            return true;
        } catch (RuntimeException e) {
            // valid JSON that the owner could not make sense of: it starts empty, so keep a copy before anything is written over it
            String kept = copyAside(p);
            Notices.add("Saved data", p.getFileName() + " could not be understood (" + e.getMessage() + "), so it starts empty." + kept);
            return false;
        }
    }

    private static String copyAside(Path p) {
        try {
            Path copy = p.resolveSibling(p.getFileName() + ".unread");
            Files.copy(p, copy, StandardCopyOption.REPLACE_EXISTING);
            return " The file was kept as " + copy.getFileName() + ".";
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    void request() {
        Path p = bound != null ? bound : path.get();
        if (p == null) {
            stayDirty();
            return;
        }
        JsonElement tree = write.get();
        if (tree == null) return;
        if (versioned && tree.isJsonObject()) {
            JsonObject withV = new JsonObject();
            withV.addProperty("v", version);
            for (var e : tree.getAsJsonObject().entrySet()) withV.add(e.getKey(), e.getValue());
            tree = withV;
        }
        JsonElement snapshot = tree;
        submitFile(p, () -> gson.toJson(snapshot), DOCUMENT);
    }
}
