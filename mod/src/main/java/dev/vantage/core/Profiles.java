package dev.vantage.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vantage.setting.Setting;

/**
 * Config profiles ("configs"): named snapshots of every module's on/off and settings, one file each in
 * config/vantage/profiles/<name>.json. The player makes them; none ship with the mod. Keys, friends, waypoints, HUD panel
 * positions, the ClickGUI module (theme, menu size, ...), the HUD's Panel size and every HUD piece's corner stay global: loading a profile never
 * touches them. The active profile's name and a fingerprint of the state it was loaded or saved with live in config.json,
 * so "edited" survives a restart. A file can also carry the optional parts "keys", "hud" and "theme" (written only by importing
 * a share code that has them, see ConfigShare); loading applies those parts only when the file has them.
 */
public final class Profiles {
    public static final int MAX_NAME = 32;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Settings that belong to the screen rather than to a setup: module name -> setting names. */
    private static final Map<String, Set<String>> GLOBAL_SETTINGS = Map.of("HUD", Set.of("Panel size"));
    /** File names Windows refuses (also with an extension), so a config called "con" gets "_con.json". */
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6",
            "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** One file in the list. key = the file name without .json; broken = the file could not be read. */
    public record Info(String key, String name, int on, List<String> preview, long saved, boolean broken, String extras) {}

    public record Result(boolean ok, String msg) {}

    private static String active = "";
    private static long baseline;
    private static int seenChanges = -1;
    private static boolean editedNow;

    private record Cached(long mtime, long size, Info info) {}
    private static final Map<String, Cached> FILES = new HashMap<>();
    private static final Set<String> WARNED = new HashSet<>();
    private static List<Info> list = List.of();
    private static long listedAt;
    private static boolean stale = true;

    private Profiles() {}

    public static Path dir() {
        return Config.dir().resolve("profiles");
    }

    /** Letters, digits, space, - and _ only, one space at a time, trimmed, at most 32 long. */
    public static String clean(String s) {
        if (s == null) return "";
        String c = s.replaceAll("[^A-Za-z0-9 _-]", "").replaceAll("\\s+", " ").trim();
        if (c.length() > MAX_NAME) c = c.substring(0, MAX_NAME).trim();
        return c;
    }

    /** The file name (without .json) for a profile name: lower case, since Windows treats "Base" and "base" as one file. */
    public static String key(String name) {
        String k = clean(name).toLowerCase(Locale.ROOT);
        return RESERVED.contains(k) ? "_" + k : k;
    }

    private static Path file(String key) {
        return dir().resolve(key + ".json");
    }

    /** The ClickGUI module is the menu itself: its key, theme and size are never part of a profile. */
    private static boolean global(Module m) {
        return m instanceof dev.vantage.module.client.ClickGuiModule;
    }

    /**
     * Settings that are part of the HUD layout rather than of a setup: the HUD's Panel size and every HUD piece's corner (any
     * pick-one setting whose choices are the four corners, so a new HUD piece is covered without being listed here).
     */
    public static boolean globalSetting(Module m, Setting<?> st) {
        if (GLOBAL_SETTINGS.getOrDefault(m.name, Set.of()).contains(st.name)) return true;
        return st instanceof dev.vantage.setting.ModeSetting ms && ms.modes.equals(List.of(Hud.CORNERS));
    }

    /**
     * One-shot modules: a profile keeps their settings but never switches them. RTP Loop and Macro send commands while on, Freecam moves the
     * camera away from you, Relog leaves the server; none of that should start because a config was loaded.
     */
    private static boolean momentary(Module m) {
        return m instanceof dev.vantage.module.donut.RtpLoop || m instanceof dev.vantage.module.donut.Macro || m instanceof dev.vantage.module.render.Freecam || m instanceof dev.vantage.module.client.Relog;
    }

    public static String active() {
        return active;
    }

    public static boolean isActive(Info i) {
        return !active.isEmpty() && (i.key().equalsIgnoreCase(key(active)) || i.name().equalsIgnoreCase(active));
    }

    /** True when something a profile holds changed since the active profile was loaded or saved. */
    public static boolean edited() {
        if (active.isEmpty()) return false;
        int c = Config.changes();
        if (c != seenChanges) {
            seenChanges = c;
            editedNow = fingerprint() != baseline;
        }
        return editedNow;
    }

    /** Forgets which profile is active (the files stay). */
    public static void forget() {
        if (active.isEmpty()) return;
        active = "";
        Config.markDirty();
    }

