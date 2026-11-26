package dev.vantage.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Keeps the jar current from the GitHub release. One GET to the releases API tells when the release asset was last uploaded; if that
 * is newer than the jar on disk, the build is fetched. Two ways in:
 * <ul>
 * <li>{@link PreLaunch} (before Minecraft loads, Settings > Auto update): download, swap, start the game again with its own command
 * line and leave. On Linux the rename over the open jar works at once and the new process is started right here. On Windows the running
 * jar is locked, so {@link Relaunch} is started from the downloaded file: it waits for this process to end, copies the jar over and
 * starts the game. The user sees the new build come up, never the old one.</li>
 * <li>The title-screen window (Auto update off, or the relaunch was not possible): Update downloads and swaps, the next launch runs it.</li>
 * </ul>
 * Only ever talks to github.com, sends nothing but the request.
 */
public final class Updater {
    public static final String REPO = "idkhowtonamemyselfasadev/vantage";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());
    /** Set on the process the updater starts, so a fresh build never relaunches itself again. */
    public static final String RELAUNCHED = "VANTAGE_RELAUNCHED";

    /**
     * @param digest lower-case hex SHA-256 the release itself reports for the jar (GitHub's {@code digest} field), or null
     * @param digestUrl download URL of a {@code <jar>.sha256} asset next to the jar, or null
     */
    public record Release(String tag, String url, long size, Instant uploaded, String name, String digest, String digestUrl) {
        /** True when the release carries a checksum to check the download against. Without one nothing is installed automatically. */
        public boolean verifiable() {
            return digest != null || digestUrl != null;
        }
    }

    /** The newest release's jar digest and tag as the last check saw them (also when it is not newer), for the "Verify this build" line. */
    private static volatile String releaseDigest, releaseTag;

    private static volatile Release newer;
    private static volatile String state = "";
    private static volatile boolean busy;
    private static boolean asked;

    /** The release that is newer than the jar on disk, or null. */
    public static Release pending() {
        return newer;
    }

    /** Test hook: pretend this release is the pending one (the update window shows it). */
    public static void testPending(Release r) { newer = r; }

    public static String state() {
        return statusLine();
    }

    public static boolean busy() {
        return busy;
    }

    /** True once the title screen has shown the question this launch. */
    public static boolean asked() {
        return asked;
    }

    public static void markAsked() {
        asked = true;
    }

    /** The jar this build runs from, or null in a dev run. */
    public static Path jar() {
        var c = FabricLoader.getInstance().getModContainer("vantage");
        if (c.isEmpty()) return null;
        List<Path> paths = c.get().getOrigin().getPaths();
        if (paths.isEmpty()) return null;
        Path p = paths.get(0);
        return Files.isRegularFile(p) && p.toString().endsWith(".jar") ? p : null;
    }

    /** Newer when the asset was uploaded after the local file was written, with two minutes of slack for clock drift. */
    public static boolean newer(Instant uploaded, Instant local) {
        return uploaded.isAfter(local.plusSeconds(120));
    }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Asks GitHub for the release jar; the release when it is newer than the jar on disk, null otherwise or when GitHub is out of reach. */
    public static Release fetch(Path jar, int timeoutSeconds) {
        if (Terms.off()) return null; // the terms gate: no Vantage network
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(timeoutSeconds + 2))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "Vantage-updater").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) { state = "GitHub answered " + res.statusCode(); return null; }
            Release r = parse(res.body(), Files.getLastModifiedTime(jar).toInstant());
            return r;
        } catch (Exception e) {
            state = "check failed: " + e.getClass().getSimpleName();
            Vantage.LOG.info("Update check skipped: {}", e.toString());
        }
        return null;
    }

    /** SHA-256 hex from "sha256:HEX" (GitHub's digest field), lower-cased; null for anything else. */
    public static String parseDigest(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (!t.startsWith("sha256:")) return null;
        t = t.substring(7);
        return t.matches("[0-9a-f]{64}") ? t : null;
    }

    /** First 64-hex token of a {@code sha256sum}-style file ("HEX  name"), lower-cased; null when there is none. */
    public static String parseSidecar(String text) {
        if (text == null) return null;
        var m = java.util.regex.Pattern.compile("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])").matcher(text);
        return m.find() ? m.group().toLowerCase(java.util.Locale.ROOT) : null;
    }

    /**
     * Reads a releases/latest answer: the release when its jar was uploaded after {@code local} (with the digest or checksum asset it
     * carries), null otherwise. Sets state() and remembers the release's digest. No network: tests feed it a local string.
     */
    public static Release parse(String body, Instant local) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String tag = root.has("tag_name") ? root.get("tag_name").getAsString() : "?";
        for (var a : root.getAsJsonArray("assets")) {
            JsonObject o = a.getAsJsonObject();
            String name = o.get("name").getAsString();
            if (!name.endsWith(".jar")) continue;
            Instant up = Instant.parse(o.get("updated_at").getAsString());
            String digest = o.has("digest") && !o.get("digest").isJsonNull() ? parseDigest(o.get("digest").getAsString()) : null;
            String sideUrl = null;
            for (var b : root.getAsJsonArray("assets")) {
                JsonObject so = b.getAsJsonObject();
                if (so.get("name").getAsString().equals(name + ".sha256")) sideUrl = so.get("browser_download_url").getAsString();
            }
            releaseDigest = digest;
            releaseTag = tag;
            Release r = newer(up, local)
                    ? new Release(tag, o.get("browser_download_url").getAsString(), o.get("size").getAsLong(), up, name, digest, sideUrl) : null;
            if (r == null) state = "up to date";
            else if (!r.verifiable()) {
                state = "newer build from " + WHEN.format(up) + ", but the release has no SHA-256, so it is not installed automatically";
                Vantage.LOG.warn("Update {} has no digest and no .sha256 asset: not installing it automatically", tag);
            } else state = "newer build from " + WHEN.format(up);
            return r;
        }
        state = "no jar in the release";
        return null;
    }

    // ---- integrity: checksum, previous jar, rollback ------------------------------------------------------------------------

    /** Test hook: a folder that stands in for config/vantage (the marker and the rollback note live there). */
    private static volatile Path testDir;

    public static void testDir(Path p) { testDir = p; }

    static Path stateDir() {
        return testDir != null ? testDir : FabricLoader.getInstance().getConfigDir().resolve("vantage");
    }

    /** The jar as it was before the last update; the way back when the new one does not start. */
    public static Path prevJar(Path jar) {
        return jar.resolveSibling(jar.getFileName() + ".prev");
    }

    public static Path sidecar(Path fresh) {
        return fresh.resolveSibling(fresh.getFileName() + ".sha256");
    }

    /** SHA-256 of a file, lower-case hex. */
    public static String sha256(Path f) throws IOException {
        try (var in = Files.newInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * The only gate a download passes: its SHA-256 must equal {@code expected} (from the release itself). A match leaves a
     * {@code <file>.sha256} note next to it, which swap() insists on; no expected value or a different one deletes the file.
     * Never falls back to comparing sizes.
     */
    public static Path accept(Path fresh, String expected) {
        try {
            if (expected == null) {
                state = "no SHA-256 to check the download against, not installed";
                Vantage.LOG.warn("Update download has no digest to check against: deleted, not installing");
            } else {
                String got = sha256(fresh);
                if (got.equals(expected)) {
                    Files.writeString(sidecar(fresh), got + "  " + fresh.getFileName() + "\n");
                    Vantage.LOG.info("Update download verified (sha256 {})", got.substring(0, 12));
                    return fresh;
                }
                state = "download rejected: SHA-256 does not match the release";
                Vantage.LOG.warn("Update download rejected: sha256 {} but the release says {}", got.substring(0, 12), expected.substring(0, 12));
            }
        } catch (IOException e) {
            state = "download could not be verified: " + e.getClass().getSimpleName();
            Vantage.LOG.warn("Update verification failed", e);
        }
        try { Files.deleteIfExists(fresh); Files.deleteIfExists(sidecar(fresh)); } catch (IOException ignored) {}
        return null;
    }

    /** True when {@code fresh} still hashes to the digest that accept() wrote next to it. */
    public static boolean verifiedFresh(Path fresh) {
        try {
            if (!Files.isRegularFile(fresh) || !Files.isRegularFile(sidecar(fresh))) return false;
            String want = parseSidecar(Files.readString(sidecar(fresh)));
            return want != null && want.equals(sha256(fresh));
        } catch (Exception e) {
            return false;
        }
    }

    /** The checksum asset's content, or null. Same host as the jar. */
    private static String fetchSidecar(String url) {
        if (Terms.off()) return null;
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "Vantage-updater").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 && res.body().length() < 2000 ? parseSidecar(res.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** What the status line says when the downloaded update cannot be replaced because its helper holds it. */
    public static final String HELD = "already downloaded: close the game, then start it again";

    /** Downloads the release next to the jar as {@code <jar>.new}; the file once its SHA-256 matches the release's, null otherwise. */
    public static Path download(Release r, Path jar) {
        if (Terms.off()) return null;
        if (!r.verifiable()) {
            state = "the release has no SHA-256, not installed automatically";
            return null;
        }
        Path fresh = jar.resolveSibling(jar.getFileName() + ".new");
        try {
            try {
                Files.deleteIfExists(fresh);
            } catch (java.nio.file.FileSystemException held) {
                // Windows: the helper of an update fetched earlier in this session runs from that file and waits for the game to close.
                // Nothing failed: the update is already here. (This used to read "download failed: FileSystemException".)
                // Only a file that is in use counts: no rights (AccessDenied: antivirus, read-only folder) is a real failure.
                if (held instanceof java.nio.file.AccessDeniedException) {
                    state = "download failed: cannot replace the old download (" + held.getClass().getSimpleName() + ")";
                    Vantage.LOG.warn("Update download failed", held);
                    return null;
                }
                state = HELD;
                Vantage.LOG.info("Update already downloaded and waiting for the game to close ({})", held.getClass().getSimpleName());
                return null;
            }
            Files.deleteIfExists(sidecar(fresh));
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(r.url())).timeout(Duration.ofMinutes(3)).header("User-Agent", "Vantage-updater").GET().build();
            HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(fresh));
            if (res.statusCode() == 200) {
                String expected = r.digest() != null ? r.digest() : fetchSidecar(r.digestUrl());
                return accept(fresh, expected);
            }
            state = "download failed (" + res.statusCode() + ")";
        } catch (Exception e) {
            state = "download failed: " + e.getClass().getSimpleName();
            Vantage.LOG.warn("Update download failed", e);
        }
        try { Files.deleteIfExists(fresh); } catch (IOException ignored) {}
        return null;
    }

    /** Leftovers of an earlier update (the Windows helper runs from the .new file and cannot delete it) go once nothing holds them. */
    public static void cleanStale(Path jar) {
        Path fresh = jar.resolveSibling(jar.getFileName() + ".new");
        try { Files.deleteIfExists(fresh); Files.deleteIfExists(sidecar(fresh)); Files.deleteIfExists(waitingNote()); } catch (IOException ignored) {}
    }

    /** Starts the check in the background; nothing happens in a dev run or when the setting is off. */
    public static void check() {
        Path jar = jar();
        if (jar == null || Terms.off()) return; // while the terms gate is up the check is held back; Confirm starts it
        Thread t = new Thread(() -> newer = fetch(jar, 6), "Vantage update check");
        t.setDaemon(true);
        t.start();
    }

    /** Downloads the release next to the jar and swaps it in. Runs in the background; state() tells how it went. */
    public static void update() {
        Release r = newer;
        Path jar = jar();
        if (r == null || jar == null || busy) return;
        if (!r.verifiable()) { state = "the release has no SHA-256, not installed automatically"; return; }
        busy = true;
        state = "downloading";
        Thread t = new Thread(() -> {
            try {
                Path fresh = download(r, jar);
                if (fresh == null) return;
                state = swap(fresh, jar);
                newer = null;
            } catch (Exception e) {
                state = "update failed: " + e.getClass().getSimpleName();
                Vantage.LOG.warn("Update failed", e);
            } finally {
                busy = false;
            }
        }, "Vantage update");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Before anything replaces the jar: the new file must still match the checksum accept() recorded, the old jar is kept as
     * {@code <jar>.prev}, and the marker is written that makes the next launch watch whether the game reaches the title screen.
     * Throws when the file is not verified, so nothing unverified can ever reach swapRaw.
     */
    public static void prepareSwap(Path fresh, Path jar) throws IOException {
        if (!verifiedFresh(fresh)) throw new IOException("update file is not verified, refusing to install it");
        String oldSha = "?";
        if (Files.isRegularFile(jar)) {
            Files.copy(jar, prevJar(jar), StandardCopyOption.REPLACE_EXISTING);
            oldSha = sha256(jar);
        }
        arm(oldSha, parseSidecar(Files.readString(sidecar(fresh))));
    }

    /** What the status line says when the new build is downloaded but only goes in once this game has been closed. */
    public static final String CLOSE_AND_START = "downloaded: close the game, then start it again";

    /** How the verified file gets to where the jar is: {@link #swapRaw}, or a test's stand-in for a machine where it cannot. */
    interface Put {
        String put(Path fresh, Path jar) throws IOException;
    }

    /** Verifies, keeps the old jar, arms the rollback marker and puts the new file where the old jar is. */
    public static String swap(Path fresh, Path jar) throws IOException {
        return swap(fresh, jar, Updater::swapRaw);
    }

    static String swap(Path fresh, Path jar, Put put) throws IOException {
        prepareSwap(fresh, jar);
        String r = put.put(fresh, jar);
        // a file left for the next launch keeps its checksum note: without it leftover() would not touch it
        if (!swapWaiting()) Files.deleteIfExists(sidecar(fresh));
        return r;
    }

    /** Puts the file where the old jar is, no checks (swap() and the rollback do theirs first). Windows keeps the running jar locked: see {@link #lockedSwap}. */
    public static String swapRaw(Path fresh, Path jar) throws IOException {
        if (!windows()) {
            Files.move(fresh, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.setLastModifiedTime(jar, java.nio.file.attribute.FileTime.from(Instant.now()));
            Files.deleteIfExists(waitingNote());
            return "updated, start the game again";
        }
        try {
            Files.move(fresh, jar, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(waitingNote());
            return "updated, start the game again";
        } catch (IOException locked) {
            return lockedSwap(fresh, jar, ProcessHandle.current().info().command());
        }
    }

    /**
     * The running jar is locked. With the java binary known, a helper from the downloaded file waits for this process to end and copies
     * it over (no restart: an empty command line). Without it nothing is started: a verified .new stays where it is with its checksum
     * note, and the next launch finds it ({@link #leftover}) and swaps it in then, also with Auto update off ({@link #swapWaiting}).
     */
    static String lockedSwap(Path fresh, Path jar, Optional<String> java) throws IOException {
        if (java.isPresent()) {
            ProcessBuilder pb = new ProcessBuilder(java.get(), "-cp", fresh.toString(), Relaunch.class.getName(),
                    String.valueOf(ProcessHandle.current().pid()), jar.toString(), fresh.toString(), System.getProperty("user.dir"));
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process helper = pb.start();
            helper.getOutputStream().close();
            Files.deleteIfExists(waitingNote());
            return CLOSE_AND_START;
        }
        if (verifiedFresh(fresh)) {
            Files.createDirectories(stateDir());
            Files.writeString(waitingNote(), fresh.getFileName() + "\n");
            Files.deleteIfExists(marker()); // nothing was swapped, so no launch has to prove itself yet: the swap arms it again
            Vantage.LOG.warn("The jar is in use and the java binary is unknown: the downloaded update waits for the next launch");
        } else {
            Vantage.LOG.warn("The jar is in use and the java binary is unknown: {} was not put in place", fresh.getFileName());
        }
        return CLOSE_AND_START;
    }

    private static Path waitingNote() {
        return stateDir().resolve("update-waiting");
    }

    /** True when a verified download was left for a later launch to put in place. */
    public static boolean swapWaiting() {
        return Files.isRegularFile(waitingNote());
    }

    /** A verified download an earlier session left next to the jar (the swap after closing did not happen): a whole jar newer than ours, or null. */
    public static Path leftover(Path jar) {
        Path fresh = jar.resolveSibling(jar.getFileName() + ".new");
        try {
            if (!Files.isRegularFile(fresh) || Files.size(fresh) < 100_000) return null;
            if (!Files.getLastModifiedTime(fresh).toInstant().isAfter(Files.getLastModifiedTime(jar).toInstant())) return null;
            try (var zip = new java.util.zip.ZipFile(fresh.toFile())) {
                if (zip.getEntry("fabric.mod.json") == null) return null;
            }
            return verifiedFresh(fresh) ? fresh : null; // a .new without its checksum note was never verified: not touched
        } catch (Exception e) {
            return null;
        }
    }

    // ---- the launch after an update: marker, title screen, rollback --------------------------------------------------------

    /** What the launch found: nothing pending, the first launch after an update (now watched), or a watched launch that never finished. */
    public enum Guard { NONE, WATCHING, ROLLBACK }

    private static Path marker() {
        return stateDir().resolve("update-pending");
    }

    private static void arm(String oldSha, String newSha) throws IOException {
        Files.createDirectories(stateDir());
        Files.writeString(marker(), "armed\n" + oldSha + "\n" + newSha + "\n");
    }

    /** The marker's phase ("armed" after a swap, "booting" once the new jar started), or "" when there is none. */
    public static String guardPhase() {
        try {
            return Files.isRegularFile(marker()) ? Files.readAllLines(marker()).get(0) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Called first thing at every launch (before the update logic). armed -> booting: this launch is the one that has to reach the
     * title screen, and says so on disk. Already booting: the last launch on the new jar never got there, so go back.
     */
    public static Guard launchGuard() {
        try {
            String phase = guardPhase();
            if (phase.isEmpty()) return Guard.NONE;
            if (phase.equals("armed")) {
                var lines = Files.readAllLines(marker());
                Files.writeString(marker(), "booting\n" + (lines.size() > 1 ? lines.get(1) : "?") + "\n" + (lines.size() > 2 ? lines.get(2) : "?") + "\n");
                return Guard.WATCHING;
            }
            return Guard.ROLLBACK;
        } catch (Exception e) {
            return Guard.NONE;
        }
    }

    private static volatile boolean titleSeen;

    /** Test hook: the title screen counts as not seen yet. */
    public static void testResetTitle() { titleSeen = false; }

    /** The title screen is up: a watched launch has proven itself, the marker goes. Cheap to call every tick. */
    public static void reachedTitle() {
        if (titleSeen) return;
        titleSeen = true;
        try {
            if (guardPhase().equals("booting")) {
                Files.deleteIfExists(marker());
                Vantage.LOG.info("First launch after the update reached the title screen: the new build is kept");
            }
        } catch (IOException e) {
            Vantage.LOG.warn("Could not clear the update marker", e);
        }
    }

    /**
     * The new build never reached the title screen: the kept previous jar is copied to {@code <jar>.new} and returned for the caller to
     * put in place (same hand-over as an update), the marker goes and a note says what happened. Null when there is no usable previous jar
     * (the marker is dropped anyway, so a broken state cannot loop).
     */
    public static Path prepareRollback(Path jar) {
        try {
            Files.deleteIfExists(marker());
            Path prev = prevJar(jar);
            if (!Files.isRegularFile(prev)) { Vantage.LOG.warn("Update did not start and there is no previous jar to go back to"); return null; }
            try (var zip = new java.util.zip.ZipFile(prev.toFile())) {
                if (zip.getEntry("fabric.mod.json") == null) { Vantage.LOG.warn("Previous jar is not a mod jar, not rolling back"); return null; }
            }
            Path fresh = jar.resolveSibling(jar.getFileName() + ".new");
            Files.copy(prev, fresh, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(stateDir().resolve("update-note.txt"), "rolled back to the previous build: the updated one did not reach the title screen\n");
            Vantage.LOG.warn("The updated build never reached the title screen: going back to the previous jar");
            return fresh;
        } catch (Exception e) {
            Vantage.LOG.warn("Rollback failed", e);
            return null;
        }
    }

    private static volatile String note = "";

    /** Reads (and removes) the note a rollback left, so the menu can say it for this session. */
    public static void loadNote() {
        try {
            Path f = stateDir().resolve("update-note.txt");
            if (Files.isRegularFile(f)) { note = Files.readString(f).trim(); Files.deleteIfExists(f); }
        } catch (Exception ignored) {}
    }

    // ---- "Verify this build" ------------------------------------------------------------------------------------------------

    private static volatile String ownSha;
    private static boolean shaStarted;

    /** The running jar's SHA-256, hashed once in the background; null until done (and in a dev run). */
    public static String ownSha() {
        Path jar = jar();
        if (jar == null) return null;
        if (!shaStarted) {
            shaStarted = true;
            Thread t = new Thread(() -> { try { ownSha = sha256(jar); } catch (Exception e) { ownSha = "unreadable"; } }, "Vantage hash");
            t.setDaemon(true);
            t.start();
        }
        return ownSha;
    }

    /** One line for Settings: short digest of the running jar and whether it matches the release digest the last check saw. */
    public static String verifyLine() {
        if (jar() == null) return "dev run, no jar to check";
        return describe(ownSha(), releaseDigest, releaseTag);
    }

    public static String describe(String own, String release, String tag) {
        if (own == null) return "hashing...";
        if (own.equals("unreadable")) return "could not read the jar";
        String shortOwn = own.substring(0, Math.min(12, own.length()));
        if (release == null) return "sha256 " + shortOwn + "  -  no release digest known (update check off, offline, or the release has none)";
        return "sha256 " + shortOwn + (own.equals(release) ? "  -  matches release " + tag : "  -  differs from release " + tag + " (a newer or local build)");
    }

    /** Status text of the update row: a rollback note first, then what the last check said. */
    public static String statusLine() {
        return note.isEmpty() ? state : state.isEmpty() ? note : note + "; " + state;
    }

    // ---- starting the game again ------------------------------------------------------------------------------------------

    /** This process's own command line: the java binary first, then every argument. Empty when the JVM cannot tell. */
    public static List<String> ownCommand() {
        if (!windows()) {
            // the JDK's ProcessHandle cuts a long /proc cmdline short (a launcher's classpath is 10 KB+); the file itself is whole
            try {
                String raw = Files.readString(Path.of("/proc/self/cmdline"), java.nio.charset.StandardCharsets.UTF_8);
                List<String> parts = new ArrayList<>(List.of(raw.split("\u0000")));
                parts.removeIf(String::isEmpty);
                if (parts.size() > 1) {
                    ProcessHandle.current().info().command().ifPresent(c -> parts.set(0, c)); // the real binary, not "java"
                    return parts;
                }
            } catch (Exception ignored) {}
        }
        ProcessHandle.Info info = ProcessHandle.current().info();
        Optional<String> cmd = info.command();
        if (cmd.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        if (info.arguments().isPresent()) {
            out.add(cmd.get());
            out.addAll(List.of(info.arguments().get()));
            return out;
        }
        // Windows only gives the whole line
        if (info.commandLine().isEmpty()) return List.of();
        List<String> parts = splitWindows(info.commandLine().get());
        if (parts.isEmpty()) return List.of();
        parts.set(0, cmd.get());
        return parts;
    }

    /**
     * Splits a Windows command line the way CreateProcess does: whitespace separates, double quotes group, 2n backslashes before a
     * quote are n backslashes, 2n+1 are n backslashes and a literal quote, other backslashes are literal.
     */
    public static List<String> splitWindows(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false, have = false;
        int i = 0, n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\\') {
                int bs = 0;
                while (i < n && line.charAt(i) == '\\') { bs++; i++; }
                if (i < n && line.charAt(i) == '"') {
                    cur.append("\\".repeat(bs / 2));
                    if (bs % 2 == 1) { cur.append('"'); i++; } else { inQuote = !inQuote; i++; }
                } else {
                    cur.append("\\".repeat(bs));
                }
                have = true;
            } else if (c == '"') {
                inQuote = !inQuote;
                have = true;
                i++;
            } else if (!inQuote && (c == ' ' || c == '\t')) {
                if (have) { out.add(cur.toString()); cur.setLength(0); have = false; }
                i++;
            } else {
                cur.append(c);
                have = true;
                i++;
            }
        }
        if (have) out.add(cur.toString());
        return out;
    }

    /**
     * Starts the game again from the given command line in this folder, marked so the new process does not update itself once
     * more, and gives it a head start: when it is still running after the wait the old process can go. False when it could not be
     * started or died right away (then the caller keeps booting what it has).
     */
    public static boolean relaunch(List<String> command, long headStartSeconds) {
        if (command.isEmpty()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(Path.of(System.getProperty("user.dir")).toFile()).inheritIO();
            pb.environment().put(RELAUNCHED, "1");
            Process p = pb.start();
            if (p.waitFor(headStartSeconds, TimeUnit.SECONDS)) {
                Vantage.LOG.warn("Relaunched game ended at once with code {}", p.exitValue());
                return false;
            }
            return true;
        } catch (Exception e) {
            Vantage.LOG.warn("Could not start the game again", e);
            return false;
        }
    }

    private Updater() {}
}
