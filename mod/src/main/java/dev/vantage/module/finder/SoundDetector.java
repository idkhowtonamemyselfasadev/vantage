package dev.vantage.module.finder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.LeakLog;
import dev.vantage.core.Module;
import dev.vantage.core.Toasts;
import dev.vantage.core.Ui;
import dev.vantage.core.log.DataLogs;
import dev.vantage.core.util.ChunkMath;
import dev.vantage.core.util.Colors;
import dev.vantage.core.util.Compass;
import dev.vantage.core.util.Dims;
import dev.vantage.core.util.Dist;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds farms, storage and players by ear: every positioned sound and level event the server sends (it sends them to anyone within
 * 16 blocks, louder ones further, whether the source is visible or not) is sorted into a kind, weighted by how much it says about
 * players, and summed per chunk with a 2 minute half-life. A chunk past the threshold is a find (column, label, chat, toast); single
 * strong signs (a container opening with nobody there, footsteps or player noises with no player in sight, a copper golem sorting
 * chests) are red flags on their own. Weather, cave ambience, water, lava, fire, dripstone, sculk, amethyst, bats, fish and wild
 * monster noises are dropped; sounds next to you or next to a visible player are theirs, not a find. Read-only: nothing is sent.
 */
public class SoundDetector extends Module {
    private static final long HALF_MS = 120_000, HOLD_MS = 600_000, FLAG_MS = 90_000, WINDOW_MS = 30_000;
    private static final double CAT_CAP = 6; // one kind may add at most this much per 30 s, so one noisy source never makes a find alone
    private static final int C_FIND = 0xFFE8963C, C_FLAG = 0xFFE06A6A;
    public static final BaseLogic.Kind HEARD = BaseLogic.HEARD;

    /** The switches the owner's spec asks for: each group can be turned off and is named in the reasons. */
    public enum Group { STEPS, BUILD, GATHER, CRAFT, STORAGE, FARM, PLAYER, BASE }

    public enum Cat {
        PLAYER_STEPS("footsteps, nobody there", "footsteps", 3, true, Group.STEPS),
        UNSEEN_STEPS("unseen mob footsteps", "unseen steps", 0.4, false, Group.STEPS),
        PLAYER_NOISE("player noises, nobody there", "player noises", 8, true, Group.PLAYER),
        ITEMS("item pickup", "pickups", 1.5, false, Group.PLAYER),
        CONTAINER_HIDDEN("container opened, nobody there", "hidden container opens", 8, true, Group.STORAGE),
        GOLEM_SORT("copper golem sorting chests", "golem sorting", 6, true, Group.STORAGE),
        CONTAINER("container used", "container opens", 3, false, Group.STORAGE),
        VILLAGER("villager", "villager sounds", 0.8, false, Group.FARM),
        JOB("job site", "job sites", 2, false, Group.FARM),
        TRADE("trade", "trades", 3, false, Group.FARM),
        IRON_GOLEM("iron golem", "iron golem", 1, false, Group.FARM),
        ANIMAL("animal", "animal sounds", 0.4, false, Group.FARM),
        DEATH("mob death", "mob deaths", 1.5, false, Group.FARM),
        CROP("bone meal, planting, tilling", "crop work", 2, false, Group.FARM),
        MACHINE("piston / dispenser / dropper / hopper", "machine", 2, false, Group.BUILD),
        PLACED("block placed, nobody there", "blocks placed", 3, false, Group.BUILD),
        DOOR("door / gate / trapdoor", "doors", 2.5, false, Group.BUILD),
        ANVIL("anvil", "anvil", 4, false, Group.BUILD),
        BROKEN("block mined, nobody there", "blocks mined", 1.5, false, Group.GATHER),
        CHOP("wood chopped, nobody there", "wood chopped", 2, false, Group.GATHER),
        HARVEST("crop harvested, nobody there", "crops harvested", 2, false, Group.GATHER),
        STATION("furnace / brewing / crafter / grindstone / smithing / enchanting / loom / stonecutter / composter", "crafting", 3, false, Group.CRAFT),
        REDSTONE("lever / button / plate", "redstone clicks", 3, false, Group.BASE),
        UTILITY("beacon / anchor / lodestone / portal / lectern / wax", "utility", 4, false, Group.BASE),
        NOTE("note block / bell / jukebox", "music", 2, false, Group.BASE),
        DECOR("item frame / armor stand", "frames, stands", 2, false, Group.BASE);

        public final String flag, noun;
        public final double weight;
        public final boolean strong;
        public final Group group;

        Cat(String flag, String noun, double weight, boolean strong, Group group) {
            this.flag = flag; this.noun = noun; this.weight = weight; this.strong = strong; this.group = group;
        }
    }

    /** What a sound is before looking at who is around: steps, containers and breaks are split by the caller. */
    public enum Raw { STEP_PLAYER, STEP_MOB, STEP_VILLAGER, CONTAINER, COPPER_CHEST, PLAYER, GOLEM_SORT, VILLAGER, JOB, TRADE, IRON_GOLEM, ANIMAL, DEATH,
        MACHINE, CROP, REDSTONE, DOOR, ANVIL, UTILITY, STATION, NOTE, PLACE, BREAK, CHOP, HARVEST, DECOR, ITEMS }

    public final BoolSetting steps = add(new BoolSetting("Footsteps", "Steps with nobody in sight: players, and mobs you can't see walking", true));
    public final BoolSetting build = add(new BoolSetting("Construction", "Blocks placed, pistons, dispensers, droppers, anvils, doors and trapdoors", true));
    public final BoolSetting gather = add(new BoolSetting("Resource gathering", "Stone and ore mined, wood chopped, crops harvested where no player is visible", true));
    public final BoolSetting craft = add(new BoolSetting("Crafting stations", "Furnaces, brewing stands, crafters, grindstones, smithing, enchanting, looms, stonecutters, composters", true));
    public final BoolSetting storage = add(new BoolSetting("Containers", "Chests, barrels and shulkers opening; red flag when nobody is there", true));
    public final BoolSetting farms = add(new BoolSetting("Villagers & animals", "Villagers, job sites, trades, golems, animals, mob deaths in one spot, bone meal", true));
    public final BoolSetting players = add(new BoolSetting("Hidden players", "Eating, armour, pearls, totems and item pickups with no player in sight", true));
    public final BoolSetting blocks = add(new BoolSetting("Base blocks", "Levers, buttons, beacons, anchors, note blocks, jukeboxes, frames", true));
    public final BoolSetting ignoreVisible = add(new BoolSetting("Ignore visible players", "Sounds right next to a player you can see are theirs", true));
    public final ModeSetting sensitivity = add(new ModeSetting("Sensitivity", "High finds quieter places (and more false ones)", "Medium", "Low", "Medium", "High"));
    public final NumberSetting nearMe = add(new NumberSetting("Ignore near me", "Sounds this close to you are skipped", 3, 0, 12, 1));
    public final BoolSetting arrow = add(new BoolSetting("Arrow", "Arrow at the top of the screen to the strongest place right now, with distance", true));
    public final BoolSetting chat = add(new BoolSetting("Chat", "Chat line for each new find and red flag (plus the HUD ping sound if that is on)", true));
    public final BoolSetting toast = add(new BoolSetting("Toast", "Card for each new find", true));
    public final BoolSetting panel = add(new BoolSetting("Panel", "List of finds and red flags on the HUD", true));
    public final BoolSetting waypoint = add(new BoolSetting("Waypoint on find", "Adds a waypoint for strong places (twice the threshold, or recurring)", false));
    public final BoolSetting log = add(new BoolSetting("Sound log", "Writes every counted sound to config/vantage/sounds/<server>.log", true));
    public final NumberSetting range = add(new NumberSetting("Range", "Draw finds this close", 160, 32, 512, 16));

