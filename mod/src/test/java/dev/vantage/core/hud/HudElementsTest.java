package dev.vantage.core.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** How HUD elements are saved, read from the old formats, and written in the old share form. */
class HudElementsTest {
    private final Map<String, HudElement> els = new LinkedHashMap<>();

    private HudElement el(String id) {
        return els.computeIfAbsent(id, k -> new HudElement(k, "Title " + k, "Top Left"));
    }

    private HudElementsTest() {
        el("coords");
        el("radar");
        el("modules");
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void aDefaultElementIsNotWritten() {
        assertEquals(0, HudElements.write(els.values(), Map.of()).size());
    }

    @Test
    void pinnedAndHiddenElementsRoundTrip() {
        el("coords").pinTo(new HudLayout.Pin(Anchor.BOTTOM_RIGHT, 12, 8.5f));
        el("radar").setHidden(true);
        el("modules").pinTo(new HudLayout.Pin(Anchor.TOP, -20, 3));
        el("modules").setHidden(true);
        JsonObject out = HudElements.write(els.values(), Map.of());
        assertEquals(3, out.size());
        assertEquals("{\"anchor\":\"bottom-right\",\"dx\":12,\"dy\":8.5}", out.get("coords").toString());
        assertEquals("{\"hidden\":true}", out.get("radar").toString());

        Map<String, HudElement> fresh = new LinkedHashMap<>();
        for (String id : els.keySet()) fresh.put(id, new HudElement(id, id, "Top Left"));
        Map<String, JsonElement> unknown = new LinkedHashMap<>();
        assertTrue(HudElements.read(out, fresh::get, unknown).isEmpty());
        assertEquals(new HudLayout.Pin(Anchor.BOTTOM_RIGHT, 12, 8.5f), fresh.get("coords").pin());
        assertFalse(fresh.get("coords").hidden());
        assertNull(fresh.get("radar").pin());
        assertTrue(fresh.get("radar").hidden());
        assertEquals(new HudLayout.Pin(Anchor.TOP, -20, 3), fresh.get("modules").pin());
        assertTrue(fresh.get("modules").hidden());
        assertTrue(unknown.isEmpty());
    }

    @Test
    void aDamagedEntryCostsOnlyThatEntryAndIsReported() {
        JsonObject section = json("{\"coords\":{\"anchor\":\"nowhere\",\"dx\":1,\"dy\":2},\"radar\":\"oops\",\"modules\":{\"anchor\":\"top\",\"dx\":\"x\"},"
                + "\"ok\":{\"anchor\":\"left\",\"dx\":4,\"dy\":5,\"hidden\":true}}");
        el("ok");
        List<String> problems = HudElements.read(section, els::get, new LinkedHashMap<>());
        assertEquals(3, problems.size());
        assertTrue(problems.get(0).contains("Title coords"), problems.get(0));
        assertFalse(el("coords").pinned());
        assertFalse(el("radar").pinned());
        assertFalse(el("modules").pinned());
        assertEquals(new HudLayout.Pin(Anchor.LEFT, 4, 5), el("ok").pin());
        assertTrue(el("ok").hidden());
    }

    @Test
    void unknownIdsAreKeptAndWrittenBack() {
        JsonObject section = json("{\"future\":{\"anchor\":\"center\",\"dx\":1,\"dy\":2,\"extra\":7},\"coords\":{\"hidden\":true}}");
        Map<String, JsonElement> unknown = new LinkedHashMap<>();
        assertTrue(HudElements.read(section, els::get, unknown).isEmpty());
        assertEquals(1, unknown.size());
        JsonObject out = HudElements.write(els.values(), unknown);
        assertEquals(section.get("future"), out.get("future"));
        assertTrue(out.getAsJsonObject("coords").get("hidden").getAsBoolean());
    }

    @Test
    void oldPanelEntriesOfLengthTwoAndThreeBecomeWaitingPositions() {
        JsonObject root = json("{\"panels\":{\"coords\":[1200,600],\"radar\":[10,20,0],\"Active\":[300,40,1],\"modules\":[1,\"x\"]}}");
        List<String> problems = HudElements.migratePanels(root, els::containsKey);
        assertEquals(1, problems.size(), "the damaged entry is reported");
        assertTrue(problems.get(0).contains("modules"));
        assertEquals(1, root.getAsJsonObject("panels").size(), "the menu's panel stays");
        assertEquals("[300,40,1]", root.getAsJsonObject("panels").get("Active").toString());
        assertEquals(2, root.getAsJsonObject("hud").size());

        assertTrue(HudElements.read(root.getAsJsonObject("hud"), els::get, new LinkedHashMap<>()).isEmpty());
        assertNull(el("coords").pin());
        assertNotNull(el("coords").absolute());
        assertTrue(el("coords").pinned());
        assertEquals(1200, el("coords").absolute()[0], 0.001);
        assertEquals(20, el("radar").absolute()[1], 0.001);
    }

    @Test
    void aWaitingPositionConvertsAtTheScreenSizeItIsFirstPlacedOn() {
        HudElement e = el("coords");
        e.pinAbsolute(1100, 650);
        e.setSize(150, 60);
        e.convert(1280, 720);
        assertNull(e.absolute());
        assertEquals(Anchor.BOTTOM_RIGHT, e.pin().anchor());
        assertEquals(30, e.pin().dx(), 0.001);
        assertEquals(10, e.pin().dy(), 0.001);
        float[] at = e.position(1280, 720);
        assertEquals(1100, at[0], 0.001);
        assertEquals(650, at[1], 0.001);
        at = e.position(1680, 720); // wider screen: still 30 from the right edge
        assertEquals(1680 - 150 - 30, at[0], 0.001);
    }

    @Test
    void aWaitingPositionIsSavedAsXAndYUntilItConverts() {
        el("radar").pinAbsolute(10, 20);
        JsonObject out = HudElements.write(els.values(), Map.of());
        assertEquals("{\"x\":10,\"y\":20}", out.get("radar").toString());
    }

    @Test
    void theNewSectionWinsOverAnOldPanelEntry() {
        JsonObject root = json("{\"panels\":{\"coords\":[5,5],\"radar\":[7,8]},\"hud\":{\"coords\":{\"anchor\":\"top\",\"dx\":0,\"dy\":0},\"radar\":{\"hidden\":true}}}");
        HudElements.migratePanels(root, els::containsKey);
        assertEquals("top", root.getAsJsonObject("hud").getAsJsonObject("coords").get("anchor").getAsString());
        assertFalse(root.getAsJsonObject("hud").getAsJsonObject("coords").has("x"));
        assertEquals(7, root.getAsJsonObject("hud").getAsJsonObject("radar").get("x").getAsInt(), "a hidden-only entry gets the old position too");
        assertTrue(root.getAsJsonObject("hud").getAsJsonObject("radar").get("hidden").getAsBoolean());
    }

    @Test
    void theOldXyFormRoundTripsForSharedCodesAndProfiles() {
        el("coords").pinTo(new HudLayout.Pin(Anchor.BOTTOM_RIGHT, 12, 8));
        el("radar").pinAbsolute(40, 50);
        JsonObject old = HudElements.exportAbsolute(els.values(), 1280, 720);
        assertEquals(2, old.size(), "an unpinned element is not exported");
        HudElement c = el("coords");
        c.setSize(100, 40);
        old = HudElements.exportAbsolute(els.values(), 1280, 720);
        assertEquals("[1168,672]", old.get("coords").toString());
        assertEquals("[40,50]", old.get("radar").toString());

        Map<String, HudElement> fresh = new LinkedHashMap<>();
        for (String id : els.keySet()) fresh.put(id, new HudElement(id, id, "Top Left"));
        old.add("stranger", json("{\"a\":1}").get("a"));
        JsonObject rest = HudElements.acceptAbsolute(old, fresh::get);
        assertEquals(1, rest.size(), "ids without an element come back to the caller");
        assertTrue(rest.has("stranger"));
        fresh.get("coords").setSize(100, 40);
        fresh.get("coords").convert(1280, 720);
        assertEquals(Anchor.BOTTOM_RIGHT, fresh.get("coords").pin().anchor());
        assertEquals(12, fresh.get("coords").pin().dx(), 0.001);
        assertEquals(8, fresh.get("coords").pin().dy(), 0.001);
        assertEquals(40, fresh.get("radar").absolute()[0], 0.001);
    }
}
