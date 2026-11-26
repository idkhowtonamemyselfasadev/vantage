package dev.vantage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import com.google.gson.JsonObject;
import dev.vantage.setting.BlockColorSetting;
import dev.vantage.setting.BlockListSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.EntityListSetting;
import dev.vantage.setting.IntSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import dev.vantage.setting.TextSetting;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Share codes as two people use them: made by one, sent through a chat app, checked on the other side. Pure logic, no game: probe
 * modules stand in for the real ones. The in-game half (the real buttons, the real clipboard) is UIONLY=configshare.
 */
class ConfigShareTest {
    static class Probe extends Module {
        final BoolSetting flag = add(new BoolSetting("Flag", "", false));
        final NumberSetting range = add(new NumberSetting("Range", "", 3.0, 0.5, 10, 0.5));
        final IntSetting count = add(new IntSetting("Count", "", 5, 1, 9));
        final ModeSetting mode = add(new ModeSetting("Mode", "", "A", "A", "B", "C"));
        final ColorSetting colour = add(new ColorSetting("Colour", "", 0xFF336699));
        final BlockListSetting blocks = add(new BlockListSetting("Blocks", "", List.of("minecraft:stone")));
        final EntityListSetting mobs = add(new EntityListSetting("Mobs", "", List.of("minecraft:zombie")));
        final BlockColorSetting paints = add(new BlockColorSetting());
        final TextSetting secret = add(new TextSetting("Secret", "", ""));

        Probe(String name) {
            super(name, "test module", Category.CLIENT);
        }
    }

    static class BetaProbe extends Module {
        final NumberSetting size = add(new NumberSetting("Size", "", 1.0, 0, 10, 0.5));

        BetaProbe() {
            super("Cfg Beta Probe", "test beta", Category.CLIENT);
            beta();
        }
    }

    static Probe a, b;
    static BetaProbe beta;

