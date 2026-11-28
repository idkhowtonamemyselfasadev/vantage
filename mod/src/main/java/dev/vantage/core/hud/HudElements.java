package dev.vantage.core.hud;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The registry of HUD elements ({@link HudElement}) and how they are saved. Every element is declared once, in the constructor of
 * the module that draws it, so {@link #all()} lists all of them even when their modules are off.
 *
 * Saved form (a section "hud" of config.json): {id: {"anchor": "bottom-right", "dx": 12, "dy": 8, "hidden": true}}, written only for
 * elements that are pinned, in another corner ("corner": "top-right" instead of an anchor and offset) or hidden. An old absolute position that has not met a screen size yet is written as {"x": n, "y": n}
 * and turns into an anchor at the first frame the element is drawn. Entries of ids this version has no element for are kept and
 * written back as they were. Everything that touches JSON is a pure function, so it is tested without the game.
 */
public final class HudElements {
    private static final Map<String, HudElement> ALL = new LinkedHashMap<>();
    /** Saved entries of ids with no element in this version (a newer client's), written back untouched. */
    private static final Map<String, JsonElement> UNKNOWN = new LinkedHashMap<>();

    private HudElements() {}

    /** Declares an element (once; a second call returns the one that exists). corner is the stack it sits in by default. */
    public static HudElement declare(String id, String title, String corner) {
        return ALL.computeIfAbsent(id, k -> new HudElement(id, title, corner));
    }

    /** Declares an element drawn by the module of this class, so {@link HudElement#moduleState} can say whether that module is on. */
    public static HudElement declare(String id, String title, String corner, Class<? extends dev.vantage.core.Module> module) {
        return declare(id, title, corner).ownedBy(() -> {
            dev.vantage.core.Module m = dev.vantage.core.Modules.get(module);
            return m != null && m.isEnabled();
        });
    }

    /** The element with this id, or null when there is none. */
    public static HudElement get(String id) {
        return ALL.get(id);
    }

    public static boolean isKnown(String id) {
        return ALL.containsKey(id);
    }

    /** Every declared element, in the order they were declared. */
    public static List<HudElement> all() {
        return new ArrayList<>(ALL.values());
    }

    /** The ids of every declared element, for completion. */
    public static List<String> ids() {
        return new ArrayList<>(ALL.keySet());
    }

    /** The name to show for an id: the element's title, or the id itself for one nobody declared. */
    public static String title(String id) {
        HudElement e = ALL.get(id);
        return e == null ? id : e.title();
    }

    /** Hides or shows an element for good (saved). Returns false when there is no such element. */
    public static boolean setHidden(String id, boolean hidden) {
        HudElement e = ALL.get(id);
        if (e == null) return false;
        if (e.hidden() != hidden) {
            e.setHidden(hidden);
            dev.vantage.core.Config.markDirty();
        }
        return true;
    }

    /** Start of a frame: the elements placed during the last one count as shown. */
    public static void endFrame() {
        for (HudElement e : ALL.values()) e.endFrame();
    }

    public static void unpinAll() {
        for (HudElement e : ALL.values()) e.unpin();
    }

    /**
     * Moves an element to an anchor for good (saved). A corner anchor sends it to that corner's stack, so it still makes room for the
     * others and nothing overlaps; the other five anchors pin it there, an inch from the edge. Returns false when there is no such element.
     */
    public static boolean moveTo(String id, Anchor anchor) {
        HudElement e = ALL.get(id);
        if (e == null) return false;
        if (anchor.corner() != null) e.stackIn(anchor.corner());
        else e.pinTo(new HudLayout.Pin(anchor, anchor.col == 1 ? 0 : 6, anchor.row == 1 ? 0 : 6));
        dev.vantage.core.Config.markDirty();
        return true;
    }

    /** Puts one element back to its default corner and shows it again (saved). Returns false when there is no such element. */
    public static boolean reset(String id) {
        HudElement e = ALL.get(id);
        if (e == null) return false;
        e.reset();
        dev.vantage.core.Config.markDirty();
        return true;
    }

    /** Puts every element back to its default place and shows it. */
    public static void resetAll() {
        for (HudElement e : ALL.values()) e.reset();
        dev.vantage.core.Config.markDirty();
    }

    // ---- saved section ----------------------------------------------------------------------------------------------------

    /** Reads the section "hud" into the registry (see {@link #read}); returns what could not be read, in words. */
    public static List<String> load(JsonObject section) {
        UNKNOWN.clear();
        for (HudElement e : ALL.values()) e.reset();
        return read(section, ALL::get, UNKNOWN);
    }

    /** The section to write: the registry plus what was kept from the file. */
    public static JsonObject save() {
        return write(ALL.values(), UNKNOWN);
    }

    /**
     * Applies a saved section to elements found through find. An entry that cannot be read costs that one element (it keeps its
     * defaults) and adds a line to the result; an id find does not know goes into unknown.
     */
    public static List<String> read(JsonObject section, Function<String, HudElement> find, Map<String, JsonElement> unknown) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonElement> en : section.entrySet()) {
            HudElement e = find.apply(en.getKey());
            if (e == null) {
                unknown.put(en.getKey(), en.getValue());
                continue;
            }
            String bad = apply(e, en.getValue());
            if (bad != null) problems.add("HUD element " + e.title() + ": its saved place was not usable (" + bad + "), the default is used.");
        }
        return problems;
    }

    /** Parses one entry and applies it only when all of it is good. Returns why not, or null. */
    private static String apply(HudElement e, JsonElement value) {
        if (!(value instanceof JsonObject o)) return "not an object";
        boolean hidden = false;
        HudLayout.Pin pin = null;
        float[] abs = null;
        if (o.has("hidden")) {
            if (!(o.get("hidden") instanceof JsonPrimitive p) || !p.isBoolean()) return "hidden is not true or false";
            hidden = p.getAsBoolean();
        }
        String corner = null;
        if (o.has("corner")) {
            Anchor c = o.get("corner") instanceof JsonPrimitive p && p.isString() ? Anchor.byKey(p.getAsString()) : null;
            if (c == null || c.corner() == null) return "unknown corner " + o.get("corner");
            corner = c.corner();
        }
        if (o.has("anchor")) {
            Anchor a = o.get("anchor") instanceof JsonPrimitive p && p.isString() ? Anchor.byKey(p.getAsString()) : null;
            if (a == null) return "unknown anchor " + o.get("anchor");
            Float dx = o.has("dx") ? number(o.get("dx")) : Float.valueOf(0), dy = o.has("dy") ? number(o.get("dy")) : Float.valueOf(0);
            if (dx == null || dy == null) return "the offset is not a number";
            pin = new HudLayout.Pin(a, dx, dy);
        } else if (o.has("x") || o.has("y")) {
            Float x = number(o.get("x")), y = number(o.get("y"));
            if (x == null || y == null) return "the position is not a number";
            abs = new float[] {x, y};
        }
        e.setHidden(hidden);
        if (pin != null) e.pinTo(pin);
        else if (abs != null) e.pinAbsolute(abs[0], abs[1]);
        else if (corner != null) e.stackIn(corner);
        else e.unpin();
        return null;
    }

    private static Float number(JsonElement v) {
        if (!(v instanceof JsonPrimitive p) || !p.isNumber()) return null;
        double d = p.getAsDouble();
        return Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > 100_000 ? null : Float.valueOf((float) d);
    }

    private static Number clean(float v) {
        float r = Math.round(v * 100f) / 100f;
        return r == Math.rint(r) ? (Number) Integer.valueOf((int) r) : (Number) Float.valueOf(r);
    }

    /** The section for a set of elements: only pinned or hidden ones, then the unknown entries that were kept. */
    public static JsonObject write(Collection<HudElement> elements, Map<String, JsonElement> unknown) {
        JsonObject out = new JsonObject();
        for (HudElement e : elements) {
            if (!e.moved() && !e.hidden()) continue;
            JsonObject o = new JsonObject();
            if (e.pin() != null) {
                o.addProperty("anchor", e.pin().anchor().key);
                o.addProperty("dx", clean(e.pin().dx()));
                o.addProperty("dy", clean(e.pin().dy()));
            } else if (e.absolute() != null) {
                o.addProperty("x", clean(e.absolute()[0]));
                o.addProperty("y", clean(e.absolute()[1]));
            } else if (e.stackCorner() != null) {
                o.addProperty("corner", Anchor.byCorner(e.stackCorner()).key);
            }
            if (e.hidden()) o.addProperty("hidden", true);
            out.add(e.id(), o);
        }
        for (Map.Entry<String, JsonElement> u : unknown.entrySet()) if (!out.has(u.getKey())) out.add(u.getKey(), u.getValue());
        return out;
    }

    // ---- old files ----------------------------------------------------------------------------------------------------------

    /**
     * The old way to save HUD positions: entries of "panels" ({id: [x, y]} or [x, y, collapsed], absolute design units from the top
     * left). Moves the entries of HUD ids into root's "hud" section as {"x", "y"} so a position is kept until the screen size is known
     * (the conversion to an anchor happens at the first frame, {@link HudElement#convert}). Entries of other ids (the menu's panels)
     * stay where they are. An entry the section already has a position for is dropped; a hidden-only entry is completed. Returns what
     * could not be read.
     */
    public static List<String> migratePanels(JsonObject root, Predicate<String> isHud) {
        List<String> problems = new ArrayList<>();
        if (!(root.get("panels") instanceof JsonObject panels)) return problems;
        List<String> moved = new ArrayList<>();
        for (Map.Entry<String, JsonElement> en : panels.entrySet()) if (isHud.test(en.getKey())) moved.add(en.getKey());
        for (String id : moved) {
            JsonElement v = panels.remove(id);
            Float x = null, y = null;
            if (v instanceof JsonArray a && a.size() >= 2) {
                x = number(a.get(0));
                y = number(a.get(1));
            }
            if (x == null || y == null) {
                problems.add("HUD element " + id + ": its old saved position was not usable, the default is used.");
                continue;
            }
            JsonObject hud = root.get("hud") instanceof JsonObject h ? h : new JsonObject();
            root.add("hud", hud);
            JsonObject entry = hud.get(id) instanceof JsonObject o ? o : null;
            if (entry == null && hud.has(id)) continue; // a damaged new entry is reported when the section is read
            if (entry == null) hud.add(id, entry = new JsonObject());
            if (entry.has("anchor") || entry.has("x") || entry.has("y")) continue; // the new section wins
            entry.addProperty("x", clean(x));
            entry.addProperty("y", clean(y));
        }
        return problems;
    }

    /**
     * Share codes and profiles still carry HUD positions the old way: {id: [x, y]}, absolute design units. This writes that form for
     * the pinned elements on a screen W by H, so older versions read what this version writes.
     */
    public static JsonObject exportAbsolute(Collection<HudElement> elements, float screenW, float screenH) {
        JsonObject out = new JsonObject();
        for (HudElement e : elements) {
            float[] at = e.pin() != null ? e.position(screenW, screenH) : e.absolute();
            if (at == null) continue;
            JsonArray a = new JsonArray();
            a.add(Math.round(at[0]));
            a.add(Math.round(at[1]));
            out.add(e.id(), a);
        }
        return out;
    }

    public static JsonObject exportAbsolute(float screenW, float screenH) {
        return exportAbsolute(ALL.values(), screenW, screenH);
    }

    /**
     * The pinned elements as {id: [anchor, dx, dy]}, for share codes and the configs made from them. Unlike the absolute form this does
     * not depend on the sender's screen or Panel size: a piece pinned to a corner lands in the same corner on any screen.
     */
    public static JsonObject exportPins(Collection<HudElement> elements) {
        JsonObject out = new JsonObject();
        for (HudElement e : elements) {
            if (e.pin() == null) continue;
            JsonArray a = new JsonArray();
            a.add(e.pin().anchor().key);
            a.add(Math.round(e.pin().dx()));
            a.add(Math.round(e.pin().dy()));
            out.add(e.id(), a);
        }
        return out;
    }

    public static JsonObject exportPins() {
        return exportPins(ALL.values());
    }

    /** One [anchor, dx, dy] entry, or null when it is not one. */
    public static HudLayout.Pin readPin(JsonElement v) {
        if (!(v instanceof JsonArray a) || a.size() != 3 || !(a.get(0) instanceof JsonPrimitive k) || !k.isString()) return null;
        Anchor anchor = Anchor.byKey(k.getAsString());
        Float dx = number(a.get(1)), dy = number(a.get(2));
        return anchor == null || dx == null || dy == null ? null : new HudLayout.Pin(anchor, dx, dy);
    }

    /** Pins every element find knows to its [anchor, dx, dy] entry; unusable entries and unknown ids are left alone. */
    public static void acceptPins(JsonObject pins, Function<String, HudElement> find) {
        for (Map.Entry<String, JsonElement> en : pins.entrySet()) {
            HudElement e = find.apply(en.getKey());
            HudLayout.Pin p = readPin(en.getValue());
            if (e != null && p != null) e.pinTo(p);
        }
    }

    public static void acceptPins(JsonObject pins) {
        acceptPins(pins, ALL::get);
    }

    /**
     * Takes the old {id: [x, y]} form: every id find knows is pinned at that absolute spot (the anchor follows at its first frame);
     * the entries of other ids come back, for the caller to deal with. Entries that are not two whole numbers are skipped.
     */
    public static JsonObject acceptAbsolute(JsonObject panels, Function<String, HudElement> find) {
        JsonObject rest = new JsonObject();
        for (Map.Entry<String, JsonElement> en : panels.entrySet()) {
            HudElement e = find.apply(en.getKey());
            if (e == null) {
                rest.add(en.getKey(), en.getValue());
                continue;
            }
            if (!(en.getValue() instanceof JsonArray a) || a.size() < 2) continue;
            Float x = number(a.get(0)), y = number(a.get(1));
            if (x != null && y != null) e.pinAbsolute(x, y);
        }
        return rest;
    }

    public static JsonObject acceptAbsolute(JsonObject panels) {
        return acceptAbsolute(panels, ALL::get);
    }
}
