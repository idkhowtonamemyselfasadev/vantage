package dev.vantage.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeFileTest {
    @TempDir
    Path dir;

    private List<String> names() throws IOException {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aFirstWriteMakesTheFileAndNothingElse() throws IOException {
        Path f = dir.resolve("config.json");
        SafeFile.write(f, "{\"a\":1}", SafeFile.JSON_OBJECT);
        assertEquals(List.of("config.json"), names());
        SafeFile.Read r = SafeFile.read(f, SafeFile.JSON_OBJECT);
        assertEquals("{\"a\":1}", r.text());
        assertFalse(r.fromBackup());
        assertFalse(r.damaged());
    }

    @Test
    void theSecondWriteKeepsTheFirstAsTheBackup() throws IOException {
        Path f = dir.resolve("config.json");
        SafeFile.write(f, "{\"a\":1}", SafeFile.JSON_OBJECT);
        SafeFile.write(f, "{\"a\":2}", SafeFile.JSON_OBJECT);
        assertEquals(List.of("config.json", "config.json.bak"), names());
        assertEquals("{\"a\":2}", Files.readString(f));
        assertEquals("{\"a\":1}", Files.readString(SafeFile.backupOf(f)));
    }

    @Test
    void aDamagedFileIsKeptAsideAndTheBackupIsLoaded() throws IOException {
        Path f = dir.resolve("config.json");
        SafeFile.write(f, "{\"a\":1}", SafeFile.JSON_OBJECT);
        SafeFile.write(f, "{\"a\":2}", SafeFile.JSON_OBJECT);
        Files.writeString(f, "{ this is not json"); // a crash in another program, a full disk, a hand edit
        SafeFile.Read r = SafeFile.read(f, SafeFile.JSON_OBJECT);
        assertTrue(r.ok());
        assertTrue(r.fromBackup());
        assertTrue(r.damaged());
        assertEquals("{\"a\":1}", r.text());
        assertEquals("{ this is not json", Files.readString(r.badCopy()), "the damaged file is still there to look at");
    }

    @Test
    void savingOverADamagedFileNeverReplacesTheGoodBackupWithIt() throws IOException {
        Path f = dir.resolve("config.json");
        SafeFile.write(f, "{\"good\":1}", SafeFile.JSON_OBJECT);
        SafeFile.write(f, "{\"good\":2}", SafeFile.JSON_OBJECT);
        Files.writeString(f, "garbage");
        SafeFile.write(f, "{\"new\":3}", SafeFile.JSON_OBJECT);
        assertEquals("{\"new\":3}", Files.readString(f));
        assertEquals("{\"good\":1}", Files.readString(SafeFile.backupOf(f)), "the backup is still the last good version");
        assertTrue(names().stream().anyMatch(n -> n.startsWith("config.json.bad-")));
    }

    @Test
    void anEmptyFileCountsAsDamaged() throws IOException {
        Path f = dir.resolve("config.json");
        SafeFile.write(f, "{\"a\":1}", SafeFile.JSON_OBJECT);
        SafeFile.write(f, "{\"a\":2}", SafeFile.JSON_OBJECT);
        Files.writeString(f, ""); // what a power cut in the middle of a plain write leaves behind
        SafeFile.Read r = SafeFile.read(f, SafeFile.JSON_OBJECT);
        assertTrue(r.fromBackup());
        assertEquals("{\"a\":1}", r.text());
    }

    @Test
    void aMissingFileWithABackupUsesTheBackupAndAMissingFileAloneIsNotAnError() throws IOException {
        Path f = dir.resolve("config.json");
        SafeFile.Read none = SafeFile.read(f, SafeFile.JSON_OBJECT);
        assertFalse(none.ok());
        assertFalse(none.damaged());
        assertNull(none.text());
        Files.writeString(SafeFile.backupOf(f), "{\"a\":1}");
        SafeFile.Read r = SafeFile.read(f, SafeFile.JSON_OBJECT);
        assertTrue(r.fromBackup());
        assertFalse(r.damaged());
    }

    @Test
    void whenFileAndBackupAreBothDamagedNothingIsLoadedAndBothAreStillThere() throws IOException {
        Path f = dir.resolve("config.json");
        Files.writeString(f, "x");
        Files.writeString(SafeFile.backupOf(f), "y");
        SafeFile.Read r = SafeFile.read(f, SafeFile.JSON_OBJECT);
        assertFalse(r.ok());
        assertNotNull(r.badCopy());
        assertEquals("x", Files.readString(f));
        assertEquals("y", Files.readString(SafeFile.backupOf(f)));
    }

    @Test
    void onlyTheNewestFiveDamagedCopiesAreKept() throws IOException {
        Path f = dir.resolve("config.json");
        for (int i = 0; i < 9; i++) {
            Files.writeString(f, "broken " + i);
            SafeFile.read(f, SafeFile.JSON_OBJECT);
        }
        assertEquals(5, names().stream().filter(n -> n.startsWith("config.json.bad-")).count());
    }

    @Test
    void noTempFileIsLeftBehindAndFoldersAreCreated() throws IOException {
        Path f = dir.resolve("deep/er/data.json");
        SafeFile.write(f, "{}", SafeFile.JSON_OBJECT);
        SafeFile.write(f, "{\"x\":true}", SafeFile.JSON_OBJECT);
        try (var s = Files.list(f.getParent())) {
            assertEquals(List.of("data.json", "data.json.bak"), s.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void textIsWrittenAsUtf8() throws IOException {
        Path f = dir.resolve("names.json");
        String text = "{\"n\":\"Müller 日本 🦊\"}";
        SafeFile.write(f, text, SafeFile.JSON_OBJECT);
        assertEquals(text, SafeFile.read(f, SafeFile.JSON_OBJECT).text());
    }
}
