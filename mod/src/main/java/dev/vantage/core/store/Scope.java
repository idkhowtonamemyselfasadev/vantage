package dev.vantage.core.store;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.vantage.core.Config;

/**
 * Where a piece of saved data belongs: for the whole client, for one server, or for one dimension of one server (ARCHITECTURE.md 18.2).
 * One place decides the names, so no module builds a path by hand and two spellings of one server stop being two servers.
 *
 * <h3>File names</h3>
 * Everything a store of this service writes is under {@code config/vantage/data/<store name>}:
 * <pre>
 * GLOBAL     data/&lt;name&gt;&lt;ext&gt;
 * SERVER     data/&lt;name&gt;/&lt;server key&gt;&lt;ext&gt;
 * DIMENSION  data/&lt;name&gt;/&lt;server key&gt;/&lt;dimension key&gt;&lt;ext&gt;
 * </pre>
 * A file name holds only the characters {@code a-z A-Z 0-9 . _ -}. Nothing else is ever written into a name.
 *
 * <h3>The server key ({@link #serverKey})</h3>
 * The address the player typed or saved, made canonical: surrounding spaces removed, lower case, trailing dots removed, an
 * international name turned into its ASCII form (punycode), the default port 25565 dropped. A port that is not the default is
 * appended as {@code _<port>}. An IPv6 literal (with or without brackets) becomes {@code v6-} plus its groups with {@code -} for
 * {@code :}. Any other character becomes {@code _}, and then a dash and 8 hex digits of the SHA-256 of the original are added, so two
 * different odd names never end up in one file. A key longer than 64 characters is cut to 55 and gets the same dash and hash. A name
 * Windows reserves for devices (con, aux, nul, com1 ...) gets a trailing {@code _}.
 * Examples: {@code Play.Example.org.:25565} is {@code play.example.org}; {@code 10.0.0.5:25566} is {@code 10.0.0.5_25566};
 * {@code [2001:db8::1]:25566} is {@code v6-2001-db8--1_25566}.
 *
 * <h3>A single-player world ({@link #worldKey})</h3>
 * {@code sp-} plus the name of the world's save folder, case kept (the folder does not change when the world is renamed in the menu; the
 * display name, which the old key used, does). Spaces become {@code _}; any other odd character becomes {@code _} plus the hash as above.
 * Two worlds in different folders are two scopes even when they show the same name.
 *
 * <h3>No world yet</h3>
 * While there is no world (title screen, loading) there is no place: {@link #current()} is null, {@link #path} is null for SERVER and
 * DIMENSION, and a store has nothing loaded and writes nothing. There is no {@code unknown} key and never a file under one.
 *
 * <h3>The dimension key ({@link #dimensionKey})</h3>
 * The dimension id in lower case with {@code :} and {@code /} turned into {@code _}: {@code minecraft:the_nether} is
 * {@code minecraft_the_nether}. Other odd characters get the hash rule above; longer than 64 is cut the same way.
 *
 * <h3>What a key cannot do</h3>
 * Many worlds behind one address (hubs, proxies, a season wipe) share one scope; joining somebody's LAN world or a Realm gives a
 * key that may change per session. Telling worlds apart needs a fingerprint of the world, which belongs to phase 2.
 *
 * <h3>Old files</h3>
 * Before this class, data lived in per-module folders under the old spelling of the server (typed as it was, only odd characters
 * replaced) and the display name of a single-player world. {@link Place#oldServerKeys} lists every old spelling that can belong to
 * the current place; {@link OldFiles} finds the files, and a store adopts them (copies, never moves) when its new file does not exist.
 */
public enum Scope {
    GLOBAL, SERVER, DIMENSION;

    private static final String ROOT_FOLDER = "data";
    private static final int MAX_KEY = 64;
    private static final int KEPT_BEFORE_HASH = 55;
    private static final int DEFAULT_PORT = 25565;
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /**
     * Where the player is: the keys that name the files, and what the old spellings are made from. Not built by callers; it comes from
     * {@link #current()} (or {@link #place} in tests).
     *
     * @param serverKey    key of the server or single-player world
     * @param dimensionKey key of the dimension, null when there is none
     * @param address      the address as the game holds it (null for a single-player world)
     * @param worldName    display name of a single-player world (null for a server)
     * @param dimensionId  the dimension id as text
     */
    public record Place(String serverKey, String dimensionKey, String address, String worldName, String dimensionId) {
        /**
         * Every spelling the old per-module files can have for this server, as the old code wrote it (lower case; names are compared
         * ignoring case): the address as typed, the host with and without the default port, with and without a trailing dot. For a
         * single-player world the old key was {@code sp-} plus the display name. A different port is a different server and is not listed.
         */
        public List<String> oldServerKeys() {
            Set<String> out = new LinkedHashSet<>();
            if (address == null) {
                if (worldName != null) out.add(oldKey("sp-" + worldName));
                return new ArrayList<>(out);
            }
            out.add(oldKey(address.trim()));
            Addr a = parse(address);
            if (a == null || a.v6) return new ArrayList<>(out);
            for (String host : new String[] {a.host, a.host + "."}) {
                if (a.port == null || a.port == DEFAULT_PORT) {
                    out.add(oldKey(host));
                    out.add(oldKey(host + ":" + DEFAULT_PORT));
                } else {
                    out.add(oldKey(host + ":" + a.port));
                }
            }
            return new ArrayList<>(out);
        }

        /** The old spellings of the dimension in a file name: odd characters replaced by an underscore (the old {@code Dims.fileKey}), or only the colon. */
        public List<String> oldDimensionKeys() {
            if (dimensionId == null || dimensionId.isEmpty()) return List.of();
            Set<String> out = new LinkedHashSet<>();
            out.add(dimensionId.replaceAll("[^A-Za-z0-9._-]", "_"));
            out.add(dimensionId.replace(':', '_'));
            if (dimensionKey != null) out.add(dimensionKey);
            return new ArrayList<>(out);
        }
    }

