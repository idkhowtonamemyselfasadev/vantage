package dev.vantage.core;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.notify.Notify;

/**
 * The one list of things the user must be told about saved data: a settings file that was damaged, a store that started empty.
 * Anything can add to it from any thread; {@link #show()} says the new ones in chat, once each, when a world is joined (there is no
 * chat before that). The config adds its load problems under "Settings", the stores under "Saved data".
 */
public final class Notices {
    private record Notice(String source, String text, boolean shown) {}

    private static final List<Notice> ALL = new ArrayList<>();
    private static final int SHOWN_AT_ONCE = 4;

    private Notices() {}

    /** Adds one notice and logs it. */
    public static synchronized void add(String source, String text) {
        ALL.add(new Notice(source, text, false));
        Vantage.LOG.warn("{}: {}", source, text);
    }

    /** What was added under this source and not removed with {@link #clear}, in order. */
    public static synchronized List<String> texts(String source) {
        List<String> out = new ArrayList<>();
        for (Notice n : ALL) if (n.source.equals(source)) out.add(n.text);
        return out;
    }

    /** Forgets everything of one source (the config does this at the start of each load, so a reload does not repeat old news). */
    public static synchronized void clear(String source) {
        ALL.removeIf(n -> n.source.equals(source));
    }

    public static synchronized boolean hasUnshown() {
        for (Notice n : ALL) if (!n.shown) return true;
        return false;
    }

    /** Tells the user the notices they have not seen: at most four lines, then how many more there are (they are in the log). */
    public static synchronized void show() {
        List<Notice> fresh = new ArrayList<>();
        for (int i = 0; i < ALL.size(); i++) {
            Notice n = ALL.get(i);
            if (n.shown) continue;
            fresh.add(n);
            ALL.set(i, new Notice(n.source, n.text, true));
        }
        if (fresh.isEmpty()) return;
        int shown = Math.min(SHOWN_AT_ONCE, fresh.size());
        for (int i = 0; i < shown; i++) Notify.warning(Notify.CORE, null, 0, fresh.get(i).source + ": " + fresh.get(i).text);
        if (fresh.size() > shown) {
            String source = fresh.get(0).source;
            for (Notice n : fresh) if (!n.source.equals(source)) source = "Saved data";
            Notify.warning(Notify.CORE, null, 0, source + ": " + (fresh.size() - shown) + " more problem(s), listed in the log.");
        }
    }
}
