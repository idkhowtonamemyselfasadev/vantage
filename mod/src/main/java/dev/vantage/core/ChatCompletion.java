package dev.vantage.core;

import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.vantage.core.command.Commands;

/**
 * Tab completion for client commands in the chat box. The game's own suggestion box takes its words from a pending Brigadier
 * result; {@code CommandSuggestionsMixin} lets this class replace that result when the typed line starts with the command prefix.
 */
public final class ChatCompletion {
    private ChatCompletion() {}

    /** The suggestions for the line with the cursor where it is, in the form the game's box reads, or null to leave the game's own. */
    public static CompletableFuture<Suggestions> forLine(String line, int cursor) {
        if (Terms.off()) return null;
        var found = Commands.main().suggest(line, cursor);
        if (found.isEmpty()) return null;
        SuggestionsBuilder b = new SuggestionsBuilder(line.substring(0, Math.min(cursor, line.length())), found.from());
        for (String s : found.items()) b.suggest(s);
        return CompletableFuture.completedFuture(b.build());
    }
}
