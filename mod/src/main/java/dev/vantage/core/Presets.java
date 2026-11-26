package dev.vantage.core;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;

/**
 * Ready-made setups, offered on the Configs page and applied only when the player presses Apply. They go through the same
 * {@link Profiles#apply} a saved config uses, but they are code, not files: applying one writes nothing into config/vantage/profiles and
 * never touches a saved config. A preset sets every finder (on or off), Base Finder's finder and through-wall ESP switches, and the few other
 * modules it names (Quiet switches Xray off); cosmetics, the HUD, keys, Fullbright, Hand View and everything else stay as they are. Seed Cracker
 * is in none of them, so applying a preset switches it off.
 */
public final class Presets {
    private Presets() {}

    public record Preset(String key, String name, String blurb, Set<String> on, Set<String> scanners, Set<String> off) {}

    /** The through-wall ESPs: off in a preset that does not list them. */
    private static final Set<String> WALL = Set.of("Storage ESP", "Block ESP", "Entity ESP", "Item ESP", "Hole ESP", "Search");

    public static final List<Preset> ALL = List.of(
            new Preset("hunting", "Base hunting", "Base Finder with its scanners and ESPs, Sound Detector, waypoints",
                    Set.of("Base Finder", "Sound Detector", "Waypoints", "ESP Style"),
                    Set.of("Stash Finder", "Base ESP", "Activated Spawners", "Light Scan", "Deep Leaks", "Trail Finder", "Shard Watch", "Storage ESP"), Set.of()),
            new Preset("stash", "Stash runs", "Stash Finder, Storage ESP and trails: the short list for walking to chests",
                    Set.of("Base Finder", "Waypoints", "ESP Style"),
                    Set.of("Stash Finder", "Storage ESP", "Trail Finder", "Base ESP"), Set.of()),
            new Preset("quiet", "Quiet", "Only low-profile display helpers: radar and waypoints, nothing that draws through walls",
                    Set.of("Radar", "Waypoints", "ESP Style"),
                    Set.of(), Set.of("Xray")));

    /** The presets offered on the Configs page (the same in every build). */
    public static List<Preset> list() {
        return ALL;
    }

    public static Preset find(String key) {
        for (Preset p : list()) if (p.key().equals(key)) return p;
        return null;
    }

    /** The module list this preset would apply, as Profiles.apply wants it. */
    public static JsonObject build(Preset p) {
        JsonObject mods = new JsonObject();
        for (Module m : Modules.all()) {
            if (m.owner != null || m instanceof dev.vantage.module.client.ClickGuiModule) continue;
            // every finder is set (on if the preset lists it, else off); anything else only when the preset names it
            if (m.category != Category.FINDER && !p.on().contains(m.name) && !p.off().contains(m.name)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("enabled", p.on().contains(m.name));
            if (!m.parts.isEmpty()) {
                JsonObject s = new JsonObject();
                for (Module.Part part : m.parts) {
                    String n = part.toggle().name;
                    if (p.scanners().contains(n) || p.on().contains(part.module().name)) s.addProperty(n, true); // a preset that names a part in its on list switches that part on
                    else if (part.module().category == Category.FINDER || WALL.contains(n)) s.addProperty(n, false);
                    // everything else (Fullbright, Hand View, Donut Wheel, Player Notifier, ...) is left as the player has it
                }
                o.add("settings", s);
            }
            mods.add(m.name, o);
        }
        return mods;
    }

    public static Profiles.Result apply(String key) {
        Preset p = find(key);
        if (p == null) return new Profiles.Result(false, "No such preset");
        int skipped = Profiles.apply(build(p));
        for (Module m : Modules.all()) for (Module.Part part : m.parts) // the preset's states win over a by-hand flag: parts it names are 'with', parts it switches off are off
            if (!part.toggle().on() || p.on().contains(part.module().name)) part.hand().set(false);
        long on = Modules.all().stream().filter(m -> m.owner == null && m.isEnabled()).count();
        Toasts.ok("Preset " + p.name(), on + " modules on (not saved as a config)");
        return new Profiles.Result(true, "Applied " + p.name() + (skipped > 0 ? " (" + skipped + " skipped)" : "") + ", nothing saved");
    }
}
