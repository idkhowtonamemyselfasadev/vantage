package dev.vantage.core.store;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the old files of a module for the place the player is in, so a store can adopt them (see {@link ChunkSetStore#adopt}).
 *
 * A pattern is a path under the config folder with two placeholders: {@code {server}} stands for every old spelling of the server
 * ({@link Scope.Place#oldServerKeys}), {@code {dim}} for every old spelling of the dimension. Example:
 * {@code sky-protected/{server}_{dim}.json}. Every combination is tried against the real directory listing, ignoring upper and lower
 * case (the old key kept the case the address was typed in). Only files that exist are returned; nothing is created, opened or
 * changed here.
 */
final class OldFiles {
    private OldFiles() {}

    /** The existing files that match the pattern for this place, in path order. Empty when the place is null or has no old spelling. */
    static List<Path> find(Path root, String pattern, Scope.Place place) {
        if (place == null) return List.of();
        List<String> servers = pattern.contains("{server}") ? place.oldServerKeys() : List.of("");
        List<String> dims = pattern.contains("{dim}") ? place.oldDimensionKeys() : List.of("");
        Set<String> expanded = new LinkedHashSet<>();
        for (String s : servers) for (String d : dims) expanded.add(pattern.replace("{server}", s).replace("{dim}", d));
        Set<Path> found = new TreeSet<>();
        for (String e : expanded) match(root, e.split("/", -1), 0, found);
        return new ArrayList<>(found);
    }

    /** Walks one segment at a time, taking the names the directory really has: a segment that is ".." or empty matches nothing, so no pattern can leave the root. */
    private static void match(Path dir, String[] segments, int i, Set<Path> out) {
        if (segments[i].isEmpty() || !Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (!entry.getFileName().toString().equalsIgnoreCase(segments[i])) continue;
                if (i == segments.length - 1) {
                    if (Files.isRegularFile(entry)) out.add(entry);
                } else {
                    match(entry, segments, i + 1, out);
                }
            }
        } catch (IOException e) {
            dev.vantage.core.Vantage.LOG.warn("Could not look for old files in {}", dir, e);
        }
    }
}
