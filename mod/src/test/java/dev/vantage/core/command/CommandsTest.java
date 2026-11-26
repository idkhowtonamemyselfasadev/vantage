package dev.vantage.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vantage.core.command.Fakes.Recorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The dispatcher: reading a line into typed arguments, and what is said when the line is wrong. */
class CommandsTest {
    private final Commands commands = Fakes.registry();
    private final Recorder out = new Recorder();

    @AfterEach
    void restorePrefix() {
        CommandLine.setPrefix('.');
        Fakes.RADAR.on = false;
        Fakes.STORAGE.on = false;
    }

    private boolean run(String line) {
        return commands.run(line, out);
    }

    @Test
    void ordinaryChatIsNotACommandAndGetsNoAnswer() {
        assertFalse(run("hello there"));
        assertFalse(run("..."));
        assertFalse(run(".5 is half"));
        assertTrue(out.lines.isEmpty());
    }

    @Test
    void aLineThatLooksLikeACommandIsAlwaysAnsweredHere() {
        String[] lines = {".toggle radar", ".toggle", ".toggle nosuch", ".frend", ".zzzz", ".go 1", ".go x y z", ".set radar range", ".say", ".toggle radar extra", ".go \"unclosed", ".go 99999999999999999999 1",
                ".go NaN 1", ".TOGGLE Radar", "  .mode  ", ".mode fast slow"};
        for (String line : lines) assertTrue(run(line), "a command attempt must be kept here: " + line);
    }

    @Test
    void aCommandRunsWithItsTypedArguments() {
        assertTrue(run(".toggle radar"));
        assertEquals("success: Radar on", out.last());
        assertTrue(Fakes.RADAR.on);
        run(".go 5 1.5 on");
        assertEquals("info: go 5 1.5 true", out.last());
        run(".go -3 2");
        assertEquals("info: go -3 2.0 null", out.last());
    }

    @Test
    void theCommandWordIsCaseInsensitiveAndAliasesWork() {
        run(".TOGGLE radar");
        assertEquals("success: Radar on", out.last());
        run(".s radar range 7");
        assertEquals("success: set", out.last());
        assertEquals("7", Fakes.RADAR.settings.get(0).value);
        Fakes.RADAR.settings.get(0).reset();
    }

    @Test
    void anUnknownCommandNamesTheClosestOneWhenOneIsClose() {
        run(".frend");
        assertEquals("error: Unknown command .frend. Did you mean .friend?", out.last());
        run(".togle radar");
        assertEquals("error: Unknown command .togle. Did you mean .toggle?", out.last());
        run(".tog");
        assertEquals("error: Unknown command .tog. Did you mean .toggle?", out.last(), "the beginning of a command is close too");
    }

    @Test
    void anUnknownCommandWithNothingCloseSendsTheUserToHelp() {
        run(".qqqqqq");
        assertEquals("error: Unknown command .qqqqqq. Type .help for the list of commands.", out.last());
    }

    @Test
    void aMissingArgumentIsNamedAndTheUsageIsShown() {
        run(".toggle");
        assertEquals("error: Missing <module>: the module. Usage: .toggle <module>", out.last());
        run(".set radar");
        assertEquals("error: Missing <setting>: the setting. Usage: .set <module> <setting> <value...>", out.last());
        run(".set radar range");
        assertTrue(out.last().startsWith("error: Missing <value>"), out.last());
        run(".say bob");
        assertTrue(out.last().startsWith("error: Missing <what>"), out.last());
    }

    @Test
    void aWrongArgumentSaysWhichOneWhatWasExpectedAndTheUsage() {
        run(".go abc 1");
        assertEquals("error: Wrong <x>: 'abc' is not a whole number. Expected a whole number from -100 to 100. Usage: .go <x> <speed> [fast]", out.last());
        run(".go 1 fast");
        assertTrue(out.last().startsWith("error: Wrong <speed>: 'fast' is not a number. Expected a number from 0.5 to 3."), out.last());
        run(".go 1 1 maybe");
        assertTrue(out.last().startsWith("error: Wrong <fast>: 'maybe' is not on or off. Expected on or off."), out.last());
        run(".mode quick");
        assertTrue(out.last().contains("Expected one of: fast, slow, steady"), out.last());
        run(".set radar range abc");
        assertTrue(out.last().startsWith("error: Wrong <value>: 'abc' is not a whole number. Expected a whole number from 1 to 10."), out.last());
        run(".toggle nosuch");
        assertTrue(out.last().startsWith("error: Wrong <module>: there is no module called 'nosuch'"), out.last());
    }

    @Test
    void aSurplusArgumentIsReported() {
        run(".toggle radar extra");
        assertEquals("error: Too many arguments: 'extra' is not expected. Usage: .toggle <module>", out.last());
        assertFalse(Fakes.RADAR.on, "nothing ran");
    }

