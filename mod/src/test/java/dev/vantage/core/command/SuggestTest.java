package dev.vantage.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tab completion: what is offered at each place in a line, and where the offered word goes. */
class SuggestTest {
    private final Commands commands = Fakes.registry();

    @AfterEach
    void restorePrefix() {
        CommandLine.setPrefix('.');
    }

    private List<String> at(String line) {
        return commands.suggest(line, line.length()).items();
    }

    @Test
    void theFirstWordOffersCommandNamesThenAliases() {
        assertEquals(List.of("toggle"), at(".tog"));
        assertEquals(List.of("set", "say", "s"), at(".s"));
        assertEquals(List.of("toggle", "set", "go", "say", "mode", "friend", "s"), at("."), "every command after the bare prefix");
    }

    @Test
    void theRangeCoversExactlyTheWordUnderTheCursor() {
        Suggestions s = commands.suggest(".tog", 4);
        assertEquals(1, s.from());
        assertEquals(4, s.to());
        Suggestions m = commands.suggest(".toggle rad", 11);
        assertEquals(8, m.from());
        assertEquals(List.of("radar"), m.items());
    }

    @Test
    void everyCursorPositionOfALineGivesTheAnswerForTheWordBeforeIt() {
        String line = ".set radar style ar";
        for (int cursor = 0; cursor <= line.length(); cursor++) commands.suggest(line, cursor); // none of them may throw
        assertTrue(commands.suggest(line, 0).isEmpty(), "before the prefix");
        assertEquals(7, commands.suggest(line, 1).items().size(), "just the prefix: every command");
        assertEquals(List.of("set", "say", "s"), commands.suggest(line, 2).items());
        assertEquals(List.of("set"), commands.suggest(line, 3).items());
        assertEquals(List.of("set"), commands.suggest(line, 4).items());
        assertEquals(List.of("radar", "storage-esp"), commands.suggest(line, 5).items(), "right after the space the module word starts");
        assertEquals(List.of("radar"), commands.suggest(line, 6).items());
        assertEquals(List.of("radar"), commands.suggest(line, 10).items());
        assertEquals(List.of("range", "style"), commands.suggest(line, 11).items());
        assertEquals(List.of("style"), commands.suggest(line, 13).items());
        assertEquals(List.of("Dots", "Arrows", "Icons"), commands.suggest(line, 17).items());
        assertEquals(List.of("Arrows"), commands.suggest(line, 19).items());
        assertEquals(List.of(), commands.suggest(".set radar range ", 17).items(), "a number setting has nothing to offer");
    }

    @Test
    void onOffAndOneOfValuesAreOffered() {
        assertEquals(List.of("on", "off"), at(".go 1 1 "));
        assertEquals(List.of("on", "off"), at(".go 1 1 o"));
        assertEquals(List.of("off"), at(".go 1 1 of"));
        assertEquals(List.of("fast", "slow", "steady"), at(".mode "));
        assertEquals(List.of("slow", "steady"), at(".mode s"));
        assertEquals(List.of("steady"), at(".mode ste"));
    }

    @Test
    void aSettingIsOfferedByItsNameWithoutSpacesToo() {
        assertEquals(List.of("fill"), at(".set storageesp fi"));
        assertEquals(List.of("box-colour", "box_colour"), at(".set storage-esp box"));
        assertEquals(List.of("box-colour", "box_colour", "fill"), at(".set storageesp "));
        assertEquals(List.of("storageesp"), at(".set storagee"), "a squeezed name is offered as typed when the id does not start with it");
    }

    @Test
    void nothingIsOfferedWhenAnEarlierWordIsWrongOrTheLineIsNotACommand() {
        assertTrue(at(".set nosuch ").isEmpty());
        assertTrue(at(".nosuchcommand ").isEmpty());
        assertTrue(at("hello").isEmpty());
        assertTrue(at("/set ").isEmpty());
        assertTrue(at(".toggle radar ").isEmpty(), "no more arguments");
        assertTrue(at(".go x ").isEmpty(), "the first argument is wrong");
    }

    @Test
    void theNewPrefixIsWhatCompletionLooksFor() {
        CommandLine.setPrefix('!');
        assertEquals(List.of("toggle"), at("!tog"));
        assertTrue(at(".tog").isEmpty());
    }

    @Test
    void aCursorBeyondTheLineIsKeptInside() {
        assertEquals(List.of("toggle"), commands.suggest(".tog", 99).items());
        assertTrue(commands.suggest("", 0).isEmpty());
    }
}
