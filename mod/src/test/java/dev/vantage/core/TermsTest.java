package dev.vantage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TermsTest {
    @Test
    void noFilesIsNotConfirmed() {
        assertFalse(Terms.confirmed(null, null, null));
    }

    @Test
    void termsFileForThisVersionConfirms() {
        assertTrue(Terms.confirmed("confirmed " + Terms.VERSION + "\n", null, null));
        assertTrue(Terms.confirmed("confirmed " + (Terms.VERSION + 1), "no", "1")); // a newer confirm still counts for this text
    }

    @Test
    void termsFileForAnOlderVersionOrGarbageDoesNotConfirm() {
        assertFalse(Terms.confirmed("confirmed 1\n", null, null));
        assertFalse(Terms.confirmed("confirmed\n", null, null));
        assertFalse(Terms.confirmed("yes\n", null, null));
        assertFalse(Terms.confirmed("", null, null));
    }

    @Test
    void anOldPresenceYesForVersionTwoNoLongerCounts() {
        // terms version 3 (1.0.7) asks everybody once more, so the version 2 text's yes is not enough any more
        assertFalse(Terms.confirmed(null, "yes\n", "2\n"));
        assertTrue(Terms.confirmed(null, "yes", "3"));
        assertEquals(3, Terms.VERSION);
    }

    @Test
    void anOldYesWithoutVersionOrWithAnOldVersionIsAsked() {
        assertFalse(Terms.confirmed(null, "yes\n", null));
        assertFalse(Terms.confirmed(null, "yes\n", "1\n"));
        assertFalse(Terms.confirmed(null, "yes\n", "junk"));
    }

    @Test
    void anOldNoIsAsked() {
        assertFalse(Terms.confirmed(null, "no\n", "2\n"));
        assertFalse(Terms.confirmed(null, "no\n", null));
    }

    @Test
    void presenceSwitchedOffAfterAConfirmStaysConfirmed() {
        assertTrue(Terms.confirmed("confirmed 3\n", "no\n", "2\n"));
        assertFalse(Terms.confirmed("confirmed 2\n", "no\n", "2\n")); // the old terms text is not confirmed any more
    }

    @Test
    void autoUpdateIsSwitchedOnOnlyOnceNoMatterWhatTheConfigSaid() {
        assertTrue(Terms.autoSwitchPending(null)); // no marker: the first confirm of the new terms
        assertFalse(Terms.autoSwitchPending("done\n")); // marker present: the setting is the user's
        assertFalse(Terms.autoSwitchPending(""));
    }

    @Test
    void confirmedOnDiskReadsTheFilesWithoutGameClasses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        assertFalse(Terms.confirmedOnDisk(dir)); // fresh install: PreLaunch must not fetch anything
        java.nio.file.Files.writeString(dir.resolve("terms-answer"), "confirmed 2\n");
        assertFalse(Terms.confirmedOnDisk(dir));
        java.nio.file.Files.writeString(dir.resolve("terms-answer"), "confirmed 3\n");
        assertTrue(Terms.confirmedOnDisk(dir));
    }

    @Test
    void parseVersion() {
        assertEquals(2, Terms.parseVersion(" 2\n", 1));
        assertEquals(1, Terms.parseVersion(null, 1));
        assertEquals(7, Terms.parseVersion("x", 7));
    }

    @Test
    void theGateIsUpOnlyWhenNotExemptAndNotConfirmed() {
        assertTrue(Terms.gateUp(false, false));
        assertFalse(Terms.gateUp(false, true));
        assertFalse(Terms.gateUp(true, false));
        assertFalse(Terms.gateUp(true, true));
    }

    @Test
    void theGateIsDownInUnitTests() {
        assertFalse(Terms.off()); // nothing initialised it: modules, events and commands behave as before
    }
}
