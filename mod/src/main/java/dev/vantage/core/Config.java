package dev.vantage.core;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vantage.core.hud.HudElements;
import dev.vantage.core.io.SafeFile;
import dev.vantage.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/vantage/config.json: every module's on/off, key and settings, plus where the GUI panels sit and which config profile
 * (core/Profiles, config/vantage/profiles/) was loaded last.
 * Changes mark the config dirty; it is written at most once a second, through {@link SafeFile} (temp file, backup, atomic move).
 *
 * Loading never loses more than it must: a value a setting refuses costs that one setting, an entry that cannot be read costs
 * that one module, and a file that cannot be read at all is kept aside while the backup is loaded. Whatever went wrong is
 * collected in {@link #problems()} and shown to the user when they are next in a world.
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean dirty;
    private static long lastSave;
    private static boolean loading;
    private static boolean saveFailureLogged;
    /** Panel positions in the click GUI, by category name: {x, y, collapsed}. */
    public static final Map<String, int[]> PANELS = new HashMap<>();

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("vantage");
    }

    public static void markDirty() {
        if (!loading) { dirty = true; changes++; }
    }

    private static final String NOTICE_SOURCE = "Settings";

    /** What the last load could not take over, in words for the user. Empty after a clean load. */
    public static java.util.List<String> problems() {
        return Notices.texts(NOTICE_SOURCE);
    }

    private static void problem(String text) {
        Notices.add(NOTICE_SOURCE, text);
    }

    /** Tells the user once, in chat, what the load could not take over (and any other saved-data notice). Called when a world is joined (there is no chat before). */
    public static void showProblems() {
        Notices.show();
    }

    private static int changes;

    /** Beta settings found in the file while beta is locked, written back as they were (never added when the file had none). */
    private static final Map<String, JsonObject> RAW_BETA = new java.util.HashMap<>();
    /** Stored entries of locked betas, kept untouched between load and save. */
    private static final Map<String, JsonElement> LOCKED_RAW = new java.util.HashMap<>();
    /** Entries of a file written by a newer client that this version has no module for, by the key they had; written back as they were. */
    private static final Map<String, JsonElement> NEWER_RAW = new java.util.LinkedHashMap<>();

    /** Goes up with every change; config profiles use it to know when to look again whether the active one was edited. */
    public static int changes() {
        return changes;
    }

    public static void tick() {
        if (dirty && System.currentTimeMillis() - lastSave > 1000) save();
    }

    public static void load() {
        loading = true;
        // Modules that are on by default were switched on before this point and marked the config as changed. Nothing may be
        // written before the file has been read: a damaged file would be overwritten with defaults on the first tick.
        dirty = false;
        Notices.clear(NOTICE_SOURCE);
        try {
            Path f = dir().resolve("config.json");
            SafeFile.Read read = SafeFile.read(f, SafeFile.JSON_OBJECT);
            if (read.damaged()) {
                String kept = read.badCopy() == null ? "" : " The damaged file was kept as " + read.badCopy().getFileName() + ".";
                problem(read.ok() ? "the settings file was damaged, so the last good copy was loaded." + kept
                        : "the settings file was damaged and there was no usable backup, so the defaults are used." + kept);
            } else if (read.fromBackup()) {
                problem("the settings file was missing, so the backup copy was loaded.");
            }
            if (!read.ok()) {
                // first start (or nothing usable on disk): every module starts the way it declares
                for (Module m : Modules.everything()) if (m.startsOn() && !m.locked()) m.loadEnabled(true);
                dirty = true;
                return;
            }
            JsonObject root = JsonParser.parseString(read.text()).getAsJsonObject();
            int fileVersion = Migrations.versionOf(root);
            for (String done : Migrations.upgrade(root)) Vantage.LOG.info("Config brought up to date: {}", done);
            JsonObject mods = root.has("modules") && root.get("modules").isJsonObject() ? root.getAsJsonObject("modules") : new JsonObject();
            LOCKED_RAW.clear();
            RAW_BETA.clear();
            NEWER_RAW.clear();
            // entries are found by id, so a module is matched whether the file has its name or, after a rename, its id
            Map<String, String> fileKeys = Migrations.keysById(mods);
            if (fileVersion > Migrations.CURRENT) {
                // written by a newer client: what this version does not know is kept and written back, not dropped
                for (Map.Entry<String, String> e : fileKeys.entrySet()) if (Modules.byId(e.getKey()) == null) NEWER_RAW.put(e.getValue(), mods.get(e.getValue()));
                problem("the settings file was written by a newer version of the client; what this version does not know is kept as it is.");
            }
            for (Module m : Modules.everything()) {
                String saved = fileKeys.get(m.id);
                if (m.locked()) { // a locked beta: its stored entry is ignored but kept, so the next save writes it back
                    if (saved != null) LOCKED_RAW.put(m.name, mods.get(saved));
                    continue;
                }
                if (saved == null) {
                    if (m.holdKey() || m.startsOn()) m.loadEnabled(true); // not in this file yet (new in this version): it starts the way it declares
                    continue;
                }
                try {
                    JsonObject o = mods.getAsJsonObject(saved);
                    if (o.has("bind")) {
                        try {
                            m.bind = o.get("bind").getAsInt();
                        } catch (RuntimeException e) {
                            problem(m.name + ": its key could not be read and was left unset.");
                        }
                    }
                    if (o.has("settings") && o.get("settings").isJsonObject()) {
                        JsonObject s = o.getAsJsonObject("settings");
                        Map<String, String> settingKeys = Migrations.keysById(s);
                        for (Setting<?> st : m.settings) {
                            String key = settingKeys.get(st.id());
                            if (key == null) continue;
                            String refused = st.tryLoad(s.get(key));
                            if (refused != null) problem(m.name + " > " + st.name + ": the saved value was not usable (" + refused + "), the default is used.");
                            if (!st.stored()) RAW_BETA.computeIfAbsent(m.name, k -> new JsonObject()).add(key, s.get(key)); // a locked launch passes a beta key that was already in the file through untouched
                        }
                    }
                    if (m.keepsStandalone) {
                        if (o.has("enabled")) m.standaloneEnabled = o.get("enabled").getAsBoolean();
                        continue; // as a part its state comes from Base Finder's switches (see migratePart), not from its own saved flag
                    }
                    if (o.has("enabled")) m.loadEnabled(o.get("enabled").getAsBoolean());
                } catch (RuntimeException e) {
                    // one module's entry is not what it should be: that module keeps its defaults, the others still load
                    problem(m.name + ": its saved entry could not be read, the defaults are used.");
                    Vantage.LOG.warn("Config entry of {}", m.name, e);
                }
            }
            java.util.Set<String> pausedNames = new java.util.HashSet<>();
            if (root.has("pausedList")) for (JsonElement e : root.getAsJsonArray("pausedList")) pausedNames.add(e.getAsString());
            migratePart(mods, pausedNames);
            if (fileVersion < 2) { // 2026-11-07: Storage ESP went from filled boxes (a wall of chests became one orange slab) to outlines
                var st = Modules.get(dev.vantage.module.render.StorageEsp.class);
                if (st != null && st.mode.is("Both") && st.fillAlpha.i() == 50) { st.mode.set("Outline"); st.fillAlpha.set(20.0); }
                dirty = true;
            }
            if (mods.has("Base Hunt")) { // Base Hunt became part of Base Finder: its key moves over, once
                var bf = Modules.get(dev.vantage.module.finder.BaseFinder.class);
                int old = mods.getAsJsonObject("Base Hunt").get("bind").getAsInt();
                if (old != -1) bf.bind = old;
                bf.amethyst.set(true);
            }
            if (root.has("pausedList")) {
                java.util.List<String> names = new java.util.ArrayList<>();
                for (JsonElement e : root.getAsJsonArray("pausedList")) names.add(e.getAsString());
                Modules.loadPaused(names);
            }
            if (root.has("profile")) Profiles.restore(root.get("profile"));
            Favourites.load(root.get("favs"));
            // HUD positions used to live in "panels" next to the menu's; they move to the "hud" section (an absolute one waits to be
            // converted to an anchor until the first frame, when the screen size is known). What stays in "panels" is the menu's.
            for (String bad : HudElements.migratePanels(root, HudElements::isKnown)) problem(bad);
            if (root.get("hud") instanceof JsonObject hud) for (String bad : HudElements.load(hud)) problem(bad);
            if (root.get("panels") instanceof JsonObject panels) {
                for (Map.Entry<String, JsonElement> e : panels.entrySet()) {
                    try {
                        var a = e.getValue().getAsJsonArray();
                        PANELS.put(e.getKey(), new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.size() > 2 ? a.get(2).getAsInt() : 0});
                    } catch (RuntimeException ex) {
                        problem("the saved position of the panel " + e.getKey() + " could not be read, the default is used.");
                    }
                }
            }
        } catch (Exception e) {
            problem("part of the settings file could not be read; what was read before that point is kept.");
            Vantage.LOG.warn("Config load stopped early", e);
        } finally {
            for (Module m : Modules.all()) m.syncParts(); // a part's saved state can't outvote its switch
            loading = false;
        }
    }

    /**
     * Beta: Sound Detector used to be a row of its own and is a Base Finder scanner now. The first time a config without part state meets it
     * (no "(by hand)" key saved yet), its old switch decides: was on with Base Finder on = starts with Base Finder, was on with Base Finder
     * off = runs by hand, was off = off. After that the part state is the truth.
     */
    public static void migratePart(JsonObject mods) {
        migratePart(mods, java.util.Set.of());
    }

    /** paused: names the master switch had put to sleep; they count as on. */
    public static void migratePart(JsonObject mods, java.util.Set<String> paused) {
        var bf = Modules.get(dev.vantage.module.finder.BaseFinder.class);
        for (Module.Part p : bf.parts) {
            Module m = p.module();
            if (!m.keepsStandalone || !mods.has(m.name)) continue;
            boolean wasOn = mods.getAsJsonObject(m.name).has("enabled") && mods.getAsJsonObject(m.name).get("enabled").getAsBoolean() || paused.contains(m.name);
            JsonObject b = mods.has(bf.name) ? mods.getAsJsonObject(bf.name) : null;
            JsonObject bs = b != null && b.has("settings") ? b.getAsJsonObject("settings") : null;
            if (bs != null && bs.has(p.hand().name)) continue; // part state already saved
            boolean bfOn = b != null && b.has("enabled") && b.get("enabled").getAsBoolean() || paused.contains(bf.name);
            p.toggle().set(wasOn);
            p.hand().set(wasOn && !bfOn);
        }
    }

    /** Rig only: forget the beta keys a locked load found in the file / pretend the file held one. */
    public static void rawBetaClearForTest() { RAW_BETA.clear(); }

    public static void rawBetaPutForTest(String module, String key, JsonElement value) { RAW_BETA.computeIfAbsent(module, k -> new JsonObject()).add(key, value); }

    public static void save() {
        if (dev.vantage.core.Terms.off()) return; // the terms gate: nothing is written (dirty stays, so a Confirm saves)
        dirty = false;
        lastSave = System.currentTimeMillis();
        try {
            JsonObject root = new JsonObject();
            JsonObject mods = new JsonObject();
            for (Module m : Modules.everything()) {
                String moduleKey = Migrations.saveKey(m.name, m.id);
                if (m.locked()) { // never written fresh, only what the file held
                    JsonElement raw = LOCKED_RAW.get(m.name);
                    if (raw != null) mods.add(moduleKey, raw);
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("enabled", m.keepsStandalone ? m.standaloneEnabled : Panic.persisted(m)); // beta part with a standalone past: its old switch is kept as it was
                o.addProperty("bind", m.bind);
                JsonObject s = new JsonObject();
                for (Setting<?> st : m.settings) {
                    if (!st.stored()) continue;
                    JsonElement v = st.save();
                    if (!v.isJsonNull()) s.add(Migrations.saveKey(st.name, st.id()), v);
                }
                JsonObject keep = RAW_BETA.get(m.name);
                if (keep != null) for (var kv : keep.entrySet()) if (!s.has(kv.getKey())) s.add(kv.getKey(), kv.getValue());
                o.add("settings", s);
                mods.add(moduleKey, o);
            }
            NEWER_RAW.forEach((k, v) -> { if (!mods.has(k)) mods.add(k, v); });
            root.addProperty("v", Migrations.CURRENT);
            root.add("modules", mods);
            if (Modules.paused()) {
                var list = new com.google.gson.JsonArray();
                for (String n : Modules.pausedList()) list.add(n);
                root.add("pausedList", list);
            }
            JsonObject panels = new JsonObject();
            PANELS.forEach((k, v) -> {
                var a = new com.google.gson.JsonArray();
                for (int x : v) a.add(x);
                panels.add(k, a);
            });
            root.add("panels", panels);
            JsonObject hud = HudElements.save();
            if (hud.size() > 0) root.add("hud", hud);
            if (Favourites.count() > 0) root.add("favs", Favourites.save());
            JsonObject profile = Profiles.state();
            if (profile != null) root.add("profile", profile);
            SafeFile.write(dir().resolve("config.json"), GSON.toJson(root), SafeFile.JSON_OBJECT);
            saveFailureLogged = false;
        } catch (Exception e) {
            dirty = true; // B-183: a save that failed is tried again (at most once a second, from tick), not forgotten until the next change
            if (!saveFailureLogged) Vantage.LOG.warn("Could not save config.json (tried again every second until it works)", e);
            saveFailureLogged = true;
        }
    }

    /** Folder-safe name for the current server or singleplayer world, for per-server data files. */
    public static String serverKey() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var sd = mc.getCurrentServer();
        String k;
        if (sd != null) k = sd.ip;
        else if (mc.getSingleplayerServer() != null) k = "sp-" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else k = "unknown";
        return k.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Config() {}
}
