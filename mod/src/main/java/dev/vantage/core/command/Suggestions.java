package dev.vantage.core.command;

import java.util.List;

/** What tab completion offers: the words that can replace the text from {@code from} to {@code to} of the typed line. */
public record Suggestions(int from, int to, List<String> items) {
    public static final Suggestions NONE = new Suggestions(0, 0, List.of());

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
