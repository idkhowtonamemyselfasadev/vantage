package dev.vantage.core.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vantage.core.command.Reply;
import org.junit.jupiter.api.Test;

/** What a user can do to an element (hide, show, move, reset), how that is saved, and the {@code hud} command that does it. */
class HudModelTest {
    private final Map<String, HudElement> els = new LinkedHashMap<>();

    private HudElement el(String id, String corner) {
        return els.computeIfAbsent(id, k -> new HudElement(k, "Title " + k, corner));
    }

    private Map<String, HudElement> fresh() {
        Map<String, HudElement> m = new LinkedHashMap<>();
        for (HudElement e : els.values()) m.put(e.id(), new HudElement(e.id(), e.title(), e.defaultCorner()));
        return m;
    }

    @Test
    void hidingAndShowingKeepsTheRestOfTheElement() {
        HudElement e = el("radar", "Top Left");
        e.stackIn("Bottom Right");
        e.setHidden(true);
        assertTrue(e.hidden());
        assertEquals("Bottom Right", e.stackCorner());
        e.setHidden(false);
        assertFalse(e.hidden());
        assertEquals("Bottom Right", e.stackCorner(), "showing does not undo a move");
    }

    @Test
    void movingToACornerChangesTheStackAndLeavesAPin() {
        HudElement e = el("radar", "Top Left");
        e.pinTo(new HudLayout.Pin(Anchor.CENTER, 3, 4));
        e.stackIn("Top Right");
        assertNull(e.pin());
        assertEquals("Top Right", e.stackCorner());
        assertTrue(e.moved());
        e.pinTo(new HudLayout.Pin(Anchor.LEFT, 6, 0));
        assertNull(e.stackCorner(), "a pin replaces the other corner");
    }

    @Test
    void resetPutsItBackAndShowsIt() {
        HudElement e = el("radar", "Top Left");
        e.stackIn("Bottom Left");
        e.setHidden(true);
        e.reset();
        assertFalse(e.moved());
        assertFalse(e.hidden());
        assertEquals("Top Left", e.defaultCorner());
    }

    @Test
    void aMovedAndAHiddenElementSurviveTheSavedForm() {
        el("a", "Top Left").stackIn("Bottom Right");
        el("b", "Top Left").setHidden(true);
        el("c", "Top Left").pinTo(new HudLayout.Pin(Anchor.TOP, -5, 6));
        el("d", "Top Left");
        JsonObject saved = HudElements.write(els.values(), Map.of());
        assertEquals("{\"corner\":\"bottom-right\"}", saved.get("a").toString());
        assertFalse(saved.has("d"), "an element in its default place is not written");

        Map<String, HudElement> back = fresh();
        assertTrue(HudElements.read(saved, back::get, new LinkedHashMap<>()).isEmpty());
        assertEquals("Bottom Right", back.get("a").stackCorner());
        assertTrue(back.get("b").hidden());
        assertEquals(new HudLayout.Pin(Anchor.TOP, -5, 6), back.get("c").pin());
        assertFalse(back.get("d").moved());
        assertEquals(saved, HudElements.write(back.values(), Map.of()), "written again, it is the same file");
    }

    @Test
    void anUnknownIdAndAnUnknownAnchorAreSkippedWithoutLosingTheRest() {
        el("a", "Top Left");
        el("b", "Top Left");
        el("c", "Top Left");
        JsonObject file = JsonParser.parseString("{\"a\":{\"corner\":\"bottom-left\"},\"ghost\":{\"anchor\":\"center\",\"dx\":1,\"dy\":1},"
                + "\"b\":{\"anchor\":\"upside-down\",\"dx\":1,\"dy\":1},\"c\":{\"corner\":\"center\"},\"d\":{\"hidden\":true}}").getAsJsonObject();
        Map<String, JsonElement> unknown = new LinkedHashMap<>();
        List<String> problems = HudElements.read(file, els::get, unknown);
        assertEquals(2, problems.size(), "the unknown anchor and the corner that is not a corner are reported");
        assertEquals("Bottom Left", el("a", "Top Left").stackCorner(), "the good entry is applied");
        assertFalse(el("b", "Top Left").moved(), "the bad one keeps its default");
        assertFalse(el("c", "Top Left").moved());
        assertEquals(2, unknown.size(), "unknown ids are kept");
        JsonObject out = HudElements.write(els.values(), unknown);
        assertTrue(out.has("ghost") && out.has("d") && out.has("a"), "and written back with the rest");
    }

    @Test
    void theHudCommandListsHidesMovesAndResetsThroughTheRegistry() {
        HudElements.declare("t-one", "Test One", "Top Left").ownedBy(() -> false);
        HudElements.declare("t-two", "Test Two", "Top Right");
        List<String> said = new ArrayList<>();
        Reply r = new Reply() {
            @Override public void info(String text) { said.add("info: " + text); }
            @Override public void success(String text) { said.add("success: " + text); }
            @Override public void error(String text) { said.add("error: " + text); }
        };
        HudCommand.run("list", null, null, r);
        String list = said.get(0);
        assertTrue(list.contains("t-one: Test One, in the top-left stack (default), shown, module off"), list);
        assertTrue(list.contains("t-two: Test Two, in the top-right stack (default), shown, module unknown"), list);

        HudCommand.run("hide", "t-one", null, r);
        HudCommand.run("move", "t-two", "bottom-left", r);
        assertTrue(HudElements.get("t-one").hidden());
        assertEquals("Bottom Left", HudElements.get("t-two").stackCorner());
        HudCommand.run("list", null, null, r);
        assertTrue(said.get(said.size() - 1).contains("t-one: Test One, in the top-left stack (default), hidden, module off"));
        assertTrue(said.get(said.size() - 1).contains("t-two: Test Two, in the bottom-left stack, shown"));

        HudCommand.run("move", "t-two", "center", r);
        assertEquals(Anchor.CENTER, HudElements.get("t-two").pin().anchor());
        HudCommand.run("show", "t-one", null, r);
        assertTrue(said.get(said.size() - 1).contains("its module is off"), "showing a panel of a module that is off says so");
        HudCommand.run("reset", "t-two", null, r);
        assertFalse(HudElements.get("t-two").moved());

        HudCommand.run("hide", "nosuch", null, r);
        assertTrue(said.get(said.size() - 1).startsWith("error: There is no HUD panel called 'nosuch'"));
        HudCommand.run("move", "t-one", null, r);
        assertTrue(said.get(said.size() - 1).startsWith("error: Say which panel and where"));
        HudCommand.run("move", "t-one", "middle", r);
        assertTrue(said.get(said.size() - 1).startsWith("error: 'middle' is not an anchor"));
        HudCommand.run("hide", "t-one", null, r);
        HudCommand.run("reset", null, null, r);
        assertFalse(HudElements.get("t-one").hidden());
    }
}
