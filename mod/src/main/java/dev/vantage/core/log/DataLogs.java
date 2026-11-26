package dev.vantage.core.log;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import dev.vantage.core.Config;
import dev.vantage.core.store.Stores;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game-side half of {@link RollingFile}: one instance per path, joined to the storage writer's tick and flush, and the name of
 * the server a line belongs to. The data logs (leaks, probe, sounds, profit, stutter) go through here.
 *
 * The server is known when a line is made, not when the file is written: the tail of a log is written after the player has left, and
 * {@code Config.serverKey()} then answers "unknown" (B-184). A line made with no server is dropped and counted with {@code once}.
 */
public final class DataLogs {
    private static final Logger GAME = LoggerFactory.getLogger("Vantage");
    private static final ConcurrentHashMap<Path, RollingFile> FILES = new ConcurrentHashMap<>();
    /** Per-server logs: 2 MB, 3 old files. */
    public static final long SERVER_MAX = 2L * 1024 * 1024;
    public static final int SERVER_KEEP = 3;

    private record Key(String raw, String key) {}

    private static volatile Key lastKey = new Key("", "");

    private DataLogs() {}

    /** The rolling file for a path; the same object every time, so batching and rotation have one owner. */
    public static RollingFile open(Path path, long maxBytes, int keep) {
        RollingFile f = FILES.get(path);
        if (f != null) return f;
        return FILES.computeIfAbsent(path, p -> {
            RollingFile r = new RollingFile(p, maxBytes, keep, System::currentTimeMillis, Stores.writerExecutor(), Stores.synchronousWrites(), msg -> GAME.warn(msg));
            Stores.register(new Stores.Writable() {
                @Override public void tick() { r.tick(); }
                @Override public void flushRequest() { r.flushRequest(); }
            });
            return r;
        });
    }

    /** The server (or single-player world) of the current game folder-safe, spelled like {@code Config.serverKey()}; null when there is none. */
    public static String serverKey() {
        Minecraft mc = Minecraft.getInstance();
        ServerData sd = mc.getCurrentServer();
        String raw;
        if (sd != null) raw = sd.ip;
        else if (mc.getSingleplayerServer() != null) raw = "sp-" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else return null;
        Key last = lastKey;
        if (last.raw.equals(raw)) return last.key;
        String key = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        lastKey = new Key(raw, key);
        return key;
    }

    /** Appends a record to {@code <dir>/<server><suffix>} for the server known right now; dropped (and counted once) when there is none. */
    public static void appendServer(String dir, String suffix, String record) {
        String server = serverKey();
        if (server == null) {
            noServer(dir, suffix);
            return;
        }
        appendFor(server, dir, suffix, record);
    }

    /** Counts a dropped line (once in the log, the number at exit). Always true, so a caller can write {@code if (serverKey() == null && noServer(...)) return}. */
    public static boolean noServer(String dir, String suffix) {
        Log.core("data-logs").once("no-server " + dir + suffix, () -> "a line for " + dir + "/<server>" + suffix + " arrived with no server and was dropped");
        return true;
    }

    /** Hands everything pending in every data log to the writer now; cheap when nothing waits. */
    public static void flushRequest() {
        for (RollingFile f : FILES.values()) f.flushRequest();
    }

    /** Appends to the file of an already known server. */
    public static void appendFor(String server, String dir, String suffix, String record) {
        open(Config.dir().resolve(dir).resolve(server + suffix), SERVER_MAX, SERVER_KEEP).append(record);
    }

    /** Appends to a log under the config folder that belongs to no server (stutter.log). */
    public static void appendGlobal(String name, long maxBytes, int keep, String record) {
        open(Config.dir().resolve(name), maxBytes, keep).append(record);
    }
}