    @Test
    void aRestArgumentTakesTheWholeLineAndDropsSurroundingQuotes() {
        run(".say bob hello there  world");
        assertEquals("info: bob: hello there  world", out.last());
        run(".say bob \"quoted text here\"");
        assertEquals("info: bob: quoted text here", out.last());
        run(".say \"big bob\" hi");
        assertEquals("info: big bob: hi", out.last(), "a quoted word keeps its space");
        run(".say \"bob hi");
        assertTrue(out.last().contains("the quote is never closed"), out.last());
    }

    @Test
    void aSettingValueIsReadByTheSettingItself() {
        run(".set radar range 11");
        assertEquals("error: too big", out.last());
        run(".set radar style arrows");
        assertEquals("success: set", out.last());
        run(".set radar style nothing");
        assertTrue(out.last().startsWith("error: Wrong <value>: 'nothing' is not one of the choices"), out.last());
        run(".set radar nosuch 1");
        assertTrue(out.last().startsWith("error: Wrong <setting>: there is no setting of Radar called 'nosuch'"), out.last());
    }

    @Test
    void aCommandThatThrowsIsReportedAndDoesNotEscape() {
        commands.register(Command.named("boom", "Fails").runs((a, r) -> { throw new IllegalStateException("bug"); }));
        assertTrue(run(".boom"));
        assertEquals("error: .boom ran into an error. The details are in the log.", out.last());
    }

    @Test
    void aCommandThatIsNotAvailableDoesNotExist() {
        boolean[] on = {false};
        commands.register(Command.named("beta", "Only sometimes").availableIf(() -> on[0]).runs((a, r) -> r.info("beta")));
        run(".beta");
        assertTrue(out.last().startsWith("error: Unknown command .beta"), out.last());
        on[0] = true;
        run(".beta");
        assertEquals("info: beta", out.last());
    }

    @Test
    void theNewPrefixDecidesWhatIsACommand() {
        CommandLine.setPrefix('!');
        assertTrue(run("!toggle radar"));
        assertTrue(Fakes.RADAR.on);
        assertFalse(run(".toggle radar"), "the dot is ordinary chat now");
        run("!go 1");
        assertTrue(out.last().contains("Usage: !go"), out.last());
    }

    @Test
    void registrationRejectsClashesAndBadDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> commands.register(Command.named("toggle", "again").runs((a, r) -> {})));
        assertThrows(IllegalArgumentException.class, () -> commands.register(Command.named("other", "x").alias("s").runs((a, r) -> {})), "alias of another command");
        assertThrows(IllegalArgumentException.class, () -> commands.register(Command.named("Bad Name", "x").runs((a, r) -> {})));
        assertThrows(IllegalArgumentException.class, () -> commands.register(Command.named("nocode", "x")));
        assertThrows(IllegalArgumentException.class, () -> commands.register(Command.named("order", "x").arg(Args.word("a", "a").optional()).arg(Args.word("b", "b")).runs((a, r) -> {})));
        assertThrows(IllegalArgumentException.class, () -> commands.register(Command.named("restfirst", "x").arg(Args.text("a", "a")).arg(Args.word("b", "b")).runs((a, r) -> {})));
    }

    @Test
    void aModuleCommandWhileItsModuleIsOffIsAnsweredByTheDispatcherAndNeverRuns() {
        boolean[] on = {false};
        int[] ran = {0};
        Commands own = new Commands();
        own.register(Command.named("scan", "needs the module").ownedBy("Demo Module", "demo-module", () -> on[0]).arg(Args.word("what", "a word").optional()).runs((a, r) -> {
            ran[0]++;
            r.info("scanned");
        }));
        own.register(Command.named("saved", "works while off").usableWhileOff().ownedBy("Demo Module", "demo-module", () -> on[0]).runs((a, r) -> r.info("listed")));
        assertTrue(own.run(".scan", out));
        assertEquals("error: Demo Module is off. Turn it on with .toggle demo-module", out.last());
        own.run(".scan one two three", out); // even a line with too many arguments gets the same answer: the module's state comes first
        assertEquals("error: Demo Module is off. Turn it on with .toggle demo-module", out.last());
        assertEquals(0, ran[0]);
        own.run(".saved", out);
        assertEquals("info: listed", out.last()); // declared usable while off
        on[0] = true;
        own.run(".scan x", out);
        assertEquals("info: scanned", out.last());
        assertEquals(1, ran[0]);
        on[0] = false;
        CommandLine.setPrefix('!');
        own.run("!scan", out);
        assertEquals("error: Demo Module is off. Turn it on with !toggle demo-module", out.last()); // the prefix the user chose
    }

    @Test
    void aCommandWithNoModuleIsNeverAnsweredAsOff() {
        Commands own = new Commands();
        own.register(Command.named("plain", "no owner").runs((a, r) -> r.info("ok")));
        own.run(".plain", out);
        assertEquals("info: ok", out.last());
    }
}
