package dev.vantage.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** First-run welcome (beta): shown once on the title screen, only while beta is unlocked, and re-openable from Settings. The marker is a file in config/vantage. */
public final class Welcome {
    private Welcome() {}

    private static Path marker() {
        return Config.dir().resolve("welcome-shown");
    }

    /** True when the welcome screen should open by itself: beta unlocked and never shown before. */
    public static boolean due() {
        return Modules.betaUnlocked() && !Files.exists(marker());
    }

    public static void markShown() {
        try {
            Files.createDirectories(marker().getParent());
            Files.writeString(marker(), "shown\n");
        } catch (Exception ignored) {
            // worst case it shows once more
        }
    }

    /** Rig / "show me again": forget that it was shown. */
    public static void forget() {
        try {
            Files.deleteIfExists(marker());
        } catch (Exception ignored) {
            // nothing to do
        }
    }

    public static boolean shown() {
        return Files.exists(marker());
    }
}
