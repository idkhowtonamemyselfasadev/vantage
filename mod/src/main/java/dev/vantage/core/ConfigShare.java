package dev.vantage.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.vantage.core.util.Colors;
import dev.vantage.setting.BlockColorSetting;
import dev.vantage.setting.BlockListSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.EntityListSetting;
import dev.vantage.setting.IntSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import dev.vantage.setting.Setting;
import net.minecraft.client.Minecraft;

/**
 * Config share codes: "VNT1:" + base64url( version byte, CRC-32 of the JSON, gzip(JSON) ). A code is plain data, nothing in it is ever
 * run. It holds the on/off of every module and every module setting that is not at its default (defaults are put back on import),
 * and, only when asked for, three extra parts: keybinds, HUD layout, theme + menu size. Never in a code: friends, waypoints, stashes,
 * seeds, server addresses, names, and any text setting (RTP Loop's commands, Void Rescue's command: they can hold
 * a name or an address, so they are left out whatever the options say).
 * <p>
 * Importing only reads, checks and previews the code (a Decoded); the player names it and saves it as a normal config file, and
 * only loading that file changes anything. Like load, import never switches on RTP Loop, Freecam or Relog.
 */
public final class ConfigShare {
    public static final String PREFIX = "VNT1:";
    public static final int VERSION = 1;
    /** Decompressed JSON limit and the longest pasted text that is even looked at. */
    public static final int MAX_RAW = 64 * 1024, MAX_CODE = 200_000;
    /** Discord's message limit: a longer code is still copied but the toast says so. */
    public static final int DISCORD = 2000;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.:/-]{1,100}");
    private static final Pattern PIECE = Pattern.compile("[A-Za-z0-9 _.:-]{1,40}");
    private static final Pattern HEX = Pattern.compile("#?[0-9A-Fa-f]{1,8}");

    /** The three "Include ..." boxes on the Configs page; all off by default. */
    public static boolean optKeys, optHud, optTheme;

    /** A checked code. error == null means usable. modules is in the saved-file form (every module, every setting explicit). */
    public record Decoded(String error, String name, JsonObject modules, JsonObject keys, JsonObject hud, JsonObject theme, int mods, int settings, int skipped) {
        public boolean ok() { return error == null; }

        public List<String> extraParts() {
            List<String> l = new ArrayList<>();
            if (keys != null) l.add("keybinds (" + keys.size() + ")");
            if (hud != null) l.add("HUD layout");
            if (theme != null) l.add("theme");
            return l;
        }

        public JsonObject extras() {
            JsonObject o = new JsonObject();
            if (keys != null) o.add("keys", keys);
            if (hud != null) o.add("hud", hud);
            if (theme != null) o.add("theme", theme);
            return o;
        }

        public String preview() {
            List<String> x = extraParts();
            return "Config '" + name + "' from code: " + mods + (mods == 1 ? " module" : " modules") + ", " + settings
                    + (settings == 1 ? " setting" : " settings") + ", " + skipped + " skipped" + (x.isEmpty() ? "" : "  ·  also carries " + String.join(", ", x));
        }
    }

    /** The checked import waiting for a name; nothing of it is applied. */
    public static Decoded pending;

    private ConfigShare() {}

    // ---- what a setting is worth in a code ----------------------------------------------------------------------------------

    /**
     * What a code carries: numbers (decimal and whole), switches, choices, colours, block and entity lists. Left out on purpose: text
     * settings (they can hold a name, an address or a seed), key settings (key binds only travel with the "keybinds" box), and
     * actions, headers and info lines (they hold nothing). The screen-level settings belong to the extras.
     */
    private static boolean shareable(Module m, Setting<?> st) {
        return isShareable(st) && !Profiles.globalSetting(m, st) && st.stored();
    }

    private static boolean isShareable(Setting<?> st) {
        return st instanceof NumberSetting || st instanceof IntSetting || st instanceof BoolSetting || st instanceof ModeSetting || st instanceof ColorSetting
                || st instanceof BlockListSetting || st instanceof EntityListSetting || st instanceof BlockColorSetting;
    }

    /** A value that passed the setting's own limits. json is what goes into a file, same shape as Setting.save(). */
    private record P(Object typed, JsonElement json) {}

    /** The value checked against this setting: numbers clamped to its range, choices must exist, lists must look like block ids. null = not usable. */
    private static P parse(Setting<?> st, JsonElement e) {
        try {
            if (e == null || e.isJsonNull()) return null;
            if (st instanceof NumberSetting n) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
                double v = e.getAsDouble();
                if (Double.isNaN(v) || Double.isInfinite(v)) return null;
                v = Math.max(n.min, Math.min(n.max, v));
                return new P(v, new JsonPrimitive(v));
            }
            if (st instanceof IntSetting n) {
                if (!isInt(e)) return null; // the setting's own file form refuses a fraction, so a code with one is unusable, not rounded
                int v = (int) Math.max(n.min, Math.min(n.max, e.getAsDouble()));
                return new P(v, new JsonPrimitive(v));
            }
            if (st instanceof BoolSetting) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) return null;
                return new P(e.getAsBoolean(), new JsonPrimitive(e.getAsBoolean()));
            }
            if (st instanceof ModeSetting ms) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || !ms.modes.contains(e.getAsString())) return null;
                return new P(e.getAsString(), new JsonPrimitive(e.getAsString()));
            }
            if (st instanceof ColorSetting) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || !HEX.matcher(e.getAsString()).matches()) return null;
                int c = Colors.parse(e.getAsString());
                return new P(c, new JsonPrimitive(Colors.hex(c)));
            }
            if (st instanceof BlockListSetting || st instanceof EntityListSetting) {
                if (!e.isJsonArray() || e.getAsJsonArray().size() > 2000) return null;
                List<String> l = new ArrayList<>();
                JsonArray a = new JsonArray();
                for (JsonElement x : e.getAsJsonArray()) {
                    if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isString() || !ID.matcher(x.getAsString()).matches()) return null;
                    l.add(x.getAsString());
                    a.add(x.getAsString());
                }
                return new P(List.copyOf(l), a);
            }
            if (st instanceof BlockColorSetting) {
                if (!e.isJsonObject()) return null;
                JsonObject o = e.getAsJsonObject(), out = new JsonObject(), cols = new JsonObject();
                JsonArray off = new JsonArray();
                if (o.get("colors") instanceof JsonObject c) {
                    if (c.size() > 2000) return null;
                    for (var en : c.entrySet()) {
                        if (!ID.matcher(en.getKey()).matches() || !en.getValue().isJsonPrimitive() || !HEX.matcher(en.getValue().getAsString()).matches()) return null;
                        cols.addProperty(en.getKey(), Colors.hex(Colors.parse(en.getValue().getAsString())));
                    }
                }
                if (o.get("off") instanceof JsonArray a) {
                    if (a.size() > 2000) return null;
                    for (JsonElement x : a) {
                        if (!x.isJsonPrimitive() || !ID.matcher(x.getAsString()).matches()) return null;
                        off.add(x.getAsString());
                    }
                }
                out.add("colors", cols);
                out.add("off", off);
                return new P(out.toString(), out);
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return null;
    }

    /** The default of a shareable setting in file form. */
    private static P defaultOf(Setting<?> st) {
        Object d = st.defaultValue();
        if (st instanceof NumberSetting) return new P(d, new JsonPrimitive((Double) d));
        if (st instanceof IntSetting) return new P(d, new JsonPrimitive((Integer) d));
        if (st instanceof BoolSetting) return new P(d, new JsonPrimitive((Boolean) d));
        if (st instanceof ModeSetting) return new P(d, new JsonPrimitive((String) d));
        if (st instanceof ColorSetting) return new P(d, new JsonPrimitive(Colors.hex((Integer) d)));
        if (st instanceof BlockListSetting || st instanceof EntityListSetting) {
            JsonArray a = new JsonArray();
            for (Object s : (List<?>) d) a.add(String.valueOf(s));
            return new P(d, a);
        }
        JsonObject o = new JsonObject();
        o.add("colors", new JsonObject());
        o.add("off", new JsonArray());
        return new P(o.toString(), o);
    }

    private static boolean isDefault(Setting<?> st, P p) {
        return Objects.equals(p.typed, defaultOf(st).typed);
    }

    private static boolean one(Module m) {
        return m.owner == null && !(m instanceof dev.vantage.module.donut.RtpLoop || m instanceof dev.vantage.module.donut.Macro || m instanceof dev.vantage.module.render.Freecam || m instanceof dev.vantage.module.client.Relog);
    }

    private static boolean skipModule(Module m) {
        return m instanceof dev.vantage.module.client.ClickGuiModule;
    }

    private static Setting<?> find(Module m, String name) {
        for (Setting<?> s : m.settings) if (s.name.equals(name)) return s;
        return null;
    }

    // ---- encoding ---------------------------------------------------------------------------------------------------------

    /** What the live client holds for an extra part, in file form (same shape as in a saved config). null part = none. */
    public static JsonObject liveKeys() {
        JsonObject o = new JsonObject();
        for (Module m : Modules.all()) if (!skipModule(m) && m.bind != -1) o.addProperty(m.name, m.bind);
        return o;
    }

    public static JsonObject liveHud() {
        JsonObject o = new JsonObject(), panels = new JsonObject(), set = new JsonObject();
        // the pinned HUD elements in the old {x, y} form, so older versions read this code; then any other two-number entry of the old map
        JsonObject abs = dev.vantage.core.hud.HudElements.exportAbsolute(Ui.W(), Ui.H());
        for (var e : abs.entrySet()) if (PIECE.matcher(e.getKey()).matches()) panels.add(e.getKey(), e.getValue());
        for (Map.Entry<String, int[]> e : Config.PANELS.entrySet()) {
            if (e.getValue().length != 2 || !PIECE.matcher(e.getKey()).matches() || panels.has(e.getKey())) continue;
            JsonArray a = new JsonArray();
            a.add(e.getValue()[0]);
            a.add(e.getValue()[1]);
            panels.add(e.getKey(), a);
        }
        for (Module m : Modules.all()) {
            JsonObject s = new JsonObject();
            for (Setting<?> st : m.settings) {
                if (!Profiles.globalSetting(m, st) || !st.stored()) continue;
                JsonElement v = st.save();
                if (v != null && !v.isJsonNull()) s.add(st.name, v);
            }
            if (s.size() > 0) set.add(m.name, s);
        }
        o.add("panels", panels);
        o.add("settings", set);
        o.add("screen", screen(Ui.W(), Ui.H()));
        o.add("anchors", dev.vantage.core.hud.HudElements.exportPins());
        return o;
    }

    public static JsonObject liveTheme() {
        JsonObject o = new JsonObject();
        var g = Modules.get(dev.vantage.module.client.ClickGuiModule.class);
        if (g != null) for (Setting<?> st : new Setting<?>[] {g.theme, g.accent, g.size}) o.add(st.name, st.save());
        return o;
    }

    /** The code for a config. modules is a config in file form (a saved file's "modules", or Profiles.snapshot()); fileExtras the optional
     *  keys/hud/theme parts the file already carries (null for the live setup). A part is only written when its box is on; when the file
     *  has none the live one is used. */
    public static String encode(String name, JsonObject modules, JsonObject fileExtras, boolean keys, boolean hud, boolean theme) {
        JsonObject root = new JsonObject();
        root.addProperty("n", Profiles.clean(name));
        JsonArray on = new JsonArray();
        JsonObject s = new JsonObject();
        for (Module m : Modules.all()) {
            if (skipModule(m) || !(modules.get(m.name) instanceof JsonObject mo)) continue;
            if (one(m) && mo.get("enabled") instanceof JsonPrimitive en && en.isBoolean() && en.getAsBoolean()) on.add(m.name);
            JsonObject ms = new JsonObject();
            if (mo.get("settings") instanceof JsonObject so) {
                for (Setting<?> st : m.settings) {
                    if (!shareable(m, st) || !so.has(st.name)) continue;
                    P p = parse(st, so.get(st.name));
                    if (p != null && !isDefault(st, p)) ms.add(st.name, p.json);
                }
            }
            if (ms.size() > 0) s.add(m.name, ms);
        }
        root.add("on", on);
        root.add("s", s);
        if (keys) root.add("k", part(fileExtras, "keys", liveKeys()));
        if (hud) {
            JsonObject h = part(fileExtras, "hud", liveHud());
            JsonObject small = new JsonObject();
            small.add("p", h.has("panels") ? h.get("panels") : new JsonObject());
            if (validScreen(h.get("screen")) != null) small.add("z", h.get("screen")); // the sender's screen in design units, so the layout fits another monitor shape
            JsonObject hs = new JsonObject();
            if (h.get("settings") instanceof JsonObject so) {
                for (var me : so.entrySet()) {
                    Module m = Modules.byName(me.getKey());
                    if (m == null || !(me.getValue() instanceof JsonObject vals)) continue;
                    JsonObject keep = new JsonObject();
                    for (var se : vals.entrySet()) {
                        Setting<?> st = find(m, se.getKey());
                        P p = st == null || !Profiles.globalSetting(m, st) ? null : parse(st, se.getValue());
                        if (p != null && !isDefault(st, p)) keep.add(st.name, p.json);
                    }
                    if (keep.size() > 0) hs.add(m.name, keep);
                }
            }
            small.add("s", hs);
            JsonObject pins = validPins(h.get("anchors"));
            if (pins.size() > 0) small.add("a", pins);
            root.add("h", small);
        }
        if (theme) {
            JsonObject t = part(fileExtras, "theme", liveTheme()), keep = new JsonObject();
            var g = Modules.get(dev.vantage.module.client.ClickGuiModule.class);
            if (g != null) for (Setting<?> st : new Setting<?>[] {g.theme, g.accent, g.size}) {
                P p = parse(st, t.get(st.name));
                if (p != null && !isDefault(st, p)) keep.add(st.name, p.json);
            }
            root.add("t", keep);
        }
        byte[] json = root.toString().getBytes(StandardCharsets.UTF_8);
        return wrap(json);
    }

    private static JsonArray screen(float w, float h) {
        JsonArray a = new JsonArray();
        a.add(Math.round(w));
        a.add(Math.round(h));
        return a;
    }

    /** [width, height] of a screen in design units when it is a sane pair of whole numbers, else null. */
    static int[] validScreen(JsonElement e) {
        if (!(e instanceof JsonArray a) || a.size() != 2 || !isInt(a.get(0)) || !isInt(a.get(1))) return null;
        int w = a.get(0).getAsInt(), h = a.get(1).getAsInt();
        return w < 200 || h < 200 || w > 20000 || h > 20000 ? null : new int[] {w, h};
    }

    /** The usable [anchor, dx, dy] entries of a HUD part (at most 200); empty when there are none. */
    static JsonObject validPins(JsonElement e) {
        JsonObject out = new JsonObject();
        if (!(e instanceof JsonObject o)) return out;
        for (var en : o.entrySet())
            if (out.size() < 200 && PIECE.matcher(en.getKey()).matches() && dev.vantage.core.hud.HudElements.readPin(en.getValue()) != null) out.add(en.getKey(), en.getValue());
        return out;
    }

    /**
     * One coordinate of a HUD piece moved from a screen {@code from} units long to one {@code to} units long: a piece in the first third
     * keeps its distance to the start, one in the last third its distance to the end, one in the middle its distance to the centre.
     */
    public static int refit(int v, int from, int to) {
        if (from == to) return v;
        if (v * 3 < from) return v;
        if (v * 3 >= from * 2) return to - (from - v);
        return Math.round(to / 2f + (v - from / 2f));
    }

    private static JsonObject part(JsonObject fileExtras, String key, JsonObject live) {
        return fileExtras != null && fileExtras.get(key) instanceof JsonObject o ? o : live;
    }

    /** PREFIX + base64url(version, CRC-32 of the JSON, gzip of the JSON). */
    public static String wrap(byte[] json) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            CRC32 crc = new CRC32();
            crc.update(json);
            long c = crc.getValue();
            bo.write(VERSION);
            for (int i = 3; i >= 0; i--) bo.write((int) (c >> (8 * i)) & 255);
            try (GZIPOutputStream gz = new GZIPOutputStream(bo) { { def.setLevel(java.util.zip.Deflater.BEST_COMPRESSION); } }) { gz.write(json); }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bo.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- decoding ---------------------------------------------------------------------------------------------------------

    private static Decoded bad(String why) {
        return new Decoded(why, "", null, null, null, null, 0, 0, 0);
    }

    /** True for a character a chat app may put into or around a pasted code without it being part of the code: spaces of every kind, line
     *  breaks, zero-width and other invisible characters (Java's \\s knows only ASCII ones, a no-break space slips past it). */
    private static boolean invisible(int c) {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) return true;
        int t = Character.getType(c);
        return t == Character.FORMAT || t == Character.CONTROL || t == Character.NON_SPACING_MARK || t == Character.LINE_SEPARATOR || t == Character.PARAGRAPH_SEPARATOR;
    }

    private static boolean alphabet(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_';
    }

    /**
     * The pieces of a pasted text that can make up the code: everything after the first "VNT1:", split where the chat app broke or
     * spaced it, while a piece is made only of code characters. Words after the code ("hope it works") are tried off again by the
     * caller. The last piece loses trailing sentence punctuation and quote or markdown marks. Null = no code marker in the text.
     */
    private static List<String> pieces(String text) {
        int at = -1;
        for (int i = 0; i + PREFIX.length() <= text.length() && at < 0; i++) if (text.regionMatches(true, i, PREFIX, 0, PREFIX.length())) at = i;
        if (at < 0) return null;
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String rest = text.substring(at + PREFIX.length());
        boolean stop = false;
        for (int i = 0; i < rest.length() && !stop; ) {
            int c = rest.codePointAt(i);
            i += Character.charCount(c);
            if (c < 128 && alphabet((char) c)) { cur.append((char) c); continue; }
            if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            // anything that is neither invisible nor a mark a chat app adds around a code (quotes, backticks, bold, brackets) ends the code
            if (!invisible(c) && "`'\"<>*|~()[]{}\u2018\u2019\u201C\u201D".indexOf(c) < 0) stop = true;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /**
     * Checks a pasted code and turns it into the saved-file form. Never touches the running setup. The text may carry words before or
     * after the code, quotes or backticks, line breaks and any kind of space in the middle: it is the code that is looked for.
     */
    public static Decoded decode(String text) {
        try {
            if (text == null || text.isBlank()) return bad("The clipboard is empty");
            if (text.length() > MAX_CODE) return bad("That text is too long to be a config code");
            List<String> p = pieces(text);
            if (p == null) {
                String flat = text.replaceAll("[\\s\\p{C}]", "");
                return bad(flat.regionMatches(true, 0, "VNT", 0, 3) ? "This code is from a different Vantage version (it starts with " + flat.substring(0, Math.min(5, flat.length())) + ")"
                        : "That is not a Vantage config code (a code starts with VNT1:)");
            }
            if (p.isEmpty()) return bad("The code is empty after VNT1:, copy it again");
            if (p.size() > 5000) return bad("That text is too long to be a config code");
            Decoded first = null;
            // all pieces first (the usual case); if that fails, drop words from the end that were not part of the code
            for (int n = p.size(); n >= 1 && n >= p.size() - 40; n--) {
                Decoded d = decodeBody(String.join("", p.subList(0, n)));
                if (d.ok()) return d;
                if (first == null) first = d;
            }
            return first;
        } catch (RuntimeException | StackOverflowError e) {
            Vantage.LOG.warn("Config code check failed: {}", e.toString());
            return bad("The code could not be read");
        }
    }

    /** True when the text holds a code marker ("VNT" and a digit): it is meant as a share code, whether or not it reads. */
    public static boolean looksLikeCode(String text) {
        if (text == null) return false;
        for (int i = 0; i + 4 <= text.length(); i++) if (text.regionMatches(true, i, "VNT", 0, 3) && Character.isDigit(text.charAt(i + 3))) return true;
        return false;
    }

    /** The part of a code after VNT1:, with nothing but code characters in it. */
    private static Decoded decodeBody(String body) {
        try {
            if (body.length() % 4 == 1) return bad("The code looks cut off (the end is missing), copy the whole code again");
            byte[] bin;
            try { bin = Base64.getUrlDecoder().decode(body); }
            catch (IllegalArgumentException e) { return bad("The code is damaged (bad characters), copy it again"); }
            if (bin.length < 6) return bad("The code looks cut off (it is too short), copy the whole code again");
            if (bin[0] != VERSION) return bad("This code is from a newer Vantage (format " + (bin[0] & 255) + "), update to read it");
            long want = 0;
            for (int i = 1; i <= 4; i++) want = want << 8 | (bin[i] & 255);
            byte[] json;
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bin, 5, bin.length - 5))) {
                json = gz.readNBytes(MAX_RAW + 1);
            } catch (java.io.EOFException e) { return bad("The code looks cut off (the end is missing), copy the whole code again");
            } catch (java.io.IOException e) { return bad("The code is damaged (can't unpack it)"); }
            if (json.length > MAX_RAW) return bad("The code unpacks to more than 64 KB, so it is refused");
            CRC32 crc = new CRC32();
            crc.update(json);
            if (crc.getValue() != want) return bad("The code is damaged (checksum does not match), copy it again");
            JsonElement el;
            try { el = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)); }
            catch (RuntimeException | StackOverflowError e) { return bad("The code is damaged (not readable)"); }
            if (!el.isJsonObject()) return bad("The code is damaged (wrong shape)");
            return build(el.getAsJsonObject());
        } catch (RuntimeException | StackOverflowError e) {
            Vantage.LOG.warn("Config code check failed: {}", e.toString());
            return bad("The code could not be read");
        }
    }

    private static Decoded build(JsonObject root) {
        int[] skipped = {0};
        String name = root.get("n") instanceof JsonPrimitive p && p.isString() ? Profiles.clean(p.getAsString()) : "";
        if (name.isEmpty()) name = "Imported";
        java.util.Set<String> on = new java.util.HashSet<>();
        int mods = 0, settings = 0;
        if (root.get("on") instanceof JsonArray a) {
            if (a.size() > 1000) return bad("The code lists too many modules");
            for (JsonElement x : a) {
                String nm = x.isJsonPrimitive() ? Migrations.currentName(x.getAsString()) : "";
                Module m = Modules.byName(nm);
                if (m == null && Modules.lockedName(nm)) continue; // a beta this build does not have: ignored, not an error
                if (m == null || skipModule(m) || !one(m)) { skipped[0]++; continue; }
                if (on.add(m.name)) mods++;
            }
        }
        JsonObject given = new JsonObject();
        if (root.get("s") instanceof JsonObject o) for (var e : o.entrySet()) given.add(Migrations.currentName(e.getKey()), e.getValue());
        JsonObject modules = new JsonObject();
        for (var e : given.entrySet()) {
            Module known = Modules.byName(e.getKey());
            if (known == null && Modules.lockedName(e.getKey())) continue; // a beta this build does not have: ignored, not an error
            if (known == null || skipModule(known) || !(e.getValue() instanceof JsonObject)) skipped[0]++;
        }
        for (Module m : Modules.all()) {
            if (skipModule(m)) continue;
            JsonObject mo = new JsonObject();
            if (one(m)) mo.addProperty("enabled", on.contains(m.name));
            JsonObject have = given.get(m.name) instanceof JsonObject h ? h : new JsonObject();
            for (String k : have.keySet()) {
                Setting<?> st = find(m, k);
                if (st == null || !shareable(m, st) || parse(st, have.get(k)) == null) skipped[0]++;
            }
            JsonObject so = new JsonObject();
            for (Setting<?> st : m.settings) {
                if (!shareable(m, st)) continue;
                P p = have.has(st.name) ? parse(st, have.get(st.name)) : null;
                if (p == null) p = defaultOf(st);
                else if (!isDefault(st, p)) settings++;
                so.add(st.name, p.json);
            }
            mo.add("settings", so);
            modules.add(m.name, mo);
        }
        JsonObject keys = null, hud = null, theme = null;
        if (root.get("k") instanceof JsonObject k) {
            keys = new JsonObject();
            for (var e : k.entrySet()) {
                Module m = Modules.byName(e.getKey());
                if (m == null || skipModule(m) || !e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isNumber()) { skipped[0]++; continue; }
                double d = e.getValue().getAsDouble();
                if (d != Math.rint(d) || d < -120 || d > 400) { skipped[0]++; continue; }
                keys.addProperty(m.name, (int) d);
            }
        }
        if (root.get("h") instanceof JsonObject h) {
            hud = new JsonObject();
            JsonObject panels = new JsonObject(), set = new JsonObject();
            if (h.get("p") instanceof JsonObject pn) {
                if (pn.size() > 200) skipped[0] += pn.size() - 200;
                int n = 0;
                for (var e : pn.entrySet()) {
                    if (n >= 200) break;
                    if (!PIECE.matcher(e.getKey()).matches() || !(e.getValue() instanceof JsonArray a) || a.size() != 2 || !isInt(a.get(0)) || !isInt(a.get(1))) { skipped[0]++; continue; }
                    JsonArray c = new JsonArray();
                    c.add(Math.max(-10000, Math.min(10000, a.get(0).getAsInt())));
                    c.add(Math.max(-10000, Math.min(10000, a.get(1).getAsInt())));
                    panels.add(e.getKey(), c);
                    n++;
                }
            }
            JsonObject hs = h.get("s") instanceof JsonObject x ? x : new JsonObject();
            for (var e : hs.entrySet()) {
                Module m = Modules.byName(e.getKey());
                if (m == null || !(e.getValue() instanceof JsonObject vals)) { skipped[0]++; continue; }
                JsonObject out = new JsonObject();
                for (var se : vals.entrySet()) {
                    Setting<?> st = find(m, se.getKey());
                    P p = st == null || !Profiles.globalSetting(m, st) ? null : parse(st, se.getValue());
                    if (p == null) skipped[0]++; else out.add(st.name, p.json);
                }
                if (out.size() > 0) set.add(m.name, out);
            }
            // settings not named are at their defaults: the layout is the code's whole layout
            for (Module m : Modules.all()) {
                for (Setting<?> st : m.settings) {
                    if (!Profiles.globalSetting(m, st) || !shareable0(st)) continue;
                    JsonObject out = set.get(m.name) instanceof JsonObject o ? o : new JsonObject();
                    if (!out.has(st.name)) out.add(st.name, defaultOf(st).json);
                    set.add(m.name, out);
                }
            }
            hud.add("panels", panels);
            hud.add("settings", set);
            if (validScreen(h.get("z")) != null) hud.add("screen", h.get("z"));
            JsonObject pins = validPins(h.get("a"));
            if (pins.size() > 0) hud.add("anchors", pins);
        }
        if (root.get("t") instanceof JsonObject t) {
            theme = new JsonObject();
            var g = Modules.get(dev.vantage.module.client.ClickGuiModule.class);
            for (var e : t.entrySet()) {
                Setting<?> st = g == null ? null : find(g, e.getKey());
                P p = g != null && (st == g.theme || st == g.accent || st == g.size) ? parse(st, e.getValue()) : null;
                if (p == null) skipped[0]++; else theme.add(st.name, p.json);
            }
            if (g != null) for (Setting<?> st : new Setting<?>[] {g.theme, g.accent, g.size}) if (!theme.has(st.name)) theme.add(st.name, defaultOf(st).json);
        }
        for (String k : root.keySet()) if (!java.util.Set.of("n", "on", "s", "k", "h", "t", "v").contains(k)) skipped[0]++;
        return new Decoded(null, name, modules, keys, hud, theme, mods, settings, skipped[0]);
    }

    private static boolean shareable0(Setting<?> st) {
        return st instanceof NumberSetting || st instanceof IntSetting || st instanceof BoolSetting || st instanceof ModeSetting || st instanceof ColorSetting;
    }

    private static boolean isInt(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsDouble() == Math.rint(e.getAsDouble()) && Math.abs(e.getAsDouble()) < 1e9;
    }

    // ---- applying the extra parts of a loaded config ------------------------------------------------------------------------

    /** Puts the optional keys / hud / theme parts of a config file into effect (each only if the file has it). Returns what was applied. */
    public static List<String> applyExtras(JsonObject root) {
        List<String> done = new ArrayList<>();
        try {
            if (root.get("keys") instanceof JsonObject k) {
                for (var e : k.entrySet()) {
                    Module m = Modules.byName(e.getKey());
                    if (m == null || skipModule(m) || !isInt(e.getValue())) continue;
                    int v = e.getValue().getAsInt();
                    if (v < -120 || v > 400) continue;
                    m.bind = v;
                }
                done.add("keybinds");
                Config.markDirty();
            }
            if (root.get("hud") instanceof JsonObject h) {
                Config.PANELS.entrySet().removeIf(e -> e.getValue().length == 2);
                dev.vantage.core.hud.HudElements.unpinAll();
                if (h.get("panels") instanceof JsonObject pn) {
                    JsonObject valid = new JsonObject();
                    for (var e : pn.entrySet()) {
                        if (!PIECE.matcher(e.getKey()).matches() || !(e.getValue() instanceof JsonArray a) || a.size() != 2 || !isInt(a.get(0)) || !isInt(a.get(1))) continue;
                        valid.add(e.getKey(), e.getValue());
                    }
                    // HUD elements take their spot as an absolute one (the anchor follows at the first frame); ids without an element keep the old map
                    int[] from = validScreen(h.get("screen"));
                    if (from != null) {
                        // the positions were taken on the sender's screen: keep each piece's distance to the edge (or the middle) it was nearest to
                        JsonObject moved = new JsonObject();
                        for (var e : valid.entrySet()) {
                            JsonArray a = e.getValue().getAsJsonArray(), b = new JsonArray();
                            b.add(refit(a.get(0).getAsInt(), from[0], Math.round(Ui.W())));
                            b.add(refit(a.get(1).getAsInt(), from[1], Math.round(Ui.H())));
                            moved.add(e.getKey(), b);
                        }
                        valid = moved;
                    }
                    for (var e : dev.vantage.core.hud.HudElements.acceptAbsolute(valid).entrySet()) {
                        JsonArray a = e.getValue().getAsJsonArray();
                        Config.PANELS.put(e.getKey(), new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()});
                    }
                }
                // codes that carry anchors: they win over the absolute spots above, which only older versions need
                dev.vantage.core.hud.HudElements.acceptPins(validPins(h.get("anchors")));
                if (h.get("settings") instanceof JsonObject hs) {
                    for (var e : hs.entrySet()) {
                        Module m = Modules.byName(e.getKey());
                        if (m == null || !(e.getValue() instanceof JsonObject vals)) continue;
                        for (var se : vals.entrySet()) if (!se.getKey().equals("Panel size")) putChecked(m, se.getKey(), se.getValue(), true); // sizes stay yours, see the theme part
                    }
                }
                done.add("HUD layout");
                Config.markDirty();
            }
            if (root.get("theme") instanceof JsonObject t) {
                var g = Modules.get(dev.vantage.module.client.ClickGuiModule.class);
                if (g != null) for (var e : t.entrySet()) if (e.getKey().equals(g.theme.name) || e.getKey().equals(g.accent.name)) putChecked(g, e.getKey(), e.getValue(), false); // never Menu size: sizes fit the sender's screen and eyes, not yours
                done.add("theme");
                Config.markDirty();
            }
        } catch (RuntimeException ex) {
            Vantage.LOG.warn("Config extras only partly applied: {}", ex.toString());
        }
        return done;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putChecked(Module m, String name, JsonElement v, boolean hudPart) {
        Setting st = find(m, name);
        if (st == null || (hudPart && !Profiles.globalSetting(m, st))) return;
        P p = parse(st, v);
        if (p == null) return;
        JsonElement old = st.save();
        Object before = st.get();
        st.load(p.json);
        Object after = st.get();
        if (Objects.equals(before, after)) return;
        st.load(old);
        st.set(after); // through set(), so the change hook runs
    }

    /** The names of the extra parts a saved config file carries, comma separated ("" = none). */
    public static String extrasOf(JsonObject root) {
        List<String> l = new ArrayList<>();
        if (root.get("keys") instanceof JsonObject) l.add("keybinds");
        if (root.get("hud") instanceof JsonObject) l.add("HUD layout");
        if (root.get("theme") instanceof JsonObject) l.add("theme");
        return String.join(", ", l);
    }

    // ---- clipboard, buttons, chat -----------------------------------------------------------------------------------------

    private static Minecraft mc() { return Minecraft.getInstance(); }

    /** Test hook: when set, these replace the system clipboard. */
    public static java.util.function.Supplier<String> clipGet;
    public static java.util.function.Consumer<String> clipSet;

    public static String clipboard() {
        try { return clipGet != null ? clipGet.get() : mc().keyboardHandler.getClipboard(); } catch (RuntimeException e) { return ""; }
    }

    private static void setClipboard(String s) {
        if (clipSet != null) clipSet.accept(s); else mc().keyboardHandler.setClipboard(s);
    }

    private static Profiles.Result copy(String name, String code) {
        setClipboard(code);
        if (code.length() > DISCORD) {
            int parts = (code.length() + DISCORD - 100) / (DISCORD - 100);
            String how = "over Discord's " + DISCORD + " limit: send it as " + parts + " messages in order (Import joins them), or as a file";
            Toasts.warn("Copied " + name, "Code is " + code.length() + " characters, " + how);
            return new Profiles.Result(true, "Copied " + name + ": " + code.length() + " characters, " + how);
        }
        Toasts.ok("Copied " + name, code.length() + " characters on the clipboard");
        return new Profiles.Result(true, "Copied " + name + " (" + code.length() + " characters)");
    }

    /** The code of a saved config (what is in its file; extra parts when their box is on). */
    public static String codeOf(String raw) {
        Profiles.Info i = Profiles.find(raw);
        if (i == null || i.broken()) return null;
        try {
            JsonObject root = JsonParser.parseString(java.nio.file.Files.readString(Profiles.dir().resolve(i.key() + ".json"))).getAsJsonObject();
            if (!(root.get("modules") instanceof JsonObject mods)) return null;
            return encode(i.name(), mods, root, optKeys, optHud, optTheme);
        } catch (Exception e) {
            return null;
        }
    }

    public static String codeOfCurrent() {
        String n = Profiles.active().isEmpty() ? "My config" : Profiles.active();
        return encode(n, Profiles.snapshot(), null, optKeys, optHud, optTheme);
    }

    public static Profiles.Result exportConfig(String raw) {
        String code = codeOf(raw);
        Profiles.Info i = Profiles.find(raw);
        if (code == null) return new Profiles.Result(false, i == null ? "No config called " + Profiles.clean(raw) : i.name() + " can't be read, nothing copied");
        return copy(i.name(), code);
    }

    public static Profiles.Result exportCurrent() {
        return copy(Profiles.active().isEmpty() ? "current setup" : Profiles.active(), codeOfCurrent());
    }

    /** Reads the clipboard, checks it and keeps it as the pending import. Applies nothing. */
    public static Profiles.Result importClipboard() {
        Decoded d = decode(clipboard());
        if (!d.ok()) { pending = null; return new Profiles.Result(false, d.error()); }
        pending = d;
        return new Profiles.Result(true, d.preview());
    }

    /** Saves the pending import as a normal config file under this name (empty = the code's own name). It is not loaded or made active. */
    public static Profiles.Result savePending(String raw, boolean overwrite) {
        Decoded d = pending;
        if (d == null) return new Profiles.Result(false, "Nothing to save: import a code first");
        String name = Profiles.clean(raw == null || raw.isBlank() ? d.name() : raw);
        if (name.isEmpty()) return new Profiles.Result(false, "Give the config a name: letters, digits, space, - and _");
        if (!overwrite && Profiles.find(name) != null) return new Profiles.Result(false, Profiles.find(name).name() + " already exists");
        Profiles.Result r = Profiles.saveFile(name, d.modules(), d.extras());
        if (r.ok()) {
            pending = null;
            Toasts.ok("Saved " + name, "Imported config. Click it to load");
            return new Profiles.Result(true, "Saved " + name + " from the code. Click it to load it");
        }
        return r;
    }

    /** ".config import", ".config import save <name>", ".config export [name] [keys] [hud] [theme]". */
    public static String command(String verb, String rest) {
        if (verb.equals("import")) {
            String[] p = rest.split("\\s+", 2);
            if (p[0].equalsIgnoreCase("save")) {
                String nm = p.length > 1 ? p[1].trim() : "";
                boolean force = nm.equalsIgnoreCase("force") || nm.toLowerCase().endsWith(" force");
                if (force) nm = nm.equalsIgnoreCase("force") ? "" : nm.substring(0, nm.length() - 6).trim();
                Profiles.Result sr = savePending(nm, force);
                return !sr.ok() && sr.msg().endsWith("already exists") ? sr.msg() + ". Add the word force to replace it: .config import save <name> force" : sr.msg();
            }
            if (p[0].equalsIgnoreCase("cancel")) { pending = null; return "Import dropped"; }
            Profiles.Result r = importClipboard();
            return r.ok() ? r.msg() + ". Save it with .config import save <name>" : r.msg();
        }
        boolean k = optKeys, h = optHud, t = optTheme;
        StringBuilder name = new StringBuilder();
        for (String w : rest.split("\\s+")) {
            switch (w.toLowerCase(java.util.Locale.ROOT)) {
                case "keys", "keybinds" -> k = true;
                case "hud" -> h = true;
                case "theme" -> t = true;
                case "" -> {}
                default -> name.append(name.length() > 0 ? " " : "").append(w);
            }
        }
        boolean a = optKeys, b = optHud, c = optTheme;
        optKeys = k; optHud = h; optTheme = t;
        try {
            return (name.length() == 0 ? exportCurrent() : exportConfig(name.toString())).msg();
        } finally {
            optKeys = a; optHud = b; optTheme = c;
        }
    }
}