    private static void setActive(String name) {
        Config.markDirty();
        active = name;
        baseline = fingerprint();
        seenChanges = Config.changes();
        editedNow = false;
    }

    // ---- what a profile holds ---------------------------------------------------------------------------------------------

    /**
     * Every module's on/off and settings as they are now. While the client is switched off, "on" means what the master switch
     * would bring back. Parts (Base Finder's scanners) carry only their settings: whether they run follows Base Finder and the
     * part's switch, which is one of Base Finder's settings. One-shot modules (momentary) carry only their settings too.
     */
    public static JsonObject snapshot() {
        JsonObject mods = new JsonObject();
        boolean paused = Modules.paused();
        for (Module m : Modules.all()) {
            if (global(m)) continue;
            JsonObject o = new JsonObject();
            if (m.owner == null && !momentary(m)) o.addProperty("enabled", paused ? Modules.pausedList().contains(m.name) : m.isEnabled());
            else if (m.keepsStandalone && m.owner != null && Modules.betaUnlocked()) { // the part also writes the standalone switch an old or locked client reads
                for (Module.Part p : m.owner.parts) if (p.module() == m) o.addProperty("enabled", p.state() != Module.Part.State.OFF);
            }
            JsonObject s = new JsonObject();
            for (Setting<?> st : m.settings) {
                if (globalSetting(m, st) || !st.stored()) continue;
                JsonElement v = st.save();
                if (v != null && !v.isJsonNull()) s.add(st.name, v);
            }
            o.add("settings", s);
            mods.add(m.name, o);
        }
        return mods;
    }

    /** 64-bit FNV-1a of the snapshot, to tell "edited" without keeping a copy. */
    private static long fingerprint() {
        String s = snapshot().toString();
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * Puts a snapshot into effect: modules that should be off go off first, then the settings, then the ones that should be on
     * come on, each through its normal onEnable/onDisable. Modules or settings this version does not know are skipped; modules
     * the snapshot does not mention stay as they are. Returns how many entries were skipped.
     */
    public static int apply(JsonObject mods) {
        int skipped = 0;
        Map<Module, JsonObject> found = new LinkedHashMap<>();
        Map<Module, Boolean> want = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : mods.entrySet()) {
            Module m = Modules.byName(e.getKey());
            if (m == null && Modules.lockedName(e.getKey())) continue; // a locked beta does not exist for the user
            if (m == null || !e.getValue().isJsonObject()) { skipped++; continue; }
            if (global(m)) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            found.put(m, o);
            JsonElement en = o.get("enabled");
            if (m.owner == null && !momentary(m) && en != null && en.isJsonPrimitive()) want.put(m, en.getAsBoolean());
        }
        if (Modules.betaUnlocked()) Config.migratePart(mods); // a profile from a locked client has Sound Detector's old switch and no part state
        boolean paused = Modules.paused();
        if (!paused) for (var w : want.entrySet()) if (!w.getValue()) w.getKey().setEnabled(false);
        for (var f : found.entrySet()) {
            Module m = f.getKey();
            JsonElement se = f.getValue().get("settings");
            if (se == null || !se.isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> e : se.getAsJsonObject().entrySet()) {
                Setting<?> st = null;
                for (Setting<?> s : m.settings) if (s.name.equals(e.getKey())) { st = s; break; }
                if (st != null && globalSetting(m, st)) continue;
                if (st == null) { if (!GLOBAL_SETTINGS.getOrDefault(m.name, Set.of()).contains(e.getKey())) skipped++; continue; }
                try {
                    put(st, e.getValue());
                } catch (Throwable t) {
                    skipped++;
                    Vantage.LOG.warn("Profile: {} > {} not loaded: {}", m.name, st.name, t.toString());
                }
            }
        }
        if (paused) {
            // the client is switched off: everything stays asleep, the master switch will bring back the profile's set
            List<String> names = new ArrayList<>();
            for (Module m : Modules.all()) {
                if (m.owner != null || global(m)) continue;
                Boolean w = want.get(m);
                if (w != null ? w : Modules.pausedList().contains(m.name)) names.add(m.name);
            }
            Modules.loadPaused(names);
        } else {
            for (var w : want.entrySet()) if (w.getValue()) w.getKey().setEnabled(true);
        }
        for (Module m : Modules.all()) if (!m.parts.isEmpty()) m.syncParts();
        Config.markDirty();
        return skipped;
    }

