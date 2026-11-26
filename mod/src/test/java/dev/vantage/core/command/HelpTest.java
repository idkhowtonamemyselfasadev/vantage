package dev.vantage.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The help text, the usage lines and the edit distance behind "did you mean". */
class HelpTest {
    @AfterEach
    void restorePrefix() {
        CommandLine.setPrefix('.');
    }

    @Test
    void theListingHasOneLinePerCommandInRegistrationOrderWithItsDescription() {
        String[] lines = Help.listing(Fakes.registry()).split("\n");
        assertEquals("Commands (.help <command> explains one):", lines[0]);
        assertEquals(7, lines.length);
        assertEquals("  .toggle <module>  -  Switch a module", lines[1]);
        assertEquals("  .set <module> <setting> <value...>  -  Change a setting", lines[2]);
        assertEquals("  .go <x> <speed> [fast]  -  Go somewhere", lines[3]);
        assertEquals("  .say <who> <what...>  -  Say something", lines[4]);
        assertEquals("  .mode [kind]  -  Pick one", lines[5]);
        assertEquals("  .friend  -  A friend", lines[6]);
    }

    @Test
    void aCommandThatIsNotAvailableIsNotListed() {
        Commands c = Fakes.registry();
        c.register(Command.named("hidden", "Hidden").availableIf(() -> false).runs((a, r) -> {}));
        assertFalse(Help.listing(c).contains("hidden"));
    }

    @Test
    void helpForOneCommandHasUsageAliasesArgumentsAndNotes() {
        Command set = Fakes.registry().find("set");
        String text = Help.explain(set);
        assertTrue(text.startsWith(".set <module> <setting> <value...>\n  Change a setting\n  also: .s\n"), text);
        assertTrue(text.contains("<module>: the module (a module: its id (storage-esp) or its name without spaces (StorageESP))"), text);
        assertTrue(text.contains("<setting>: the setting (a setting of that module: its id or its name without spaces)"), text);
        assertTrue(text.contains("Names are written as ids or without spaces."), text);
    }

    @Test
    void theUsageUsesThePrefixInForce() {
        CommandLine.setPrefix('!');
        assertEquals("!toggle <module>", Fakes.registry().find("toggle").usage(CommandLine.prefix()));
        assertTrue(Help.listing(Fakes.registry()).startsWith("Commands (!help"));
    }

    @Test
    void editDistanceAndClosestWord() {
        assertEquals(0, Names.distance("abc", "abc"));
        assertEquals(1, Names.distance("frend", "friend"));
        assertEquals(3, Names.distance("kitten", "sitting"));
        assertEquals("friend", Names.closest("frend", java.util.List.of("toggle", "friend")));
        assertEquals(null, Names.closest("zzzzzz", java.util.List.of("toggle", "friend")));
        assertEquals(null, Names.closest("x", java.util.List.of("toggle", "friend")), "one letter is not close to anything");
    }
}
