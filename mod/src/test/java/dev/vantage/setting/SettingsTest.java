package dev.vantage.setting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.vantage.core.Category;
import dev.vantage.core.Module;
import org.junit.jupiter.api.Test;

/** Validation, defaults, conditions and change notification: one path for every way a value can arrive. */
class SettingsTest {
    @Test
    void aNumberIsSnappedToItsStepAndKeptInRangeWhicheverWayItArrives() {
        NumberSetting n = new NumberSetting("Range", "", 3.0, 0.5, 10, 0.5);
        n.set(4.26);
        assertEquals(4.5, n.get());
        n.set(99.0);
        assertEquals(10.0, n.get());
        assertNull(n.tryLoad(new JsonPrimitive(7.3)));
        assertEquals(7.5, n.get(), "a loaded value is snapped too (it was only clamped before)");
        assertNull(n.tryLoad(new JsonPrimitive(-5)));
        assertEquals(0.5, n.get());
    }

    @Test
    void aNumberRefusesWhatIsNotANumberAndKeepsItsValue() {
        NumberSetting n = new NumberSetting("Range", "", 3.0, 0.5, 10, 0.5);
        assertNotNull(n.trySet(Double.NaN));
        assertNotNull(n.trySet(Double.POSITIVE_INFINITY));
        n.set(null); // used to throw
        assertEquals("expected a number", n.tryLoad(new JsonPrimitive("abc")));
        assertEquals("expected a number", n.tryLoad(new JsonObject()));
        assertEquals(3.0, n.get());
        assertThrows(IllegalArgumentException.class, () -> new NumberSetting("Bad", "", 1, 0, 2, 0));
    }

    @Test
    void anUntouchedOldDefaultGetsTheNewDefault() {
        NumberSetting n = new NumberSetting("Range", "", 6.0, 0, 10, 1).legacy(4.0);
        assertNull(n.tryLoad(new JsonPrimitive(4.0)));
        assertEquals(6.0, n.get());
        assertNull(n.tryLoad(new JsonPrimitive(5.0)));
        assertEquals(5.0, n.get());
    }

    @Test
    void wholeNumbers() {
        IntSetting i = new IntSetting("Count", "", 5, 1, 9);
        i.set(40);
        assertEquals(9, i.i());
        assertNull(i.tryLoad(new JsonPrimitive(3)));
        assertEquals(3, i.i());
        assertEquals("expected a whole number", i.tryLoad(new JsonPrimitive(2.5)));
        assertEquals("expected a whole number", i.tryLoad(new JsonPrimitive("x")));
        assertEquals(3, i.i());
        assertEquals(new JsonPrimitive(3), i.save());
        assertThrows(IllegalArgumentException.class, () -> new IntSetting("Bad", "", 10, 1, 9));
    }

    @Test
    void aChoiceOnlyTakesWhatIsOnItsList() {
        ModeSetting m = new ModeSetting("Mode", "", "A", "A", "B", "C");
        assertNotNull(m.trySet("Z"));
        assertEquals("A", m.get());
        m.set("B");
        m.cycle(1);
        assertEquals("C", m.get());
        m.cycle(1);
        assertEquals("A", m.get());
        assertNotNull(m.tryLoad(new JsonPrimitive("nope")));
        assertNull(m.tryLoad(new JsonPrimitive("C")));
        assertTrue(m.is("C"));
    }

    @Test
    void textIsOneLineAndNotLongerThanItsLimit() {
        TextSetting t = new TextSetting("Text", "", "");
        t.set("a\nb");
        assertEquals("a b", t.get());
        assertNull(t.tryLoad(new JsonPrimitive("x".repeat(500))));
        assertEquals(TextSetting.MAX, t.get().length(), "the limit was declared but never enforced before");
        assertNotNull(t.tryLoad(new JsonArray()));
    }

    @Test
    void booleansAndColours() {
        BoolSetting b = new BoolSetting("On", "", false);
        assertEquals("expected true or false", b.tryLoad(new JsonPrimitive("yes")));
        assertNull(b.tryLoad(new JsonPrimitive(true)));
        assertTrue(b.on());
        ColorSetting c = new ColorSetting("Colour", "", 0xFF112233);
        assertEquals(new JsonPrimitive("#FF112233"), c.save());
        assertNull(c.tryLoad(new JsonPrimitive("#80ABCDEF")));
        assertEquals(0x80ABCDEF, c.argb());
        assertNotNull(c.tryLoad(new JsonPrimitive("#GGGGGGGG")));
        assertNotNull(c.tryLoad(new JsonPrimitive("#123456789")));
        assertEquals(0x80ABCDEF, c.argb());
        assertEquals(0x40ABCDEF, c.withAlpha(0x40));
    }