    @BeforeAll
    static void modules() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        a = Modules.add(new Probe("Cfg Probe A"));
        b = Modules.add(new Probe("Cfg Probe B"));
        beta = Modules.add(new BetaProbe());
        Modules.refresh();
    }

    private static String code(JsonObject mods) {
        return ConfigShare.encode("Test cfg", mods, null, false, false, false);
    }

    private static JsonObject fileForm(boolean aOn, boolean bOn) {
        JsonObject m = new JsonObject();
        for (Module x : Modules.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", x == a && aOn || x == b && bOn);
            JsonObject s = new JsonObject();
            for (var st : x.settings) if (st.stored()) s.add(st.name, st.save());
            o.add("settings", s);
            m.add(x.name, o);
        }
        return m;
    }

    private static void reset() {
        for (Probe p : List.of(a, b)) for (var st : p.settings) st.reset();
        beta.size.reset();
    }

    private static JsonObject setting(ConfigShare.Decoded d, String module, String setting) {
        JsonObject w = new JsonObject();
        w.add("v", d.modules().getAsJsonObject(module).getAsJsonObject("settings").get(setting));
        return w;
    }

    private static String raw(String json) {
        return ConfigShare.wrap(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String chatWrap(String code, int width, String eol) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i += width) sb.append(code, i, Math.min(code.length(), i + width)).append(eol);
        return sb.toString();
    }

    private static String sample() {
        reset();
        a.flag.set(true);
        a.range.set(7.5);
        a.count.set(8);
        a.mode.set("C");
        a.colour.set(0xFFAA5500);
        a.blocks.set(List.of("minecraft:diamond_ore", "minecraft:gold_ore"));
        a.mobs.set(List.of("minecraft:creeper"));
        b.mode.set("B");
        return code(fileForm(true, false));
    }

    @Test
    void everySettingTypeSurvivesTheRoundTrip() {
        String c = sample();
        ConfigShare.Decoded d = ConfigShare.decode(c);
        assertTrue(d.ok(), d.error());
        assertEquals("Test cfg", d.name());
        assertEquals(1, d.mods());
        assertEquals(0, d.skipped());
        assertTrue(d.settings() >= 8);
        var sa = d.modules().getAsJsonObject("Cfg Probe A").getAsJsonObject("settings");
        assertEquals(true, sa.get("Flag").getAsBoolean());
        assertEquals(7.5, sa.get("Range").getAsDouble());
        assertEquals(8, sa.get("Count").getAsInt());
        assertEquals("C", sa.get("Mode").getAsString());
        assertEquals("#FFAA5500", sa.get("Colour").getAsString());
        assertEquals(2, sa.getAsJsonArray("Blocks").size());
        assertEquals("minecraft:creeper", sa.getAsJsonArray("Mobs").get(0).getAsString());
        assertEquals("B", d.modules().getAsJsonObject("Cfg Probe B").getAsJsonObject("settings").get("Mode").getAsString());
        assertTrue(d.modules().getAsJsonObject("Cfg Probe A").get("enabled").getAsBoolean());
        assertFalse(d.modules().getAsJsonObject("Cfg Probe B").get("enabled").getAsBoolean());
        assertFalse(sa.has("Secret"), "text settings never travel");
    }

    @Test
    void aFreshImporterGetsDefaultsForWhatTheCodeLeavesOut() {
        String c = sample();
        reset();
        ConfigShare.Decoded d = ConfigShare.decode(c);
        assertTrue(d.ok());
        var sb = d.modules().getAsJsonObject("Cfg Probe B").getAsJsonObject("settings");
        assertEquals(false, sb.get("Flag").getAsBoolean());
        assertEquals(3.0, sb.get("Range").getAsDouble());
        assertEquals(5, sb.get("Count").getAsInt());
    }

    @Test
    void theCodeUsesOnlyCharactersAChatAppLeavesAlone() {
        String c = sample();
        assertTrue(c.startsWith("VNT1:"));
        assertTrue(c.substring(5).matches("[A-Za-z0-9_-]+"), "no padding, no + or /, nothing a chat app turns into formatting except _ inside words");
    }

    @Test
    void whatAChatAppDoesToACode() {
        String c = sample();
        String[] sent = {
            c, c + "\n", c + "\r\n", "  " + c + "  ", "`" + c + "`", "```\n" + c + "\n```", "\"" + c + "\"", "<" + c + ">", "**" + c + "**", "||" + c + "||",
            chatWrap(c, 40, "\n"), chatWrap(c, 57, "\r\n"), chatWrap(c, 10, " "), chatWrap(c, 33, "\n\n"), chatWrap(c, 25, "\t"),
            chatWrap(c, 30, "\u00A0"), chatWrap(c, 30, "\u200B"), chatWrap(c, 30, "\u2028"), chatWrap(c, 30, "\u00A0\u200B\n"),
            "\uFEFF" + c, c.substring(0, 20) + "\u200D" + c.substring(20),
            "here is my config: " + c, "here is my config:\n" + c + "\nhope it works", c + ".", c + "!", c + ")", "(" + c + ")", "Alex: " + c + " lol",
            "see " + chatWrap(c, 45, "\n") + "thanks!", "\u201C" + c + "\u201D", c + "\n\nVNT1:garbage",
            "[VNT1 code](" + c + ")",
        };
        for (String s : sent) {
            ConfigShare.Decoded d = ConfigShare.decode(s);
            assertTrue(d.ok(), "failed for: " + s.replace("\n", "\\n").replace("\r", "\\r").replace("\u00A0", "<nbsp>").replace("\u200B", "<zwsp>") + " -> " + d.error());
            assertEquals(1, d.mods());
        }
    }

    @Test
    void aCutOffCodeSaysSoAndNeverImportsHalf() {
        String c = sample();
        reset();
        String before = fileForm(true, true).toString();
        int cuts = 0;
        for (int n = 6; n < c.length(); n += 7) {
            ConfigShare.Decoded d = ConfigShare.decode(c.substring(0, n));
            assertFalse(d.ok(), "a code cut at " + n + " was accepted");
            assertNotNull(d.error());
            cuts++;
        }
        assertTrue(cuts > 10);
        assertTrue(ConfigShare.decode(c.substring(0, c.length() / 2)).error().contains("cut off") || ConfigShare.decode(c.substring(0, c.length() / 2)).error().contains("damaged"));
        assertTrue(ConfigShare.decode(c.substring(0, c.length() - 1)).error().contains("cut off"), ConfigShare.decode(c.substring(0, c.length() - 1)).error());
        assertEquals(before, fileForm(true, true).toString(), "checking a code changes no setting");
    }

    @Test
    void notACodeAndWrongVersionsGetOneClearLine() {
        assertEquals("The clipboard is empty", ConfigShare.decode("").error());
        assertEquals("The clipboard is empty", ConfigShare.decode("  \n ").error());
        assertEquals("The clipboard is empty", ConfigShare.decode(null).error());
        assertTrue(ConfigShare.decode("hello there").error().contains("not a Vantage config code"));
        assertTrue(ConfigShare.decode("https://example.com/a?b=c").error().contains("not a Vantage config code"));
        assertTrue(ConfigShare.decode("VNT2:abcd").error().contains("different Vantage version"));
        assertTrue(ConfigShare.decode("VNT1:").error().contains("empty"));
        assertTrue(ConfigShare.decode("VNT1:!!!!").error().contains("empty"));
        assertFalse(ConfigShare.decode("VNT1:abcd").ok());
        byte[] bin = java.util.Base64.getUrlDecoder().decode(sample().substring(5));
        bin[0] = 2;
        ConfigShare.Decoded newer = ConfigShare.decode("VNT1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bin));
        assertTrue(newer.error().contains("newer Vantage"), newer.error());
        bin[0] = 1;
        bin[3] ^= 0x55;
        assertTrue(ConfigShare.decode("VNT1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bin)).error().contains("checksum"));
    }

    @Test
    void anOverLongCodeIsRefusedWithoutWork() {
        ConfigShare.Decoded d = ConfigShare.decode("VNT1:" + "A".repeat(ConfigShare.MAX_CODE + 1));
        assertFalse(d.ok());
        assertTrue(d.error().contains("too long"));
        assertFalse(ConfigShare.decode(raw("{\"n\":\"" + "a".repeat(70000) + "\"}")).ok());
        // a big but legal code: 2000 block ids
        reset();
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 2000; i++) ids.append(i > 0 ? "," : "").append("\"minecraft:b").append(i).append('"');
        ConfigShare.Decoded big = ConfigShare.decode(raw("{\"n\":\"Big\",\"s\":{\"Cfg Probe A\":{\"Blocks\":[" + ids + "]}}}"));
        assertTrue(big.ok(), big.error());
        assertEquals(2000, big.modules().getAsJsonObject("Cfg Probe A").getAsJsonObject("settings").getAsJsonArray("Blocks").size());
        assertFalse(ConfigShare.decode(raw("{\"s\":{\"Cfg Probe A\":{\"Blocks\":[" + ids + ",\"minecraft:x\"]}}}")).modules().getAsJsonObject("Cfg Probe A").getAsJsonObject("settings").getAsJsonArray("Blocks").size() > 2000,
                "a list over the limit is skipped, not applied");
    }

    @Test
    void unknownModulesAndSettingsAreSkippedAndCounted() {
        reset();
        ConfigShare.Decoded d = ConfigShare.decode(raw("{\"n\":\"Unk\",\"on\":[\"Cfg Probe A\",\"Ghost Module\",\"Cfg Probe B\"],\"s\":{\"Cfg Probe A\":{\"Range\":9999,\"Old thing\":5,\"Mode\":\"Z\",\"Count\":2.5,\"Colour\":\"nope\"},\"Ghost\":{\"a\":1}},\"zzz\":1}"));
        assertTrue(d.ok(), d.error());
        assertEquals(2, d.mods());
        assertEquals(1 + 1 + 1 + 1 + 1 + 1 + 1, d.skipped(), "Ghost Module, Ghost, Old thing, Mode, Count, Colour, zzz");
        var sa = d.modules().getAsJsonObject("Cfg Probe A").getAsJsonObject("settings");
        assertEquals(10.0, sa.get("Range").getAsDouble(), "out of range is clamped");
        assertEquals("A", sa.get("Mode").getAsString(), "a refused value leaves the default");
        assertEquals(5, sa.get("Count").getAsInt());
        assertTrue(d.preview().contains("skipped"));
    }

    @Test
    void aBetaModuleInACodeIsIgnoredByALockedBuildWithoutError() {
        assertFalse(Modules.betaUnlocked());
        ConfigShare.Decoded d = ConfigShare.decode(raw("{\"n\":\"B\",\"on\":[\"Cfg Probe A\",\"Cfg Beta Probe\"],\"s\":{\"Cfg Beta Probe\":{\"Size\":4.0},\"Cfg Probe A\":{\"Flag\":true}}}"));
        assertTrue(d.ok(), d.error());
        assertEquals(0, d.skipped(), "a beta this build does not have is not an error");
        assertEquals(1, d.mods());
        assertFalse(d.modules().has("Cfg Beta Probe"));
        assertTrue(d.modules().getAsJsonObject("Cfg Probe A").getAsJsonObject("settings").get("Flag").getAsBoolean());
    }

    @Test
    void aBetaModuleIsAppliedByAnUnlockedBuild() {
        Modules.betaForTest(beta, true, true);
        try {
            ConfigShare.Decoded d = ConfigShare.decode(raw("{\"n\":\"B\",\"s\":{\"Cfg Beta Probe\":{\"Size\":4.0}}}"));
            assertTrue(d.ok(), d.error());
            assertEquals(4.0, d.modules().getAsJsonObject("Cfg Beta Probe").getAsJsonObject("settings").get("Size").getAsDouble());
            beta.size.set(6.0);
            String made = code(fileForm(false, false));
            Modules.betaForTest(beta, true, false);
            ConfigShare.Decoded locked = ConfigShare.decode(made);
            assertTrue(locked.ok(), locked.error());
            assertEquals(0, locked.skipped(), "made unlocked with beta settings inside, read locked");
            assertFalse(locked.modules().has("Cfg Beta Probe"));
        } finally {
            Modules.betaForTest(beta, true, null);
            beta.size.reset();
        }
    }

    @Test
    void aCodeNamingARemovedModuleStillImportsTheRest() {
        ConfigShare.Decoded d = ConfigShare.decode(raw("{\"n\":\"Old\",\"on\":[\"Cfg Probe A\",\"Seed Ores\",\"Seed Coal\"],\"s\":{\"Seed Ores\":{\"Flag\":true}},\"k\":{\"Seed Ores\":80}}"));
        assertTrue(d.ok(), d.error());
        assertEquals(1, d.mods(), "the module that still exists is switched on");
        assertEquals(4, d.skipped(), "two names in the on list, one settings block, one key");
        assertFalse(d.modules().has("Seed Ores"));
        assertTrue(d.modules().getAsJsonObject("Cfg Probe A").get("enabled").getAsBoolean());
    }

    /** Samples in the form the first released encoder wrote (git history of ConfigShare starts at 66c0c75, wire format VNT1 unchanged since): no whole numbers, no entity lists. */
    @Test
    void oldFormatCodesStillImport() {
        String old = raw("{\"n\":\"Old pack\",\"on\":[\"Cfg Probe A\"],\"s\":{\"Cfg Probe A\":{\"Range\":7.0,\"Count\":3.0,\"Colour\":\"#FFAA5500\",\"Mode\":\"B\",\"Flag\":true,\"Blocks\":[\"minecraft:coal_ore\"]}}}");
        ConfigShare.Decoded d = ConfigShare.decode(old);
        assertTrue(d.ok(), d.error());
        assertEquals(0, d.skipped());
        var sa = d.modules().getAsJsonObject("Cfg Probe A").getAsJsonObject("settings");
        assertEquals(7.0, sa.get("Range").getAsDouble());
        assertEquals(3, sa.get("Count").getAsInt(), "a whole number written as 3.0 by an older build");
        assertEquals("#FFAA5500", sa.get("Colour").getAsString());
        // a code that carries the three extra parts, as old builds wrote them
        ConfigShare.Decoded x = ConfigShare.decode(raw("{\"n\":\"X\",\"on\":[],\"s\":{},\"k\":{\"Cfg Probe A\":71},\"h\":{\"p\":{\"Radar\":[10,20]},\"s\":{}},\"t\":{\"Theme\":\"x\"}}"));
        assertTrue(x.ok(), x.error());
        assertNotNull(x.keys());
        assertNotNull(x.hud());
        assertEquals(71, x.keys().get("Cfg Probe A").getAsInt());
    }

    @Test
    void newCodesHaveTheOldWireFormat() {
        byte[] bin = java.util.Base64.getUrlDecoder().decode(sample().substring(5));
        assertEquals(1, bin[0], "version byte 1: a client from before this change reads it");
        assertEquals((byte) 0x1f, bin[5], "gzip magic");
        assertEquals((byte) 0x8b, bin[6]);
    }

    @Test
    void checkingACodeNeverTouchesTheLiveSetup() {
        reset();
        a.range.set(9.0);
        String before = fileForm(true, true).toString();
        String c = sample();
        reset();
        a.range.set(9.0);
        assertTrue(ConfigShare.decode(c).ok());
        assertFalse(ConfigShare.decode("VNT1:nonsense").ok());
        assertEquals(before, fileForm(true, true).toString());
        reset();
    }

    @Test
    void randomDamageNeverThrowsAndNeverHalfAccepts() {
        String c = sample();
        Random r = new Random(7);
        for (int i = 0; i < 400; i++) {
            char[] ch = c.toCharArray();
            for (int k = 0; k < 1 + r.nextInt(3); k++) ch[5 + r.nextInt(ch.length - 5)] = "abcXYZ019-_ !?.".charAt(r.nextInt(15));
            ConfigShare.Decoded d = ConfigShare.decode(new String(ch));
            if (d.ok()) assertTrue(d.mods() <= 1 && d.settings() >= 0); // a damaged body can only decode if it still checks out
            else assertNotNull(d.error());
        }
    }

    @Test
    void theImportHoldsNothingUntilItIsChecked() {
        ConfigShare.clipGet = () -> "not a code";
        ConfigShare.clipSet = s -> {};
        try {
            ConfigShare.pending = ConfigShare.decode(sample());
            var r = ConfigShare.importClipboard();
            assertFalse(r.ok());
            assertNull(ConfigShare.pending, "a failed import drops what was waiting");
            assertTrue(r.msg().contains("not a Vantage config code"));
            ConfigShare.clipGet = () -> "pasted: " + chatWrap(sample(), 50, "\n");
            var ok = ConfigShare.importClipboard();
            assertTrue(ok.ok(), ok.msg());
            assertNotNull(ConfigShare.pending);
            assertTrue(ok.msg().startsWith("Config 'Test cfg' from code:"));
        } finally {
            ConfigShare.pending = null;
            ConfigShare.clipGet = null;
            ConfigShare.clipSet = null;
        }
    }

    @Test
    void pasteRoutingKnowsACodeFromAName() {
        assertTrue(ConfigShare.looksLikeCode("VNT1:abc"));
        assertTrue(ConfigShare.looksLikeCode("my code vnt1:abc"));
        assertTrue(ConfigShare.looksLikeCode("VNT2:abc"));
        assertFalse(ConfigShare.looksLikeCode("Base hunting"));
        assertFalse(ConfigShare.looksLikeCode(null));
    }

    /** A HUD layout made on one monitor shape lands at the same edges on another (a 21:9 sender, a 16:9 receiver and back). */
    @Test
    void hudPositionsRefitToAnotherScreen() {
        int wide = 1720, normal = 1280;
        assertEquals(10, ConfigShare.refit(10, wide, normal), "left edge keeps its distance to the left");
        assertEquals(normal - 120, ConfigShare.refit(wide - 120, wide, normal), "right edge keeps its distance to the right");
        assertEquals(normal / 2 - 50, ConfigShare.refit(wide / 2 - 50, wide, normal), "the middle keeps its distance to the centre");
        assertEquals(wide - 120, ConfigShare.refit(normal - 120, normal, wide), "and the other way round");
        assertEquals(333, ConfigShare.refit(333, 1280, 1280), "same screen: untouched");
        for (int v = 0; v <= wide; v += 7) {
            int r = ConfigShare.refit(v, wide, normal);
            assertTrue(r >= -1 && r <= normal + 1 || v * 3 < wide, "on screen: " + v + " -> " + r);
        }
        assertNull(ConfigShare.validScreen(null));
        assertNull(ConfigShare.validScreen(com.google.gson.JsonParser.parseString("[1280]")));
        assertNull(ConfigShare.validScreen(com.google.gson.JsonParser.parseString("[5, 720]")));
        assertNotNull(ConfigShare.validScreen(com.google.gson.JsonParser.parseString("[1280, 720]")));
    }
}
