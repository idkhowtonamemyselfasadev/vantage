package dev.vantage.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the frame time goes inside Vantage: nanoseconds per module per hook, plus a few fixed buckets (R3 flush, HUD extras),
 * summed since the last report. Always counting (two nanoTime calls per hook call); -Dvantage.prof=true logs the top lines
 * every 10 s, and the perf rig reads report() directly. A frame over 25 ms and 4x the running average is a stutter: its breakdown
 * goes to the log and to config/vantage/stutter.log, so "I drop to 100 fps" has numbers behind it.
 */
public final class Perf {
    public static final boolean LOG = Boolean.getBoolean("vantage.prof");
    private static final Map<String, long[]> sums = new HashMap<>(); // name -> {ns, calls, max ns of one call}
    private static long frames, since = System.currentTimeMillis();

    private Perf() {}

    public static void add(String key, long ns) {
        long[] v = sums.computeIfAbsent(key, k -> new long[3]);
        v[0] += ns;
        v[1]++;
        if (ns > v[2]) v[2] = ns;
        long[] c = cur.computeIfAbsent(key, k -> new long[2]);
        c[0] += ns;
        c[1]++;
    }

    // ---- stutter log: one frame much longer than the ones before it gets its Vantage breakdown written down ----
    private static final Map<String, long[]> cur = new HashMap<>(); // this frame only: name -> {ns, calls}
    private static long lastFrameNs, lastStutterMs, stutters;
    private static double avgMs = -1;
    /** A frame counts as a stutter above this and above 4x the running average. */
    private static final double STUTTER_MS = 25;
    private static final int STUTTER_MAX = 300;

    /** Milliseconds summed for one key since the last reset (0 when the key was never timed). */
    public static double totalMs(String key) {
        long[] v = sums.get(key);
        return v == null ? 0 : v[0] / 1e6;
    }

    /** The longest single call of one key since the last reset, in milliseconds. */
    public static double worstMs(String key) {
        long[] v = sums.get(key);
        return v == null ? 0 : v[2] / 1e6;
    }

    /** Calls counted for one key since the last reset, for the rig. */
    public static long calls(String key) {
        long[] v = sums.get(key);
        return v == null ? 0 : v[1];
    }

    /** Frame Budget (beta) switches this on: a smoothed ms per frame per module is kept for the Active tab and the hint. */
    public static volatile boolean track;
    private static final Map<String, double[]> EMA = new HashMap<>();

    /** Smoothed ms per frame the module costs in its tick, 2D and 3D hooks (0 when unknown or tracking is off). */
    public static double msFor(String module) {
        Double t = TEST_MS.get(module);
        if (t != null) return t;
        double[] v = EMA.get(module);
        return v == null ? 0 : v[0];
    }

    /** Rig only: costs (ms per frame) that stand in for measured ones, so both sides of the heaviest-module rule can be proved at any frame rate. */
    public static final Map<String, Double> TEST_MS = new HashMap<>();

    /** The module with the highest smoothed cost if it is at least minMs, else null. */
    public static String heaviest(double minMs) {
        String best = null;
        double top = minMs;
        for (var e : EMA.entrySet()) if (e.getValue()[0] >= top) { top = e.getValue()[0]; best = e.getKey(); }
        for (var e : TEST_MS.entrySet()) if (e.getValue() >= top) { top = e.getValue(); best = e.getKey(); }
        return best;
    }

    private static void smooth() {
        Map<String, Double> now = new HashMap<>();
        for (var e : cur.entrySet()) {
            String k = e.getKey();
            int sp = k.lastIndexOf(' ');
            if (sp <= 0) continue;
            String hook = k.substring(sp + 1);
            if (!hook.equals("tick") && !hook.equals("3d") && !hook.equals("2d")) continue;
            now.merge(k.substring(0, sp), e.getValue()[0] / 1e6, Double::sum);
        }
        for (var e : now.entrySet()) EMA.computeIfAbsent(e.getKey(), k -> new double[1])[0] += (e.getValue() - EMA.get(e.getKey())[0]) * 0.05;
        for (var e : EMA.entrySet()) if (!now.containsKey(e.getKey())) e.getValue()[0] *= 0.95;
    }