    BoolSetting groupSetting(Group g) {
        return switch (g) {
            case STEPS -> steps; case BUILD -> build; case GATHER -> gather; case CRAFT -> craft;
            case STORAGE -> storage; case FARM -> farms; case PLAYER -> players; case BASE -> blocks;
        };
    }

    public static final class Cluster {
        public final int cx, cz;
        public final EnumMap<Cat, Integer> counts = new EnumMap<>(Cat.class);
        private final EnumMap<Cat, double[]> window = new EnumMap<>(Cat.class); // {start, added}
        final ArrayDeque<double[]> deaths = new ArrayDeque<>(); // x, y, z, time
        final ArrayDeque<double[]> recent = new ArrayDeque<>(); // time, weight: last minute, for the strength
        public double score;
        public long first, last, flaggedAt;
        public boolean recurring, waypointed, alerted;
        double sx, sy, sz, sw;
        public String title = "";

        Cluster(int cx, int cz) { this.cx = cx; this.cz = cz; }

        public Vec3 pos() { return sw == 0 ? new Vec3((cx << 4) + 8, 64, (cz << 4) + 8) : new Vec3(sx / sw, sy / sw, sz / sw); }
        public boolean flagged() { return flaggedAt > 0; }
        public int count(Cat c) { return counts.getOrDefault(c, 0); }

        /** What the sounds were made of (kelp, cherry, skeleton, tnt...): names the farm, never adds score. */
        public final Map<String, Integer> tags = new HashMap<>();
        public int tag(String t) { return tags.getOrDefault(t, 0); }

        /** Strength: weighted hits in the last minute. */
        public double perMinute(long now) {
            double t = 0;
            for (double[] r : recent) if (now - r[0] <= 60_000) t += r[1];
            return t;
        }

