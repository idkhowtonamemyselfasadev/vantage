package dev.vantage.module.render;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.ChunkTracker;
import dev.vantage.core.Config;
import dev.vantage.core.LeakLog;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Toasts;
import dev.vantage.core.store.JsonStore;
import dev.vantage.core.util.Cooldown;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a player logged out near you. Two signals, either order within five seconds: their entity leaves the world and
 * their name leaves the player list. Walking out of view only gives the first, so that never marks a spot; a name that
 * drops from the list while the body is still in view is a certain logout and marks it at once. The spot is a ghost box of
 * their hitbox with the name, how long ago and the distance, kept until they come back online or the keep time runs out,
 * saved per server in config/vantage/logouts/<server>.json so a relog keeps it. Reads only.
 */
public class LogoutSpots extends Module {
    private static final long WINDOW_MS = 5000;

    public final ColorSetting color = add(new ColorSetting("Colour", "Box and label", 0xFFE06AE0));
    public final NumberSetting fillAlpha = add(new NumberSetting("Fill alpha", "Ghost fill", 40, 0, 200, 5));
    public final NumberSetting width = add(new NumberSetting("Line width", "", 2, 1, 5, 0.5));
    public final BoolSetting through = add(new BoolSetting("Through walls", "", true));
    public final NumberSetting keep = add(new NumberSetting("Keep", "Minutes a spot stays when they do not come back", 60, 5, 720, 5));
    public final BoolSetting chat = add(new BoolSetting("Chat", "Line when a spot appears", true));
    public final BoolSetting toast = add(new BoolSetting("Toast", "", true));
    public final BoolSetting leak = add(new BoolSetting("Leak log", "Also write LOGOUT lines to the leak log", true));

    public record Spot(UUID uuid, String name, double x, double y, double z, double w, double h, String dim, long time) {
        AABB box() { return new AABB(x - w / 2, y, z - w / 2, x + w / 2, y + h, z + w / 2); }
    }

    private final List<Spot> spots = new ArrayList<>();
    /** Bodies gone but names still listed, and names gone but bodies not seen leaving yet. */
    private final Map<UUID, Object[]> bodyGone = new HashMap<>(), nameGone = new HashMap<>();
    private String loadedFor;
    private final Cooldown tickGate = Cooldown.wall(1000);

