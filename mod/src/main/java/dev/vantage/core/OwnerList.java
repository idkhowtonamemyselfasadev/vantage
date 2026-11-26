package dev.vantage.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The pure part of the owner view of Vantage Online: reading the service's answer to /presence/all, turning entries into rows
 * (name, where, friend), sorting them and writing the summary line. No game classes, no network, so JUnit can test it.
 */
public final class OwnerList {
    public enum Where { HERE, OTHER, MENU }

    /** One account the service lists: its UUID and the server hash it pinged with, or "menu". */
    public record Entry(UUID id, String server) {}

    /** One line of the view. {@code name} is never empty: a name when one is known, else the shortened UUID. */
    public record Row(UUID id, String name, Where where, boolean friend) {}

    private OwnerList() {}

    /** Reads {"users":[{"uuid","server"}]}. Entries with a bad UUID or a missing server are skipped; a body that is not the answer gives an empty list. */
    public static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return out;
            return parse(root.getAsJsonObject());
        } catch (RuntimeException e) {
            return out;
        }
    }

    public static List<Entry> parse(JsonObject o) {
        List<Entry> out = new ArrayList<>();
        if (o == null || !o.has("users") || !o.get("users").isJsonArray()) return out;
        for (JsonElement e : o.getAsJsonArray("users")) {
            try {
                if (!e.isJsonObject()) continue;
                JsonObject u = e.getAsJsonObject();
                UUID id = UUID.fromString(u.get("uuid").getAsString());
                String server = u.get("server").getAsString().toLowerCase();
                if (server.isEmpty()) continue;
                out.add(new Entry(id, server));
            } catch (RuntimeException bad) {
                // one bad entry does not spoil the list
            }
        }
        return out;
    }

    /** "menu" is the menu; the hash of the server you stand on is here (null = you are not on a server); anything else is another server. */
    public static Where where(String server, String mine) {
        if ("menu".equals(server)) return Where.MENU;
        return mine != null && mine.equals(server) ? Where.HERE : Where.OTHER;
    }

    public static String whereText(Where w) {
        return switch (w) {
            case HERE -> "your server";
            case OTHER -> "other server";
            case MENU -> "menu";
        };
    }

    /** The first eight hex digits of the UUID: what is shown when no name could be found. */
    public static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    /**
     * Rows for the view. {@code name} gives a name or null (then the short UUID is shown), {@code isFriend} is asked only for real names.
     * Friends first, then your server, other servers, the menu; inside a group by name, ignoring case.
     */
    public static List<Row> rows(List<Entry> entries, String mine, Function<UUID, String> name, Predicate<String> isFriend) {
        List<Row> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            String n = name.apply(e.id());
            boolean known = n != null && !n.isBlank();
            out.add(new Row(e.id(), known ? n : shortId(e.id()), where(e.server(), mine), known && isFriend.test(n)));
        }
        out.sort(Comparator.comparing((Row r) -> !r.friend()).thenComparing(r -> r.where().ordinal())
                .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER).thenComparing(r -> r.id().toString()));
        return out;
    }

    /** "3 online, 2 friends". */
    public static String summary(List<Row> rows) {
        int friends = 0;
        for (Row r : rows) if (r.friend()) friends++;
        return rows.size() + " online, " + friends + (friends == 1 ? " friend" : " friends");
    }
}