    public static void frame() {
        if (track) smooth();
        frames++;
        long now = System.nanoTime();
        if (lastFrameNs != 0) {
            double ms = (now - lastFrameNs) / 1e6;
            if (avgMs < 0) avgMs = ms;
            if (ms > STUTTER_MS && ms > 4 * avgMs && avgMs < 1000) stutter(ms);
            if (ms < 1000) avgMs += (ms - avgMs) * 0.05;
        }
        lastFrameNs = now;
        cur.clear();
        if (LOG && System.currentTimeMillis() - since >= 10_000) {
            for (String line : report()) Vantage.LOG.info("perf {}", line);
            reset();
        }
    }

    /** Writes the slow frame's Vantage breakdown: how much of it was ours and which hooks took it. */
    private static void stutter(double frameMs) {
        long wall = System.currentTimeMillis();
        if (wall - lastStutterMs < 1000 || stutters >= STUTTER_MAX) return;
        lastStutterMs = wall;
        stutters++;
        long ours = 0;
        for (long[] c : cur.values()) ours += c[0];
        List<Map.Entry<String, long[]>> all = new ArrayList<>(cur.entrySet());
        all.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder();
        sb.append(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(java.time.LocalTime.now()));
        sb.append(String.format(" frame %.1f ms (avg %.1f) vantage %.2f ms:", frameMs, avgMs, ours / 1e6));
        int n = 0;
        for (var e : all) {
            if (n++ >= 6 || e.getValue()[0] < 100_000) break;
            sb.append(String.format("  %s %.2f ms x%d", e.getKey(), e.getValue()[0] / 1e6, e.getValue()[1]));
        }
        if (ours < frameMs * 1e6 * 0.2) sb.append("  (not Vantage: GC, chunk builds or another mod)");
        String line = sb.toString();
        Vantage.LOG.info("stutter {}", line);
        dev.vantage.core.log.DataLogs.appendGlobal("stutter.log", 1024 * 1024, 2, line);
    }

    /** Top entries by time, as "name: ms total, ms per frame, calls", most expensive first. */
    public static List<String> report() {
        double sec = Math.max(0.001, (System.currentTimeMillis() - since) / 1000.0);
        List<Map.Entry<String, long[]>> all = new ArrayList<>(sums.entrySet());
        all.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        List<String> out = new ArrayList<>();
        long total = 0;
        for (var e : all) total += e.getValue()[0];
        out.add(String.format("frames %d (%.0f fps), vantage total %.2f ms/frame", frames, frames / sec, frames == 0 ? 0 : total / 1e6 / frames));
        int n = 0;
        for (var e : all) {
            if (n++ >= 14) break;
            long ns = e.getValue()[0], calls = e.getValue()[1];
            out.add(String.format("%-34s %8.1f ms  %6.3f ms/frame  %7d calls  worst %6.2f ms", e.getKey(), ns / 1e6, frames == 0 ? 0 : ns / 1e6 / frames, calls, e.getValue()[2] / 1e6));
        }
        all.sort((a, b) -> Long.compare(b.getValue()[2], a.getValue()[2]));
        out.add("spikes (worst single call):");
        n = 0;
        for (var e : all) {
            if (n++ >= 8 || e.getValue()[2] < 200_000) break;
            out.add(String.format("  %-32s worst %6.2f ms  (%d calls)", e.getKey(), e.getValue()[2] / 1e6, e.getValue()[1]));
        }
        return out;
    }

    public static void reset() {
        sums.clear();
        frames = 0;
        since = System.currentTimeMillis();
    }
}
