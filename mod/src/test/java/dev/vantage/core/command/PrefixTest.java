package dev.vantage.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What may be the command prefix, and that the line check follows it. */
class PrefixTest {
    @AfterEach
    void restorePrefix() {
        CommandLine.setPrefix('.');
    }

    @Test
    void theDefaultIsADot() {
        assertEquals('.', CommandLine.prefix());
    }

    @Test
    void oneCharacterThatIsNotALetterADigitASpaceOrASlashIsAccepted() {
        for (String ok : new String[] {".", "!", "-", ",", ";", "#", "$", "~", "+", "?", "@", "&", "§"}) assertNull(CommandLine.problemWith(ok), ok);
    }

    @Test
    void everythingElseIsRefusedWithAReason() {
        for (String bad : new String[] {"a", "Z", "5", " ", "/", "", "..", "ab", "\t", "\n", "ä", null}) assertNotNull(CommandLine.problemWith(bad), String.valueOf(bad));
        assertEquals("the prefix is one character", CommandLine.problemWith(""));
        assertEquals("the prefix cannot be a letter or a digit", CommandLine.problemWith("x"));
        assertEquals("the prefix cannot be a letter or a digit", CommandLine.problemWith("7"));
        assertEquals("the prefix cannot be a space", CommandLine.problemWith(" "));
        assertTrue(CommandLine.problemWith("/").contains("server command"));
    }

    @Test
    void theLineCheckFollowsThePrefix() {
        CommandLine.setPrefix('!');
        assertTrue(CommandLine.isAttempt("!help"));
        assertTrue(CommandLine.isAttempt("  !Help me"));
        assertFalse(CommandLine.isAttempt(".help"), "the dot is ordinary chat now");
        assertFalse(CommandLine.isAttempt("!!"));
        assertFalse(CommandLine.isAttempt("!5"));
        assertEquals("help", CommandLine.word("!Help me"));
    }
}
