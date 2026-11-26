package dev.vantage.core;

import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/** The modules the owner starred in the menu. They get a Favourites section at the top of the Active tab and of their category. Saved in config.json as "favs". */
public final class Favourites {
    private static final Set<String> NAMES = new LinkedHashSet<>();

    public static boolean is(Module m) { return NAMES.contains(m.name); }

    public static boolean toggle(Module m) {
        boolean now = NAMES.add(m.name);
        if (!now) NAMES.remove(m.name);
        Config.markDirty();
        return now;
    }

    public static int count() { return NAMES.size(); }

    static void load(JsonElement e) {
        NAMES.clear();
        if (e != null && e.isJsonArray()) for (JsonElement n : e.getAsJsonArray()) if (n.isJsonPrimitive()) NAMES.add(n.getAsString());
    }

    static JsonArray save() {
        JsonArray a = new JsonArray();
        for (String n : NAMES) a.add(n);
        return a;
    }

    private Favourites() {}
}