    /** Sets a setting from saved JSON through set(), so its change hook runs (a part's switch starts or stops the part). */
    /** Loads one saved value into a setting; a value the setting refuses is an exception, which the caller counts as a skipped entry. */
    private static <T> void put(Setting<T> s, JsonElement e) {
        JsonElement old = s.save();
        T before = s.get();
        String refused = s.tryLoad(e);
        if (refused != null) throw new IllegalArgumentException(refused);
        T after = s.get();
        if (Objects.equals(before, after)) return;
        s.load(old);
        s.set(after);
    }

    // ---- files ------------------------------------------------------------------------------------------------------------

    /** The profiles on disk, sorted by name. Looks at the folder at most once a second and only re-reads files that changed. */
    public static List<Info> list() {
        long now = System.currentTimeMillis();
        if (!stale && now - listedAt < 1000) return list;
        stale = false;
        listedAt = now;
        List<Info> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Path d = dir();
        if (Files.isDirectory(d)) {
            try (var files = Files.list(d)) {
                for (Path p : files.toList()) {
                    String fn = p.getFileName().toString();
                    if (!fn.endsWith(".json") || !Files.isRegularFile(p)) continue;
                    String stem = fn.substring(0, fn.length() - 5);
                    if (stem.isEmpty()) continue;
                    seen.add(stem);
                    long mt = Files.getLastModifiedTime(p).toMillis(), size = Files.size(p);
                    Cached c = FILES.get(stem);
                    if (c == null || c.mtime != mt || c.size != size) {
                        c = new Cached(mt, size, read(stem, p, mt));
                        FILES.put(stem, c);
                    }
                    out.add(c.info);
                }
            } catch (Exception e) {
                Vantage.LOG.warn("Could not list config profiles", e);
            }
        }
        FILES.keySet().retainAll(seen);
        out.sort(Comparator.comparing((Info i) -> i.name().toLowerCase(Locale.ROOT)).thenComparing(Info::key));
        list = List.copyOf(out);
        return list;
    }

    public static int count() {
        return list().size();
    }

    private static void changed() {
        stale = true;
    }