    @Test
    void keys() {
        KeySetting k = new KeySetting("Key", "", KeySetting.NONE);
        assertFalse(k.isSet());
        k.set(82); // R
        assertTrue(k.isSet());
        assertFalse(k.isMouse());
        k.set(KeySetting.mouse(1));
        assertTrue(k.isMouse());
        assertEquals(1, k.button());
        assertNotNull(k.trySet(5), "5 is not a GLFW key code");
        assertNotNull(k.trySet(-200));
        assertEquals(-101, k.get());
        assertNull(k.tryLoad(new JsonPrimitive(-1)));
        assertFalse(k.isSet());
        assertNotNull(k.tryLoad(new JsonPrimitive("R")));
    }

    @Test
    void aMissingSavedValueKeepsTheDefaultAndIsNotAnError() {
        NumberSetting n = new NumberSetting("Range", "", 3.0, 0, 10, 1);
        assertNull(n.tryLoad(null));
        assertNull(n.tryLoad(JsonNull.INSTANCE));
        assertEquals(3.0, n.get());
        assertTrue(n.isDefault());
        n.set(4.0);
        assertFalse(n.isDefault());
        n.reset();
        assertTrue(n.isDefault());
    }

    @Test
    void idsVisibilityAndTheAdvancedFlag() {
        BoolSetting hint = new BoolSetting("Show hint", "", false);
        NumberSetting budget = new NumberSetting("Scan budget (ms)", "", 3.0, 0.5, 10, 0.5).visibleIf(hint::on);
        assertEquals("scan-budget-ms", budget.id());
        assertFalse(budget.visible());
        hint.set(true);
        assertTrue(budget.visible());
        assertFalse(budget.isAdvanced());
        NumberSetting adv = new NumberSetting("Fine tuning", "", 1, 0, 2, 1).advanced();
        assertTrue(adv.isAdvanced());
        NumberSetting renamed = new NumberSetting("Brand new name", "", 1, 0, 2, 1).withId("old-name");
        assertEquals("old-name", renamed.id());
        assertThrows(IllegalArgumentException.class, () -> new BoolSetting("X", "", false).withId("Not An Id"));
    }

    static class Owner extends Module {
        final BoolSetting flag = add(new BoolSetting("Flag", "", false));
        final NumberSetting num = add(new NumberSetting("Num", "", 1, 0, 9, 1));
        final List<String> seen = new ArrayList<>();

        Owner() {
            super("Settings Owner", "test", Category.CLIENT);
        }

        @Override
        protected void onSettingChanged(Setting<?> s) {
            seen.add(s.id());
        }
    }

    @Test
    void listenersAddUpAndTheModuleHearsEveryChange() {
        Owner o = new Owner();
        int[] a = {0}, b = {0};
        o.flag.onChange(() -> a[0]++);
        o.flag.onChange(() -> b[0]++); // used to replace the first listener
        o.flag.set(true);
        assertEquals(1, a[0]);
        assertEquals(1, b[0]);
        o.flag.set(true); // no change: nobody is called
        assertEquals(1, a[0]);
        o.num.set(5.0);
        assertNull(o.num.tryLoad(new JsonPrimitive(7)));
        assertEquals(List.of("flag", "num", "num"), o.seen, "set and load both reach the module");
        assertEquals(1, a[0], "loading does not run onChange listeners");
        assertNotNull(o.num.tryLoad(new JsonPrimitive("bad")));
        assertEquals(3, o.seen.size(), "a refused value notifies nobody");
        assertEquals(o, o.flag.owner());
    }

    static class Twins extends Module {
        Twins() {
            super("Settings Twins", "test", Category.CLIENT);
            add(new BoolSetting("Alert (chat)", "", false));
            add(new Header("Alert chat")); // a header may share the name
            add(new BoolSetting("Alert chat", "", false)); // a saved setting may not
        }
    }

    @Test
    void twoSavedSettingsOfOneModuleCannotShareAnId() {
        assertThrows(IllegalStateException.class, Twins::new);
    }
}
