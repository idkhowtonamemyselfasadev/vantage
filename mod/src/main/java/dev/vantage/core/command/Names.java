package dev.vantage.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** How modules and settings are named in a command, and the word matching used for typos and completion. */
public final class Names {
    private Names() {}

    /** The display name as it is typed: lower case, no spaces ("Storage ESP" gives "storageesp"). */
    public static String squeeze(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isWhitespace(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * Finds the item a typed word means. The stable id always wins; otherwise the display name without spaces, in any case. When two
     * items squeeze to the same word the answer is a problem that lists both ids, never a guess.
     *
     * @param kind  "module" or "setting", for the reason
     * @param where for a setting, the module it was looked for in, else null
     */
    public static <T extends Named> Parse<T> find(String text, List<? extends T> items, String kind, String where) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (T t : items) if (t.id().equals(lower)) return Parse.ok(t);
        String sq = squeeze(text);
        List<T> hits = new ArrayList<>(2);
        for (T t : items) if (squeeze(t.name()).equals(sq)) hits.add(t);
        if (hits.size() == 1) return Parse.ok(hits.get(0));
        if (hits.size() > 1) {
            StringBuilder ids = new StringBuilder();
            for (T t : hits) ids.append(ids.length() == 0 ? "" : ", ").append(t.id());
            return Parse.fail("'" + text + "' fits more than one " + kind + (where == null ? "" : " of " + where) + " (" + ids + "), write the id of the one you mean");
        }
        List<String> words = new ArrayList<>();
        for (T t : items) words.add(t.id());
        String near = closest(lower, words);
        String none = "there is no " + kind + (where == null ? "" : " of " + where) + " called '" + text + "'";
        return Parse.fail(near == null ? none : none + ". Did you mean " + near + "?");
    }

    /** For the items whose id or squeezed name starts with the typed text, in list order: the id when it fits, else the name without spaces (so that the offered word really starts with what was typed). */
    public static List<String> suggestNamed(String partial, List<? extends Named> items) {
        String p = squeeze(partial);
        List<String> out = new ArrayList<>();
        for (Named n : items) {
            String word = n.id().startsWith(p) ? n.id() : squeeze(n.name()).startsWith(p) ? squeeze(n.name()) : null;
            if (word != null && !out.contains(word)) out.add(word);
        }
        return out;
    }

    /** The candidates that start with the partial word, ignoring case, in the order given. */
    public static List<String> startingWith(String partial, List<String> candidates) {
        String p = partial.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : candidates) if (c.toLowerCase(Locale.ROOT).startsWith(p) && !out.contains(c)) out.add(c);
        return out;
    }

    /**
     * The known word nearest to a mistyped one, or null when none is close: within one edit for short words, a third of the length
     * for longer ones, or when the typed word is the beginning of a known one.
     */
    public static String closest(String typed, List<String> known) {
        String t = typed.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String k : known) {
            int d = distance(t, k.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = k;
            }
        }
        if (best != null && bestDistance <= Math.max(1, t.length() / 3)) return best;
        if (t.length() >= 2) for (String k : known) if (k.toLowerCase(Locale.ROOT).startsWith(t)) return k;
        return null;
    }

    /** Edit distance: the fewest inserted, deleted or changed letters that turn one word into the other. */
    public static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int same = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(same, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
