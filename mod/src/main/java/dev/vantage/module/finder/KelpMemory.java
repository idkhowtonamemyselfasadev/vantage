package dev.vantage.module.finder;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vantage.core.Config;
import dev.vantage.core.store.JsonStore;

/**
 * The kelp the user watched grow, per server and dimension, in config/vantage/kelp/&lt;server&gt;.json: "dim|chunk" -> [plants, day last
 * seen]. Only chunk keys and counts. An entry not seen for {@link #KEEP_DAYS} days is dropped, so a wiped or reused world does not
 * hide kelp for ever; the newest {@link #MAX} entries are kept.
 */
public final class KelpMemory {
    public static final int KEEP_DAYS = 30, MAX = 20_000;

    private record Own(int plants, long day) {}

    private final Map<String, Own> map = new LinkedHashMap<>();
    private final Supplier<String> server;
    private final Supplier<Path> dir;
    private String loadedFor = "";
    private final JsonStore store;

    public KelpMemory() {
        this(Config::serverKey, () -> Config.dir().resolve("kelp"));
    }

    KelpMemory(Supplier<String> server, Supplier<Path> dir) {
        this.server = server;
        this.dir = dir;
        store = new JsonStore("kelp", () -> loadedFor.isEmpty() ? null : this.dir.get().resolve(loadedFor + ".json"), 1, this::write,
                (root, v) -> read(root.getAsJsonObject(), today()));
    }

    static long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }

    JsonObject write() {
        JsonObject o = new JsonObject();
        for (var e : map.entrySet()) {
            JsonArray a = new JsonArray();
            a.add(e.getValue().plants());
            a.add(e.getValue().day());
            o.add(e.getKey(), a);
        }
        return o;
    }

    void read(JsonObject o, long today) {
        map.clear();
        for (var e : o.entrySet()) {
            if (e.getKey().indexOf('|') < 0 || !e.getValue().isJsonArray()) continue;
            JsonArray a = e.getValue().getAsJsonArray();
            if (a.size() != 2) continue;
            Own own = new Own(a.get(0).getAsInt(), a.get(1).getAsLong());
            if (own.plants() > 0 && today - own.day() <= KEEP_DAYS) map.put(e.getKey(), own);
        }
    }

    private void ensure() {
        String key = server.get();
        if (key.equals(loadedFor)) return;
        loadedFor = key;
        if (!store.load()) map.clear();
    }

    /** Tests: everything changed is on disk when this returns. */
    void flush() {
        store.flush();
    }

    private int own(String id, long today) {
        ensure();
        Own o = map.get(id);
        if (o == null) return 0;
        if (o.day() != today) { // seen again: the entry stays alive
            put(id, new Own(o.plants(), today));
        }
        return o.plants();
    }

    private void put(String id, Own own) {
        map.remove(id); // newest last
        if (own.plants() > 0) map.put(id, own);
        while (map.size() > MAX) map.remove(map.keySet().iterator().next());
        store.markDirty();
    }

    /** The memory of one dimension of the current server, as KelpWatch reads it. */
    public KelpWatch.Memory in(Supplier<String> dim) {
        return new KelpWatch.Memory() {
            @Override public int own(long chunk) {
                return KelpMemory.this.own(dim.get() + "|" + chunk, today());
            }

            @Override public void keep(long chunk, int plants) {
                ensure();
                put(dim.get() + "|" + chunk, new Own(plants, today()));
            }
        };
    }
}
