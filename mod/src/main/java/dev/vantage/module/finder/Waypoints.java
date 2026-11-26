package dev.vantage.module.finder;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Config;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.store.JsonStore;
import dev.vantage.core.util.Dims;
import dev.vantage.core.util.Fmt;
import dev.vantage.gui.WaypointScreen;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.ActionSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Saved points per server, drawn as a beam + label through walls. Finders can drop waypoints here too. */
public class Waypoints extends Module {
    public static class Waypoint {
        public String name;
        public int x, y, z;
        public String dim;
        public int color;
        public boolean visible = true;

        public Waypoint(String name, int x, int y, int z, String dim, int color) {
            this.name = name; this.x = x; this.y = y; this.z = z; this.dim = dim; this.color = color;
        }
    }

    public static final int[] PALETTE = {0xFF6CBC7A, 0xFF78A8A8, 0xFFE0BD62, 0xFFDE6F4D, 0xFFAD879B, 0xFF7FBEA4, 0xFFEEF3EE};

    public final BoolSetting beams = add(new BoolSetting("Beams", "Vertical line through the whole world height", true));
    public final BoolSetting labels = add(new BoolSetting("Labels", "Name and distance above the point", true));
    public final NumberSetting maxDist = add(new NumberSetting("Max distance", "Hide waypoints further than this (0 = no limit)", 0, 0, 30000, 100));
    public final BoolSetting deaths = add(new BoolSetting("Death points", "Drop a waypoint where you die", true));
    public final NumberSetting deathBeams = add(new NumberSetting("Death beams", "Only the newest N death points keep their beam. Box and label stay for all of them.", 3, 0, 20, 1));
    /** For the rig: death point beams drawn in the last frame. */
    public static int deathBeamsDrawn;
    public final BoolSetting netherCoords = add(new BoolSetting("Cross-dimension", "Show overworld points in the Nether (/8) and the other way round", true));

    private final List<Waypoint> list = new ArrayList<>();
    private String loadedFor = "";
    private boolean wasDead;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Waypoints() {
        super("Waypoints", "Saved points with beams and distance labels", Category.FINDER);
        onByDefault();
        add(new ActionSetting("Open manager", "List, add, hide and delete waypoints", () -> mc.setScreen(new WaypointScreen(mc.screen))));
        add(new ActionSetting("Add here", "Waypoint at your feet", () -> {
            if (mc.player == null) return;
            var p = mc.player.blockPosition();
            add("Point " + (list.size() + 1), p.getX(), p.getY(), p.getZ(), PALETTE[list.size() % PALETTE.length]);
        }));
    }

    public static Waypoints get() {
        return Modules.get(Waypoints.class);
    }

    public List<Waypoint> list() {
        ensureLoaded();
        return list;
    }

    public void ensureLoaded() {
        if (mc.level == null) return;
        String key = Config.serverKey();
        if (!key.equals(loadedFor)) load(key);
    }

    public Waypoint add(String name, int x, int y, int z, int color) {
        ensureLoaded();
        String dim = mc.level == null ? Dims.OVERWORLD : Dims.id(mc.level);
        // don't stack duplicates on the same block
        for (Waypoint w : list) if (w.dim.equals(dim) && w.x == x && w.y == y && w.z == z) return w;
        Waypoint w = new Waypoint(name, x, y, z, dim, color);
        list.add(w);
        save();
        Chat.send(Component.literal("waypoint ").withColor(0xFF7D8A80).append(Component.literal(name).withColor(color))
                .append(Component.literal(" at " + Chat.coords(x, y, z)).withColor(0xFF7D8A80)));
        return w;
    }

    public void remove(Waypoint w) {
        list.remove(w);
        save();
    }

    @Override
    public void onJoinWorld() {
        forgetWorld();
    }

    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    private void forgetWorld() {
        loadedFor = "";
        wasDead = false;
    }

