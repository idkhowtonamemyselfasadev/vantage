package dev.vantage.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The terms gate. Until the user has confirmed the terms (for this version of them) Vantage is OFF for the launch: {@link #off()} is
 * true, and the narrow hooks that check it (the module switch, the event bus, keys, commands, HUD, network, config writes) do nothing.
 * Reject = stay off, nothing stored, asked again next launch. Confirm = remembered in config/vantage/terms-answer, and also the yes
 * to Presence the terms text asks for, and (once, marker file auto-update-default) Auto update on.
 *
 * The decision from the stored files is the pure function {@link #confirmed}; the rest is one static flag. The rigs (any of the
 * -Dvantage.ui / prodtest / shot ... properties) are exempt, so that their checks run unchanged; the "termsgate" UiTest group turns that off.
 */
public final class Terms {
    /**
     * The version of the terms text. A confirm given to an older text does not count. Was the Presence consent version (2) up to 1.0.6;
     * 3 (1.0.7) adds "Vantage updates itself" and asks everybody once more. Presence keeps its own version number.
     */
    public static final int VERSION = 3;

    /** Remembers that the one-time "Auto update on" of the 1.0.7 terms was done; after that the setting is the user's. */
    static final String AUTO_MARKER = "auto-update-default";

    /** Rig properties that exempt a launch from the gate (the rigs are not users and must not stop at a question). */
    static final String[] RIG_PROPERTIES = {"vantage.ui", "vantage.prodtest", "vantage.shot", "vantage.video", "vantage.perf", "vantage.menu",
            "vantage.promo", "vantage.seedtest", "vantage.soundtest"};

    private static volatile boolean gate;
    private static Boolean exemptOverride;
    private static boolean askedThisLaunch;

    private Terms() {}

    // ---- pure logic -------------------------------------------------------------------------------------------------------------

    /** The version in a stored version file ("2\n"), or {@code fallback} when it is absent or not a number. */
    public static int parseVersion(String text, int fallback) {
        if (text == null) return fallback;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Is the stored answer a confirm of the current terms? {@code termsFile} is the content of terms-answer (null = no file): "confirmed 2".
     * {@code presenceAnswer} / {@code presenceVersion} are the contents of the older presence-answer files: a "yes" given to version 2 or
     * later counts as a confirm (that text said the same thing); a "yes" without a version (version 1) and any "no" do not.
     */
    public static boolean confirmed(String termsFile, String presenceAnswer, String presenceVersion) {
        if (termsFile != null) {
            String t = termsFile.trim();
            if (t.startsWith("confirmed ") && parseVersion(t.substring("confirmed ".length()), 0) >= VERSION) return true;
        }
        return presenceAnswer != null && presenceAnswer.trim().equals("yes") && parseVersion(presenceVersion, 1) >= VERSION;
    }

    /** Is Confirm still to switch Auto update on once? Only when the marker file does not exist yet (null = no file). */
    public static boolean autoSwitchPending(String marker) {
        return marker == null;
    }

    /**
     * Reads the stored answer files in {@code dir} (config/vantage) with no game classes: true when they confirm the current terms.
     * PreLaunch uses it, because it runs before the gate is initialised and must not fetch anything for an unconfirmed install.
     */
    public static boolean confirmedOnDisk(Path dir) {
        return confirmed(read(dir.resolve("terms-answer")), read(dir.resolve("presence-answer")), read(dir.resolve("presence-answer-version")));
    }

    /** The decision at launch: the gate is up unless the launch is exempt or the stored answer confirms. */
    public static boolean gateUp(boolean exempt, boolean confirmed) {
        return !exempt && !confirmed;
    }

    // ---- state ------------------------------------------------------------------------------------------------------------------

    /** True while Vantage must do nothing: the terms are not confirmed (rejected, or not answered yet) on a non-rig launch. */
    public static boolean off() {
        return gate;
    }

    private static boolean exempt() {
        if (exemptOverride != null) return exemptOverride;
        if (!System.getProperty("vantage.prodtest.terms", "").isEmpty()) return false; // the real-jar launch that drives the gate itself
        for (String p : RIG_PROPERTIES) if (Boolean.getBoolean(p)) return true;
        return false;
    }

    private static Path termsFile() {
        return Config.dir().resolve("terms-answer");
    }

    private static String read(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p) : null;
        } catch (Exception e) {
            return null; // unreadable = not an answer
        }
    }

    /** Reads the stored answer and puts the gate up or down. First thing at start; also what the rig calls to "relaunch". */
    public static void init() {
        Path dir = Config.dir();
        boolean ok = confirmed(read(termsFile()), read(dir.resolve("presence-answer")), read(dir.resolve("presence-answer-version")));
        gate = gateUp(exempt(), ok);
        askedThisLaunch = false;
        if (ok && read(termsFile()) == null && !exempt()) store(); // an older presence "yes" for this text version becomes a terms confirm, so a later Presence switch-off cannot un-confirm
        Vantage.LOG.info("Terms: {}", gate ? "not confirmed, Vantage is off until you confirm" : "confirmed or exempt");
    }

    private static void store() {
        try {
            Files.createDirectories(termsFile().getParent());
            Files.writeString(termsFile(), "confirmed " + VERSION + "\n");
        } catch (Exception e) {
            Vantage.LOG.warn("Could not save the terms answer", e);
        }
    }

    /** Confirm: remembered, the gate drops, Presence says yes (the terms text says so), the update check that was held back runs. */
    public static void confirm() {
        store();
        gate = false;
        PresenceConsent.set(PresenceConsent.Answer.YES);
        var gui = Modules.get(dev.vantage.module.client.ClickGuiModule.class);
        // the terms say Vantage updates itself: this Confirm is the consent, so Auto update goes on once, whatever an old config said.
        // The marker keeps it from ever happening again: a user who switches it off afterwards keeps it off.
        Path marker = Config.dir().resolve(AUTO_MARKER);
        if (autoSwitchPending(read(marker))) {
            if (gui != null) gui.auto.set(true);
            try {
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, "done\n");
            } catch (Exception e) {
                Vantage.LOG.warn("Could not save the auto update marker", e);
            }
        }
        if (gui != null && gui.updates.on()) Updater.check();
        Config.markDirty();
        Vantage.LOG.info("Terms confirmed (version {})", VERSION);
    }

    /** Reject: nothing is stored and nothing changes; Vantage stays off for this launch and the question comes again next launch. */
    public static void reject() {
        Vantage.LOG.info("Terms rejected: Vantage stays off for this launch");
    }

    /** The one line a command answers while off. */
    public static final String OFF_LINE = "Vantage is off until you confirm the terms, restart to be asked again.";

    /** Title screen, first chance each launch while the gate is up: opens the terms screen once. True when it was opened now. */
    public static boolean askOnTitle(net.minecraft.client.gui.screens.Screen current, java.util.function.Consumer<net.minecraft.client.gui.screens.Screen> open) {
        if (!gate || askedThisLaunch || !(current instanceof net.minecraft.client.gui.screens.TitleScreen)) return false;
        askedThisLaunch = true;
        open.accept(new dev.vantage.gui.TermsScreen(current));
        return true;
    }

    // ---- rig --------------------------------------------------------------------------------------------------------------------

    /** Test hook: true/false forces the rig exemption on/off (null = the properties decide); then reads the files again like a new launch. */
    public static void testRelaunch(Boolean exempt) {
        exemptOverride = exempt;
        init();
    }
}
