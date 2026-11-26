package dev.vantage.core.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Predicate;

import com.google.gson.JsonParser;

/**
 * Reading and writing a saved file so that it cannot be lost: the rule every saved file of the client follows.
 *
 * Writing: the new content goes to {@code name.tmp} and is forced to disk; the file that is there now becomes {@code name.bak}
 * (only if it is readable: a damaged file must never replace a good backup); then the temp file is moved into place in one step.
 * A crash at any point leaves either the old file, or the new file, or the backup.
 *
 * Reading: the file is used when it is readable. When it is not, it is copied to {@code name.bad-<time>} (never overwritten, the
 * newest five are kept) and the backup is used instead. The caller learns which of these happened and tells the user.
 */
public final class SafeFile {
    private SafeFile() {}

    /** What a read found. {@code text} is null when neither the file nor its backup could be used. */
    public record Read(String text, boolean fromBackup, Path badCopy) {
        public boolean ok() {
            return text != null;
        }

        /** True when the file on disk was damaged, whether or not a backup saved the day. */
        public boolean damaged() {
            return badCopy != null;
        }
    }

    /** "Readable" for a JSON file whose top level is an object. */
    public static final Predicate<String> JSON_OBJECT = s -> {
        try {
            return JsonParser.parseString(s).isJsonObject();
        } catch (RuntimeException e) {
            return false;
        }
    };

    private static final int BAD_COPIES_KEPT = 5;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private static Path tempOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".tmp");
    }

    /**
     * Replaces the file's content. {@code readable} decides whether the file that is there now is worth keeping as the backup.
     * Throws when the content could not be written; the file on disk is then still the old one.
     */
    public static void write(Path file, String text, Predicate<String> readable) throws IOException {
        if (dev.vantage.core.Terms.off()) return; // the terms gate: Vantage writes no saved data until the terms are confirmed
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = tempOf(file);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true); // on disk before it takes the old file's place, or a power cut could leave an empty file under the real name
        }
        if (Files.isRegularFile(file)) {
            String current = readOrNull(file);
            if (current != null && readable.test(current)) Files.copy(file, backupOf(file), StandardCopyOption.REPLACE_EXISTING);
            else keepAside(file);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); // some network and removable drives cannot swap in one step
        }
    }

    /** Reads the file, falling back to its backup. Never throws: a file that cannot be read is a result, not an error. */
    public static Read read(Path file, Predicate<String> readable) {
        Path bad = null;
        if (Files.isRegularFile(file)) {
            String text = readOrNull(file);
            if (text != null && readable.test(text)) return new Read(text, false, null);
            bad = keepAside(file);
        }
        Path bak = backupOf(file);
        if (Files.isRegularFile(bak)) {
            String text = readOrNull(bak);
            if (text != null && readable.test(text)) return new Read(text, true, bad);
        }
        return new Read(null, false, bad);
    }

    private static String readOrNull(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null; // unreadable bytes are the same case as unreadable content: the caller falls back
        }
    }

    /** Copies a damaged file next to itself under a name that is never reused, and returns that name (null when even that failed). */
    private static Path keepAside(Path file) {
        try {
            String base = file.getFileName() + ".bad-";
            Path copy = file.resolveSibling(base + LocalDateTime.now().format(STAMP));
            for (int n = 2; Files.exists(copy); n++) copy = file.resolveSibling(base + LocalDateTime.now().format(STAMP) + "-" + n);
            Files.copy(file, copy);
            Path dir = file.toAbsolutePath().getParent();
            try (var all = Files.list(dir)) {
                List<Path> old = all.filter(p -> p.getFileName().toString().startsWith(base)).sorted().toList();
                for (int i = 0; i < old.size() - BAD_COPIES_KEPT; i++) Files.deleteIfExists(old.get(i));
            }
            return copy;
        } catch (IOException e) {
            return null;
        }
    }
}