    @Override
    public void onTick() {
        ensureLoaded();
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead && deaths.on()) {
            var p = mc.player.blockPosition();
            add("Death " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), p.getX(), p.getY(), p.getZ(), 0xFFDE6F4D);
        }
        wasDead = dead;
    }

    /** Where to draw a waypoint in the current dimension, or null if it belongs elsewhere. */
    public Vec3 shownPos(Waypoint w) {
        String dim = Dims.id(mc.level);
        if (w.dim.equals(dim)) return new Vec3(w.x + 0.5, w.y, w.z + 0.5);
        if (!netherCoords.on()) return null;
        boolean inNether = dim.equals(Dims.NETHER);
        if (inNether && w.dim.equals(Dims.OVERWORLD)) return new Vec3(Dims.overworldToNether(w.x) + 0.5, w.y, Dims.overworldToNether(w.z) + 0.5);
        if (dim.equals(Dims.OVERWORLD) && w.dim.equals(Dims.NETHER)) return new Vec3(Dims.netherToOverworld(w.x) + 0.5, w.y, Dims.netherToOverworld(w.z) + 0.5);
        return null;
    }

    @Override
    public void onRender3D(R3 r) {
        int minY = mc.level.getMinY(), maxY = mc.level.getMaxY();
        // the newest few death points keep their beam; dying a lot must not plant a forest of red lines
        java.util.Set<Waypoint> beamed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int deathsLeft = deathBeams.i();
        for (int i = list.size() - 1; i >= 0; i--) {
            Waypoint w = list.get(i);
            if (!w.name.startsWith("Death ")) { beamed.add(w); continue; }
            if (deathsLeft > 0) { beamed.add(w); deathsLeft--; }
        }
        int deathBeamCount = 0;
        for (Waypoint w : list) {
            if (!w.visible) continue;
            Vec3 p = shownPos(w);
            if (p == null) continue;
            double dist = r.cam.distanceTo(p);
            if (maxDist.get() > 0 && dist > maxDist.get()) continue;
            boolean foreign = !w.dim.equals(Dims.id(mc.level));
            int c = foreign ? (w.color & 0xFFFFFF) | 0x90000000 : w.color;
            if (beams.on() && beamed.contains(w)) {
                EspStyle.beam(r, p.x, minY, maxY, p.z, c);
                if (w.name.startsWith("Death ")) deathBeamCount++;
            }
            EspStyle.box(r, new AABB(p.x - 0.5, p.y, p.z - 0.5, p.x + 0.5, p.y + 1, p.z + 0.5), c, true);
            if (labels.on()) {
                // labels far away are pulled closer so they stay on screen and readable
                Vec3 lp = p.add(0, 1.6, 0);
                if (dist > 128) lp = r.cam.add(lp.subtract(r.cam).normalize().scale(128));
                r.label(lp, w.name + (foreign ? " *" : "") + "  " + Fmt.distance(dist), c);
            }
        }
        deathBeamsDrawn = deathBeamCount;
    }

    private Path file(String key) {
        return Config.dir().resolve("waypoints").resolve(key + ".json");
    }

    /** A pretty-printed list, all dimensions of the server in one file; on JsonStore, so it is written off the client thread and a damaged file is never written over. */
    private final JsonStore store = new JsonStore("waypoints", () -> loadedFor.isEmpty() ? null : file(loadedFor), 1, () -> GSON.toJsonTree(list), (root, v) -> {
        List<Waypoint> l = GSON.fromJson(root, new TypeToken<List<Waypoint>>() {}.getType());
        list.clear();
        if (l != null) list.addAll(l);
    }).pretty().unversioned();

    private void load(String key) {
        loadedFor = key; // from here the store's path is this server's file; an unsaved change of the old server is handed over first
        if (!store.load()) list.clear();
    }

    public void save() {
        if (loadedFor.isEmpty()) return;
        store.markDirty();
    }

    @Override
    public int labelClass() {
        return LABEL_NAME;
    }
}
