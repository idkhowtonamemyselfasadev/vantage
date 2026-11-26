package dev.vantage.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Everything that turns an older saved file into what this version reads, in one place and in order.
 *
 * A saved file has a version number ("v"). A change to the saved layout gets a step here that works on the JSON tree before any
 * module reads it, so a module only ever sees the current layout and an old file is never wiped by an update. Steps only read and
 * rewrite JSON; they know nothing about the running modules, which is what makes each of them testable with a string.
 *
 * Keys: a module or setting is saved under its name as long as the name still gives its id ({@link Ids#of}), and under its id once
 * the display name has changed. The reader therefore compares ids, never names: "Storage ESP" and "storage-esp" are the same key.
 */
public final class Migrations {
    private Migrations() {}

    /** The layout version this client writes. */
    public static final int CURRENT = 2;

    /** One change to the saved layout. */
    private record Step(String what, java.util.function.Consumer<JsonObject> change) {}

    /** Renames that happened without a version bump, oldest first. They run on every file; each does nothing once it has been applied. None at the moment. */
    private static final List<Step> RENAMES = List.of();

    /** Module names that were renamed, old to new: a share code made before the rename still names the old one. */
    private static final java.util.Map<String, String> OLD_NAMES = java.util.Map.of();

    /** The current name of a module a saved thing calls by an older name (the name itself when it was never renamed). */
    public static String currentName(String name) {
        return name == null ? "" : OLD_NAMES.getOrDefault(name, name);
    }

    /** The version a file says it has; a file without the field is version 1. */
    public static int versionOf(JsonObject root) {
        try {
            return root.has("v") ? root.get("v").getAsInt() : 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** Brings the tree of a config (or of a profile, which has the same module layout) up to date. Returns what was changed, for the log. */
    public static List<String> upgrade(JsonObject root) {
        List<String> done = new ArrayList<>();
        for (Step s : RENAMES) {
            String before = root.toString();
            s.change.accept(root);
            if (!before.equals(root.toString())) done.add(s.what);
        }
        return done;
    }

    /** Moves a module's saved entry to its new name, unless the new name already has one (then the old entry is only dropped). */
    static void renameModule(JsonObject root, String oldName, String newName) {
        if (!root.has("modules") || !root.get("modules").isJsonObject()) return;
        JsonObject mods = root.getAsJsonObject("modules");
        String oldKey = keyFor(mods, Ids.of(oldName));
        if (oldKey == null) return;
        JsonElement entry = mods.remove(oldKey);
        if (keyFor(mods, Ids.of(newName)) == null) mods.add(newName, entry);
    }

    /** The key under which an object holds the thing with this id, whether it was written as a name or as an id; null when it is absent. */
    public static String keyFor(JsonObject o, String id) {
        if (o.has(id)) return id;
        for (String k : o.keySet()) if (id.equals(idOf(k))) return k;
        return null;
    }

    /** All keys of an object by the id they stand for (first one wins when two keys mean the same). */
    public static Map<String, String> keysById(JsonObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : o.keySet()) m.putIfAbsent(idOf(k), k);
        return m;
    }

    /** The id a saved key stands for. A key no id can be made from (only punctuation) stands for itself. */
    public static String idOf(String key) {
        try {
            return Ids.of(key);
        } catch (IllegalArgumentException e) {
            return key;
        }
    }

    /** The key to write for something with this name and id: the readable name while it still gives the id, the id after a rename. */
    public static String saveKey(String name, String id) {
        return id.equals(idOf(name)) ? name : id;
    }
}
