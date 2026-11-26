package dev.vantage.module.finder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Config;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.SeedCheck;
import dev.vantage.core.SeedTrust;
import dev.vantage.core.Ui;
import dev.vantage.core.hud.HudElements;
import dev.vantage.core.store.JsonStore;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.TextSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Seed Cracker. The client only COLLECTS: structure evidence it can see (buried treasure chests automatically, anything
 * else with `.crack add` / `.crack here`) goes into config/vantage/crack/&lt;server&gt;-evidence.json, and is copied to the
 * sync folder (see the "Sync folder" setting). The heavy search runs
 * in a separate cracker (not part of this repository) and writes &lt;server&gt;-result.json back; this module polls it. A seed the login hash confirms
 * is announced as the world's seed. Without a hash match the candidates stay unverified; `.crack check &lt;seed&gt;` checks any
 * seed against the login hash.
 * Maths: structure positions use the legacy 48-bit LCG, so they fix the low 48 bits; the cracker is not included here.
 */
public class SeedCrack extends Module {
    /** name -> {salt, spacing, separation, triangular(0/1)} from data/minecraft/worldgen/structure_set (1.21.11). */
    private static final Map<String, int[]> TYPES = Map.ofEntries(
        Map.entry("village", new int[] {10387312, 34, 8, 0}), Map.entry("ancient_city", new int[] {20083232, 24, 8, 0}),
        Map.entry("trial_chambers", new int[] {94251327, 34, 12, 0}), Map.entry("desert_pyramid", new int[] {14357617, 32, 8, 0}),
        Map.entry("igloo", new int[] {14357618, 32, 8, 0}), Map.entry("jungle_temple", new int[] {14357619, 32, 8, 0}),
        Map.entry("swamp_hut", new int[] {14357620, 32, 8, 0}), Map.entry("ocean_ruin", new int[] {14357621, 20, 8, 0}),
        Map.entry("shipwreck", new int[] {165745295, 24, 4, 0}), Map.entry("monument", new int[] {10387313, 32, 5, 1}),
        Map.entry("mansion", new int[] {10387319, 80, 20, 1}), Map.entry("ruined_portal", new int[] {34222645, 40, 15, 0}));
    private static final double NEED_BITS = 36;

    public final BoolSetting hud = add(new BoolSetting("HUD panel", "Progress panel on screen", true));
    public final ModeSetting corner = add(new ModeSetting("Corner", "Where the panel goes", "Top Right", Hud.CORNERS)).visibleIf(hud::on);
    public final BoolSetting treasure = add(new BoolSetting("Buried treasure", "Collect buried treasure chests (chest at 9,9 of a chunk under sand or stone) as evidence", true));
    public final TextSetting sync = add(new TextSetting("Sync folder", "Folder the home-server cracker reads evidence from and writes results to. Empty or missing folder = config/vantage/crack only", ""));

    private final List<JsonObject> obs = new ArrayList<>();
    private String key = "";
    private long stamp;
    private String state = "";
    private double progress = -1;
    private long structureSeeds;
    private int timer;
    private boolean announced;
    /** "" (nothing yet), "verified" (the login hash confirmed a seed) or "unverified" (candidates only). */
    private String trust = "";
    static {
        HudElements.declare("crack", "Seed Cracker", "Top Right", SeedCrack.class);
    }

    public SeedCrack() {
        super("Seed Cracker", "Collects structure evidence to recover the world seed (the search runs on the home server)", Category.FINDER);
        command(dev.vantage.core.command.Command.named("crack", "Seed cracker: show its state, add structure evidence, check a seed").usableWhileOff()
                .arg(dev.vantage.core.command.Args.text("args", "status, here <type> [r], add <type> cx cz [r], check <seed>, result, clear or types", List.of("status", "here", "add", "check", "result", "clear", "types")).optional())
                .runs((a, r) -> r.info(command(a.has(0) ? a.get(0) : ""))));
    }

    // ---- state ----

    @Override
    public String info() {
        return obs.size() + "/" + needPieces() + (state.isEmpty() ? "" : " " + state);
    }

    private double bits() {
        double b = 0;
        for (JsonObject o : obs) b += bitsOf(o);
        return b;
    }

    private static double bitsOf(JsonObject o) {
        if (o.get("kind").getAsString().equals("freq")) return -Math.log(o.get("p").getAsDouble()) / Math.log(2);
        int sp = o.get("spacing").getAsInt(), b = sp - o.get("sep").getAsInt();
        int wx = Math.min(b, o.get("cx1").getAsInt() - o.get("cx0").getAsInt() + 1), wz = Math.min(b, o.get("cz1").getAsInt() - o.get("cz0").getAsInt() + 1);
        return Math.log((double) b * b / ((double) wx * wz)) / Math.log(2);
    }

