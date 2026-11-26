package dev.vantage.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Windows with the jar locked and the java binary unknown: nothing is started, the verified .new stays, the status says to close and
 * start the game again, and a later launch finds the file and can swap it in.
 */
class UpdaterLockedTest {
    @AfterEach
    void realStateDir() {
        Updater.testDir(null);
    }

    private static void modJar(Path f, int seed) throws IOException {
        byte[] filler = new byte[200_000]; // leftover() wants a whole jar, not a stub
        new Random(seed).nextBytes(filler);
        try (var zip = new ZipOutputStream(Files.newOutputStream(f))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write("{}".getBytes());
            zip.putNextEntry(new ZipEntry("filler"));
            zip.write(filler);
        }
    }

    /** An old jar and a newer download next to it that passed its checksum. */
    private static Path[] downloaded(Path dir) throws IOException {
        Updater.testDir(dir.resolve("state"));
        Path jar = dir.resolve("vantage.jar"), fresh = dir.resolve("vantage.jar.new");
        modJar(jar, 1);
        Files.setLastModifiedTime(jar, FileTime.from(Instant.now().minusSeconds(3600)));
        modJar(fresh, 2);
        assertEquals(fresh, Updater.accept(fresh, Updater.sha256(fresh)));
        return new Path[] {jar, fresh};
    }

    @Test
    void unknownJavaLeavesTheVerifiedDownloadForTheNextLaunch(@TempDir Path dir) throws Exception {
        Path[] p = downloaded(dir);
        Path jar = p[0], fresh = p[1];
        byte[] old = Files.readAllBytes(jar), update = Files.readAllBytes(fresh);

        String status = Updater.swap(fresh, jar, (f, j) -> Updater.lockedSwap(f, j, Optional.empty()));

        assertEquals(Updater.CLOSE_AND_START, status);
        assertTrue(status.contains("close the game") && status.contains("start it again"), status);
        assertArrayEquals(old, Files.readAllBytes(jar), "the jar in use is not touched");
        assertArrayEquals(update, Files.readAllBytes(fresh), "the download stays");
        assertTrue(Files.exists(Updater.sidecar(fresh)), "with its checksum note");
        assertTrue(Updater.swapWaiting());
        assertEquals("", Updater.guardPhase(), "nothing was swapped, so no launch is watched for it");

        // the next launch, as PreLaunch does it
        assertEquals(Updater.Guard.NONE, Updater.launchGuard());
        assertEquals(fresh, Updater.leftover(jar));
    }

    @Test
    void theRealSwapClearsTheWaitingNote(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(Updater.windows());
        Path[] p = downloaded(dir);
        Updater.swap(p[1], p[0], (f, j) -> Updater.lockedSwap(f, j, Optional.empty()));
        byte[] update = Files.readAllBytes(p[1]);
        Updater.swap(Updater.leftover(p[0]), p[0]);
        assertArrayEquals(update, Files.readAllBytes(p[0]));
        assertFalse(Updater.swapWaiting());
        assertFalse(Files.exists(Updater.sidecar(p[1])));
    }

    @Test
    void anUnverifiedFileIsNotLeftAsWaiting(@TempDir Path dir) throws Exception {
        Path[] p = downloaded(dir);
        Files.delete(Updater.sidecar(p[1]));
        Updater.lockedSwap(p[1], p[0], Optional.empty());
        assertFalse(Updater.swapWaiting());
        assertEquals(null, Updater.leftover(p[0]));
    }

    @Test
    void cleaningUpALeftoverDropsTheWaitingNote(@TempDir Path dir) throws Exception {
        Path[] p = downloaded(dir);
        Updater.lockedSwap(p[1], p[0], Optional.empty());
        Updater.cleanStale(p[0]);
        assertFalse(Updater.swapWaiting());
        assertFalse(Files.exists(p[1]));
    }
}