    public LogoutSpots() {
        super("Logout Spots", "Ghost box where a player logged out near you, kept until they return", Category.RENDER);
        command(dev.vantage.core.command.Command.named("logouts", "List the logout spots, or forget them with clear").usableWhileOff()
                .arg(dev.vantage.core.command.Args.oneOf("action", "clear forgets every spot", List.of("clear")).optional())
                .runs((a, r) -> r.info(command(a.has(0) ? a.get(0) : ""))));
        // a player's body leaving and a name leaving the list, both seen before the game removes them, so position and name are still known
        listen(dev.vantage.core.event.PacketInEvent.class, e -> {
            if (mc.level == null) return;
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket p) {
                p.getEntityIds().forEach(id -> {
                    net.minecraft.world.entity.Entity gone = mc.level.getEntity(id);
                    if (gone != null) onEntityRemoved(gone);
                });
            } else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket p && mc.getConnection() != null) {
                for (java.util.UUID id : p.profileIds()) {
                    net.minecraft.client.multiplayer.PlayerInfo info = mc.getConnection().getPlayerInfo(id);
                    onNameRemoved(id, info != null ? info.getProfile().name() : null);
                }
            }
        });
    }

    public List<Spot> spots() { return spots; }

    @Override
    public String info() { return spots.isEmpty() ? null : spots.size() + ""; }

    // ---- the two signals, from the packet listener in the constructor ----

    /** An entity is about to be removed by a packet. */
    public void onEntityRemoved(Entity e) {
        if (!(e instanceof AbstractClientPlayer p) || p == mc.player) return;
        long now = System.currentTimeMillis();
        Object[] n = nameGone.remove(p.getUUID());
        if (n != null && now - (long) n[1] < WINDOW_MS) { mark(p.getUUID(), p.getGameProfile().name(), p.position(), p.getBbWidth(), p.getBbHeight()); return; }
        bodyGone.put(p.getUUID(), new Object[] {p.getGameProfile().name(), now, p.position(), p.getBbWidth(), p.getBbHeight()});
    }

    /** A name is leaving the player list. */
    public void onNameRemoved(UUID id, String name) {
        if (mc.player != null && id.equals(mc.player.getUUID())) return;
        long now = System.currentTimeMillis();
        Object[] b = bodyGone.remove(id);
        if (b != null && now - (long) b[1] < WINDOW_MS) { mark(id, name != null ? name : (String) b[0], (Vec3) b[2], (double) b[3], (double) b[4]); return; }
        if (mc.level != null) {
            var pl = mc.level.getPlayerByUUID(id);
            if (pl != null) { mark(id, pl.getGameProfile().name(), pl.position(), pl.getBbWidth(), pl.getBbHeight()); return; } // still in view: certain
        }
        nameGone.put(id, new Object[] {name, now});
    }

    /** For the rig: a logout at a made-up place. */
    public void simulate(String name, UUID id, Vec3 pos) {
        mark(id, name, pos, 0.6, 1.8);
    }

    private void mark(UUID id, String name, Vec3 pos, double w, double h) {
        if (name == null) name = "?";
        spots.removeIf(s -> s.uuid().equals(id));
        Spot s = new Spot(id, name, pos.x, pos.y, pos.z, w, h, ChunkTracker.dim(), System.currentTimeMillis());
        spots.add(s);
        String where = (int) Math.floor(pos.x) + " " + (int) Math.floor(pos.y) + " " + (int) Math.floor(pos.z);
        if (chat.on()) Chat.info("Logout: " + name + " at " + where);
        if (toast.on()) Toasts.ok("Logout spot", name + " at " + where);
        if (leak.on()) LeakLog.add("LOGOUT", (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z), name);
        save();
    }

    @Override
    public void onTick() {
        if (mc.level == null) return;
        String key = Config.serverKey();
        if (!key.equals(loadedFor)) load();
        long now = System.currentTimeMillis();
        if (!tickGate.tryUse()) return;
        bodyGone.values().removeIf(v -> now - (long) v[1] > WINDOW_MS);
        nameGone.values().removeIf(v -> now - (long) v[1] > WINDOW_MS);
        boolean changed = false;
        var conn = mc.getConnection();
        for (var it = spots.iterator(); it.hasNext();) {
            Spot s = it.next();
            boolean back = conn != null && conn.getPlayerInfo(s.uuid()) != null && now - s.time() > 3000;
            if (back || now - s.time() > keep.get() * 60_000) { it.remove(); changed = true; if (back && chat.on()) Chat.info(s.name() + " is back online, spot removed"); }
        }
        if (changed) save();
    }

    @Override
    public void onRender3D(R3 r) {
        if (spots.isEmpty()) return;
        String dim = ChunkTracker.dim();
        long now = System.currentTimeMillis();
        int c = color.argb();
        for (Spot s : spots) {
            if (!dim.equals(s.dim())) continue;
            AABB b = s.box();
            double d = mc.player.position().distanceTo(b.getCenter());
            // distance fade is R3's (lines, fills and labels), not applied a second time here
            EspStyle.box(r, b, c | 0xFF000000, true, fillAlpha.i() > 0, width.f(), fillAlpha.i() / 40f, through.on());
            r.label(new Vec3(s.x(), s.y() + s.h() + 0.45, s.z()), s.name() + "  ·  " + ago(now - s.time()) + "  ·  " + Math.round(d) + "m", c | 0xFF000000);
        }
    }

    public static String ago(long ms) {
        long m = ms / 60_000;
        return m < 1 ? "just now" : m < 60 ? m + "m ago" : (m / 60) + "h " + (m % 60) + "m ago";
    }

    // ---- storage ----

    private static Path file(String key) {
        return Config.dir().resolve("logouts").resolve(key + ".json");
    }

    /**
     * A bare array of spots, as the file has always been (no v field). On JsonStore, so a mark or an expiry only marks dirty and the file
     * is written on the storage thread. Once loaded, the store keeps writing to the file of the server it loaded (B9: a mark that arrives
     * a tick before the reload after a server change no longer lands in the new server's file).
     */
    private final JsonStore store = new JsonStore("logout spots", () -> file(loadedFor == null ? Config.serverKey() : loadedFor), 1, () -> {
        JsonArray arr = new JsonArray();
        for (Spot s : spots) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", s.uuid().toString());
            o.addProperty("name", s.name());
            o.addProperty("x", s.x()); o.addProperty("y", s.y()); o.addProperty("z", s.z());
            o.addProperty("w", s.w()); o.addProperty("h", s.h());
            o.addProperty("dim", s.dim());
            o.addProperty("time", s.time());
            arr.add(o);
        }
        return arr;
    }, (root, v) -> {
        List<Spot> loaded = new ArrayList<>();
        for (var e : root.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            loaded.add(new Spot(UUID.fromString(o.get("uuid").getAsString()), o.get("name").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(),
                    o.get("z").getAsDouble(), o.has("w") ? o.get("w").getAsDouble() : 0.6, o.has("h") ? o.get("h").getAsDouble() : 1.8, o.get("dim").getAsString(), o.get("time").getAsLong()));
        }
        spots.clear();
        spots.addAll(loaded);
    }).unversioned();

    private void load() {
        loadedFor = Config.serverKey(); // from here the store's path is this server's file; unsaved spots of the last one are handed over first
        if (!store.load()) spots.clear();
    }

    private void save() {
        store.markDirty();
    }

    /** ".logouts" lists the spots, ".logouts clear" forgets them. */
    public static String command(String args) {
        LogoutSpots m = Modules.get(LogoutSpots.class);
        if (m == null) return "Logout Spots is not loaded";
        if (args.trim().equalsIgnoreCase("clear")) { int n = m.spots.size(); m.spots.clear(); m.save(); return "Forgot " + n + " logout spots"; }
        if (m.spots.isEmpty()) return "No logout spots" + (m.on() ? "" : " (module is off)");
        StringBuilder sb = new StringBuilder(m.spots.size() + " logout spots:");
        long now = System.currentTimeMillis();
        for (Spot s : m.spots) sb.append("\n  ").append(s.name()).append("  ").append((int) s.x()).append(" ").append((int) s.y()).append(" ").append((int) s.z())
                .append("  ").append(s.dim()).append("  ").append(ago(now - s.time()));
        return sb.toString();
    }

    @Override
    public int labelClass() {
        return LABEL_NAME;
    }
}
