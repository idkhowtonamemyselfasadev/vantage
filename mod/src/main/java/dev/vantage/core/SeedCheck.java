package dev.vantage.core;

import dev.vantage.mixin.BiomeManagerAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Is a rumoured world seed the real one? The server never sends the seed, but it sends its SHA-256 hash at login for biome noise,
 * so any guess can be checked against that hash without sending anything. A match is proof. No match can also mean the server
 * scrambles the hash (some do), so a miss is only "not confirmed".
 */
public final class SeedCheck {
    /** Seeds worth trying per server, with where the rumour comes from (see README "World seed"). Only used once the login hash proves one. */
    private static final Object[][] KNOWN = {
        {"donut", 6608149111735331168L, "SeedcrackerX database, 2024-2026"},
        {"donut", 1183117317397157290L, "SeedcrackerX database, 2026"},
        {"donut", 2424310L, "\"Neon\" rumour"},
    };
    private static Object checked;

    public static boolean matches(long seed) {
        var level = Minecraft.getInstance().level;
        if (level == null) return false;
        return ((BiomeManagerAccessor) level.getBiomeManager()).vantage$biomeZoomSeed() == BiomeManager.obfuscateSeed(seed);
    }

    /** The hash the server sent at login, as a plain number, so a miss can be compared with seed databases later. */
    public static long loginHash() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : ((BiomeManagerAccessor) level.getBiomeManager()).vantage$biomeZoomSeed();
    }

    /** Once per world: tries the rumoured seeds for this server and says what it found. */
    public static void onWorld() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) { checked = null; return; }
        // every new level: a hub world and the survival world of one network carry different seeds
        if (mc.level == checked) return;
        checked = mc.level;
        LeakLog.note("SEED", "login hash " + loginHash());
        boolean tried = false;
        for (Object[] k : KNOWN) {
            if (!Config.serverKey().toLowerCase().contains((String) k[0])) continue;
            long seed = (Long) k[1];
            tried = true;
            boolean ok = matches(seed);
            LeakLog.note("SEED", seed + " " + k[2] + (ok ? " confirmed by the login hash" : " does not match the login hash"));
            if (ok) {
                Chat.send(Component.literal("World seed " + seed + " (" + k[2] + ") confirmed: the server's login hash matches").withStyle(ChatFormatting.GREEN));
                return;
            }
        }
        if (tried) Chat.info("No known seed matches this server's login hash " + loginHash() + ": seed results here stay " + SeedTrust.LABEL);
    }

    /** A seed typed by hand: a number, or text hashed the way vanilla hashes a text seed. */
    public static long parse(String text) {
        try { return Long.parseLong(text.trim()); } catch (NumberFormatException e) { return text.trim().hashCode(); }
    }

    /** ".seed 123" or ".seed Neon" in chat: says whether that seed matches this world's login hash. */
    public static void tryTyped(String text) {
        long seed = parse(text);
        boolean ok = matches(seed);
        LeakLog.note("SEED", seed + " (" + text.trim() + ")" + (ok ? " confirmed by the login hash" : " does not match the login hash"));
        if (ok) Chat.send(Component.literal("Seed " + seed + " matches this world's login hash").withStyle(ChatFormatting.GREEN));
        else Chat.info("Seed " + seed + " does not match this world's login hash (results with it are " + SeedTrust.LABEL + ")");
    }

    /** The chat command ".seed <text>". */
    public static dev.vantage.core.command.Command asCommand() {
        return dev.vantage.core.command.Command.named("seed", "Check a world seed (a number, or text) against this server's login hash")
                .arg(dev.vantage.core.command.Args.text("seed", "a number, or text that is hashed the way the game hashes a text seed"))
                .runs((a, r) -> tryTyped(a.get(0)));
    }

    private SeedCheck() {}
}
