package dev.vantage.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs before Minecraft loads (Fabric's preLaunch entrypoint, no game classes yet). With Settings > Auto update on (on by default since 1.0.7, once the terms are confirmed) it asks GitHub for
 * a newer build and, when there is one, fetches it and starts the game again on the new jar: Linux swaps the file and starts the new
 * process here; Windows hands over to {@link Relaunch}, which waits for this process to close the locked jar. Offline or up to date
 * means a few hundred milliseconds and nothing else. A process this started (VANTAGE_RELAUNCHED in its environment) never does it
 * again, so a bad clock can at most cost one restart per launch.
 */
public final class PreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            run();
        } catch (Throwable t) {
            Vantage.LOG.warn("Auto update skipped", t);
        }
    }

    private static void run() throws Exception {
        Path jar = Updater.jar();
        if (jar == null) return; // dev run
        Updater.loadNote();
        // the launch after an update has to reach the title screen; when the last one on the new jar did not, go back to the kept jar
        Updater.Guard guard = Updater.launchGuard();
        if (guard == Updater.Guard.WATCHING) Vantage.LOG.info("First launch after an update: rolls back automatically if the title screen is not reached");
        if (guard == Updater.Guard.ROLLBACK) {
            Path prev = Updater.prepareRollback(jar);
            if (prev != null) handOver(prev, jar, false);
            return; // no previous jar or no restart possible: this launch keeps going, the marker is gone either way
        }
        if (System.getenv(Updater.RELAUNCHED) != null) { Updater.cleanStale(jar); return; }
        // nothing is fetched before the terms are confirmed (this runs before the in-game gate exists, so it reads the answer files itself)
        if (!Terms.confirmedOnDisk(FabricLoader.getInstance().getConfigDir().resolve("vantage"))) return;
        boolean auto = setting("Check for updates", true) && setting("Auto update", true); // same defaults as ClickGuiModule
        // with Auto update off only one thing is still done here: finishing a download that was left for this launch to put in place
        if (!auto && !Updater.swapWaiting()) return;
        // a build downloaded last time but not swapped in (Windows kept the jar locked): finish it now
        Path fresh = Updater.leftover(jar);
        if (fresh != null) {
            Vantage.LOG.info("Finishing the update downloaded last session (checksum already verified)");
        } else {
            Updater.cleanStale(jar);
            if (!auto) return;
            Updater.Release r = Updater.fetch(jar, 3);
            if (r == null) return;
            if (!r.verifiable()) { Vantage.LOG.warn("Newer build on GitHub but it carries no SHA-256: not installing it automatically ({})", Updater.state()); return; }
            Vantage.LOG.info("Newer build on GitHub ({}), fetching it before the game starts", Updater.state());
            fresh = Updater.download(r, jar);
            if (fresh == null) { Vantage.LOG.warn("Update not installed: {}", Updater.state()); return; }
        }
        handOver(fresh, jar, true);
    }

    /**
     * Puts {@code fresh} in place of the jar and starts the game again on it. {@code update}: it came from a download, so it must be
     * verified and the old jar is kept; a rollback hands over the kept jar itself. Returns when the caller keeps booting; exits the
     * process when the new build is running.
     */
    private static void handOver(Path fresh, Path jar, boolean update) throws Exception {
        List<String> cmd = Updater.ownCommand();
        Vantage.LOG.info("Own command line: {} parts, {} bytes", cmd.size(), cmd.stream().mapToInt(String::length).sum());
        if (update) Updater.prepareSwap(fresh, jar); // throws for an unverified file: nothing gets replaced
        if (Updater.windows()) {
            // no command line to start the game with again: a helper copies it over after this session, or the next launch does
            if (cmd.isEmpty()) { Vantage.LOG.info("Update: {}", Updater.swapRaw(fresh, jar)); return; }
            // the helper runs from the downloaded file, waits for this process to let go of the jar, copies it over and starts the game
            ProcessBuilder pb = new ProcessBuilder(cmd.get(0), "-cp", fresh.toString(), Relaunch.class.getName(),
                    String.valueOf(ProcessHandle.current().pid()), jar.toString(), fresh.toString(), System.getProperty("user.dir"));
            pb.environment().put(Updater.RELAUNCHED, "1");
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process helper = pb.start();
            try (var w = new java.io.OutputStreamWriter(helper.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                for (String part : cmd) w.write(part.replace("\n", " ") + "\n");
            }
            Vantage.LOG.info("Handing over to the new build, this process is done");
            System.exit(0);
        }
        Updater.swapRaw(fresh, jar);
        java.nio.file.Files.deleteIfExists(Updater.sidecar(fresh));
        if (Updater.relaunch(cmd, 25)) {
            Vantage.LOG.info("New build is running, this process is done");
            System.exit(0);
        }
        // the file is in place for next time; this launch keeps going on the classes it has
    }

    /** A ClickGUI setting straight from config.json (the game's config classes are not loaded yet); {@code def} when unset. */
    static boolean setting(String name, boolean def) {
        try {
            Path f = FabricLoader.getInstance().getConfigDir().resolve("vantage").resolve("config.json");
            if (!Files.exists(f)) return def;
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            JsonObject gui = root.getAsJsonObject("modules").getAsJsonObject("ClickGUI");
            JsonObject s = gui == null ? null : gui.getAsJsonObject("settings");
            return s == null || !s.has(name) ? def : s.get(name).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }
}