    /** The old rule, frozen: this is what {@code Config.serverKey()} did to the typed address. Old files are under names made this way. */
    private static String oldKey(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ---- keys ----------------------------------------------------------------------------------------------------------------

    private record Addr(String host, Integer port, boolean v6) {}

    private static Integer port(String text) {
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < '0' || text.charAt(i) > '9') return null;
        int start = 0;
        while (start < text.length() - 1 && text.charAt(start) == '0') start++;
        text = text.substring(start);
        if (text.isEmpty() || text.length() > 5) return null;
        int p = Integer.parseInt(text);
        return p >= 1 && p <= 65535 ? p : null;
    }

    private static Addr parse(String address) {
        if (address == null) return null;
        String s = address.trim();
        if (s.isEmpty()) return null;
        String host = s;
        Integer port = null;
        boolean v6 = false;
        if (s.startsWith("[") && s.indexOf(']') > 0) {
            int end = s.indexOf(']');
            host = s.substring(1, end);
            v6 = true;
            if (s.startsWith(":", end + 1)) port = port(s.substring(end + 2));
        } else {
            int first = s.indexOf(':');
            if (first >= 0 && first == s.lastIndexOf(':')) {
                Integer p = port(s.substring(first + 1));
                if (p != null) {
                    host = s.substring(0, first);
                    port = p;
                }
            } else if (first >= 0) {
                v6 = true; // two or more colons and no brackets: a bare IPv6 literal, which has no port
            }
        }
        host = host.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return null;
        if (!v6) {
            try {
                host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                // not a valid international name: it is spelled as it is, and the odd characters are handled by the hash rule
            }
        }
        return new Addr(host, port, v6);
    }

    /**
     * The key of a server address as typed, or null when there is nothing usable in it. See the class comment for the rule.
     */
    public static String serverKey(String address) {
        Addr a = parse(address);
        if (a == null) return null;
        String text = a.v6 ? "v6-" + a.host.replace(':', '-') : a.host;
        if (a.port != null && a.port != DEFAULT_PORT) text += ":" + a.port;
        return safe(text, ":");
    }

    /** The key of a single-player world from the name of its save folder, or null when the name is empty. See the class comment. */
    public static String worldKey(String saveFolder) {
        if (saveFolder == null) return null;
        String name = saveFolder.trim();
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty()) return null;
        return safe("sp-" + name, " ");
    }

    /** The key of a dimension id ({@code minecraft:the_nether}), or null for an empty id. */
    public static String dimensionKey(String id) {
        if (id == null || id.isBlank()) return null;
        return safe(id.trim().toLowerCase(Locale.ROOT), ":/");
    }

    /**
     * Turns text into a file-name part: {@code a-z A-Z 0-9 . _ -} stay, the characters in {@code free} become {@code _}, any other
     * character becomes {@code _} and marks the text as odd, which adds a dash and 8 hex digits of its SHA-256 at the end.
     */
    private static String safe(String text, String free) {
        StringBuilder sb = new StringBuilder(text.length() + 9);
        boolean odd = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_') sb.append(c);
            else {
                sb.append('_');
                if (free.indexOf(c) < 0) odd = true;
            }
        }
        String out = sb.toString();
        if (odd) out = out + "-" + hash(text);
        if (out.length() > MAX_KEY) out = out.substring(0, KEPT_BEFORE_HASH) + "-" + hash(text);
        int dot = out.indexOf('.');
        if (RESERVED.contains((dot < 0 ? out : out.substring(0, dot)).toLowerCase(Locale.ROOT))) out += "_";
        return out;
    }

    private static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    // ---- places and paths ------------------------------------------------------------------------------------------------------

    /**
     * A place from what the game says: {@code address} for a server (the typed or saved address), or {@code saveFolder} and
     * {@code worldName} for a single-player world, and the dimension id. Null when there is no usable server key.
     */
    public static Place place(String address, String saveFolder, String worldName, String dimensionId) {
        String key = address != null ? serverKey(address) : worldKey(saveFolder);
        if (key == null) return null;
        return new Place(key, dimensionKey(dimensionId), address, address != null ? null : worldName, dimensionId);
    }

    /** Where the player is now, or null when there is no world. Cheap: the same object is returned while nothing changed. */
    public static Place current() {
        return WorldPlace.current();
    }

    /** What decides whether a store bound to this scope must load again: null when the scope has no place, else the part of the place it depends on. */
    String identity(Place p) {
        return switch (this) {
            case GLOBAL -> "";
            case SERVER -> p == null ? null : p.serverKey();
            case DIMENSION -> p == null || p.dimensionKey() == null ? null : p.serverKey() + "/" + p.dimensionKey();
        };
    }

    /** The file of a store in this scope under {@code root} (a config folder); null when the scope needs a place that there is not. {@code ext} includes the dot. */
    public static Path path(Path root, Scope scope, Place place, String name, String ext) {
        Path base = root.resolve(ROOT_FOLDER);
        return switch (scope) {
            case GLOBAL -> base.resolve(name + ext);
            case SERVER -> place == null ? null : base.resolve(name).resolve(place.serverKey() + ext);
            case DIMENSION -> place == null || place.dimensionKey() == null ? null : base.resolve(name).resolve(place.serverKey()).resolve(place.dimensionKey() + ext);
        };
    }

    /** {@link #path(Path, Scope, Place, String, String)} for the real config folder and the place the player is in. */
    public static Path path(Scope scope, String name, String ext) {
        return path(Config.dir(), scope, scope == GLOBAL ? null : current(), name, ext);
    }
}
