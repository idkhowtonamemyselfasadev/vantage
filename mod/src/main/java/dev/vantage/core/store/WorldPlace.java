package dev.vantage.core.store;

import dev.vantage.core.util.Dims;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Reads where the player is from the game and keeps the {@link Scope.Place} until something that decides it changes, so asking is
 * a few reference comparisons and no allocation. Client thread only. Kept apart from {@link Scope} so that the key rules can be
 * tested without the game.
 *
 * Between losing the player (a respawn, a dimension change) and getting the new one the answer stays what it was: the old rule
 * ({@code Config.serverKey()}) turned that gap into {@code unknown}.
 */
final class WorldPlace {
    private static Scope.Place cached;
    private static String address;
    private static MinecraftServer singleplayer;
    private static ResourceKey<Level> dimension;

    private WorldPlace() {}

    static Scope.Place current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            cached = null;
            address = null;
            singleplayer = null;
            dimension = null;
            return null;
        }
        if (mc.player == null) return cached;
        ServerData sd = mc.getCurrentServer();
        MinecraftServer sp = sd == null ? mc.getSingleplayerServer() : null;
        ResourceKey<Level> dim = mc.level.dimension();
        String ip = sd == null ? null : sd.ip;
        if (cached != null && dim == dimension && sp == singleplayer && (ip == null ? address == null : ip.equals(address))) return cached;
        Scope.Place p = null;
        if (ip != null) p = Scope.place(ip, null, null, Dims.id(dim));
        else if (sp != null) {
            var folder = sp.getWorldPath(LevelResource.ROOT).normalize().getFileName();
            p = Scope.place(null, folder == null ? null : folder.toString(), sp.getWorldData().getLevelName(), Dims.id(dim));
        }
        cached = p;
        address = ip;
        singleplayer = sp;
        dimension = dim;
        return p;
    }
}