        /** "14 villager sounds, 3 job sites, 2 trades": the three loudest kinds. */
        public String reasons() {
            List<Map.Entry<Cat, Integer>> l = new ArrayList<>(counts.entrySet());
            l.sort((a, b) -> Double.compare(b.getValue() * b.getKey().weight, a.getValue() * a.getKey().weight));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < Math.min(3, l.size()); i++) s.append(i > 0 ? ", " : "").append(l.get(i).getValue()).append(' ').append(l.get(i).getKey().noun);
            return s.toString();
        }
    }

    public record Flag(Cat cat, Vec3 pos, long time) {}

    private final Map<Long, Cluster> clusters = new HashMap<>();
    private final List<Flag> flags = new ArrayList<>();
    private final Map<Long, Long> flagPinged = new HashMap<>();
    private long lastAlert, lastContainerScreen;
    /** Rig counters. */
    public static int heard, flagsRaised, findsRaised;

    public SoundDetector() {
        super("Sound Detector", "Finds farms, storage and hidden players from the sounds the server sends you, surface or underground", Category.FINDER);
        listen(dev.vantage.core.event.PacketHandledEvent.class, e -> {
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket p) onSound(p);
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundLevelEventPacket p) onLevelEvent(p);
        });
        // bottom left stack: the old fixed spot (y 42 % of the screen) overlapped the Debris panel and the Donut Wheel RTP card
        hudPanel("sound-detector", "Sound Detector panel", "Bottom Left").overScreens().when(() -> panel.on() && mc.player != null && (!flags.isEmpty() || anyFind())).size(wh -> {
            buildRows();
            wh[0] = 340;
            wh[1] = 34 + panelList.size() * 15;
        }).draw((g, x, y, w, h) -> {
            Ui.rr(g, x, y, w, h, 7, Ui.GLASS, Ui.BORDER);
            Ui.text(g, "SOUND DETECTOR", x + 10, y + 8, Ui.TEXT, 1.05f, false);
            Ui.hline(g, x + 10, y + 26, w - 20, Ui.LINE);
            float ly = y + 31;
            for (Object[] row : panelList) {
                Ui.text(g, (String) row[0], x + 10, ly, (Integer) row[1], 0.85f, false);
                ly += 15;
                panelRows++;
            }
        });
    }

    /** The panel's rows (text, colour), built when it is measured: the nearest finds first, then the recent flags. */
    private final List<Object[]> panelList = new ArrayList<>();

    private void buildRows() {
        panelList.clear();
        List<Cluster> f = finds();
        Vec3 me = mc.player.position();
        long now = System.currentTimeMillis();
        f.sort((a, b) -> Double.compare(a.pos().distanceToSqr(me), b.pos().distanceToSqr(me)));
        for (int i = 0; i < Math.min(4, f.size()); i++) {
            Cluster c = f.get(i);
            Vec3 p = c.pos();
            double pm = c.perMinute(now);
            panelList.add(new Object[] { cap(c.title) + "  " + (int) Math.sqrt(p.distanceToSqr(me)) + "m " + Compass.name(p.x - me.x, p.z - me.z)
                    + String.format(Locale.ROOT, "  %.0f/min  ", pm) + c.reasons(), colorOf(pm) });
        }
        for (int i = flags.size() - 1, n = 0; i >= 0 && n < 3; i--, n++) {
            Flag fl = flags.get(i);
            panelList.add(new Object[] { cap(fl.cat().flag) + "  " + (int) Math.sqrt(fl.pos().distanceToSqr(me)) + "m  " + (now - fl.time()) / 1000 + "s ago", Ui.RED });
        }
    }

    @Override
    public String info() {
        long n = clusters.values().stream().filter(Cluster::flagged).count();
        return n + (flags.isEmpty() ? "" : " · " + flags.size() + " flags") + (fed > 0 ? " · " + fed + " fed to Base Finder" : "");
    }

    private boolean anyFind() {
        for (Cluster c : clusters.values()) if (c.flagged()) return true;
        return false;
    }

    public List<Cluster> finds() {
        List<Cluster> out = new ArrayList<>();
        for (Cluster c : clusters.values()) if (c.flagged()) out.add(c);
        return out;
    }

    public List<Flag> flags() { return new ArrayList<>(flags); }

    /** Every cluster that is not a find yet, for the rig log: where, what was counted, which hints. */
    public String debug() {
        StringBuilder sb = new StringBuilder();
        for (Cluster c : clusters.values()) {
            sb.append(String.format(Locale.ROOT, " [%d,%d %s%s score %.1f", c.cx, c.cz, c.flagged() ? "FIND " : "", c.title, c.score));
            c.counts.forEach((k, v) -> sb.append(' ').append(k.noun).append('=').append(v));
            if (!c.tags.isEmpty()) sb.append(" tags ").append(c.tags);
            sb.append(']');
        }
        return sb.toString();
    }

    /** What Sound Detector tells Base Finder about one chunk: points and the reason lines for the card ("heard: villager hall 14/min"). */
    public static final class Evidence {
        public int points;
        public int x, y, z;
        public String title = "";
        public boolean flagged;
        public final List<Object[]> lines = new ArrayList<>(); // text, points
    }

    /** Chunks fed to Base Finder in the last rebuild, for the info line. */
    public int fed;

    /**
     * Per chunk, what was heard, as Base Finder score points. A find is worth 16 + 0.6 per weighted hit per minute (34 at most), a
     * recurring place 8 more, and every red flag a lot on its own: a container opened with nobody near it 12 (+4 each, 24 at most),
     * footsteps or player noises 6, a copper golem sorting chests 8. A chunk is capped at 48, which is a base on its own. Chunks
     * that are only quiet places (below the find threshold) give nothing: Base Finder needs a verdict, not every noise.
     */
    public Map<Long, Evidence> evidence(long now) {
        Map<Long, Evidence> out = new HashMap<>();
        for (Cluster c : clusters.values()) {
            if (!c.flagged()) continue;
            Evidence e = out.computeIfAbsent(ChunkPos.asLong(c.cx, c.cz), k -> new Evidence());
            Vec3 p = c.pos();
            e.x = (int) p.x; e.y = (int) p.y; e.z = (int) p.z;
            e.title = c.title;
            e.flagged = true;
            double pm = c.perMinute(now);
            int pts = (int) Math.min(34, Math.round(16 + pm * 0.6));
            e.lines.add(new Object[] { String.format(Locale.ROOT, "heard: %s %.0f/min", c.title, pm), pts });
            e.points += pts;
            if (c.recurring) { e.lines.add(new Object[] { "heard: this place keeps coming back", 8 }); e.points += 8; }
        }
        Map<Long, EnumMap<Cat, Integer>> byChunk = new HashMap<>();
        Map<Long, Vec3> where = new HashMap<>();
        for (Flag f : flags) {
            if (now - f.time() > FLAG_MS) continue;
            long k = ChunkPos.asLong(ChunkMath.chunk(f.pos().x), ChunkMath.chunk(f.pos().z));
            byChunk.computeIfAbsent(k, kk -> new EnumMap<>(Cat.class)).merge(f.cat(), 1, Integer::sum);
            where.put(k, f.pos());
        }
        byChunk.forEach((k, m) -> {
            Evidence e = out.computeIfAbsent(k, kk -> new Evidence());
            Vec3 w = where.get(k);
            if (e.points == 0) { e.x = (int) w.x; e.y = (int) w.y; e.z = (int) w.z; }
            m.forEach((cat, n) -> {
                int pts = switch (cat) {
                    case CONTAINER_HIDDEN -> Math.min(24, 12 + 4 * (n - 1));
                    case GOLEM_SORT -> 8;
                    default -> 6;
                };
                e.lines.add(new Object[] { "heard: " + cat.flag + (n > 1 ? " x" + n : ""), pts });
                e.points += pts;
            });
        });
        out.values().removeIf(e -> e.points <= 0);
        for (Evidence e : out.values()) e.points = Math.min(48, e.points);
        return out;
    }

    // ---- sorting -------------------------------------------------------------------------------------------------------------

    private static final Set<String> ANIMALS = Set.of("cow", "sheep", "pig", "chicken", "horse", "donkey", "mule", "llama", "goat", "rabbit", "cat",
            "wolf", "fox", "bee", "turtle", "frog", "camel", "sniffer", "armadillo", "parrot", "panda", "mooshroom", "ocelot", "polar_bear", "happy_ghast");
    /** Natural noise: never a find, whatever is around. */
    private static final String[] NATURAL = { "weather.", "ambient.", "music", "block.water.", "block.lava.", "block.fire.", "block.bubble_column.",
            "block.amethyst", "block.pointed_dripstone.", "block.sculk", "block.big_dripleaf.", "block.small_amethyst", "block.medium_amethyst",
            "block.large_amethyst", "block.sand.idle", "block.dry_grass", "block.leaf_litter", "block.cactus_flower", "block.firefly_bush",
            "entity.lightning_bolt.", "entity.bat.", "entity.cod.", "entity.salmon.", "entity.tropical_fish.", "entity.pufferfish.", "entity.squid.",
            "entity.glow_squid.", "entity.dolphin.", "entity.generic.splash", "entity.generic.swim", "entity.fishing_bobber.", "entity.tadpole.",
            "entity.axolotl.", "entity.slime.", "entity.magma_cube.", "entity.phantom.", "entity.ender_dragon.", "entity.wither.", "entity.warden.",
            "entity.allay.", "entity.parrot.imitate", "entity.experience_orb.", "entity.item.break", "entity.falling_block" };

    /** Kind of a sound from its id path and source alone, or null when it says nothing about players. */
    public static Raw classify(String path, SoundSource src) {
        if ((path.startsWith("entity.slime.death") || path.startsWith("entity.magma_cube.death"))) return Raw.DEATH; // slime farms kill them in one spot
        for (String n : NATURAL) if (path.startsWith(n)) return null;
        if (path.startsWith("entity.iron_golem.")) return path.endsWith(".death") ? Raw.DEATH : path.endsWith(".step") || path.contains("repair") ? null : Raw.IRON_GOLEM; // a village golem walks around; a farm golem gets hurt
        if (path.endsWith(".step")) {
            if (src == SoundSource.PLAYERS) return Raw.STEP_PLAYER;
            return Raw.STEP_MOB;
        }
        if (path.startsWith("entity.player.")) {
            if (path.contains("splash") || path.contains("swim") || path.contains("small_fall") || path.contains("big_fall")) return null;
            return Raw.PLAYER;
        }
        if (path.startsWith("entity.copper_golem.")) return path.contains("item") ? Raw.GOLEM_SORT : null;
        if (path.startsWith("block.copper_chest")) return path.endsWith(".open") ? Raw.COPPER_CHEST : null;
        if (path.startsWith("block.anvil.")) return path.endsWith(".break") || path.endsWith(".step") || path.endsWith(".fall") || path.endsWith(".hit") ? null : Raw.ANVIL;
        if (path.contains("chest.open") || path.contains("barrel.open") || path.contains("shulker_box.open")) return Raw.CONTAINER;
        if (path.contains("chest.close") || path.contains("barrel.close") || path.contains("shulker_box.close") || path.equals("block.chest.locked")) return null;
        if (path.startsWith("entity.villager.work_")) return Raw.JOB;
        if (path.equals("entity.villager.trade") || path.equals("entity.wandering_trader.trade")) return Raw.TRADE;
        if (path.startsWith("entity.villager.")) return path.endsWith(".death") ? Raw.DEATH : Raw.VILLAGER;
        if (path.startsWith("entity.item_frame.") || path.startsWith("entity.glow_item_frame.") || path.startsWith("entity.armor_stand.")
                || path.startsWith("entity.painting.")) return Raw.DECOR;
        if (path.equals("entity.item.pickup")) return Raw.ITEMS;
        if (path.startsWith("entity.chicken.egg")) return Raw.ANIMAL;
        if (path.startsWith("entity.firework_rocket.") || path.startsWith("item.armor.equip") || path.startsWith("entity.generic.eat")
                || path.startsWith("entity.generic.drink") || path.startsWith("item.totem.") || path.startsWith("entity.ender_pearl.")
                || path.startsWith("item.shield.") || path.startsWith("item.crossbow.") || path.startsWith("item.trident.")
                || path.startsWith("entity.arrow.shoot") || path.startsWith("entity.splash_potion.") || path.startsWith("entity.experience_bottle.")
                || path.startsWith("item.elytra.") || path.startsWith("item.mace.") || path.startsWith("entity.wind_charge.")) return Raw.PLAYER;
        if (path.startsWith("entity.")) {
            String[] p = path.split("\\.");
            if (p.length < 3) return null;
            if (p[2].equals("death")) return Raw.DEATH;
            if (ANIMALS.contains(p[1])) return Raw.ANIMAL;
            return null; // monster voices and anything else alive: the wild
        }
        if (path.startsWith("block.piston.") || path.startsWith("block.dispenser.") || path.startsWith("block.hopper.")) return Raw.MACHINE;
        if (path.startsWith("block.crafter.") || path.startsWith("block.furnace.") || path.startsWith("block.blastfurnace.") || path.startsWith("block.smoker.")
                || path.startsWith("block.brewing_stand.") || path.startsWith("block.grindstone.") || path.startsWith("block.smithing_table.")
                || path.startsWith("block.enchantment_table.") || path.startsWith("block.loom.") || path.startsWith("block.cartography_table.")
                || path.startsWith("ui.stonecutter.") || path.startsWith("ui.loom.") || path.startsWith("ui.cartography_table.")
                || path.startsWith("block.composter.")) return Raw.STATION;
        if (path.startsWith("block.crop.break") || path.startsWith("block.sweet_berry_bush.pick") || path.startsWith("block.cave_vines.pick")
                || path.startsWith("block.nether_wart.break") || path.startsWith("block.sweet_berry_bush.break")) return Raw.HARVEST;
        if (path.startsWith("item.bone_meal.") || path.startsWith("item.crop.plant") || path.startsWith("item.nether_wart.plant")
                || path.startsWith("item.hoe.till") || path.startsWith("block.beehive.") || path.startsWith("item.bucket.fill")
                || path.startsWith("item.bucket.empty")) return Raw.CROP;
        if (path.startsWith("item.axe.strip")) return Raw.CHOP;
        if (path.contains("button.click") || path.contains("pressure_plate.click") || path.startsWith("block.lever.")
                || path.startsWith("block.tripwire.") || path.startsWith("block.comparator.") || path.startsWith("block.redstone_torch")) return Raw.REDSTONE;
        if (path.contains("door.") || path.contains("trapdoor.") || path.contains("fence_gate.")) return Raw.DOOR;
        if (path.startsWith("block.beacon.") || path.startsWith("block.respawn_anchor.") || path.startsWith("block.end_portal_frame.fill")
                || path.startsWith("block.portal.trigger") || path.startsWith("block.lodestone.") || path.startsWith("block.chiseled_bookshelf.")) return Raw.UTILITY;
        if (path.startsWith("block.note_block.") || path.startsWith("block.bell.") || path.startsWith("music_disc.")) return Raw.NOTE;
        if (path.endsWith(".place")) return Raw.PLACE;
        if (path.startsWith("block.") && path.endsWith(".break")) return breakOf(path);
        return null;
    }

    /** Level events that are a block doing work: the client makes their sound itself, so they never arrive as sound packets too. */
    public static Raw classifyEvent(int type) {
        return switch (type) {
            case 1000, 1001, 1002, 1004, 2000, 2010 -> Raw.MACHINE; // dispenser / dropper
            case 1049, 1050, 1500, 1035, 1042, 1044 -> Raw.STATION; // crafter, composter, brewing, grindstone, smithing
            case 1505 -> Raw.CROP; // bone meal
            case 1010 -> Raw.NOTE; // jukebox
            case 1029, 1030, 1031 -> Raw.ANVIL;
            case 1027, 1043, 1503, 3003, 3004, 3005 -> Raw.UTILITY; // villager cured, lectern, eye placed, wax/scrape
            case 2001 -> Raw.BREAK; // block broken: onLevelEvent reads the block from the data
            default -> null;
        };
    }

    /** A break sound by what broke: wood is chopping, crops are a harvest, the rest is mining. */
    static Raw breakOf(String path) {
        if (path.contains("wood") || path.contains("stem") || path.contains("bamboo") || path.contains("log")) return Raw.CHOP;
        if (path.contains("crop") || path.contains("wart") || path.contains("berry") || path.contains("sweet")) return Raw.HARVEST;
        return Raw.BREAK;
    }

    static Raw breakOf(net.minecraft.world.level.block.state.BlockState st) {
        if (st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.PLANKS)) return Raw.CHOP;
        if (st.getBlock() instanceof net.minecraft.world.level.block.CropBlock || st.is(net.minecraft.tags.BlockTags.CROPS)
                || st.is(net.minecraft.world.level.block.Blocks.NETHER_WART) || st.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)
                || st.is(net.minecraft.world.level.block.Blocks.MELON) || st.is(net.minecraft.world.level.block.Blocks.PUMPKIN)
                || st.is(net.minecraft.world.level.block.Blocks.SUGAR_CANE) || st.is(net.minecraft.world.level.block.Blocks.KELP)
                || st.is(net.minecraft.world.level.block.Blocks.KELP_PLANT) || st.is(net.minecraft.world.level.block.Blocks.BAMBOO)) return Raw.HARVEST;
        if (st.is(net.minecraft.tags.BlockTags.LEAVES) || st.canBeReplaced()) return null; // leaves decay and grass gets trampled on their own
        return Raw.BREAK;
    }

    /** The farm material or mob a sound names, or null. Hints only: they name a place that scores on its own. */
    public static String tagOfSound(String path) {
        if (path.startsWith("event.raid.horn")) return "raid";
        if (path.startsWith("entity.tnt.primed") || path.equals("entity.generic.explode")) return "tnt";
        if (path.startsWith("block.crafter.")) return "crafter";
        if (path.startsWith("block.cherry_wood") || path.startsWith("block.cherry_leaves") || path.startsWith("block.cherry_sapling")) return "cherry";
        if (path.startsWith("block.bamboo")) return "bamboo";
        if (path.startsWith("block.wet_grass.")) return "kelp";
        if (!path.startsWith("entity.")) return null;
        String m = path.split("\\.")[1];
        return switch (m) {
            case "skeleton", "stray", "bogged", "wither_skeleton" -> "skeleton";
            case "zombified_piglin", "piglin", "piglin_brute" -> "piglin";
            case "pillager", "vindicator", "evoker", "ravager", "witch", "illusioner" -> "raid";
            case "cow", "mooshroom" -> "cow";
            case "slime", "magma_cube" -> "slime";
            default -> null;
        };
    }

    /** The farm material a broken (or bone-mealed) block names, or null. */
    public static String tagOfState(net.minecraft.world.level.block.state.BlockState st) {
        var b = st.getBlock();
        if (b == net.minecraft.world.level.block.Blocks.KELP || b == net.minecraft.world.level.block.Blocks.KELP_PLANT) return "kelp";
        if (b == net.minecraft.world.level.block.Blocks.SUGAR_CANE) return "cane";
        if (b == net.minecraft.world.level.block.Blocks.BAMBOO || b == net.minecraft.world.level.block.Blocks.BAMBOO_SAPLING) return "bamboo";
        if (b == net.minecraft.world.level.block.Blocks.CHERRY_LOG || b == net.minecraft.world.level.block.Blocks.CHERRY_WOOD
                || b == net.minecraft.world.level.block.Blocks.CHERRY_LEAVES || b == net.minecraft.world.level.block.Blocks.CHERRY_SAPLING) return "cherry";
        if (st.is(net.minecraft.tags.BlockTags.LOGS)) return "log";
        if (st.is(net.minecraft.tags.BlockTags.SAPLINGS)) return "sapling";
        if (b == net.minecraft.world.level.block.Blocks.STONE || b == net.minecraft.world.level.block.Blocks.COBBLESTONE
                || b == net.minecraft.world.level.block.Blocks.DEEPSLATE || b == net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE) return "stone";
        return null;
    }

    /** Adds a hint to the chunk's cluster if it has one. */
    void hint(String tag, Vec3 at) {
        if (tag == null) return;
        Cluster c = clusters.get(ChunkPos.asLong(ChunkMath.chunk(at.x), ChunkMath.chunk(at.z)));
        if (c == null) {
            pendingTags.computeIfAbsent(ChunkPos.asLong(ChunkMath.chunk(at.x), ChunkMath.chunk(at.z)), k -> new HashMap<>()).merge(tag, 1, Integer::sum);
            return;
        }
        c.tags.merge(tag, 1, Integer::sum);
    }

    private final Map<Long, Long> pistonLooked = new HashMap<>();

    /**
     * What a piston is cutting: pistons break plants without any break sound or event, so a kelp, sugar cane or bamboo farm only ever
     * sounds like a piston. The client has the blocks around it (when the chunk is loaded), so look at what stands beside the piston.
     * At most one look per piston per 2 s.
     */
    private String plantBeside(Vec3 at) {
        if (mc.level == null) return null;
        BlockPos o = BlockPos.containing(at);
        long now = System.currentTimeMillis();
        Long was = pistonLooked.get(o.asLong());
        if (was != null && now - was < 2000) return null;
        if (pistonLooked.size() > 500) pistonLooked.clear();
        pistonLooked.put(o.asLong(), now);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -3; dy <= 2; dy++) {
            m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
            String t = tagOfState(mc.level.getBlockState(m));
            if (t != null && (t.equals("kelp") || t.equals("cane") || t.equals("bamboo"))) return t;
        }
        return null;
    }

    /** Hints heard before the chunk had a cluster (a skeleton rattling before the first death); handed over when it gets one. */
    private final Map<Long, Map<String, Integer>> pendingTags = new HashMap<>();

    // ---- taps ----------------------------------------------------------------------------------------------------------------

    public void onSound(ClientboundSoundPacket p) {
        if (mc.level == null || mc.player == null) return;
        String path = p.getSound().value().location().getPath();
        Vec3 at = new Vec3(p.getX(), p.getY(), p.getZ());
        if (at.distanceToSqr(mc.player.position()) > nearMe.get() * nearMe.get()) {
            hint(tagOfSound(path), at);
            if (path.startsWith("block.piston.")) hint(plantBeside(at), at);
        }
        Raw raw = classify(path, p.getSource());
        if (raw == null) return;
        hear(raw, at);
    }

    public void onLevelEvent(ClientboundLevelEventPacket p) {
        if (mc.level == null || mc.player == null || p.isGlobalEvent()) return;
        Raw raw = classifyEvent(p.getType());
        if (raw == null) return;
        Vec3 at = p.getPos().getCenter();
        if (p.getType() == 1049 || p.getType() == 1050) hint("crafter", at);
        if (p.getType() == 1505) hint(tagOfState(mc.level.getBlockState(p.getPos())), at); // bone meal on a cherry sapling, on kelp...
        if (p.getType() == 2001) {
            var st = net.minecraft.world.level.block.Block.stateById(p.getData());
            hint(tagOfState(st), at);
            if (st.is(net.minecraft.world.level.block.Blocks.CHERRY_LEAVES)) hint("cherry", at);
            raw = breakOf(st);
        }
        if (raw == null) return;
        hear(raw, p.getPos().getCenter());
    }

    /** Sorts one sound by who is around it and adds it to its chunk. */
    public void hear(Raw raw, Vec3 at) {
        Vec3 me = mc.player.position();
        double near = nearMe.get();
        if (at.distanceToSqr(me) <= near * near) return;
        boolean containerish = raw == Raw.CONTAINER || raw == Raw.COPPER_CHEST;
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) lastContainerScreen = System.currentTimeMillis();
        // the chest you just opened yourself
        if (containerish && at.distanceToSqr(me) < 36 && System.currentTimeMillis() - lastContainerScreen < 1500) return;
        boolean playerNear = playerNear(at, 5);
        if (ignoreVisible.on() && playerNear(at, 3.5)) return;
        boolean nobody = !playerNear;
        Cat cat = switch (raw) {
            case STEP_PLAYER -> nobody ? Cat.PLAYER_STEPS : null;
            case PLAYER -> nobody ? Cat.PLAYER_NOISE : null;
            case STEP_MOB -> !mobNear(at, 2.5) ? Cat.UNSEEN_STEPS : null; // a mob you can see walking is just a mob
            case STEP_VILLAGER, VILLAGER -> Cat.VILLAGER;
            case CONTAINER -> playerNear ? Cat.CONTAINER : Cat.CONTAINER_HIDDEN;
            case COPPER_CHEST -> playerNear ? Cat.CONTAINER : golemNear(at) ? Cat.GOLEM_SORT : Cat.CONTAINER_HIDDEN;
            case GOLEM_SORT -> Cat.GOLEM_SORT;
            case JOB -> Cat.JOB;
            case TRADE -> Cat.TRADE;
            case IRON_GOLEM -> Cat.IRON_GOLEM;
            case ANIMAL -> Cat.ANIMAL;
            case DEATH -> Cat.DEATH;
            case MACHINE -> Cat.MACHINE;
            case CROP -> Cat.CROP;
            case REDSTONE -> Cat.REDSTONE;
            case DOOR -> mobNear(at, 2.5) ? null : Cat.DOOR; // a villager walking through a village door is not a player
            case ANVIL -> Cat.ANVIL;
            case UTILITY -> Cat.UTILITY;
            case STATION -> Cat.STATION;
            case NOTE -> Cat.NOTE;
            case DECOR -> Cat.DECOR;
            case ITEMS -> nobody ? Cat.ITEMS : null;
            case PLACE -> nobody && !mobNear(at, 3) ? Cat.PLACED : null; // endermen and snow golems place blocks too
            case BREAK -> nobody && !mobNear(at, 3) ? Cat.BROKEN : null;
            case CHOP -> nobody ? Cat.CHOP : null;
            case HARVEST -> nobody && !mobNear(at, 2) ? Cat.HARVEST : null; // villager farmers harvest too: that's the villager group's
        };
        if (cat == null || !groupSetting(cat.group).on() || villageChatter(cat, at)) return;
        add(cat, at, System.currentTimeMillis());
    }

    /**
     * A generated village's own villagers: their voices and job sites say nothing about a player, and enough of them read as a
     * "villager hall". Dropped at village ground level only (a hall dug under a village, or away from one, is heard as before);
     * trades, golems, containers and everything else still count. Base Finder's Skip villages off keeps them.
     */
    private boolean villageChatter(Cat cat, Vec3 at) {
        if (cat != Cat.VILLAGER && cat != Cat.JOB) return false;
        BaseFinder bf = dev.vantage.core.Modules.get(BaseFinder.class);
        if (bf != null && !bf.skipVillages.on()) return false;
        long now = System.currentTimeMillis(), key = BlockPos.containing(at).asLong() >> 2;
        long[] was = villageLooked.get(key);
        if (was != null && now - was[0] < 3000) return was[1] == 1;
        if (villageLooked.size() > 500) villageLooked.clear();
        boolean yes = Villages.surfaceOf(mc.level, at.x, at.y, at.z);
        villageLooked.put(key, new long[] {now, yes ? 1 : 0});
        return yes;
    }

    private final Map<Long, long[]> villageLooked = new HashMap<>();

    /** Adds one heard sound; public so the rig and the unit checks can drive it without a server. */
    public void add(Cat cat, Vec3 at, long now) {
        heard++;
        long key = ChunkPos.asLong(ChunkMath.chunk(at.x), ChunkMath.chunk(at.z));
        Cluster c = clusters.computeIfAbsent(key, k -> new Cluster(ChunkPos.getX(k), ChunkPos.getZ(k)));
        if (c.first == 0) {
            c.first = now;
            Map<String, Integer> pend = pendingTags.remove(key);
            if (pend != null) pend.forEach((k, v) -> c.tags.merge(k, v, Integer::sum));
        }
        if (c.last > 0) c.score *= Math.pow(0.5, (now - c.last) / (double) HALF_MS);
        c.last = now;
        c.counts.merge(cat, 1, Integer::sum);
        double[] w = c.window.computeIfAbsent(cat, k -> new double[] { now, 0 });
        if (now - w[0] > WINDOW_MS) { w[0] = now; w[1] = 0; }
        double gain = Math.min(cat.weight, Math.max(0, CAT_CAP - w[1]));
        if (cat.strong) gain = cat.weight; // a red flag always counts in full
        if (cat == Cat.DEATH && deathSpot(c, now) >= 5) gain = cat.weight; // a kill chamber: deaths piling up on one spot count in full, a cap is for noise
        w[1] += gain;
        c.score += gain;
        c.recent.add(new double[] { now, cat.weight });
        while (!c.recent.isEmpty() && now - c.recent.peek()[0] > 60_000) c.recent.poll();
        double wt = Math.max(0.2, cat.weight);
        c.sx += at.x * wt; c.sy += at.y * wt; c.sz += at.z * wt; c.sw += wt;
        if (cat == Cat.DEATH) {
            c.deaths.add(new double[] { at.x, at.y, at.z, now });
            while (c.deaths.size() > 40) c.deaths.poll();
        }
        logHit(cat, at, c, now);
        if (cat.strong) flag(cat, at, now, key);
        // heard again after it went quiet: a place somebody keeps coming back to
        if (!c.recurring && c.score >= threshold() * 0.5) {
            Long seen = seen().get(seenKey(c));
            if (seen != null && now - seen > RECUR_GAP_MS && seen < c.first) {
                c.recurring = true;
                c.score += threshold() * 0.5;
            }
        }
        c.title = (c.recurring ? "recurring " : "") + titleOf(c, now);
        boolean busy = !c.title.contains("villager") || c.perMinute(now) >= 12; // a village's villagers murmur all day; a hall or breeder is busy
        boolean firstFlag = !c.flagged() && busy && c.score >= threshold() && (cat.strong || c.recurring || c.counts.size() >= 2 || c.title.endsWith("animal pen") || deathSpot(c, now) >= 5 || plantFarm(c) != null);
        if (firstFlag) { // flagged = evidence and the list
            c.flaggedAt = now;
            findsRaised++;
            Vec3 p = c.pos();
            String text = c.title + " (" + c.reasons() + ")";
            LeakLog.add(c.recurring ? "SOUND-RECURRING" : "SOUND", (int) p.x, (int) p.y, (int) p.z, text + String.format(Locale.ROOT, " score %.1f", c.score));
        }
        // alerted = the ping went out. Scoring v2 (Base Finder on): a cluster that was held back or rate limited tries again, so a cluster that becomes a farm later still alerts once; v1 tries once, as always
        if (firstFlag || BaseFinder.v2Active() && c.flagged() && !c.alerted) {
            Vec3 p = c.pos();
            boolean askOk = BaseFinder.soundPingOk(BaseFinder.typeOfSound(c.title)); // only the types Base Finder is set to ping; asked first, so a ping that is not wanted never uses up the alert gap
            if (askOk && now - lastAlert > 4000) {
                lastAlert = now;
                c.alerted = true;
                if (chat.on() && askOk) Chat.alert(Component.literal("Sound Detector: " + c.title + " at " + Chat.coords((int) p.x, (int) p.y, (int) p.z) + "  ")
                        .withStyle(ChatFormatting.GOLD).append(Component.literal(c.reasons()).withStyle(ChatFormatting.GRAY)), 1.4f);
                if (toast.on() && askOk) Toasts.warn("Sound Detector", c.title + " · " + c.reasons());
            }
        }
        if (c.flagged()) {
            Long was = seen().get(seenKey(c));
            if (was == null || now - was > 60_000) { seen().put(seenKey(c), now); seenDirty = true; }
            if (waypoint.on() && !c.waypointed && (!BaseFinder.v2Active() || c.alerted) && (c.recurring || c.score >= threshold() * 2)) {
                c.waypointed = true;
                Vec3 p = c.pos();
                Waypoints wp = Waypoints.get();
                if (wp != null) wp.add("Sound: " + c.title, (int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z), C_FIND);
            }
        }
    }

    // ---- log + recurring memory -----------------------------------------------------------------------------------------------

    private static final long RECUR_GAP_MS = 10 * 60_000;
    private Map<String, Long> seenMap;
    private String seenFor = "";
    private boolean seenDirty;
    private long lastFlush;

    private String dim() {
        return mc.level == null ? "?" : Dims.id(mc.level);
    }

    private String seenKey(Cluster c) {
        return dim() + " " + c.cx + " " + c.cz;
    }

    private java.nio.file.Path soundDir() {
        return dev.vantage.core.Config.dir().resolve("sounds");
    }

    /** Object "dim cx cz" -> last heard (millis), with a v field. On JsonStore: written on the storage thread. */
    private final dev.vantage.core.store.JsonStore seenStore = new dev.vantage.core.store.JsonStore("sound memory",
            () -> seenFor.isEmpty() ? null : soundDir().resolve(seenFor + "-seen.json"), 1, () -> {
                var o = new com.google.gson.JsonObject();
                for (var e : seenMap.entrySet()) o.addProperty(e.getKey(), e.getValue());
                return o;
            }, (root, v) -> {
                Map<String, Long> m = new HashMap<>();
                for (var e : root.getAsJsonObject().entrySet()) m.put(e.getKey(), e.getValue().getAsLong());
                seenMap = m;
            });

    /** Chunks that were finds before, with when they were last heard as one; per server, kept across sessions. */
    public Map<String, Long> seen() {
        String k = dev.vantage.core.Config.serverKey();
        if (seenMap == null || !k.equals(seenFor)) {
            saveSeen(); // a direct change of server must not drop the last seconds of the old one (B13)
            seenFor = k;
            if (!seenStore.load()) seenMap = new HashMap<>();
        }
        return seenMap;
    }

    private void saveSeen() {
        if (!seenDirty || seenMap == null) return;
        seenDirty = false;
        seenStore.markDirty();
    }

    private void logHit(Cat cat, Vec3 at, Cluster c, long now) {
        if (!log.on()) return;
        DataLogs.appendServer("sounds", ".log", String.format(Locale.ROOT, "%tF %<tT %s chunk %d,%d  %d %d %d  %s  score %.1f  %.1f/min%s", now, dim(), c.cx, c.cz,
                (int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z), cat.noun, c.score, c.perMinute(now), c.recurring ? "  RECURRING" : ""));
    }

    /** Rig: when the last alert went out. */
    public long lastAlertAt() { return lastAlert; }

    private void flag(Cat cat, Vec3 at, long now, long key) {
        flags.add(new Flag(cat, at, now));
        flagsRaised++;
        if (flags.size() > 60) flags.remove(0);
        LeakLog.add("SOUND-FLAG", (int) at.x, (int) at.y, (int) at.z, cat.flag);
        if (!BaseFinder.soundPingOk(BaseFinder.typeOfGroup(cat.group))) return; // v2: not a wanted type: no alert, and no cooldown taken
        Long was = flagPinged.get(key);
        if (was != null && now - was < 30_000) return; // one ping per chunk per 30 s, a walking player is a stream of steps
        flagPinged.put(key, now);
        if (now - lastAlert < 2000) return;
        lastAlert = now;
        if (chat.on()) Chat.alert(Component.literal("Sound Detector: " + cat.flag + " at " + Chat.coords((int) at.x, (int) at.y, (int) at.z)).withStyle(ChatFormatting.RED), 0.8f);
    }

    /** What a cluster most likely is, from the mix of what was heard. */
    static String titleOf(Cluster c, long now) {
        int villager = c.count(Cat.VILLAGER) + c.count(Cat.JOB) + c.count(Cat.TRADE);
        if (c.count(Cat.PLAYER_NOISE) + c.count(Cat.PLAYER_STEPS) >= 3) return "hidden player";
        if (c.count(Cat.GOLEM_SORT) > 0) return "sorting system";
        if (deathSpot(c, now) >= 5) return mobFarm(c);
        if (villager >= 8 && c.count(Cat.IRON_GOLEM) > 0) return "iron farm";
        if (villager >= 4 && c.count(Cat.CROP) >= 4) return "villager crop farm";
        if (villager >= 8) return "villager hall";
        String plant = plantFarm(c);
        if (plant != null) return plant;
        if (c.count(Cat.CONTAINER_HIDDEN) + c.count(Cat.CONTAINER) >= 2) return "storage in use";
        int machines = c.count(Cat.MACHINE) + c.count(Cat.STATION);
        if (machines >= 6 && (c.tag("tnt") >= 2 || c.tag("stone") >= 3 || c.tag("crafter") >= 10)) return "cobble farm";
        if (c.count(Cat.MACHINE) >= 6) return "redstone farm";
        if (c.count(Cat.ANIMAL) >= 40) return "animal pen";
        if (c.count(Cat.CROP) >= 4) return "crop farm";
        if (c.count(Cat.PLACED) + c.count(Cat.BROKEN) >= 4) return "someone building";
        return "base noise";
    }

    /** A kill spot named by whose voices and deaths it holds. */
    static String mobFarm(Cluster c) {
        String[][] kinds = { {"skeleton", "bone/skeleton farm"}, {"piglin", "gold farm"}, {"raid", "raid farm"}, {"slime", "slime farm"}, {"cow", "cow farm"} };
        String best = "mob farm";
        int bn = 1;
        for (String[] k : kinds) if (c.tag(k[0]) > bn) { bn = c.tag(k[0]); best = k[1]; }
        return best;
    }

    /** Pistons, harvests and bone meal over one plant: which plant. */
    static String plantFarm(Cluster c) {
        int work = c.count(Cat.MACHINE) + c.count(Cat.HARVEST) + c.count(Cat.CHOP) + c.count(Cat.BROKEN) + c.count(Cat.CROP);
        if (work < 4) return null;
        if (c.tag("kelp") >= 3) return "kelp farm";
        if (c.tag("bamboo") >= 3) return "bamboo farm";
        if (c.tag("cane") >= 3) return "sugar cane farm";
        if (c.tag("cherry") >= 3) return "tree farm (cherry)";
        if (c.tag("log") >= 3 || c.tag("sapling") >= 3) return "tree farm";
        return null;
    }

    /** Most mob deaths within 3 blocks of one point in the last 2 minutes: a kill chamber drops them all in one spot, the wild doesn't. */
    static int deathSpot(Cluster c, long now) {
        int best = 0;
        for (double[] a : c.deaths) {
            if (now - a[3] > 120_000) continue;
            int n = 0;
            for (double[] b : c.deaths) if (now - b[3] <= 120_000 && Math.abs(a[0] - b[0]) <= 3 && Math.abs(a[1] - b[1]) <= 3 && Math.abs(a[2] - b[2]) <= 3) n++;
            best = Math.max(best, n);
        }
        return best;
    }

    /** Score a chunk needs: Low 20, Medium 12, High 6. */
    public double threshold() {
        return sensitivity.is("High") ? 6 : sensitivity.is("Low") ? 20 : 12;
    }

    private boolean playerNear(Vec3 at, double r) {
        for (Player p : mc.level.players()) if (p != mc.player && p.position().distanceToSqr(at) <= r * r) return true;
        return false;
    }

    private boolean mobNear(Vec3 at, double r) {
        for (Entity e : mc.level.getEntities((Entity) null, new AABB(at, at).inflate(r))) if (e instanceof LivingEntity && e != mc.player) return true;
        return false;
    }

    private boolean golemNear(Vec3 at) {
        for (Entity e : mc.level.getEntities((Entity) null, new AABB(at, at).inflate(3))) {
            if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("copper_golem")) return true;
        }
        return false;
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        clusters.values().removeIf(c -> now - c.last > (c.flagged() ? HOLD_MS : HALF_MS * 3));
        flags.removeIf(f -> now - f.time() > FLAG_MS);
        flagPinged.values().removeIf(t -> now - t > 60_000);
        if (pendingTags.size() > 400) pendingTags.clear();
        if (now - lastFlush > 2000) { lastFlush = now; saveSeen(); }
    }

    private void reset() {
        saveSeen();
        clusters.clear();
        villageLooked.clear();
        pendingTags.clear();
        flags.clear();
        flagPinged.clear();
    }

    @Override protected void onEnable() { reset(); }
    @Override protected void onDisable() { reset(); }
    @Override
    public void onLeaveWorld() {
        reset();
    }

    @Override
    public void onDimensionChange() {
        reset();
    }

    /** Yellow when faint, orange, red when busy: weighted hits per minute. */
    public static int colorOf(double perMin) {
        float t = (float) Math.min(1, perMin / 30.0);
        return t < 0.5f ? Colors.mix(0xFFE8D25A, C_FIND, t * 2) : Colors.mix(C_FIND, 0xFFE0453C, (t - 0.5f) * 2);
    }

    /** The find heard most in the last minute (most recent score when all are quiet). */
    public Cluster strongest() {
        long now = System.currentTimeMillis();
        Cluster best = null;
        double bv = -1;
        for (Cluster c : clusters.values()) {
            if (!c.flagged()) continue;
            double v = c.perMinute(now) * 1000 + c.score;
            if (v > bv) { bv = v; best = c; }
        }
        return best;
    }

    @Override
    public void onRender3D(R3 r) {
        double max = range.get() * range.get();
        long now = System.currentTimeMillis();
        for (Cluster c : clusters.values()) {
            if (!c.flagged()) continue;
            Vec3 p = c.pos();
            double d2 = p.distanceToSqr(r.cam);
            if (d2 > max) continue;
            double pm = c.perMinute(now);
            int col = colorOf(pm);
            double x0 = c.cx << 4, z0 = c.cz << 4;
            r.column(new AABB(x0, mc.level.getMinY(), z0, x0 + 16, mc.level.getMaxY(), z0 + 16), col, 1.8f, (col & 0xFFFFFF) | 0x22000000, x0 + 8, z0 + 8);
            r.box(new AABB(BlockPos.containing(p)).inflate(0.15), col, 1.6f, true);
            r.label(p.add(0, 1.4, 0), cap(c.title) + String.format(Locale.ROOT, "  ·  %.0f/min  ·  ", pm) + c.reasons(), col);
        }
        List<Vec3> drawn = new ArrayList<>();
        for (int i = flags.size() - 1; i >= 0; i--) { // newest first; one box + label per spot, a busy chest is not ten labels
            Flag f = flags.get(i);
            if (f.pos().distanceToSqr(r.cam) > max) continue;
            Vec3 p = f.pos();
            boolean dup = false;
            for (Vec3 d : drawn) if (d.distanceToSqr(p) < 6.25) { dup = true; break; }
            if (dup) continue;
            drawn.add(p);
            r.box(new AABB(p.x - 0.35, p.y - 0.35, p.z - 0.35, p.x + 0.35, p.y + 0.35, p.z + 0.35), C_FLAG, 1.8f, true);
            r.label(p.add(0, 0.9, 0), cap(f.cat().flag) + "  " + (now - f.time()) / 1000 + "s", C_FLAG);
        }
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Drawn rows and arrow, for the rig. */
    public static int panelRows;
    public static boolean arrowDrawn;

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        panelRows = 0;
        arrowDrawn = false;
        if (mc.player == null) return;
        if (arrow.on()) drawArrow(g, delta);
    }

    /** Arrow at the top to the strongest find, under any Debris/Amethyst/Anchor arrows that are showing. */
    private void drawArrow(GuiGraphics g, DeltaTracker delta) {
        Cluster c = strongest();
        if (c == null) return;
        long now = System.currentTimeMillis();
        float partial = delta.getGameTimeDeltaPartialTick(true);
        Vec3 me = mc.player.getPosition(partial), t = c.pos();
        double dx = t.x - me.x, dz = t.z - me.z, dy = t.y - mc.player.getEyeY();
        float rel = (float) (Dist.bearing(dx, dz) - mc.player.getViewYRot(partial));
        int slot = 0;
        for (var m : dev.vantage.core.Modules.all()) if (m instanceof ArrowFinder af && af.on() && af.arrow.on() && af.closest() != null) slot++;
        int col = colorOf(c.perMinute(now));
        Ui.begin(g);
        float cx = Ui.W() / 2, cy = 40 + slot * 54;
        int dist = (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
        String s = cap(c.title) + "  " + dist + "m" + String.format(Locale.ROOT, "  %.0f/min", c.perMinute(now));
        String sub = Chat.coords((int) t.x, (int) t.y, (int) t.z) + "  " + Compass.name(dx, dz);
        float w = Math.max(Ui.width(s, 0.95f, true), Ui.width(sub, 0.85f, false)) + 24;
        Ui.rr(g, cx - w / 2, cy - 16, w, 46, 7, Ui.GLASS, Ui.BORDER);
        dev.vantage.core.Callouts.reserve(cx - w / 2, cy - 16, w, 46);
        g.pose().pushMatrix();
        g.pose().translate(cx, cy - 4);
        g.pose().rotate((float) Math.toRadians(rel));
        for (int i = 0; i <= 5; i++) g.fill(-i, -9 + i, i + 1, -8 + i, col);
        g.fill(-1, -4, 2, 6, col);
        g.pose().popMatrix();
        Ui.center(g, s, cx, cy + 7, col, 0.95f, true);
        Ui.center(g, sub, cx, cy + 19, Ui.SUB, 0.85f, false);
        Ui.end(g);
        arrowDrawn = true;
    }
}
