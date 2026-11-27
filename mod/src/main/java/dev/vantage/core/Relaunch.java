package dev.vantage.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows half of the auto update, a plain main with no Minecraft or Fabric in it: started by {@link PreLaunch} as
 * {@code java -cp <jar>.new dev.vantage.core.Relaunch <pid> <jar> <jar.new> <folder>} with the game's command line on stdin, one
 * part per line. Waits for the game process to end (it holds the jar locked), copies the new file over the jar and starts the game
 * again in its folder. Running from the .new file is what keeps that file out of the lock; the next launch deletes it.
 */
public final class Relaunch {
    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path jar = Path.of(args[1]), fresh = Path.of(args[2]);
        List<String> cmd = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null;) cmd.add(line);
        }
        // no time limit: a session can run for hours, and a helper that gives up while the jar is still locked loses the update
        ProcessHandle.of(pid).ifPresent(p -> {
            try { p.onExit().get(); } catch (Exception ignored) {}
        });
        for (int i = 0; i < 60; i++) { // the lock can outlive the process by a moment
            try {
                Files.copy(fresh, jar, StandardCopyOption.REPLACE_EXISTING);
                break;
            } catch (Exception e) {
                Thread.sleep(500);
            }
        }
        if (cmd.isEmpty()) return;
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(Path.of(args[3]).toFile()).inheritIO();
        pb.environment().put(Updater.RELAUNCHED, "1");
        pb.start();
    }
}
