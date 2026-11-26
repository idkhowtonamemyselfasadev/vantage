package dev.vantage.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vantage.core.store.JsonStore;
import net.minecraft.world.entity.player.Player;

/**
 * Players who aren't a threat: nothing flags them, Entity ESP shows them green. config/vantage/friends.json (a list of names) and
 * friend-ids.json (name to UUID), the same on every server. Names ignore case.
 *
 * Thread safety (B-096): the maps are changed from the client thread, the presence service thread and the name-lookup thread, so
 * every method that touches them is synchronized on this class, and the two stores take their snapshot under the same lock. The
 * presence service is told about a change after the lock is released.
 */
public final class Friends {
    private static final Map<String, String> NAMES = new TreeMap<>(); // lowercase -> as typed
    private static final Map<String, UUID> IDS = new TreeMap<>(); // lowercase name -> uuid, learnt from the tab list / the service
    private static final Map<String, String> CASE = new TreeMap<>(); // lowercase -> name as seen
    private static boolean loaded;

    /** A bare list of names, as the file has always been: no version field. */
    private static final JsonStore NAME_FILE = new JsonStore("friends", () -> Config.dir().resolve("friends.json"), 1, () -> {
        synchronized (Friends.class) {
            JsonArray a = new JsonArray();
            for (String n : NAMES.values()) a.add(n);
            return a;
        }
    }, (root, v) -> {
        if (!root.isJsonArray()) throw new IllegalStateException("not a list of names");
        for (JsonElement e : root.getAsJsonArray()) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) NAMES.put(e.getAsString().toLowerCase(Locale.ROOT), e.getAsString());
        }
    }).unversioned();

    private static final JsonStore ID_FILE = new JsonStore("friend-ids", () -> Config.dir().resolve("friend-ids.json"), 1, () -> {
        synchronized (Friends.class) {
            JsonObject o = new JsonObject();
            for (var e : IDS.entrySet()) o.addProperty(CASE.getOrDefault(e.getKey(), e.getKey()), e.getValue().toString());
            return o;
        }
    }, (root, v) -> {
        if (!root.isJsonObject()) throw new IllegalStateException("not a list of ids");
        for (var e : root.getAsJsonObject().entrySet()) {
            try {
                IDS.put(e.getKey().toLowerCase(Locale.ROOT), UUID.fromString(e.getValue().getAsString()));
                CASE.put(e.getKey().toLowerCase(Locale.ROOT), e.getKey());
            } catch (RuntimeException bad) {
                Vantage.LOG.warn("friend-ids.json: the entry {} is not a name and a UUID, skipped", e.getKey());
            }
        }
    });

    private static void ensure() {
        if (loaded) return;
        loaded = true;
        NAME_FILE.load(); // each file on its own: a damaged one is kept aside and the other still loads (B10)
        ID_FILE.load();
    }

    private static void save() {
        NAME_FILE.markDirty();
        ID_FILE.markDirty();
    }

    public static synchronized boolean is(String name) {
        ensure();
        return name != null && NAMES.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public static boolean is(Player p) {
        return is(p.getGameProfile().name());
    }

    /** True when the name is a friend afterwards. */
    public static boolean toggle(String name) {
        String clean = name.trim();
        boolean added;
        synchronized (Friends.class) {
            ensure();
            String k = clean.toLowerCase(Locale.ROOT);
            if (k.isEmpty()) return false;
            added = NAMES.remove(k) == null;
            if (added) NAMES.put(k, clean);
            save();
        }
        Presence.friendChanged(clean, added); // mirrored on the presence service when signed in there
        return added;
    }

    /** Local only: make this name a friend without telling the service (used when the service already has it). */
    public static synchronized void adopt(String name) {
        ensure();
        NAMES.put(name.trim().toLowerCase(Locale.ROOT), name.trim());
        save();
    }

    /** Remember who a name is, so the service can be told and names can be shown for UUIDs. */
    public static synchronized void remember(String name, UUID id) {
        ensure();
        String k = name.trim().toLowerCase(Locale.ROOT);
        UUID old = IDS.put(k, id);
        CASE.put(k, name.trim());
        if (!id.equals(old)) save();
    }

    public static synchronized UUID idOf(String name) {
        ensure();
        return name == null ? null : IDS.get(name.toLowerCase(Locale.ROOT));
    }

    public static synchronized String nameOf(UUID id) {
        ensure();
        for (var e : IDS.entrySet()) if (e.getValue().equals(id)) return CASE.getOrDefault(e.getKey(), NAMES.getOrDefault(e.getKey(), e.getKey()));
        return null;
    }

    public static synchronized List<String> list() {
        ensure();
        return new ArrayList<>(NAMES.values());
    }

    private Friends() {}

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.]{3,17}");

    /** The three chat commands for friends, declared by the Friends module. */
    public static List<dev.vantage.core.command.Command> commands() {
        var name = (java.util.function.Supplier<dev.vantage.core.command.Arg<String>>) () -> dev.vantage.core.command.Args.word("name", "a player name: 3 to 17 letters, digits, _ or .");
        return List.of(
                dev.vantage.core.command.Command.named("friend", "Add a friend, or remove one who is already a friend; no name lists your friends").alias("f")
                        .arg(name.get().optional())
                        .runs((a, r) -> {
                            if (!a.has(0)) { r.info(describeList()); return; }
                            String n = a.get(0);
                            if (!NAME.matcher(n).matches()) { r.error("That's not a player name"); return; }
                            boolean now = toggle(n);
                            r.success(n + (now ? " added as a friend" : " removed from friends"));
                        }),
                dev.vantage.core.command.Command.named("unfriend", "Remove a friend")
                        .arg(name.get())
                        .runs((a, r) -> {
                            String n = a.get(0);
                            if (!NAME.matcher(n).matches()) { r.error("That's not a player name"); return; }
                            if (!is(n)) { r.error(n + " is not a friend"); return; }
                            toggle(n);
                            r.success(n + " removed from friends");
                        }),
                dev.vantage.core.command.Command.named("friends", "List your friends").runs((a, r) -> r.info(describeList())));
    }

    private static String describeList() {
        var l = list();
        return l.isEmpty() ? "No friends yet. Type .friend Name" : l.size() + " friends: " + String.join(", ", l);
    }
}
