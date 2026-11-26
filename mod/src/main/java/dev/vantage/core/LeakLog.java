package dev.vantage.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

import dev.vantage.core.log.DataLogs;

/** config/vantage/leaks/<server>.log: one line per find, once per kind and chunk, so a play session can be checked afterwards. Local file only. */
public final class LeakLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> SEEN = new HashSet<>();
    /** Per kind and chunk: the detail last written and when, so a chunk is written again when its reasons change (at most once a minute). */
    private static final java.util.Map<String, Object[]> LAST = new java.util.HashMap<>();
    private static final long AGAIN_MS = 60_000;

    public static void add(String kind, int x, int y, int z, String detail) {
        if (noServer()) return;
        String key = kind + " " + (x >> 4) + " " + (z >> 4) + " " + ChunkTracker.dim();
        long now = System.currentTimeMillis();
        Object[] last = LAST.get(key);
        if (last != null) {
            if (detail.equals(last[0]) || now - (long) last[1] < AGAIN_MS) return;
            kind = kind + "-AGAIN"; // the same chunk with a changed verdict or reasons
        }
        LAST.put(key, new Object[] {detail, now});
        emit(TIME.format(LocalDateTime.now()) + " " + kind + " " + ChunkTracker.dim() + " " + x + " " + y + " " + z + " " + detail);
    }

    private static int diagMax = 60_000;
    private static int diagLines;
    private static int cancelMax = 4_000;
    private static int cancelLines;

    /**
     * Diagnostic lines (Base Finder, -Dvantage.bfdiag=true): same line format and file, but no repeat suppression, so a second release of one chunk is not lost. At most `diagMax`
     * (60000) lines per client run, then one line that says the cap was hit, then silence. CANCEL lines have their own budget (`cancelMax`, 4000 per client run, not counted in the
     * general cap): when it is used up one BFDIAG-CAP line says so and no more CANCEL lines are written, every other kind goes on.
     */
    public static void addDiag(String kind, int x, int y, int z, String detail) {
        if (noServer()) return;
        if (kind.equals("BFDIAG-CANCEL")) {
            if (cancelLines > cancelMax) return;
            if (cancelLines == cancelMax) {
                cancelLines++;
                emit(TIME.format(LocalDateTime.now()) + " BFDIAG-CAP " + ChunkTracker.dim() + " " + x + " " + y + " " + z + " cancel budget of " + cancelMax + " hit, no more CANCEL lines are written");
                return;
            }
            cancelLines++;
            emit(TIME.format(LocalDateTime.now()) + " " + kind + " " + ChunkTracker.dim() + " " + x + " " + y + " " + z + " " + detail);
            return;
        }
        if (diagLines > diagMax) return;
        if (diagLines == diagMax) {
            diagLines++;
            emit(TIME.format(LocalDateTime.now()) + " BFDIAG-CAP " + ChunkTracker.dim() + " " + x + " " + y + " " + z + " cap of " + diagMax + " diagnostic lines hit, no more are written");
            return;
        }
        diagLines++;
        emit(TIME.format(LocalDateTime.now()) + " " + kind + " " + ChunkTracker.dim() + " " + x + " " + y + " " + z + " " + detail);
    }

    /** Rig: lower (or restore) the cap, and read how many diagnostic lines were counted. */
    public static void setDiagCapForTest(int cap) { diagMax = cap; }

    public static int diagCountForTest() { return diagLines; }

    public static int diagCapForTest() { return diagMax; }

    /** Rig: the CANCEL budget (and how many CANCEL lines were counted), so a scene can lower it and put back what it found. */
    public static void setCancelBudgetForTest(int n) { cancelMax = n; }

    public static int cancelBudgetForTest() { return cancelMax; }

    public static int cancelCountForTest() { return cancelLines; }

    /** No position, for things like a name in the player list. */
    public static void note(String kind, String detail) {
        if (noServer()) return;
        if (SEEN.add(kind + " " + detail)) emit(TIME.format(LocalDateTime.now()) + " " + kind + " " + detail);
    }

    /** No world, so no server to name the file after: the line is dropped and counted, and no state (repeat suppression, caps) is spent on it. */
    private static boolean noServer() {
        return DataLogs.serverKey() == null && DataLogs.noServer("leaks", ".log");
    }

    /** Hands the lines to the rolling file of the server they were made on (the name is taken now, never when the file is written: B-184). */
    private static void emit(String line) {
        DataLogs.appendServer("leaks", ".log", line);
    }

    /** Hands everything pending to the writer thread now (the in-game check rig writes at once anyway). */
    public static void flush() {
        DataLogs.flushRequest();
    }

    /** Level change: forgets what was seen in the old world. Leaving a world (no level) also forgets the repeat suppression, so the next visit starts clean (B-184). */
    public static void clear() {
        flush();
        SEEN.clear();
        if (net.minecraft.client.Minecraft.getInstance().level == null) LAST.clear();
    }

    private LeakLog() {}
}