    private int needPieces() {
        double avg = obs.isEmpty() ? 6.64 : bits() / obs.size();
        return Math.max(obs.size(), (int) Math.ceil(NEED_BITS / Math.max(avg, 1)));
    }

    // ---- files ----

    private Path dir() { return Config.dir().resolve("crack"); }

    private Path syncDir() {
        String s = sync.text() == null ? "" : sync.text().trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("~")) s = System.getProperty("user.home") + s.substring(1);
        Path p = Path.of(s);
        return Files.isDirectory(p) ? p : null;
    }

    private String file(String suffix) { return key + "-" + suffix + ".json"; }

    /** The evidence as the cracker reads it ({version, stamp, dimension, loginHash?, obs}): the field "version" is part of that format, so JsonStore adds no v. */
    private JsonObject evidenceTree() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("stamp", stamp);
        o.addProperty("dimension", "overworld");
        if (mc.level != null) o.addProperty("loginHash", SeedCheck.loginHash());
        JsonArray a = new JsonArray();
        for (JsonObject x : obs) a.add(x.deepCopy());
        o.add("obs", a);
        return o;
    }

    private final JsonStore evidenceStore = new JsonStore("crack evidence", () -> key.isEmpty() ? null : dir().resolve(file("evidence")), 1, this::evidenceTree, (root, v) -> {
        JsonObject o = root.getAsJsonObject();
        obs.clear();
        for (var e : o.getAsJsonArray("obs")) obs.add(e.getAsJsonObject());
        stamp = o.has("stamp") ? o.get("stamp").getAsLong() : 0;
    }).unversioned();

    /**
     * The copy in the user's sync folder, which the cracker reads from there. A second store of its own, never loaded (the config copy is
     * the one the game reads back); the temp and backup files it makes next to it do not match the cracker's *-evidence.json search.
     */
    private final JsonStore syncStore = new JsonStore("crack evidence (sync folder)", () -> syncDir() == null || key.isEmpty() ? null : syncDir().resolve(file("evidence")), 1,
            this::evidenceTree, (root, v) -> { }).unversioned();

    private void load() {
        // the evidence of the last server is handed to the writer first (the store knows which file it belongs to), then this one is read
        boolean ok = evidenceStore.load();
        if (!ok) {
            obs.clear();
            stamp = 0;
        }
        state = ""; progress = -1; structureSeeds = 0; announced = false;
    }

    /** Writes the evidence for the cracker (config copy + sync folder) with a new stamp. */
    private void save() {
        if (key.isEmpty()) return;
        stamp = System.currentTimeMillis();
        evidenceStore.markDirty();
        if (syncDir() != null) syncStore.markDirty();
        state = "";
        progress = -1;
        announced = false;
    }

    // ---- collecting ----

    private boolean has(String kind, int a, int b, String name) {
        for (JsonObject o : obs) {
            if (!o.get("kind").getAsString().equals(kind)) continue;
            if (kind.equals("freq") ? o.get("a").getAsInt() == a && o.get("b").getAsInt() == b
                    : o.get("name").getAsString().equals(name) && o.get("cx0").getAsInt() <= a && o.get("cx1").getAsInt() >= a && o.get("cz0").getAsInt() <= b && o.get("cz1").getAsInt() >= b) return true;
        }
        return false;
    }

    public boolean addTreasure(int cx, int cz) {
        if (has("freq", cx, cz, "buried_treasure")) return false;
        JsonObject o = new JsonObject();
        o.addProperty("kind", "freq"); o.addProperty("name", "buried_treasure");
        o.addProperty("a", cx); o.addProperty("b", cz); o.addProperty("c", 10387320); o.addProperty("p", 0.01); o.addProperty("present", true);
        obs.add(o);
        return true;
    }

    /** A structure whose start chunk is within r chunks of (cx, cz). */
    public String addStructure(String type, int cx, int cz, int r) {
        int[] t = TYPES.get(type);
        if (t == null) return "unknown type " + type + ". Known: treasure, " + String.join(", ", new java.util.TreeSet<>(TYPES.keySet()));
        if (has("spread", cx, cz, type)) return "already have that " + type;
        JsonObject o = new JsonObject();
        o.addProperty("kind", "spread"); o.addProperty("name", type);
        o.addProperty("salt", t[0]); o.addProperty("spacing", t[1]); o.addProperty("sep", t[2]); o.addProperty("triangular", t[3] == 1);
        o.addProperty("cx0", cx - r); o.addProperty("cx1", cx + r); o.addProperty("cz0", cz - r); o.addProperty("cz1", cz + r);
        obs.add(o);
        return null;
    }

    @Override
    protected void onEnable() {
        key = "";
        sync0();
    }

    private void sync0() {
        if (mc.level == null) return;
        String k = Config.serverKey();
        if (k.equals(key)) return;
        key = k;
        load();
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
        key = "";
    }

    @Override
    public void onChunkLoad(LevelChunk chunk) {
        if (!treasure.on() || mc.level == null || mc.level.dimension() != Level.OVERWORLD || key.isEmpty()) return;
        for (Map.Entry<net.minecraft.core.BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
            var p = e.getKey();
            if (!(e.getValue() instanceof ChestBlockEntity) || (p.getX() & 15) != 9 || (p.getZ() & 15) != 9) continue;
            var above = chunk.getBlockState(p.above());
            // buried treasure: the chest sits under sand/gravel/sandstone/stone, never in a built place (no air above, no player block)
            if (above.isAir() || !above.getFluidState().isEmpty() || !(above.is(BlockTags.SAND) || above.is(Blocks.GRAVEL) || above.is(Blocks.STONE) || above.is(Blocks.SANDSTONE) || above.is(Blocks.DIRT))) continue;
            if (addTreasure(chunk.getPos().x, chunk.getPos().z)) {
                save();
                Chat.send(Component.literal("Buried treasure at chunk " + chunk.getPos().x + " " + chunk.getPos().z + " saved: structure data " + obs.size() + "/" + needPieces() + " pieces").withStyle(ChatFormatting.GRAY));
            }
        }
    }

    // ---- results ----

    private void poll() {
        if (obs.isEmpty()) return;
        List<Path> where = new ArrayList<>();
        if (syncDir() != null) where.add(syncDir().resolve(file("result")));
        where.add(dir().resolve(file("result")));
        for (Path f : where) {
            try {
                if (!Files.exists(f)) continue;
                JsonObject r = new Gson().fromJson(Files.readString(f), JsonObject.class);
                if (r == null || !r.has("stamp") || r.get("stamp").getAsLong() != stamp) continue;
                result(r);
                return;
            } catch (Exception e) { /* half-written file: next poll */ }
        }
        if (state.isEmpty()) state = "waiting for the cracker";
    }

    private void result(JsonObject r) {
        String st = r.get("status").getAsString();
        progress = r.has("progress") ? r.get("progress").getAsDouble() : -1;
        structureSeeds = r.has("structureSeedCount") ? r.get("structureSeedCount").getAsLong() : 0;
        switch (st) {
            case "need-more" -> state = "need more pieces";
            case "running" -> state = String.format(Locale.ROOT, "searching %.1f%%", progress * 100);
            case "error" -> state = "cracker error";
            case "done" -> {
                if (announced) return;
                announced = true;
                var conf = r.getAsJsonArray("confirmedSeeds");
                if (conf != null && conf.size() > 0) {
                    long seed = conf.get(0).getAsLong();
                    if (SeedCheck.matches(seed)) { found(seed, "the login hash confirms it"); return; }
                    Chat.info("Cracker said " + seed + " but this world's login hash does not match (another world?)");
                }
                state = structureSeeds + " candidates (unverified)";
                trust = "unverified";
                Chat.send(Component.literal("Cracker done: " + structureSeeds + " structure seed(s), none matches the login hash, so they stay " + SeedTrust.LABEL + ". More structures (.crack add) narrow them down").withStyle(ChatFormatting.YELLOW));
            }
            default -> state = st;
        }
    }

    private void found(long seed, String why) {
        trust = "verified";
        state = "SEED " + seed;
        Chat.send(Component.literal("SEED FOUND: " + seed + " (" + why + ")").withStyle(ChatFormatting.GREEN));
        dev.vantage.core.LeakLog.note("SEED", "cracked " + seed + " (" + why + ")");
    }

    /** "", "verified" or "unverified": what the last result can be believed as (for the rig). */
    public String trust() { return trust; }

    /** The line the panel shows under the state, or "" (for the rig). */
    public String trustLine() {
        return trust.isEmpty() ? "" : trust.equals("verified") ? "verified by the login hash" : SeedTrust.LABEL;
    }

    // ---- tick / HUD ----

    @Override
    public void onTick() {
        sync0();
        if (mc.level == null || key.isEmpty()) return;
        if (++timer >= 100) { timer = 0; poll(); }
    }

    /** Rig: the width of the last panel drawn. */
    public static float panelW;
    /** Rig: draw the panel without any observation. */
    public boolean testShow;

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (!hud.on() || (obs.isEmpty() && !testShow) || Hud.hidden()) return;
        float w = SeedTrust.panelWidth(170), h = trust.isEmpty() ? 58 : 72;
        panelW = w;
        float[] at = Hud.slot("crack", corner.get(), w, h);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        Ui.panel(g, x, y, w, h);
        Ui.text(g, "SEED CRACKER", x + 10, y + 8, Ui.FAINT, 0.85f, false);
        Ui.hline(g, x + 10, y + 23, w - 20, Ui.LINE);
        Ui.text(g, "structure data: " + obs.size() + "/" + needPieces() + " pieces", x + 10, y + 29, Ui.TEXT, 0.9f, false);
        Ui.text(g, state.isEmpty() ? String.format(Locale.ROOT, "%.0f of %.0f bits", bits(), NEED_BITS) : state, x + 10, y + 42, Ui.SUB, 0.88f, false);
        if (!trust.isEmpty()) Ui.text(g, trust.equals("verified") ? "verified by the login hash" : SeedTrust.LABEL, x + 10, y + 57, trust.equals("verified") ? Ui.GREEN : Ui.YELLOW, 0.85f, false);
        Ui.end(g);
    }

    // ---- .crack ----

    public static String command(String arg) {
        var m = Modules.get(SeedCrack.class);
        m.sync0();
        String[] a = arg.trim().isEmpty() ? new String[0] : arg.trim().split("\\s+");
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "status" -> { m.poll(); return m.status(); }
                case "result" -> { m.announced = false; m.poll(); return m.status(); }
                case "clear" -> { m.obs.clear(); m.save(); return "Evidence cleared"; }
                case "types" -> { return "treasure (a chest at 9,9 under sand; automatic), " + String.join(", ", new java.util.TreeSet<>(TYPES.keySet())); }
                case "here" -> {
                    if (a.length < 2 || m.mc.player == null) return ".crack here <type> [chunk radius]  (stand in the structure's start chunk: well of a village, middle of a temple)";
                    return m.add(a[1], m.mc.player.chunkPosition().x, m.mc.player.chunkPosition().z, a.length > 2 ? Integer.parseInt(a[2]) : 0);
                }
                case "add" -> {
                    if (a.length < 4) return ".crack add <type> <chunkX> <chunkZ> [chunk radius]";
                    return m.add(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]), a.length > 4 ? Integer.parseInt(a[4]) : 0);
                }
                case "check" -> {
                    if (a.length < 2) return ".crack check <seed or text seed>";
                    return m.check(SeedCheck.parse(arg.trim().substring(5)));
                }
                default -> { return "usage: .crack [status|here <type> [r]|add <type> cx cz [r]|check <seed>|result|clear|types]"; }
            }
        } catch (NumberFormatException e) {
            return "numbers expected";
        }
    }

    private String add(String type, int cx, int cz, int r) {
        String err = type.equals("treasure") ? (addTreasure(cx, cz) ? null : "already have that chunk") : addStructure(type, cx, cz, r);
        if (err != null) return err;
        save();
        return "Saved " + type + " at chunk " + cx + " " + cz + ": structure data " + obs.size() + "/" + needPieces() + " pieces, " + String.format(Locale.ROOT, "%.0f of %.0f bits", bits(), NEED_BITS);
    }

    private String status() {
        if (obs.isEmpty()) return "No structure data yet. Walk beaches for buried treasure, or stand at a village/temple and type .crack here <type>. .crack types lists them";
        return "structure data: " + obs.size() + "/" + needPieces() + " pieces (" + String.format(Locale.ROOT, "%.0f of %.0f bits", bits(), NEED_BITS) + "), " + (state.isEmpty() ? "not sent yet" : state)
                + (structureSeeds > 0 ? ", " + structureSeeds + " candidates" : "") + (syncDir() == null ? ". Sync folder missing: copy config/vantage/crack/" + file("evidence") + " to the server by hand" : "");
    }

    /** Checks one seed against the login hash. */
    public String check(long seed) {
        if (mc.level == null) return "Join a world first";
        String line = "Seed " + seed + (SeedCheck.matches(seed) ? ": login hash MATCHES." : ": login hash does not match (" + SeedTrust.LABEL + ").");
        dev.vantage.core.LeakLog.note("SEED", line);
        return line;
    }
}
