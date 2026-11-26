package dev.vantage.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Every argument type: what it accepts, and the reason in words for what it refuses. */
class ArgsTest {
    private static final Parsed NOTHING = new Parsed();

    @Test
    void aWordTakesAnythingButNothing() {
        assertEquals("abc", Args.word("w", "d").parse("abc", NOTHING).value());
        assertFalse(Args.word("w", "d").parse("", NOTHING).ok());
    }

    @Test
    void aWholeNumberIsCheckedAgainstItsRange() {
        Arg<Integer> a = Args.integer("n", "d", 1, 10);
        assertEquals(1, a.parse("1", NOTHING).value());
        assertEquals(10, a.parse("10", NOTHING).value());
        assertEquals(5, a.parse("+5", NOTHING).value());
        assertEquals("0 is below the smallest allowed value, 1", a.parse("0", NOTHING).problem());
        assertEquals("11 is above the largest allowed value, 10", a.parse("11", NOTHING).problem());
        assertEquals("'1.5' is not a whole number", a.parse("1.5", NOTHING).problem());
        assertEquals("'abc' is not a whole number", a.parse("abc", NOTHING).problem());
        assertEquals("'NaN' is not a whole number", a.parse("NaN", NOTHING).problem());
        assertEquals("'99999999999999999999' is far too large", a.parse("99999999999999999999", NOTHING).problem());
        assertEquals("a whole number from 1 to 10", a.expects());
    }

    @Test
    void aDecimalRefusesNaNInfinityAndExponentsSoThatRangeChecksCannotBeSlippedPast() {
        Arg<Double> a = Args.decimal("d", "d", 50, 25000);
        assertEquals(100.5, a.parse("100.5", NOTHING).value());
        assertEquals(60.0, a.parse("60", NOTHING).value());
        assertEquals(50.5, a.parse("+50.5", NOTHING).value());
        assertEquals(0.5, Args.decimal("d", "d", 0, 1).parse(".5", NOTHING).value());
        for (String bad : List.of("NaN", "nan", "Infinity", "-Infinity", "1e3", "0x10", "1f", "1d", "", "-", ".", "1,5", "1.2.3"))
            assertFalse(Args.decimal("d", "d", -1e9, 1e9).parse(bad, NOTHING).ok(), bad);
        assertEquals("49 is below the smallest allowed value, 50", a.parse("49", NOTHING).problem());
        assertEquals("30000 is above the largest allowed value, 25000", a.parse("30000", NOTHING).problem());
        assertEquals("a number from 50 to 25000", a.expects());
        assertEquals("a number from 0.5 to 3.5", Args.decimal("d", "d", 0.5, 3.5).expects());
    }

    @Test
    void theNumberHelperUsedByOlderCommandsRefusesNaNToo() {
        assertEquals(12.5, Args.number("12.5"));
        assertThrows(NumberFormatException.class, () -> Args.number("NaN"));
        assertThrows(NumberFormatException.class, () -> Args.number("Infinity"));
        assertThrows(NumberFormatException.class, () -> Args.number("1e400"));
        assertThrows(NumberFormatException.class, () -> Args.number("9".repeat(400)));
    }

    @Test
    void onOffTakesTheUsualWordsInAnyCase() {
        Arg<Boolean> a = Args.onOff("f", "d");
        for (String t : List.of("on", "ON", "true", "Yes")) assertEquals(true, a.parse(t, NOTHING).value(), t);
        for (String t : List.of("off", "Off", "false", "no")) assertEquals(false, a.parse(t, NOTHING).value(), t);
        assertEquals("'maybe' is not on or off", a.parse("maybe", NOTHING).problem());
        assertEquals("'1' is not on or off", a.parse("1", NOTHING).problem());
    }

    @Test
    void oneOfGivesTheListedSpellingAndNamesTheChoicesWhenItFails() {
        Arg<String> a = Args.oneOf("k", "d", List.of("Dots", "Arrows"));
        assertEquals("Arrows", a.parse("ARROWS", NOTHING).value());
        assertFalse(a.parse("Lines", NOTHING).ok());
        assertEquals("one of: Dots, Arrows", a.expects());
    }

    @Test
    void aRestArgumentTakesEverythingAndOffersItsFirstWords() {
        Arg<String> a = Args.text("t", "d", List.of("start", "stop", "here"));
        assertTrue(a.rest());
        assertEquals("a b  c", a.parse("a b  c", NOTHING).value());
        assertEquals(List.of("start", "stop"), a.suggest("st", NOTHING));
        assertEquals(List.of(), a.suggest("start now", NOTHING), "past the first word nothing is offered");
    }

    @Test
    void aModuleIsFoundByIdOrByNameWithoutSpacesInAnyCase() {
        Arg<ModuleRef> a = Args.module("m", "d", Fakes.SOURCE);
        assertSame(Fakes.STORAGE, a.parse("storage-esp", NOTHING));
        assertSame(Fakes.STORAGE, a.parse("storageesp", NOTHING));
        assertSame(Fakes.STORAGE, a.parse("StorageESP", NOTHING));
        assertSame(Fakes.STORAGE, a.parse("STORAGEESP", NOTHING));
        assertSame(Fakes.STORAGE, a.parse("Storage ESP", NOTHING));
        assertSame(Fakes.RADAR, a.parse("Radar", NOTHING));
        assertEquals("there is no module called 'radr'. Did you mean radar?", a.parse("radr", NOTHING).problem());
        assertEquals("there is no module called 'zzzzzz'", a.parse("zzzzzz", NOTHING).problem());
    }

    private static void assertSame(Object expected, Parse<?> p) {
        assertTrue(p.ok(), p.problem());
        assertTrue(expected == p.value(), "found " + p.value());
    }

    @Test
    void aSettingIsFoundInTheModuleNamedBeforeIt() {
        Arg<SettingRef> a = Args.setting("s", "d", 0);
        Parsed before = new Parsed();
        assertEquals("no module was named before it", a.parse("range", before).problem());
        before.add(Fakes.RADAR);
        assertEquals("range", a.parse("Range", before).value().id());
        assertEquals("there is no setting of Radar called 'fill'", a.parse("fill", before).problem());
    }

    @Test
    void twoSettingsWithTheSameSqueezedNameAreAnErrorThatListsBothIds() {
        Arg<SettingRef> a = Args.setting("s", "d", 0);
        Parsed before = new Parsed();
        before.add(Fakes.STORAGE);
        Parse<SettingRef> p = a.parse("boxcolour", before);
        assertFalse(p.ok());
        assertTrue(p.problem().contains("box-colour") && p.problem().contains("box_colour") && p.problem().contains("more than one setting of Storage ESP"), p.problem());
        assertEquals("box-colour", a.parse("box-colour", before).value().id(), "the id always picks one");
        assertEquals("box_colour", a.parse("box_colour", before).value().id());
        assertEquals("fill", a.parse("FILL", before).value().id());
    }

    @Test
    void aValueIsCheckedByTheSettingItIsFor() {
        Arg<String> a = Args.value("v", "d", 1);
        Parsed before = new Parsed();
        before.add(Fakes.RADAR);
        before.add(Fakes.RADAR.settings.get(0));
        assertEquals("7", a.parse("7", before).value());
        assertEquals("'x' is not a whole number", a.parse("x", before).problem());
        assertEquals("a whole number from 1 to 10", a.expects(before));
        assertTrue(a.rest());
    }
}
