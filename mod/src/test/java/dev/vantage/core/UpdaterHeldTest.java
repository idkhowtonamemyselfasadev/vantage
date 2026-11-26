package dev.vantage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pressing Update while an earlier download waits for the game to close (Windows: its helper holds the .new file) is not a failed
 * download. Here a non-empty directory stands in for the held file: deleting it fails with a FileSystemException too.
 */
class UpdaterHeldTest {
    @Test
    void heldDownloadSaysSoInsteadOfFailing(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("vantage.jar");
        Files.writeString(jar, "old");
        Path held = dir.resolve("vantage.jar.new");
        Files.createDirectory(held);
        Files.writeString(held.resolve("in-use"), "x");
        var r = new Updater.Release("v9.9.9", "http://127.0.0.1:9/never-fetched.jar", 1, Instant.now(), "vantage-9.9.9.jar", "0".repeat(64), null);
        assertNull(Updater.download(r, jar));
        assertEquals(Updater.HELD, Updater.state());
        assertTrue(Files.exists(held.resolve("in-use")), "the held file is left alone");
    }
}
