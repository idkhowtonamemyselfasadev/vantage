package dev.vantage.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.vantage.module.client.StreamerMode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Streamer mode's display filter (beta). Everything here only changes the text the client is about to draw: names, coordinates
 * and the server address are swapped for neutral stand-ins on their way to the screen. Nothing is sent, no packet is touched,
 * and the real values stay in every file and setting (server keys for data files are NOT changed).
 */
public final class Streamer {
    public static final String HIDDEN = "hidden";
    public static final String HIDDEN_SERVER = "hidden server";
    public static final String SELF = "You";
    /** Three or more numbers in a row separated by spaces, commas or slashes: a position ("1 2 3", "1.5 / 64.0 / -3.2", "[0 0 0]"). */
    private static final Pattern COORDS = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:\\s*[ ,/]\\s*-?\\d+(?:\\.\\d+)?){2,}");

    /** The "[13 29 in r.2.-12.mca]" tail of the F3 chunk line: the region file name gives the area away too. */
    private static final Pattern REGION = Pattern.compile("\\[[^\\]]*r\\.-?\\d+\\.-?\\d+\\.mca\\]");

    /** Stand-ins by real name, numbered in the order the names were first seen this session. */
    private static final Map<String, String> STAND_INS = new HashMap<>();
    private static Pattern cached;
    private static long cachedAt;
    private static String cachedKey = "";
    private static String testAddress;
    /** For the rig: frames in which the tab list header or footer had something to hide. */
    public static volatile int tabScrubbed;

    public static void testAddress(String a) { testAddress = a; }

    /** Test hook: extra names the tab list does not know. */
    private static final Set<String> EXTRA = new LinkedHashSet<>();

    private Streamer() {}

    private static StreamerMode mod() {
        return Modules.get(StreamerMode.class);
    }

    public static boolean active() {
        StreamerMode m = mod();
        return m != null && m.on();
    }

    public static boolean hidesSelf() { return active() && mod().self.on(); }
    public static boolean hidesCoords() { return active() && mod().coords.on(); }
    public static boolean hidesServer() { return active() && mod().server.on(); }
    public static boolean hidesOthers() { return active() && mod().others.on(); }

    // ---- names ----

    private static String ownName() {
        try {
            return Minecraft.getInstance().getUser().getName();
        } catch (Throwable t) {
            return "";
        }
    }

    /** The stand-in for one real name ("You" for yourself, "Player N" for anybody else), or the name itself when that part is off. */
    public static String name(String real) {
        if (real == null || real.isEmpty()) return real;
        if (real.equals(ownName())) return hidesSelf() ? SELF : real;
        if (!hidesOthers()) return real;
        return STAND_INS.computeIfAbsent(real, k -> "Player " + (STAND_INS.size() + 1));
    }

    /** Forgets the numbering (a new session of streaming starts at Player 1 again). */
    public static void reset() {
        STAND_INS.clear();
        cached = null;
    }

    public static void testNames(Collection<String> names) {
        EXTRA.clear();
        EXTRA.addAll(names);
        cached = null;
    }

    /** Every name worth hiding right now: your own and everyone in the tab list, longest first so "Bob_2" goes before "Bob". */
    private static Pattern names() {
        long now = System.currentTimeMillis();
        var mc = Minecraft.getInstance();
        List<String> all = new ArrayList<>();
        String own = ownName();
        if (!own.isEmpty()) all.add(own);
        if (mc.getConnection() != null) for (var p : mc.getConnection().getOnlinePlayers()) all.add(p.getProfile().name());
        all.addAll(EXTRA);
        all.removeIf(n -> n == null || n.isEmpty());
        String key = hidesSelf() + "|" + hidesOthers() + "|" + String.join(",", all);
        if (cached != null && key.equals(cachedKey) && now - cachedAt < 2000) return cached;
        all.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder sb = new StringBuilder();
        for (String n : new LinkedHashSet<>(all)) {
            if (n.equals(own) ? !hidesSelf() : !hidesOthers()) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(Pattern.quote(n));
        }
        cachedKey = key;
        cachedAt = now;
        cached = sb.length() == 0 ? null : Pattern.compile("(?<![A-Za-z0-9_])(?:" + sb + ")(?![A-Za-z0-9_])");
        return cached;
    }

    // ---- server ----

    /** The address the client is connected to, or "" (singleplayer, no server). */
    private static String address() {
        if (testAddress != null) return testAddress;
        var sd = Minecraft.getInstance().getCurrentServer();
        return sd == null || sd.ip == null ? "" : sd.ip;
    }

    /** A server address or world id for display: the real one, or a stand-in while the server part is on. */
    public static String serverLabel(String real) {
        return hidesServer() ? HIDDEN_SERVER : real;
    }

    /** The coordinates string for display. */
    public static String coords(String real) {
        return hidesCoords() ? HIDDEN : real;
    }

    // ---- text ----

    /** Names, server address and (for debug screen lines) positions replaced in a plain string. */
    public static String scrub(String s) {
        if (s == null || s.isEmpty() || !active()) return s;
        String out = s;
        if (hidesServer()) {
            String a = address();
            if (!a.isEmpty()) {
                String host = a.contains(":") ? a.substring(0, a.lastIndexOf(':')) : a;
                out = out.replace(a, HIDDEN_SERVER);
                if (!host.isEmpty()) out = out.replace(host, HIDDEN_SERVER);
            }
        }
        Pattern p = names();
        if (p != null) {
            Matcher m = p.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(name(m.group())));
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    /** Same for a text component, keeping every run's colour and click/hover. Returns the same object when nothing changed. */
    public static Component scrub(Component c) {
        if (c == null || !active()) return c;
        String plain = c.getString();
        if (scrub(plain).equals(plain)) return c;
        MutableComponent out = Component.empty();
        c.visit((style, text) -> {
            out.append(Component.literal(scrub(text)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /** A line of the F3 screen: every position replaced. */
    public static String debugLine(String line) {
        if (line == null || !hidesCoords()) return line;
        return REGION.matcher(COORDS.matcher(line).replaceAll(HIDDEN)).replaceAll("[" + HIDDEN + "]");
    }

    /** Positions inside any text (toasts): "28037, 65, 11166" -> "hidden". */
    public static String scrubCoords(String s) {
        if (s == null || !hidesCoords()) return s;
        return COORDS.matcher(s).replaceAll(HIDDEN);
    }
}