    private static Info read(String stem, Path p, long mtime) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            JsonElement n = root.get("name");
            String name = n != null && n.isJsonPrimitive() ? clean(n.getAsString()) : "";
            if (name.isEmpty()) name = clean(stem);
            if (name.isEmpty()) name = stem;
            JsonElement sv = root.get("saved");
            long saved = sv != null && sv.isJsonPrimitive() && sv.getAsJsonPrimitive().isNumber() ? sv.getAsLong() : mtime;
            int on = 0;
            List<String> preview = new ArrayList<>();
            JsonElement me = root.get("modules");
            if (me != null && me.isJsonObject()) {
                JsonObject mods = me.getAsJsonObject();
                for (Module m : Modules.all()) {
                    if (m.owner != null || global(m) || momentary(m) || !(mods.get(m.name) instanceof JsonObject o)) continue;
                    JsonElement en = o.get("enabled");
                    if (en == null || !en.isJsonPrimitive() || !en.getAsBoolean()) continue;
                    on++;
                    if (preview.size() < 5) preview.add(m.name);
                }
            }
            WARNED.remove(stem);
            return new Info(stem, name, on, List.copyOf(preview), saved, false, ConfigShare.extrasOf(root));
        } catch (Exception e) {
            if (WARNED.add(stem)) Vantage.LOG.warn("Config profile {} can't be read: {}", p.getFileName(), e.toString());
            return new Info(stem, stem, 0, List.of(), mtime, true, "");
        }
    }

    /** A profile by its name or file name, case ignored. */
    public static Info find(String raw) {
        if (raw == null) return null;
        String t = raw.trim(), c = clean(raw), k = key(raw);
        for (Info i : list()) if (i.key().equalsIgnoreCase(t) || (!k.isEmpty() && i.key().equalsIgnoreCase(k))) return i;
        for (Info i : list()) if (!c.isEmpty() && i.name().equalsIgnoreCase(c)) return i;
        return null;
    }

    private static void write(Path target, JsonObject root) throws java.io.IOException {
        if (dev.vantage.core.Terms.off()) return;
        Files.createDirectories(dir());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(root));
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Saves the current setup under this name, replacing a profile of the same name. */
    public static Result save(String raw) {
        String name = clean(raw);
        if (name.isEmpty()) return new Result(false, "Give the config a name: letters, digits, space, - and _");
        Info had = find(name);
        // an existing file keeps its own file name (it may differ in case on a hand-made file)
        Path target = had != null && !had.broken() ? file(had.key()) : file(key(name));
        try {
            JsonObject root = new JsonObject();
            root.addProperty("name", name);
            root.addProperty("v", 1);
            root.addProperty("saved", System.currentTimeMillis());
            root.add("modules", snapshot());
            write(target, root);
        } catch (Exception e) {
            Vantage.LOG.warn("Could not save config profile {}", name, e);
            Toasts.warn("Config not saved", name + ": " + e.getClass().getSimpleName());
            return new Result(false, "Could not save " + name + " (" + e.getClass().getSimpleName() + ")");
        }
        changed();
        setActive(name);
        return new Result(true, (had != null ? "Updated " : "Saved ") + name);
    }

    /**
     * Writes a config file from a checked share code (core/ConfigShare): the modules in file form plus the optional keys / hud / theme
     * parts. Nothing is applied and the active config stays as it is.
     */
    public static Result saveFile(String raw, JsonObject modules, JsonObject extras) {
        String name = clean(raw);
        if (name.isEmpty()) return new Result(false, "Give the config a name: letters, digits, space, - and _");
        Info had = find(name);
        Path target = had != null && !had.broken() ? file(had.key()) : file(key(name));
        try {
            JsonObject root = new JsonObject();
            root.addProperty("name", name);
            root.addProperty("v", 1);
            root.addProperty("saved", System.currentTimeMillis());
            root.addProperty("imported", true);
            root.add("modules", modules);
            for (Map.Entry<String, JsonElement> e : extras.entrySet()) root.add(e.getKey(), e.getValue());
            write(target, root);
        } catch (Exception e) {
            Vantage.LOG.warn("Could not save imported config {}", name, e);
            return new Result(false, "Could not save " + name + " (" + e.getClass().getSimpleName() + ")");
        }
        changed();
        return new Result(true, (had != null ? "Updated " : "Saved ") + name);
    }

    /** Loads a profile: modules switch at once, then config.json is saved. */
    public static Result load(String raw) {
        Info i = find(raw);
        if (i == null) return new Result(false, clean(raw).isEmpty() ? "Which config? Type its name" : "No config called " + clean(raw));
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file(i.key()))).getAsJsonObject();
            for (String done : Migrations.upgrade(root)) Vantage.LOG.info("Config profile {} brought up to date: {}", i.key(), done); // an old profile gets the renames a config gets
        } catch (Exception e) {
            Vantage.LOG.warn("Config profile {} can't be read", i.key(), e);
            Toasts.warn("Config not loaded", i.name() + " can't be read (" + e.getClass().getSimpleName() + ")");
            changed();
            return new Result(false, i.name() + " can't be read, nothing changed");
        }
        JsonElement me = root.get("modules");
        if (me == null || !me.isJsonObject()) {
            Toasts.warn("Config not loaded", i.name() + " holds no modules");
            return new Result(false, i.name() + " holds no modules, nothing changed");
        }
        int skipped;
        try {
            skipped = apply(me.getAsJsonObject());
        } catch (Throwable t) {
            Vantage.LOG.error("Loading config profile {} failed", i.name(), t);
            Toasts.warn("Config only partly loaded", i.name() + ": " + t.getClass().getSimpleName());
            setActive(i.name());
            Config.save();
            return new Result(false, i.name() + " only partly loaded");
        }
        List<String> extra = ConfigShare.applyExtras(root);
        setActive(i.name());
        Config.save();
        long on = Modules.all().stream().filter(m -> m.owner == null && !global(m) && m.isEnabled()).count();
        if (Modules.paused()) on = Modules.pausedList().size();
        Toasts.ok("Loaded " + i.name(), on + " modules on" + (skipped > 0 ? ", " + skipped + " old entries skipped" : "") + (extra.isEmpty() ? "" : ", also " + String.join(", ", extra)));
        return new Result(true, "Loaded " + i.name() + (skipped > 0 ? " (" + skipped + " old entries skipped)" : "") + (extra.isEmpty() ? "" : " + " + String.join(", ", extra)));
    }

    public static Result delete(String raw) {
        Info i = find(raw);
        if (i == null) return new Result(false, "No config called " + clean(raw));
        try {
            Files.deleteIfExists(file(i.key()));
        } catch (Exception e) {
            Vantage.LOG.warn("Could not delete config profile {}", i.key(), e);
            return new Result(false, "Could not delete " + i.name() + " (" + e.getClass().getSimpleName() + ")");
        }
        if (isActive(i)) forget();
        changed();
        return new Result(true, "Deleted " + i.name());
    }

    public static Result rename(String from, String to) {
        Info i = find(from);
        if (i == null) return new Result(false, "No config called " + clean(from));
        String name = clean(to);
        if (name.isEmpty()) return new Result(false, "Give it a name: letters, digits, space, - and _");
        if (i.broken()) return new Result(false, i.name() + " can't be read, so it can't be renamed");
        String nk = key(name);
        boolean same = nk.equalsIgnoreCase(i.key());
        Info other = find(name);
        if (!same && other != null && other != i) return new Result(false, "There is already a config called " + other.name());
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file(i.key()))).getAsJsonObject();
            root.addProperty("name", name);
            write(same ? file(i.key()) : file(nk), root);
            if (!same) Files.deleteIfExists(file(i.key()));
        } catch (Exception e) {
            Vantage.LOG.warn("Could not rename config profile {}", i.key(), e);
            changed();
            return new Result(false, "Could not rename " + i.name() + " (" + e.getClass().getSimpleName() + ")");
        }
        if (isActive(i)) { active = name; Config.markDirty(); }
        changed();
        return new Result(true, "Renamed " + i.name() + " to " + name);
    }

    // ---- config.json and chat ---------------------------------------------------------------------------------------------

    /** What config.json keeps about profiles, or null when none is active. */
    static JsonObject state() {
        if (active.isEmpty()) return null;
        JsonObject o = new JsonObject();
        o.addProperty("name", active);
        o.addProperty("hash", Long.toHexString(baseline));
        return o;
    }

    static void restore(JsonElement e) {
        try {
            if (e == null || !e.isJsonObject()) return;
            JsonObject o = e.getAsJsonObject();
            active = o.has("name") ? clean(o.get("name").getAsString()) : "";
            baseline = o.has("hash") ? Long.parseUnsignedLong(o.get("hash").getAsString(), 16) : 0;
            seenChanges = -1;
        } catch (Exception ex) {
            active = "";
            Vantage.LOG.warn("Bad profile entry in config.json, ignored", ex);
        }
    }

    /** "5 min ago", "yesterday", ... for the list. */
    public static String ago(long millis) {
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        if (s < 2 * 86400) return "yesterday";
        if (s < 30L * 86400) return (s / 86400) + " days ago";
        return java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault()).toString();
    }

    /** The chat command: the same text as {@link #command}, as a Command object. */
    public static dev.vantage.core.command.Command asCommand() {
        return dev.vantage.core.command.Command.named("config", "Save, load, delete, export or import configs; no action lists them").alias("configs")
                .arg(dev.vantage.core.command.Args.text("action", "list, save <name>, load <name>, delete <name>, export [name] [keys] [hud] [theme] or import [save <name>|cancel]",
                        List.of("list", "save", "load", "delete", "export", "import")).optional())
                .runs((a, r) -> r.info(command(a.has(0) ? a.get(0) : "")));
    }

    /** ".config ..." typed in chat; the line never reaches the server. Returns the answer for the chat box. */
    public static String command(String args) {
        String a = args == null ? "" : args.trim();
        String[] p = a.split("\\s+", 2);
        String verb = p[0].toLowerCase(Locale.ROOT), rest = p.length > 1 ? p[1].trim() : "";
        switch (verb) {
            case "", "list" -> {
                changed();
                List<Info> l = list();
                if (l.isEmpty()) return "No configs yet. Save your current setup with .config save <name>";
                StringBuilder sb = new StringBuilder(l.size() + (l.size() == 1 ? " config: " : " configs: "));
                for (int k = 0; k < l.size(); k++) {
                    Info i = l.get(k);
                    if (k > 0) sb.append(", ");
                    sb.append(i.name()).append(i.broken() ? " (broken)" : " (" + i.on() + " on)");
                }
                if (!active.isEmpty()) sb.append("  ·  active: ").append(active).append(edited() ? " (edited)" : "");
                return sb.toString();
            }
            case "save" -> { return rest.isEmpty() ? "Use .config save <name>" : save(rest).msg(); }
            case "load" -> { return rest.isEmpty() ? "Use .config load <name>" : load(rest).msg(); }
            case "delete", "del", "remove" -> { return rest.isEmpty() ? "Use .config delete <name>" : delete(rest).msg(); }
            case "import", "export" -> { return ConfigShare.command(verb, rest); }
            default -> { return "Use .config, .config save|load|delete <name>, .config export [name] [keys] [hud] [theme] or .config import"; }
        }
    }
}
