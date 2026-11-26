package dev.vantage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Old saved files are brought up to date as JSON, before any module reads them. */
class MigrationsTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void aFileWithoutAVersionIsVersionOne() {
        assertEquals(1, Migrations.versionOf(json("{}")));
        assertEquals(2, Migrations.versionOf(json("{\"v\":2}")));
        assertEquals(1, Migrations.versionOf(json("{\"v\":\"two\"}")));
        assertEquals(7, Migrations.versionOf(json("{\"v\":7}")), "a newer file says so");
    }

    @Test
    void aRenamedModuleKeepsItsSavedEntry() {
        JsonObject root = json("{\"v\":2,\"modules\":{\"Old Name\":{\"enabled\":true,\"settings\":{\"Text\":\"123\"}},\"Radar\":{\"enabled\":false}}}");
        Migrations.renameModule(root, "Old Name", "New Name");
        JsonObject mods = root.getAsJsonObject("modules");
        assertFalse(mods.has("Old Name"));
        assertTrue(mods.getAsJsonObject("New Name").get("enabled").getAsBoolean());
        assertEquals("123", mods.getAsJsonObject("New Name").getAsJsonObject("settings").get("Text").getAsString());
        assertTrue(mods.has("Radar"), "everything else is left alone");
        String once = root.toString();
        Migrations.renameModule(root, "Old Name", "New Name");
        assertEquals(once, root.toString(), "running it again changes nothing");
    }

    @Test
    void theNewNameWinsWhenBothAreInTheFile() {
        JsonObject root = json("{\"modules\":{\"Old Name\":{\"enabled\":true},\"New Name\":{\"enabled\":false}}}");
        Migrations.renameModule(root, "Old Name", "New Name");
        JsonObject mods = root.getAsJsonObject("modules");
        assertFalse(mods.has("Old Name"));
        assertFalse(mods.getAsJsonObject("New Name").get("enabled").getAsBoolean());
    }

    @Test
    void anEntryOfARemovedModuleIsLeftForTheReaderToIgnore() {
        JsonObject root = json("{\"v\":2,\"modules\":{\"Seed Ores\":{\"enabled\":true},\"Seed Coal\":{\"enabled\":true}}}");
        String before = root.toString();
        assertEquals(List.of(), Migrations.upgrade(root));
        assertEquals(before, root.toString());
    }

    @Test
    void aFileWithoutModulesIsLeftAlone() {
        JsonObject root = json("{\"v\":2,\"panels\":{}}");
        assertEquals(List.of(), Migrations.upgrade(root));
        JsonObject odd = json("{\"modules\":5}");
        assertEquals(List.of(), Migrations.upgrade(odd));
    }

    @Test
    void entriesAreFoundByIdWhetherTheFileHasTheNameOrTheId() {
        JsonObject mods = json("{\"Storage ESP\":{},\"base-finder\":{},\"3D Hand\":{},\"???\":{}}");
        Map<String, String> keys = Migrations.keysById(mods);
        assertEquals("Storage ESP", keys.get("storage-esp"));
        assertEquals("base-finder", keys.get("base-finder"));
        assertEquals("3D Hand", keys.get("3d-hand"));
        assertEquals("???", keys.get("???"), "a key no id can be made from stands for itself");
        assertEquals("Storage ESP", Migrations.keyFor(mods, "storage-esp"));
        assertNull(Migrations.keyFor(mods, "radar"));
    }

    @Test
    void aThingIsSavedUnderItsNameUntilTheNameNoLongerGivesItsId() {
        assertEquals("Storage ESP", Migrations.saveKey("Storage ESP", "storage-esp"));
        assertEquals("storage-esp", Migrations.saveKey("Chest ESP", "storage-esp"), "after a rename the id is written, so the next load still finds it");
        assertEquals("Scan budget (ms)", Migrations.saveKey("Scan budget (ms)", "scan-budget-ms"));
    }

    @Test
    void theCheckedInTestConfigIsReadableAndAlreadyUpToDate() throws Exception {
        Path seed = Path.of("rig-seed/config.json"); // the config every in-game check run starts from: a real version 2 file
        assertTrue(Files.isRegularFile(seed), "run the tests from mod/");
        JsonObject root = json(Files.readString(seed));
        String before = root.toString();
        assertEquals(2, Migrations.versionOf(root));
        assertEquals(List.of(), Migrations.upgrade(root));
        assertEquals(before, root.toString());
        Map<String, String> keys = Migrations.keysById(root.getAsJsonObject("modules"));
        assertEquals(root.getAsJsonObject("modules").size(), keys.size(), "no two saved modules share an id");
        for (Map.Entry<String, String> e : keys.entrySet()) assertEquals(e.getValue(), Migrations.saveKey(e.getValue(), e.getKey()), "today every module is written under its name");
    }
}
