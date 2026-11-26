package dev.vantage.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopeTest {
    @TempDir
    Path dir;

    private static final String SAFE = "[A-Za-z0-9._-]+";

    @Test
    void aServerKeyIsLowerCaseWithoutTrailingDotsOrTheDefaultPort() {
        assertEquals("play.example.org", Scope.serverKey("play.example.org"));
        assertEquals("play.example.org", Scope.serverKey("Play.Example.ORG"));
        assertEquals("play.example.org", Scope.serverKey("play.example.org."));
        assertEquals("play.example.org", Scope.serverKey("play.example.org..:25565"));
        assertEquals("play.example.org", Scope.serverKey("  PLAY.example.org:25565 "));
        assertEquals("play.example.org", Scope.serverKey("play.example.org:025565"));
    }

    @Test
    void anotherPortIsAnotherServer() {
        assertEquals("play.example.org_25566", Scope.serverKey("play.example.org:25566"));
        assertEquals("10.0.0.5_25566", Scope.serverKey("10.0.0.5:25566"));
        assertEquals("10.0.0.5", Scope.serverKey("10.0.0.5"));
        assertNotEquals(Scope.serverKey("10.0.0.5:25566"), Scope.serverKey("10.0.0.5:25567"));
    }

    @Test
    void anIpv6LiteralKeepsItsGroupsWithDashes() {
        assertEquals("v6-2001-db8--1", Scope.serverKey("[2001:db8::1]"));
        assertEquals("v6-2001-db8--1", Scope.serverKey("2001:db8::1"));
        assertEquals("v6-2001-db8--1", Scope.serverKey("[2001:DB8::1]:25565"));
        assertEquals("v6-2001-db8--1_25566", Scope.serverKey("[2001:db8::1]:25566"));
        assertEquals("v6---1", Scope.serverKey("[::1]"));
    }

    @Test
    void anInternationalNameBecomesItsAsciiForm() {
        assertEquals("xn--bcher-kva.example", Scope.serverKey("Bücher.example"));
        assertNotEquals(Scope.serverKey("übung.example"), Scope.serverKey("öbung.example"));
    }

    @Test
    void oddCharactersBecomeUnderscoresAndKeepTwoNamesApart() {
        String a = Scope.serverKey("my server.example");
        String b = Scope.serverKey("my_server.example");
        assertTrue(a.matches(SAFE), a);
        assertTrue(a.startsWith("my_server.example-"), a);
        assertEquals("my_server.example", b);
        assertNotEquals(a, b);
        assertEquals(a, Scope.serverKey("my server.example")); // the same text always gives the same key
        for (String s : new String[] {"../../etc/passwd", "a/b\\c", "x:y:z w", "..\u0000x", "con", "NUL.example", "a\"b*c?d<e>f|g"}) {
            String k = Scope.serverKey(s);
            assertTrue(k.matches(SAFE), s + " -> " + k);
            assertFalse(k.contains("/") || k.contains("\\"), k);
        }
    }

    @Test
    void aNameWindowsReservesGetsAnUnderscore() {
        assertEquals("con_", Scope.serverKey("con"));
        assertEquals("nul.example_", Scope.serverKey("NUL.example"));
        assertEquals("nul.example_", Scope.serverKey("nul.example"));
        assertEquals("com1_", Scope.serverKey("com1"));
        assertEquals("console", Scope.serverKey("console"));
    }

    @Test
    void aVeryLongNameIsCutAndStaysUnique() {
        String long1 = "a".repeat(200) + ".example.org";
        String long2 = "a".repeat(200) + ".example.net";
        String k1 = Scope.serverKey(long1);
        String k2 = Scope.serverKey(long2);
        assertTrue(k1.length() <= 64, "" + k1.length());
        assertTrue(k1.matches(SAFE));
        assertNotEquals(k1, k2);
        assertEquals(k1, Scope.serverKey(long1));
    }

    @Test
    void nothingUsableGivesNoKey() {
        assertNull(Scope.serverKey(null));
        assertNull(Scope.serverKey(""));
        assertNull(Scope.serverKey("   "));
        assertNull(Scope.serverKey("..."));
        assertNull(Scope.serverKey(":25566"));
        assertNull(Scope.worldKey(null));
        assertNull(Scope.worldKey("  ."));
        assertNull(Scope.dimensionKey(""));
        assertNull(Scope.dimensionKey(null));
    }

    @Test
    void aSinglePlayerWorldIsItsFolderNameWithTheCaseKept() {
        assertEquals("sp-My_World", Scope.worldKey("My World"));
        assertEquals("sp-My_World", Scope.worldKey("My_World")); // the folder name, not what the menu shows: two such folders cannot exist side by side on the usual systems
        assertEquals("sp-New_World_(1)".substring(0, 12), Scope.worldKey("New World (1)").substring(0, 12));
        assertTrue(Scope.worldKey("New World (1)").matches(SAFE));
        assertEquals("sp-world", Scope.worldKey("world."));
        assertNotEquals(Scope.worldKey("World"), Scope.worldKey("world"));
        assertTrue(Scope.worldKey("Café").matches(SAFE));
    }

    @Test
    void aDimensionKeyIsTheIdMadeFileSafe() {
        assertEquals("minecraft_overworld", Scope.dimensionKey("minecraft:overworld"));
        assertEquals("minecraft_the_nether", Scope.dimensionKey("minecraft:the_nether"));
        assertEquals("mod_sub_dim", Scope.dimensionKey("Mod:Sub/Dim"));
        assertTrue(Scope.dimensionKey("a:b$c").matches(SAFE));
        assertNotEquals(Scope.dimensionKey("a:b$c"), Scope.dimensionKey("a:b#c"));
        assertTrue(Scope.dimensionKey("x:" + "y".repeat(300)).length() <= 64);
    }

    @Test
    void pathsFollowTheScope() {
        Scope.Place p = Scope.place("Play.Example.org:25565", null, null, "minecraft:the_nether");
        assertEquals(dir.resolve("data/found.chunks"), Scope.path(dir, Scope.GLOBAL, null, "found", ".chunks"));
        assertEquals(dir.resolve("data/found/play.example.org.chunks"), Scope.path(dir, Scope.SERVER, p, "found", ".chunks"));
        assertEquals(dir.resolve("data/found/play.example.org/minecraft_the_nether.chunks"), Scope.path(dir, Scope.DIMENSION, p, "found", ".chunks"));
        assertNull(Scope.path(dir, Scope.SERVER, null, "found", ".chunks"));
        assertNull(Scope.path(dir, Scope.DIMENSION, null, "found", ".chunks"));
        Scope.Place noDim = Scope.place("play.example.org", null, null, "");
        assertNull(Scope.path(dir, Scope.DIMENSION, noDim, "found", ".chunks"));
        assertEquals(dir.resolve("data/found/play.example.org.chunks"), Scope.path(dir, Scope.SERVER, noDim, "found", ".chunks"));
    }

    @Test
    void aSinglePlayerPlaceAndANoWorldPlace() {
        Scope.Place sp = Scope.place(null, "My World", "Renamed In The Menu", "minecraft:overworld");
        assertEquals("sp-My_World", sp.serverKey());
        assertEquals(dir.resolve("data/found/sp-My_World/minecraft_overworld.chunks"), Scope.path(dir, Scope.DIMENSION, sp, "found", ".chunks"));
        assertNull(Scope.place(null, null, null, "minecraft:overworld"), "no server and no world is no place, never a key called unknown");
        assertNull(Scope.place("", null, null, "minecraft:overworld"));
    }

    @Test
    void theOldSpellingsOfAServerAreAllListed() {
        Scope.Place p = Scope.place("Play.Example.org", null, null, "minecraft:overworld");
        List<String> old = p.oldServerKeys();
        assertTrue(old.contains("Play.Example.org".replace("P", "P")) || old.stream().anyMatch(s -> s.equalsIgnoreCase("play.example.org")), "" + old);
        assertTrue(old.stream().anyMatch(s -> s.equalsIgnoreCase("play.example.org_25565")), "" + old);
        assertTrue(old.stream().anyMatch(s -> s.equalsIgnoreCase("play.example.org.")), "" + old);
        assertTrue(old.stream().anyMatch(s -> s.equalsIgnoreCase("play.example.org._25565")), "" + old);
        Scope.Place custom = Scope.place("play.example.org:25566", null, null, "minecraft:overworld");
        assertTrue(custom.oldServerKeys().stream().allMatch(s -> s.contains("25566")), "another port is another server: " + custom.oldServerKeys());
        Scope.Place sp = Scope.place(null, "folder", "My World", "minecraft:overworld");
        assertEquals(List.of("sp-My_World"), sp.oldServerKeys());
        assertEquals(List.of("minecraft_overworld"), p.oldDimensionKeys());
        assertEquals(List.of("a_b_c", "a_b/c"), Scope.place("h", null, null, "a:b/c").oldDimensionKeys().subList(0, 2));
    }

    @Test
    void oldFilesAreFoundIgnoringCaseAndEveryCombinationOfSpellings() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("sky-protected"));
        Files.writeString(folder.resolve("Play.Example.org_minecraft_overworld.json"), "[]");
        Files.writeString(folder.resolve("play.example.org_25565_minecraft_overworld.json"), "[]");
        Files.writeString(folder.resolve("play.example.org_minecraft_the_nether.json"), "[]");
        Files.writeString(folder.resolve("other.example.org_minecraft_overworld.json"), "[]");
        Files.writeString(folder.resolve("play.example.org_25566_minecraft_overworld.json"), "[]");
        Scope.Place p = Scope.place("play.example.org", null, null, "minecraft:overworld");
        List<Path> found = OldFiles.find(dir, "sky-protected/{server}_{dim}.json", p);
        assertEquals(2, found.size(), "" + found);
        assertTrue(found.stream().anyMatch(f -> f.getFileName().toString().equals("Play.Example.org_minecraft_overworld.json")));
        assertTrue(found.stream().anyMatch(f -> f.getFileName().toString().equals("play.example.org_25565_minecraft_overworld.json")));
        assertEquals(List.of(), OldFiles.find(dir, "sky-protected/{server}_{dim}.json", null));
        assertEquals(List.of(), OldFiles.find(dir, "nothing-here/{server}_{dim}.json", p));
    }

    @Test
    void anOldPatternCannotLeaveTheRootAndSeesFoldersAndSubfolders() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.json"), "[]");
        Path root = Files.createDirectories(dir.resolve("config"));
        Files.createDirectories(root.resolve("mod/play.example.org"));
        Files.writeString(root.resolve("mod/play.example.org/minecraft_overworld.chunks"), "x");
        Scope.Place p = Scope.place("play.example.org", null, null, "minecraft:overworld");
        assertEquals(1, OldFiles.find(root, "mod/{server}/{dim}.chunks", p).size());
        assertEquals(List.of(), OldFiles.find(root, "../outside/secret.json", p), ".. never matches: a listing has no such entry");
        Scope.Place dots = Scope.place("..", null, null, "minecraft:overworld");
        assertNull(dots, "a key of only dots is no key");
    }

    @Test
    void aCustomDimensionWithASlashIsFoundInItsSubfolder() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("sky-protected/play.example.org_mydims_deep"));
        Files.writeString(folder.resolve("cave.json"), "[]");
        Scope.Place p = Scope.place("play.example.org", null, null, "mydims:deep/cave");
        assertEquals(1, OldFiles.find(dir, "sky-protected/{server}_{dim}.json", p).size());
    }
}
