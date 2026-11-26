package dev.vantage.core;

import java.nio.file.Files;
import java.nio.file.Path;

import dev.vantage.module.client.ClickGuiModule;

/**
 * Presence asks first (beta). The answer to the one-time question lives in config/vantage/presence-answer ("yes" or "no"); no file
 * means the question was never answered, and then Presence sends nothing at all, also on installs whose config still says
 * Presence = on from the old default. The Settings switch is the same answer: flipping it writes it.
 */
public final class PresenceConsent {
    public enum Answer { NONE, YES, NO }

    /** The version of the consent text. A "yes" given to an older text is not a "yes" to this one (the person is asked again). */
    public static final int VERSION = 2;
    /** Version 2 added this sentence: the owner of Vantage can see who is online, and where by server hash. */
    public static final String OWNER_SENTENCE = "The owner of Vantage can see which accounts are online and on which server hash.";

    private static Answer answer;
    private static Answer override;
    private static boolean silent;
    private static boolean askedThisLaunch;

    private PresenceConsent() {}

    private static Path file() {
        return Config.dir().resolve("presence-answer");
    }

    /** Holds the version of the text the answer was given to. No file = an answer from before versions existed (version 1). */
    private static Path versionFile() {
        return Config.dir().resolve("presence-answer-version");
    }

    private static int storedVersion() {
        try {
            if (Files.isRegularFile(versionFile())) return Integer.parseInt(Files.readString(versionFile()).trim());
        } catch (Exception e) {
            // unreadable = treated as the oldest version
        }
        return 1;
    }

    public static Answer answer() {
        if (override != null) return override;
        if (answer == null) {
            answer = Answer.NONE;
            try {
                if (Files.isRegularFile(file())) {
                    String s = Files.readString(file()).trim();
                    answer = s.equals("yes") ? Answer.YES : s.equals("no") ? Answer.NO : Answer.NONE;
                    // a yes to an older text does not count: ask again, send nothing until then. A no stays a no (nothing is sent either way).
                    if (answer == Answer.YES && storedVersion() < VERSION) answer = Answer.NONE;
                }
            } catch (Exception e) {
                Vantage.LOG.warn("Could not read the presence answer, treating it as unanswered", e);
            }
        }
        return answer;
    }

    /** True once the user said yes: the only state in which anything is sent. */
    public static boolean yes() {
        return answer() == Answer.YES;
    }

    public static boolean needsAsk() {
        return answer() == Answer.NONE;
    }

    /** Records the answer on disk and makes the Settings switch agree with it. */
    public static void set(Answer a) {
        answer = a;
        if (override != null) override = a;
        try {
            Files.createDirectories(file().getParent());
            if (a == Answer.NONE) { Files.deleteIfExists(file()); Files.deleteIfExists(versionFile()); }
            else {
                Files.writeString(versionFile(), VERSION + "\n");
                Files.writeString(file(), a == Answer.YES ? "yes\n" : "no\n");
            }
        } catch (Exception e) {
            Vantage.LOG.warn("Could not save the presence answer", e);
        }
        ClickGuiModule g = Modules.get(ClickGuiModule.class);
        if (g != null && a != Answer.NONE) { silent = true; g.presence.set(a == Answer.YES); silent = false; }
        Vantage.LOG.info("Presence answer: {}", a);
    }

    /** The Settings switch changed: that is an answer too. (Not while set() or the start-up check change it themselves.) */
    public static void onToggle(boolean on) {
        if (silent) return;
        set(on ? Answer.YES : Answer.NO);
    }

    /**
     * At start: without an answer the switch must not say "on" (old installs stored the old default), so it is put to off without
     * counting as an answer. Presence.enabled() is false in that state anyway.
     */
    public static void applyAtStart() {
        ClickGuiModule g = Modules.get(ClickGuiModule.class);
        if (g == null) return;
        if (needsAsk()) { silent = true; g.presence.set(false); silent = false; }
        else if (answer() == Answer.NO && g.presence.on()) { silent = true; g.presence.set(false); silent = false; }
        else if (answer() == Answer.YES && !g.presence.on()) { silent = true; g.presence.set(true); silent = false; }
    }

    /**
     * On the title screen: puts the question once per launch while it is unanswered. {@code open} shows a screen (the title screen is
     * its parent). True when it was asked now.
     */
    public static boolean askOnTitle(net.minecraft.client.gui.screens.Screen current, java.util.function.Consumer<net.minecraft.client.gui.screens.Screen> open) {
        if (!(current instanceof net.minecraft.client.gui.screens.TitleScreen) || !needsAsk() || askedThisLaunch || Terms.off()) return false; // while the terms gate is up the terms screen goes first
        askedThisLaunch = true;
        open.accept(new dev.vantage.gui.PresenceAskScreen(current));
        return true;
    }

    /** Has the question been put to the user during this launch (so it is not put again until the next launch)? */
    public static boolean askedThisLaunch() {
        return askedThisLaunch;
    }

    public static void markAsked() {
        askedThisLaunch = true;
    }

    /** What Settings shows. */
    public static String statusLine() {
        return switch (answer()) {
            case NONE -> "not answered yet, nothing is sent";
            case YES -> "yes: UUID and a server hash (or menu) are sent once a minute; the owner of Vantage can see them";
            case NO -> "no: nothing is sent";
        };
    }

    // ---- rig -----------------------------------------------------------------------------------------------------------------

    /** Test hook: forces an answer without touching the file (null = the real state again). */
    public static void testAnswer(Answer a) {
        override = a;
    }

    /** Test hook: forget what was read, read the file again next time. */
    public static void testReload() {
        answer = null;
        override = null;
        askedThisLaunch = false;
    }
}
