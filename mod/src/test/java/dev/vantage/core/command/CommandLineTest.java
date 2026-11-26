package dev.vantage.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A line that looks like a client command is recognised as one, so that it can be kept away from the server. */
class CommandLineTest {
    @Test
    void aPrefixFollowedByALetterIsAnAttemptWhetherTheCommandExistsOrNot() {
        assertTrue(CommandLine.isAttempt(".friend Bob"));
        assertTrue(CommandLine.isAttempt(".frend Bob"), "a typo is still an attempt");
        assertTrue(CommandLine.isAttempt(".seed"), "a command without its argument too");
        assertTrue(CommandLine.isAttempt("  .config save"), "leading spaces do not make it chat");
        assertTrue(CommandLine.isAttempt(".Xyz"));
    }

    @Test
    void ordinaryChatThatHappensToStartWithADotStaysChat() {
        assertFalse(CommandLine.isAttempt("..."));
        assertFalse(CommandLine.isAttempt(".5 is half"));
        assertFalse(CommandLine.isAttempt(". ok"));
        assertFalse(CommandLine.isAttempt("."));
        assertFalse(CommandLine.isAttempt("hello there"));
        assertFalse(CommandLine.isAttempt("see you. bye"));
        assertFalse(CommandLine.isAttempt(""));
        assertFalse(CommandLine.isAttempt(null));
        assertFalse(CommandLine.isAttempt("/home"), "server commands are not ours");
    }

    @Test
    void theCommandWordIsLowerCaseWithoutThePrefix() {
        assertEquals("friend", CommandLine.word(".Friend Bob"));
        assertEquals("seed", CommandLine.word(".seed"));
        assertEquals("config", CommandLine.word("  .config save x"));
    }
}
