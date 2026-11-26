package dev.vantage.core;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.vantage.core.store.JsonStore;

/**
 * What flagged chunks held last time, in config/vantage/scans/<server>.json. The baseline is what was on disk when you joined,
 * so rescans in one session keep comparing against your last visit. Only counts of player-made blocks are kept.
 */
public final class ScanMemory {
    private static final Gson GSON = new Gson();
    private static Map<String, Map<String, Integer>> before = new HashMap<>();
    private static final Map<String, Map<String, Integer>> NOW = new HashMap<>();
    private static String server = "";
    private static boolean dirty;
    private static long lastSave;

    private static Path file(String key) {
        return Config.dir().resolve("scans").resolve(key + ".json");
    }

    /** Object "dim:cx:cz" -> block path -> count, with a v field (read removes it). The merged map is copied into a tree on the client thread; writing it is the storage thread's. */
    private static final JsonStore STORE = new JsonStore("scans", () -> server.isEmpty() ? null : file(server), 1, () -> {
        Map<String, Map<String, Integer>> all = new HashMap<>(before);
        all.putAll(NOW);
        JsonObject o = new JsonObject();
        for (var e : all.entrySet()) {
            JsonObject counts = new JsonObject();
            for (var c : e.getValue().entrySet()) counts.addProperty(c.getKey(), c.getValue());
            o.add(e.getKey(), counts);
        }
        return o;
    }, (root, v) -> {
        Map<String, Map<String, Integer>> m = GSON.fromJson(root, new TypeToken<Map<String, Map<String, Integer>>>() {}.getType());
        before = m == null ? new HashMap<>() : m;
        NOW.clear();
    });

    private static void ensure() {
        String key = Config.serverKey();
        if (key.equals(server)) return;
        save();
        server = key; // from here the store's path is this server's file; the change of the old server was handed over by save() and load()
        if (!STORE.load()) {
            before = new HashMap<>();
            NOW.clear();
        }
    }

    /** Blocks that differ from the last visit, or -1 when this chunk wasn't flagged back then. */
    public static int changed(String id, Map<String, Integer> counts) {
        ensure();
        Map<String, Integer> old = before.get(id);
        if (old == null) return -1;
        int n = 0;
        for (var e : counts.entrySet()) n += Math.abs(e.getValue() - old.getOrDefault(e.getKey(), 0));
        for (var e : old.entrySet()) if (!counts.containsKey(e.getKey())) n += e.getValue();
        return n;
    }

    public static void remember(String id, Map<String, Integer> counts) {
        ensure();
        NOW.put(id, counts);
        dirty = true;
    }

    public static void tick() {
        if (dirty && System.currentTimeMillis() - lastSave > 60_000) save();
    }

    /** Hands what changed to the store (written on the storage thread; Stores.flush waits for it on leaving a world and on exit). */
    public static void save() {
        if (!dirty || server.isEmpty()) return;
        dirty = false;
        lastSave = System.currentTimeMillis();
        STORE.markDirty();
    }

    private ScanMemory() {}
}
