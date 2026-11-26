package dev.vantage.module.finder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Theme;
import dev.vantage.core.Ui;
import dev.vantage.core.hud.HudElements;
import dev.vantage.core.util.Compass;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.ActionSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.Header;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Rates every loaded chunk by how player-made it looks: storage, workstations, beds, signs, banners, beacons,
 * concrete, stained glass, portals. Chunks above the score get pinged and marked. Villages are scored down.
 */
public class BaseFinder extends Module implements dev.vantage.render.R3.Policy {
    /** grief: what makes this look already wrecked, or null for a live base. */
    public record Find(int x, int y, int z, int score, String why, String grief, String damage, boolean intact, boolean deep,
                       int minY, int maxY, int sections, int allSections, int build, int dug, int growth, int farm, List<Reason> reasons, String storage, Ev ev) {}
    /**
     * v2 evidence the place rules read, counted by block entity / palette at scan time and saved in the ledger. known=false: an old file or a locked scan, the rules
     * then neither veto nor call it a farm. machines = hoppers + dispensers/droppers + crafters; redstone = redstone parts WITHOUT machines and lamps; kelp = roofed kelp points.
     */
    public record Ev(boolean known, int machines, int spawners, int containers, int redstone, int farmland, int portals, int beacons, boolean hall, int kelp) {
        public static final Ev UNKNOWN = new Ev(false, 0, 0, 0, 0, 0, 0, 0, false, 0);
    }
    /** One line of the hover card: "+10 deepslate somebody crafted (bricks, tiles) 16". */
    public record Reason(String text, int points, int count) {}
    /** What the panel and the marks show: a find (or just a live signal) with everything worked out. */
    public record View(int cx, int cz, int x, int y, int z, int pct, String label, int color, int dist, String dir,
                       String yRange, String sections, List<Reason> reasons, String foot, String conf, int points, int minY, int maxY, String type) {}
    /** Find types (v2): what a chunk is, decided by its strongest contributor; a farm inside a base counts as a farm. */
    public static final String T_FARM = "farm", T_STORAGE = "storage", T_MINE = "mine", T_SOUNDS = "sounds", T_SIGNS = "signs", T_ZONE = "zone";
    /** What the server hides in one chunk: containers only the chunk data admits to, and light inside solid rock. */
    private static final class Extra { int containers, heard, light, x, y, z, sky, ring, sound; String soundTitle = ""; List<Object[]> soundLines = List.of(); boolean room, owed; }
    private static final class Live { int count; long last; int x, y, z; String who; long toast; }
    private static final class Dig { int breaks, x, y, z; long last, tick; }

    /** Containers in one chunk that are a stash whatever else is around: no structure holds this many. */
    private static final int STASH_ALONE = 12;
    /** This many players around an above-ground find make it a spawn or hub. */
    private static final int CROWD = 8;
    /** Worldgen stops making deepslate at Y 8, so it got carried up. Section test, so the check starts at 16. */
    private static final int HIGH_DEEPSLATE_Y = 16, HIGH_DEEPSLATE_MIN = 4;
    /** Villages have these one at a time, so 3 different ones in a chunk means a workshop. */
    private static final Set<Block> UTILITY = Set.of(Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL, Blocks.GRINDSTONE, Blocks.SMITHING_TABLE,
            Blocks.STONECUTTER, Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.ENCHANTING_TABLE, Blocks.BREWING_STAND);
    private static final int UTILITY_KINDS = 3;
    /** Stalks in a chunk before "none of them is tall" says anything. */
    private static final int UNGROWN_KELP = 6;
    /** Fewer changed blocks than this is one block broken in passing. */
    private static final int CHANGED_MIN = 3;
    /** Blocks this far under the ground count as underground, and score half as much again. */
    private static final int DEEP = 12;
    /** Straight 1x2 run in solid rock this long is a hand-dug tunnel; caves and mineshafts don't look like that. */
    private static final int TUNNEL_MIN = 12;
    private static final int TUNNEL_POINTS = 6;
    private static final int TUNNEL_CAP = 4;
    private static final int STRUCTURE_PENALTY = 40;
    private static final int SHAFT_MIN = 10;
    /** A plain stone column this tall with the same granite, diorite, andesite, tuff or gravel blob on all four sides, and stone above and below, is a shaft someone filled back in. */
    private static final int PATCH_MIN = 5, PATCH_POINTS = 6;
    private static final int SHAFT_POINTS = 3;
    private static final int SHAFT_CAP = 2;
    /** Measured on 20k fresh chunks: worldgen kelp reaches 11 blocks, so anything taller grew while players kept the chunk loaded. */
    private static final int KELP_NATURAL = 12;
    /**
     * Generated kelp reaches the water surface in deep water and any head may have been touched by a passing player's ticks, so one or two tall plants prove
     * nothing by themselves: they never make a find, they only add to one that other clues already made.
     */
    public static final int KELP_MIN_PLANTS = 3;
    private static final String KELP_GROWN_REASON = "kelp grown past worldgen height";
    private static final String SHULKER_REASON = "shulker boxes in this chunk";
    /** Same measuring: stalactites stop at 6 and stalagmites at 7, real ticks grew a stalactite to 8. See ../antitest/growth. */
    private static final int STALACTITE_GROWN = 7, STALAGMITE_GROWN = 8;
    /** Cave vines are capped at 20 by worldgen. The Nether ones measured 17 and 16, with room left for a small sample. */
    private static final int CAVE_VINES_GROWN = 21, WEEPING_GROWN = 20, TWISTING_GROWN = 20;
    /** These score by how many blocks there are, not per section. */
    private static final List<String> COUNTED = List.of("obsidian", "redstone parts", "deepslate somebody crafted (bricks, tiles)");
    private static final long LIVE_MS = 120_000, LIVE_RECENT_MS = 60_000, LIVE_TOAST_MS = 60_000;
    /** A change this far under the ground and this far from you is someone else digging. */
    private static final int LIVE_DEPTH = 6, LIVE_MIN_DIST = 16;
    /** Digging is a chain: this many breaks, each on its own tick, each this close to the last, this soon after it. */
    private static final int DIG_BREAKS = 3, DIG_REACH = 2;
    private static final long DIG_MS = 20_000;
    /** Nobody digs in this many chunks at once. That's the server revealing hidden blocks, so all of it gets thrown out. */
    private static final int NOISE_CHUNKS = 12;
    private static final long NOISE_WINDOW_MS = 5_000, NOISE_HOLD_MS = 30_000;
    /** Hoppers and dispensers count one each, planted kelp more. */
    private static final int FARM_POINTS = 8;
    /** v2: hoppers and dispensers in one chunk from which water on the walls is the farm's own plumbing, not damage. */
    private static final int FARM_WATER_MIN = 1;
    private static final int VAULT_OBSIDIAN = 30;
    /** Left behind after fights on any server, however many, so these only count deep underground. */
    private static final Set<Block> FIGHT_BLOCKS = Set.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.RESPAWN_ANCHOR);
    /** Ender chests and signs get scattered everywhere, so only the first few of each add points. */
    private static final int ENDER_CAP = 3, SIGN_CAP = 5;
    /** Rows of beds come with generated structures, a bedroom has a few. */
    private static final int BED_CAP = 3;
    /** Scoring v2 (beta). Banners come with outposts and villages; like signs and beds only the first few add points. */
    private static final int BANNER_CAP = 2;
    /** v2: a village penalty reaches the bell chunk and its 8 neighbours at full strength; a structure marker's own chunk takes the full penalty, its 8 neighbours half. */
    private static final int VILLAGE_PENALTY = 25, STRUCTURE_NEIGHBOUR_PENALTY = STRUCTURE_PENALTY / 2;
    /** v2: digging only counts in full next to this many non-digging points (or any container) in the chunk or the 8 around it; alone it stays under Sus score. */
    private static final int DIG_EVIDENCE = 5;
    /** v2 ledger: most finds kept per server, most dismissed chunks kept per server, and how long a changed ledger waits before it is written. */
    private static final int LEDGER_MAX = 300, DISMISSED_MAX = 500;
    private static final long LEDGER_SAVE_MS = 5_000;
    /** Panel and hover read at most this far. */
    private static final int SHOW_RANGE = 512;
    private static final Set<Block> REDSTONE = Set.of(Blocks.REDSTONE_WIRE, Blocks.REPEATER, Blocks.COMPARATOR, Blocks.OBSERVER, Blocks.PISTON,
            Blocks.STICKY_PISTON, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.REDSTONE_LAMP, Blocks.REDSTONE_BLOCK);
    private static final Set<Block> CRAFTED_DEEPSLATE = Set.of(Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES,
            Blocks.CRACKED_DEEPSLATE_TILES, Blocks.POLISHED_DEEPSLATE, Blocks.CHISELED_DEEPSLATE, Blocks.DEEPSLATE_BRICK_STAIRS, Blocks.DEEPSLATE_BRICK_SLAB,
            Blocks.DEEPSLATE_BRICK_WALL, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILE_WALL,
            Blocks.POLISHED_DEEPSLATE_STAIRS, Blocks.POLISHED_DEEPSLATE_SLAB, Blocks.POLISHED_DEEPSLATE_WALL);
    /** Only generated structures hold these, and those are full of chests and carpets that look like a base. */
    private static final Set<Block> STRUCTURE = Set.of(Blocks.REINFORCED_DEEPSLATE, Blocks.SCULK_SHRIEKER, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.END_PORTAL_FRAME);

    /** Grief points a chunk needs before it counts as wrecked; no single weak sign gets there alone. */
    private static final int GRIEF_MIN = 3;
    private static final int GRIEF_ITEMS = 8;
    /** Air bitten out of a floor: 3 sides of floor around it is a hole, 2 is a ragged edge. A ladder shaft or two stays under this. */
    private static final int HOLES_MIN = 3, RAGGED_MIN = 10, FLOOR_MIN = 12;
    /** A piece this small with nothing holding it up is what's left after the rest was blown away. */
    private static final int SCRAP_SIZE = 4, FLOATING_SIZE = 12, SCRAPS_MIN = 5, FLOATING_MIN = 2;
    /** This many blocks left and not one container in the chunks around it: it was emptied. */
    private static final int STRIPPED_BLOCKS = 20;
    /** This many containers, nearly all still standing on something, is a base worth seeing whatever else happened to it. */
    private static final int INTACT_STORAGE = 6;
    /** Floors and walls people build with, on top of the blocks that score. */
    private static final Set<Block> BUILDING = Set.of(Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS, Blocks.STONE_BRICKS, Blocks.BRICKS, Blocks.QUARTZ_BLOCK,
            Blocks.SMOOTH_STONE, Blocks.SMOOTH_QUARTZ, Blocks.PURPUR_BLOCK, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GLOWSTONE);
    private static final int GRIEF_COLOR = 0xFF8A8A8A;
    private static final Pattern GRIEF_SIGN = Pattern.compile("\\b(grief\\w*|raid\\w*|rekt|ez|gg|owned)\\b|was here", Pattern.CASE_INSENSITIVE);

    public final BoolSetting structures = add(new BoolSetting("Touched structures", "Natural structures players used: harvested geodes, lit portals, disarmed temples, cave torches", true));
    public final BoolSetting amethyst = add(new BoolSetting("Amethyst clusters", "Highlight fully grown clusters, with an arrow to the nearest one", true));
    private final Header scoring = add(new Header("Scoring"));
    public final NumberSetting threshold = add(new NumberSetting("Min score", "Score a chunk needs to count as a base and ping", 30, 8, 200, 1));
    public final NumberSetting susScore = add(new NumberSetting("Sus score", "Score a chunk needs to be marked and listed as suspicious", 8, 2, 100, 1));
    public final BoolSetting zones = add(new BoolSetting("Likely areas", "Guess where a base could be from clues around it, like grown amethyst and kelp", true));
    public final BoolSetting storage = add(new BoolSetting("Count storage", "Chests, barrels, shulkers add to the score", true));
    public final BoolSetting tunnels = add(new BoolSetting("Tunnels", "Straight hand-dug tunnels add to the score", true));
    public final BoolSetting useSound = add(new BoolSetting("Use Sound Detector", "Places Sound Detector hears (farms, storage in use, hidden players) add points to their chunk, with a reason line. Sound-only finds stay visible on the surface even with Underground only", true));
    public final BoolSetting scoringV2 = add(new BoolSetting("Scoring v2 (beta)", "Beta: growth clues count once, villages and structures score down over the 3x3 chunks around them, banners are capped, mining alone is not a base, and a find keeps its best score and is saved per server. Off = the old numbers (the scanner cards and Sound Detector as a part follow beta only, not this switch)", true)).visibleIf(dev.vantage.core.Modules::betaUnlocked);
    private final Header pingHead = add(new Header("Ping for (beta)")).visibleIf(this::v2Ui);
    public final BoolSetting pingFarms = add(new BoolSetting("Ping: farms", "Scoring v2: chat, sound, toast and card for farms (hopper farms, kelp, mob and crop farms, anything Sound Detector hears as a farm)", true)).visibleIf(this::v2Ui);
    public final BoolSetting pingStorage = add(new BoolSetting("Ping: storage and builds", "Scoring v2: ping for chests, stashes and player builds (off by default: they are still drawn when shown)", false)).visibleIf(this::v2Ui);
    public final BoolSetting pingMines = add(new BoolSetting("Ping: mines and tunnels", "Scoring v2: ping for digging and mining sounds", false)).visibleIf(this::v2Ui);
    public final BoolSetting pingSounds = add(new BoolSetting("Ping: player sounds", "Scoring v2: ping for players walking around, hidden players, mob death spots and other places known only by sound", false)).visibleIf(this::v2Ui);
    public final BoolSetting pingSigns = add(new BoolSetting("Ping: growth and signs", "Scoring v2: ping for grown plants and leaks that only say somebody was here", false)).visibleIf(this::v2Ui);
    private final Header showHead = add(new Header("Show on the ground (beta)")).visibleIf(this::v2Ui);
    public final BoolSetting showFarms = add(new BoolSetting("Show: farms", "Scoring v2: draw farms", true)).visibleIf(this::v2Ui);
    public final BoolSetting showStorage = add(new BoolSetting("Show: storage and builds", "Scoring v2: draw storage and builds", true)).visibleIf(this::v2Ui);
    public final BoolSetting showMines = add(new BoolSetting("Show: mines and tunnels", "Scoring v2: draw digging", false)).visibleIf(this::v2Ui);
    public final BoolSetting showSounds = add(new BoolSetting("Show: player sounds", "Scoring v2: draw places known only by player sounds and players seen under the ground", false)).visibleIf(this::v2Ui);
    public final BoolSetting showSigns = add(new BoolSetting("Show: growth and signs", "Scoring v2: draw growth and leak finds", false)).visibleIf(this::v2Ui);
    public final BoolSetting showSus = add(new BoolSetting("Show: suspicious", "Scoring v2: also draw chunks that are only suspicious (under Min score). Off: only real finds are drawn", false)).visibleIf(this::v2Ui);
    public final dev.vantage.setting.InfoSetting legend = add(new dev.vantage.setting.InfoSetting("Colours mean", "Scoring v2: one meaning for colour: how sure. The tag on the ground says what it is, a heavier line means a real find", () -> "green sure · yellow maybe · grey wrecked · blue player here · teal guessed area")).visibleIf(this::v2Ui);
    public final dev.vantage.setting.ModeSetting cardMode = add(new dev.vantage.setting.ModeSetting("Card", "Scoring v2: when the short card shows. When I look: only while you aim at a base. New finds: also a few seconds for each new find. Always: the surest base on screen, as the old Auto card", "New finds + when I look", "When I look", "New finds + when I look", "Always")).visibleIf(this::v2Ui);
    public final NumberSetting cardSeconds = add(new NumberSetting("Card seconds", "Scoring v2: how long a new find keeps its card before it shrinks to its tag", 6, 2, 20, 1)).visibleIf(this::v2Ui);
    public final BoolSetting growth = add(new BoolSetting("Growth", "Amethyst, kelp, dripstone, long vines and bare dirt that show how long a chunk was used", true));
    public final BoolSetting skipVillages = add(new BoolSetting("Skip villages", "Chunks with a bell score lower; a village's own furniture and its villagers' sounds do not count", true));
    public final BoolSetting skipStructures = add(new BoolSetting("Skip structures", "Ancient cities, trial chambers and strongholds score lower", true));
    public final BoolSetting skipShowpieces = add(new BoolSetting("Skip showpieces", "An above-ground build with no chest, barrel or shulker here or next door is a build, not a base: listed as suspicious at most, never pinged", true));
    public final BoolSetting skipCrowds = add(new BoolSetting("Skip crowds", "Above-ground finds with 8 or more players around are a spawn or hub: listed at most, never pinged", true));
    public final BoolSetting deepOnly = add(new BoolSetting("Underground only", "Overworld: only show and ping bases under the ground. Surface finds still feed Likely areas with their growth clues (kelp, amethyst, vines)", false));
    private final Header display = add(new Header("Display"));
    public final BoolSetting panel = add(new BoolSetting("Panel", "List of the most suspicious chunks, top left", true));
    public final BoolSetting hover = add(new BoolSetting("Hover card", "Look at a chunk's dot to see why it was flagged", true));
    public final BoolSetting show = add(new BoolSetting("Chunk marks", "Flat outline with a dot on every flagged chunk", true));
    public final BoolSetting columns = add(new BoolSetting("Columns", "Tall column + score label over real finds", false));
    public final ColorSetting color = add(new ColorSetting("Color", "Marker colour", 0xFFE0BD62));
    private final Header readability = add(new Header("Readability"));
    public final BoolSetting declutter = add(new BoolSetting("Declutter", "One card for the base you look at, small tags for the rest. Each place is described once. Off = the old look.", true));
    public final BoolSetting autoCard = add(new BoolSetting("Auto card", "Without aiming, the surest base on screen gets a short card. Off: a card only when you look at a base's dot.", true));
    public final NumberSetting cardReasons = add(new NumberSetting("Card reasons", "Reason lines on the card of the base you look at (the short card shows 2).", 6, 2, 10, 1));
    public final NumberSetting maxTags = add(new NumberSetting("Max tags", "One-line tags for the other bases on screen.", 3, 0, 8, 1));
    public final NumberSetting tagRange = add(new NumberSetting("Tag range", "Bases farther away than this get no tag, only their ground mark. Bases at 90% or with a live player always get one, and bases at 50% or more get one up to 256 m.", 160, 32, 512, 8));
    public final BoolSetting edgeArrows = add(new BoolSetting("Edge arrows", "Small arrows at the screen edge for sure bases (50% or more) you are not looking at, up to 3.", true));
    public final NumberSetting maxLabels = add(new NumberSetting("Max labels", "World labels from finders and ESPs per frame. Repeats are merged, overlapping ones are dropped. Player names always show.", 10, 0, 40, 1));
    public final NumberSetting labelRange = add(new NumberSetting("Label range", "Small evidence labels (Deep Leaks, Light Scan, Entity Scan, Storage ESP...) only show this close, or up to 64 blocks inside the base you look at.", 24, 8, 128, 4));
    public final NumberSetting columnFade = add(new NumberSetting("Column fade", "Floor-to-sky columns (Columns, Stash Finder, Sky Fingerprint, Simulation Ring) disappear closer than this and fade in over the next 48 blocks.", 48, 0, 256, 8));
    private final Header alerts = add(new Header("Alerts"));
    public final BoolSetting livePlayers = add(new BoolSetting("Live players", "Players under the ground, and digging you can't see", true));
    public final BoolSetting notify = add(new BoolSetting("Chat alert", "Message + sound for each new base chunk", true));
    public final BoolSetting waypoint = add(new BoolSetting("Auto waypoint", "Drop a waypoint on each find", false));
    public final BoolSetting pingGriefed = add(new BoolSetting("Ping wrecked bases", "Also ping bases that look raided. Off: they only get a grey marker", false));

    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    /** v2: chunks already waiting in the queue, so a burst of loads and block updates scans each once. */
    private final Set<Long> queued = new HashSet<>();
    /** v2: chunks that hold a bell / a structure marker block / digging that only counted capped / build or storage evidence / digging that counted in full. */
    /** Generated structures whose blocks look like a build (ancient city, trial chamber, bastion): marker and palette chunks, claimed chunk by chunk. */
    private final StructureRules rules = StructureRules.create();
    private static Map<Block, Integer> structBits;

    /** Per block: bit 0 = sculk, then three bits per structure kind (1 scored block it generates, 2 marker block, 4 building palette). */
    private static Map<Block, Integer> structBits() {
        if (structBits != null) return structBits;
        Map<Block, Integer> m = new HashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            String n = BuiltInRegistries.BLOCK.getKey(b).getPath();
            int bits = StructureRules.sculk(n) ? 1 : 0;
            for (StructureRules.Kind k : StructureRules.Kind.values()) {
                if (StructureRules.generated(k, n)) bits |= bit(k, 1);
                if (StructureRules.marker(k, n)) bits |= bit(k, 2);
                if (StructureRules.build(k, n)) bits |= bit(k, 4);
            }
            if (bits != 0) m.put(b, bits);
        }
        return structBits = m;
    }

    private static int bit(StructureRules.Kind k, int what) { return what << (1 + 3 * k.ordinal()); }
    private static int buildMask() { int m = 0; for (StructureRules.Kind k : StructureRules.Kind.values()) m |= bit(k, 4); return m; }
    private final Set<Long> bells = new HashSet<>(), markers = new HashSet<>(), digCapped = new HashSet<>(), evidence = new HashSet<>(), digOk = new HashSet<>();
    /** v2: the best find each chunk ever had this session (and from earlier visits), what the ledger file holds for this dimension. */
    private final Map<Long, Find> best = new LinkedHashMap<>();
    /** v2: chunks the player dismissed; they stay out of the finds and the ledger. */
    private final Set<Long> dismissed = new java.util.LinkedHashSet<>();
    /** v2: finds whose ping waits until the chunks around them have been scanned (a village's bell may still be unseen), with the time they started waiting. */
    private final Map<Long, Long> holdPing = new LinkedHashMap<>();
    /** v2: the find the hold ping was judged on when it was queued (the fresh scan): the same verdict decides at release. */
    private final Map<Long, Find> holdJudge = new HashMap<>();
    /** Chunks whose last hold ended WITHOUT a ping, with the inputs of that verdict (holdSig). Nothing arms a new hold for the chunk until one of them changes (fix 8d). */
    private final Map<Long, String> noPing = new HashMap<>();
    /** Chunks that had a below-Y-0 bonus re-arm, with the time: at most one per chunk per minute (bounded, cleared wherever noPing is). */
    private final Map<Long, Long> bonusAt = new HashMap<>();
    private int bonusRearms;
    /** The Find of the latest scan of each chunk (v2): the re-arm judges this one, like the scan path does, not the kept best find. Cleared wherever noPing or best is. */
    private final Map<Long, Find> lastScan = new HashMap<>();
    /** Diagnostic, default OFF: -Dvantage.bfdiag=true writes one leak log line at every hold release (see diag). */
    private static volatile boolean BFDIAG = Boolean.getBoolean("vantage.bfdiag");
    private static final long PING_HOLD_MS = 4000;
    /** v2: the ore mode (fake ores seen or not) each best find was scored in; a find from the other mode, or from an old file without the mark, is stale: kept until its chunk is scanned again, then replaced whatever it scores. */
    private final Map<Long, Boolean> bestMode = new HashMap<>();
    private final Set<Long> hiddenSeen = new HashSet<>();
    private final Set<Long> griefSeen = new HashSet<>();
    /** v2: a find as it was before the ore-mode flip marked it, so the flip back can give it back unchanged. */
    private final Map<Long, Find> premark = new HashMap<>();
    /** v2: keys removed on purpose this visit (and a Clear finds mark): a save queued behind a slow read must not write them back from the old file. */
    private final Set<Long> removed = new HashSet<>();
    private static final int REMOVED_MAX = 4096;

    /** The chunk lost its find while a read may still be pending. Bounded: a read that hangs cannot grow this without end (past the cap the oldest read result wins nothing worse than before the mark). */
    private void markRemoved(long key) {
        if (removed.size() < REMOVED_MAX) removed.add(key);
    }
    private boolean clearedAll;
    private long hiddenCheck;
    private boolean ledgerDirty;
    private long ledgerSavedAt;
    /** The found chunks. Every change bumps `version`, so the place groups are rebuilt once per change and not per read. */
    private static final class FindMap extends LinkedHashMap<Long, Find> {
        private static final long serialVersionUID = 1L; // never serialised; LinkedHashMap is Serializable, so the compiler asks for it
        int version;
        @Override public Find put(Long k, Find v) { Find old = super.put(k, v); if (!v.equals(old)) version++; return old; } // Find and everything in it compare by value
        @Override public Find putIfAbsent(Long k, Find v) { Find old = super.putIfAbsent(k, v); if (old == null) version++; return old; }
        @Override public Find remove(Object k) { Find old = super.remove(k); if (old != null) version++; return old; }
        @Override public void clear() { version++; super.clear(); }
        void removeIfValue(java.util.function.Predicate<Find> p) { if (values().removeIf(p)) version++; }
    }
    private final FindMap finds = new FindMap();
    private final Set<Long> pinged = new HashSet<>();
    private Map<Block, Integer> palette;
    private final Map<Long, Live> live = new HashMap<>();
    private List<View> views = List.of();
    private View hovered;
    /** One entry per base (BaseSites), rebuilt with the views; the per-chunk views stay for the old look and the rig. */
    private List<BaseSites.Site> sites = List.of();
    private Map<Long, BaseSites.Site> chunkSite = Map.of();
    private final int[] nextSiteId = {1};
    /** What the screen shows this frame with Declutter on: the one card (form Callouts.FULL / BRIEF, or -1 for none), tags, arrows. */
    public record Focus(int siteId, int form) {}
    private Focus focus;
    private int heldId = -1, autoId = -1, tagsCut;
    private long holdUntil;
    private Set<Integer> onScreen = new HashSet<>();
    private List<BaseSites.Site> tagSites = List.of(), arrowSites = List.of();
    private final Map<Integer, float[]> arrowDir = new HashMap<>();
    /** For the rig: where the old hover card was drawn last frame (or null), and how many reason lines the full card showed. */
    public float[] lastCardRect;
    public int lastCardReasons;
    private int viewTimer;
    /** Y of the last tunnel or shaft found in the chunk being scanned. */
    private int digY;
    private boolean strict;

    /** The ore mode changed: finds scored in the old mode wait for a rescan, marked as such. A find scored in the new mode is not touched. */
    private void markStale() {
        for (var e : new ArrayList<>(finds.entrySet())) {
            Find f = e.getValue();
            boolean waiting = !f.reasons().isEmpty() && f.reasons().get(0).text().contains(WAITING);
            if (fresh(e.getKey())) { // current again (flipped back before it was scanned): exactly the note it had before it was marked
                if (waiting) {
                    Find was = premark.remove(e.getKey());
                    Find b = best.get(e.getKey());
                    finds.put(e.getKey(), was != null ? was : b != null ? noted(b, SEEN + ": saved on an earlier visit, scored " + b.score()) : unnoted(f));
                }
                continue;
            }
            if (!waiting) { premark.put(e.getKey(), f); finds.put(e.getKey(), noted(f, SEEN + ": scored " + f.score() + WAITING)); }
            cancelHold(e.getKey(), "stale");
            lastScan.remove(e.getKey()); // scored in the old mode: the re-arm waits for a new scan
        }
    }

    /** The note text of a find from the other ore mode (or from a file without the mark): same words for both. */
    private static final String WAITING = " in the other ore mode, waiting for a new scan";

    private static Find unnoted(Find f) {
        List<Reason> rs = new ArrayList<>();
        for (Reason r : f.reasons()) if (!r.text().startsWith(SEEN)) rs.add(r);
        return new Find(f.x(), f.y(), f.z(), f.score(), f.why(), f.grief(), f.damage(), f.intact(), f.deep(), f.minY(), f.maxY(), f.sections(), f.allSections(),
                f.build(), f.dug(), f.growth(), f.farm(), rs, f.storage(), f.ev());
    }

    /** Scored in the ore mode we are in now. */
    private boolean fresh(long key) {
        Boolean m = bestMode.get(key);
        return m != null && m == strict;
    }
    private long scanNanos, scans;

    /** One switch per module that comes on and off with this one. */
    public <M extends Module> M bundle(String label, M m) {
        return part(add(new BoolSetting(label, "Comes on and off with Base Finder", true)), m);
    }

    static {
        HudElements.declare("basefinder", "Base Finder", "Top Left", BaseFinder.class);
    }

    public BaseFinder() {
        super("Base Finder", "One key for the whole hunt: scores chunks, guesses base areas, and turns the finders and ESPs on with it", Category.FINDER);
        // registered here and not in Modules.init so the policy lives and dies with the module that owns it, however it is created
        dev.vantage.render.R3.setPolicy(this);
        // a single block change, seen before the game applies it, so the old block is still there: digging can be told from a server revealing hidden caves
        listen(dev.vantage.core.event.PacketInEvent.class, e -> {
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket p && mc.level != null) blockChanged(p.getPos(), mc.level.getBlockState(p.getPos()), p.getBlockState());
        });
        bind = org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
        // a Skip switch changed: the loaded chunks around known bells / markers are scored again, whichever way it went
        skipVillages.onChange(() -> { if (v2() && mc.level != null) for (long k : new ArrayList<>(bells)) forgetAround(new ChunkPos(k)); });
        skipStructures.onChange(() -> { if (mc.level != null) { if (v2()) for (long k : new ArrayList<>(markers)) forgetAround(new ChunkPos(k)); for (long k : rules.known()) forgetChunk(k); } });
        // beta settings are not written fresh to config.json, saved profiles or shared configs while beta is locked (Config passes keys that were already in config.json through untouched)
        for (dev.vantage.setting.Setting<?> st : List.of(scoringV2, pingFarms, pingStorage, pingMines, pingSounds, pingSigns, showFarms, showStorage, showMines, showSounds, showSigns, showSus, cardMode, cardSeconds))
            st.storedIf(dev.vantage.core.Modules::betaUnlocked);
        add(new Header("Finds"));
        add(new ActionSetting("List finds", "Print the best 10 finds in chat", () -> {
            List<Find> l = new ArrayList<>(real());
            l.sort((a, b) -> b.score() - a.score());
            if (l.isEmpty()) Chat.info("no bases found yet");
            for (int i = 0; i < Math.min(10, l.size()); i++) {
                Find f = l.get(i);
                String g = v2() ? griefOf(ChunkPos.asLong(f.x() >> 4, f.z() >> 4), f) : f.grief(); // v2 only: a locked user's list is as it was
                Chat.info("score " + f.score() + "  " + Chat.coords(f.x(), f.y(), f.z()) + "  " + f.why() + (g == null ? "" : "  GRIEFED: " + g));
            }
        }));
        add(new ActionSetting("Clear finds", "Forget all finds", () -> {
            finds.clear(); pinged.clear(); announceLog.clear(); live.clear(); views = List.of(); sites = List.of(); chunkSite = Map.of();
            if (v2()) { best.clear(); bestMode.clear(); premark.clear(); holdPing.clear(); holdJudge.clear(); noPing.clear(); bonusAt.clear(); lastScan.clear(); ledgerDirty = true; clearedAll = true; }
            Chat.info("base finds cleared");
        }));
        add(new ActionSetting("Dismiss nearest find (beta)", "Scoring v2: the find nearest to you is dropped and stays dismissed on this server, even when it is scanned again", () -> {
            int n = dismissNearest();
            Chat.info(n == 0 ? "no find to dismiss" : "dismissed the nearest find");
        })).visibleIf(this::v2Ui);
        add(new ActionSetting("Clear saved finds (beta)", "Scoring v2: wipes this server's saved finds and its dismissed list (the file under config/vantage/base-finds)", () -> {
            clearLedger();
            Chat.info("saved base finds cleared");
        })).visibleIf(this::v2Ui);
    }

    /** The v2 settings and buttons show only while v2 is on. */
    private boolean v2Ui() {
        return dev.vantage.core.Modules.betaUnlocked() && scoringV2.on();
    }

    /** Scoring v2 is on: beta unlocked and its switch on. Locked or off, every score, percentage, find and ping is what it always was. */
    public boolean v2() {
        return dev.vantage.core.Modules.betaUnlocked() && scoringV2.on();
    }

    /** One place chunks join the queue. v2 keeps each chunk in it once: it is scanned when its turn comes, from whatever the world is by then. */
    private void enqueue(ChunkPos cp) {
        if (v2() && !queued.add(cp.toLong())) return;
        queue.add(cp);
    }

    /** The find a chunk shows now (best-ever in v2), or null. For the rig. */
    public Find findAt(int cx, int cz) {
        return finds.get(ChunkPos.asLong(cx, cz));
    }

    /** True when the player dismissed this chunk's find. */
    public boolean isDismissed(int cx, int cz) {
        return dismissed.contains(ChunkPos.asLong(cx, cz));
    }

    /** Whether the chunk's last scan found digging worth full points (build or storage evidence in the 3x3), v2. For the rig. */
    public boolean digBacked(int cx, int cz) {
        return digOk.contains(ChunkPos.asLong(cx, cz));
    }

    /** Rig only: forget every find and all v2 memory, then scan the loaded chunks around you again. */
    public void resetForTest(boolean enqueue) {
        announceLog.clear(); queue.clear(); queued.clear(); kelpWatch.clear(); absorbed.clear(); countedOver.clear(); villageCentres.clear(); finds.clear(); best.clear(); bestMode.clear(); hiddenSeen.clear(); pinged.clear(); bells.clear(); markers.clear(); rules.clear(); digCapped.clear(); evidence.clear(); digOk.clear();
        dismissed.clear(); ungrown.clear(); wrecked.clear(); live.clear(); views = List.of(); sites = List.of(); chunkSite = Map.of(); zoneList = List.of(); zonesSeen.clear();
        holdJudge.clear(); noPing.clear(); bonusAt.clear(); lastScan.clear(); griefSeen.clear(); removed.clear(); clearedAll = false; premark.clear();
        ledgerServer = null; ledgerDim = null; loading = null; holdPing.clear(); cardPending.clear(); ledgerDirty = false; pings = 0; v2Was = false; giveUpLogged = false;
        cardUntil.clear(); knownSites.clear(); quietUntil = 0;
        if (enqueue && mc.level != null && mc.player != null) onEnable();
    }


    // ---- v2: finds stay (best-ever per chunk) and the per-server ledger -------------------------------------------------------

    private static final String SEEN = "seen earlier";

    /** The find with a first reason line saying what it once scored; any older note of that kind is replaced. 0 points, so no total changes. */
    private static Find noted(Find f, String text) {
        List<Reason> rs = new ArrayList<>();
        rs.add(new Reason(text, 0, 0));
        for (Reason r : f.reasons()) if (!r.text().startsWith(SEEN)) rs.add(r);
        return new Find(f.x(), f.y(), f.z(), f.score(), f.why(), f.grief(), f.damage(), f.intact(), f.deep(), f.minY(), f.maxY(), f.sections(), f.allSections(),
                f.build(), f.dug(), f.growth(), f.farm(), rs, f.storage(), f.ev());
    }

    /** The raw (unsanitised) server key: the ip, "sp-<world name>" or "unknown". The file name adds a hash of it, so "My World" and "My_World" never share a file. */
    private static String rawServerKey() {
        if (serverOverride != null) return serverOverride; // rig only
        var m = net.minecraft.client.Minecraft.getInstance();
        var sd = m.getCurrentServer();
        if (sd != null) return sd.ip;
        if (m.getSingleplayerServer() != null) return "sp-" + m.getSingleplayerServer().getWorldData().getLevelName();
        return "unknown";
    }

    private static Path ledgerFile(String rawKey) {
        String name = rawKey.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 60) name = name.substring(0, 60);
        String hash;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            hash = String.format("%02x%02x%02x%02x", h[0], h[1], h[2], h[3]);
        } catch (Exception e) {
            hash = "0000";
        }
        return dev.vantage.core.Config.dir().resolve("base-finds").resolve(name + "-" + hash + ".json");
    }

    /** All file work for the ledger happens on this one thread, in order: the tick thread only builds a snapshot and hands it over. */
    private static final java.util.concurrent.ExecutorService LEDGER_IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vantage-ledger");
        t.setDaemon(true);
        return t;
    });
    private java.util.concurrent.Future<com.google.gson.JsonObject> loading;
    private String ledgerServer, ledgerDim;
    private boolean v2Was;
    /** The ledger file could not be read: it is not written over until the next join. */
    private boolean loadFailed, skipLogged;
    private volatile boolean giveUpLogged;
    private volatile int giveUpLogs;
    private int skipLogs;
    private volatile boolean ledgerRetry;

    /** Reads the file. One that does not parse is kept aside as .bad (numbered, never over an older one) and the result is empty; a read error (the file is busy) gives null: this load or save is skipped and the file stays. */
    private static com.google.gson.JsonObject readLedger(String rawKey) {
        if (readFailsLeft > 0) { readFailsLeft--; return null; } // rig only: pretend the file is busy
        Path f = ledgerFile(rawKey);
        try { Files.deleteIfExists(f.resolveSibling(f.getFileName() + ".tmp")); } catch (Exception e) { /* a leftover from a crash */ }
        String text;
        try {
            if (!Files.exists(f)) return new com.google.gson.JsonObject();
            text = Files.readString(f);
        } catch (Exception e) {
            dev.vantage.core.Vantage.LOG.warn("base-finds ledger could not be read, left alone: {}", e.getClass().getSimpleName());
            return null;
        }
        try {
            com.google.gson.JsonObject o = new com.google.gson.Gson().fromJson(text, com.google.gson.JsonObject.class);
            return o != null ? o : new com.google.gson.JsonObject();
        } catch (com.google.gson.JsonParseException e) {
            dev.vantage.core.Vantage.LOG.warn("base-finds ledger does not parse, kept as .bad: {}", e.getClass().getSimpleName());
        }
        try {
            Path bad = f.resolveSibling(f.getFileName() + ".bad");
            for (int i = 1; Files.exists(bad) && i < 100; i++) bad = f.resolveSibling(f.getFileName() + ".bad." + i);
            Files.move(f, bad);
        } catch (Exception e) { /* nothing more to do */ }
        return new com.google.gson.JsonObject();
    }

    private static com.google.gson.JsonObject toJson(long key, Find f, Boolean mode, boolean announced) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("key", key);
        if (mode != null) o.addProperty("strict", mode);
        o.addProperty("announced", announced);
        o.addProperty("x", f.x()); o.addProperty("y", f.y()); o.addProperty("z", f.z()); o.addProperty("score", f.score());
        if (f.why() != null) o.addProperty("why", f.why());
        if (f.grief() != null) o.addProperty("grief", f.grief());
        if (f.damage() != null) o.addProperty("damage", f.damage());
        if (f.storage() != null) o.addProperty("storage", f.storage());
        o.addProperty("intact", f.intact()); o.addProperty("deep", f.deep());
        o.addProperty("minY", f.minY()); o.addProperty("maxY", f.maxY()); o.addProperty("sections", f.sections()); o.addProperty("allSections", f.allSections());
        o.addProperty("build", f.build()); o.addProperty("dug", f.dug()); o.addProperty("growth", f.growth()); o.addProperty("farm", f.farm());
        if (f.ev().known()) {
            var e = f.ev(); var eo = new com.google.gson.JsonObject();
            eo.addProperty("m", e.machines()); eo.addProperty("s", e.spawners()); eo.addProperty("c", e.containers()); eo.addProperty("r", e.redstone());
            eo.addProperty("f", e.farmland()); eo.addProperty("p", e.portals()); eo.addProperty("b", e.beacons()); eo.addProperty("h", e.hall()); eo.addProperty("k", e.kelp());
            o.add("ev", eo);
        }
        com.google.gson.JsonArray rs = new com.google.gson.JsonArray();
        for (Reason r : f.reasons()) {
            if (r.text().startsWith(SEEN) || rs.size() >= 14) continue;
            com.google.gson.JsonObject ro = new com.google.gson.JsonObject();
            ro.addProperty("t", r.text()); ro.addProperty("p", r.points()); ro.addProperty("c", r.count());
            rs.add(ro);
        }
        o.add("reasons", rs);
        return o;
    }

    /** A file without the "ev" object (every round 5 and task 7 file) has no evidence: unknown until the chunk is scanned again. */
    private static Ev evFromJson(com.google.gson.JsonObject o) {
        try {
            if (!o.has("ev") || !o.get("ev").isJsonObject()) return Ev.UNKNOWN;
            var e = o.getAsJsonObject("ev");
            return new Ev(true, e.get("m").getAsInt(), e.get("s").getAsInt(), e.get("c").getAsInt(), e.get("r").getAsInt(), e.get("f").getAsInt(), e.get("p").getAsInt(), e.get("b").getAsInt(), e.get("h").getAsBoolean(), e.get("k").getAsInt());
        } catch (RuntimeException ex) { return Ev.UNKNOWN; }
    }

    private static String str(com.google.gson.JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static Find fromJson(com.google.gson.JsonObject o) {
        List<Reason> rs = new ArrayList<>();
        for (var e : o.getAsJsonArray("reasons")) {
            var ro = e.getAsJsonObject();
            rs.add(new Reason(ro.get("t").getAsString(), ro.get("p").getAsInt(), ro.get("c").getAsInt()));
        }
        return new Find(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("score").getAsInt(), str(o, "why") == null ? "" : str(o, "why"), str(o, "grief"), str(o, "damage"),
                o.get("intact").getAsBoolean(), o.get("deep").getAsBoolean(), o.get("minY").getAsInt(), o.get("maxY").getAsInt(), o.get("sections").getAsInt(), o.get("allSections").getAsInt(),
                o.get("build").getAsInt(), o.get("dug").getAsInt(), o.get("growth").getAsInt(), o.get("farm").getAsInt(), rs, str(o, "storage"), evFromJson(o));
    }

    private void ledgerTick() {
        v2Was = true;
        if (ledgerRetry) { ledgerRetry = false; ledgerDirty = true; }
        if (mc.level == null) return;
        String s = rawServerKey(), d = dev.vantage.core.ChunkTracker.dim();
        if (!s.equals(ledgerServer) || !d.equals(ledgerDim)) {
            if (ledgerServer != null) { finishLoad(); saveLedger("other"); best.clear(); bestMode.clear(); dismissed.clear(); lastScan.clear(); }
            loadLedger(s, d);
        } else if (loading != null) {
            if (loading.isDone()) applyLoaded();
        } else if (ledgerDirty && System.currentTimeMillis() - ledgerSavedAt > LEDGER_SAVE_MS) saveLedger("tick");
    }

    /** The switch went off: what v2 kept (best finds, dismissed list, the finds loaded from the ledger) is dropped and nothing more is written. */
    private void dropV2State() {
        v2Was = false;
        finishLoad();
        if (ledgerServer != null && ledgerDirty && !loadFailed) { // what was gathered while v2 was on is written once; nothing after this
            ledgerDirty = true;
            saveLedgerNow("v2-off");
        }
        best.clear(); bestMode.clear(); griefSeen.clear(); dismissed.clear();
        ledgerDirty = false; ledgerServer = null; ledgerDim = null; loading = null;
        finds.removeIfValue(f -> !f.reasons().isEmpty() && f.reasons().get(0).text().startsWith(SEEN));
        holdPing.clear(); holdJudge.clear(); noPing.clear(); bonusAt.clear(); lastScan.clear(); hiddenSeen.clear(); resetVisitMarks();
        if (on() && mc.level != null && mc.player != null) onEnable();
    }

    /** The marks that only matter while one visit's read is pending: reset in one place when a visit starts or the switch goes off. */
    private void resetVisitMarks() {
        removed.clear(); clearedAll = false; premark.clear(); ledgerRetry = false; giveUpLogged = false;
    }

    /** Waits (a second at most) for a ledger read that is still running and applies it. */
    private void finishLoad() {
        finishLoad(1000);
    }

    private void finishLoad(long ms) {
        if (loading == null) return;
        try { loading.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS); applyLoaded(); } catch (Exception e) { /* it will be applied by the tick, or not at all */ }
    }

    /** What an earlier visit to this server and dimension saved is read in the background; applyLoaded puts it in when it is there. */
    private void loadLedger(String server, String dim) {
        ledgerServer = server;
        ledgerDim = dim;
        ledgerDirty = false;
        loadFailed = false;
        skipLogged = false;
        resetVisitMarks();
        loading = LEDGER_IO.submit(() -> {
            var g = loadGate; // rig only: holds the read so a slow load can be tested
            if (g != null) try { g.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { /* test */ }
            return readLedger(server);
        });
    }

    private void applyLoaded() {
        com.google.gson.JsonObject root;
        try { root = loading.get(); } catch (Exception e) { root = null; }
        loading = null;
        if (root == null) { loadFailed = true; return; } // an unreadable file stays as it is: nothing is saved over it this visit
        try {
            applyRoot(root);
        } finally {
            removed.clear(); clearedAll = false; // the read is done: what was removed meanwhile is already out of best, a later save replaces the file's dimension
        }
    }

    private void applyRoot(com.google.gson.JsonObject root) {
        try {
            com.google.gson.JsonObject d = null;
            if (root.has("dims") && root.getAsJsonObject("dims").has(ledgerDim)) d = root.getAsJsonObject("dims").getAsJsonObject(ledgerDim);
            if (d == null) return;
            if (d.has("dismissed")) for (var e : d.getAsJsonArray("dismissed")) dismissed.add(e.getAsLong());
            int n = 0;
            if (d.has("finds")) for (var e : d.getAsJsonArray("finds")) {
                try {
                    var o = e.getAsJsonObject();
                    long key = o.get("key").getAsLong();
                    if (dismissed.contains(key) || n >= LEDGER_MAX || clearedAll || removed.contains(key)) continue; // cleared or removed while the file was being read: it does not come back
                    Find f = fromJson(o);
                    Boolean mode = o.has("strict") ? o.get("strict").getAsBoolean() : null;
                    Find cur = best.get(key);
                    if (cur == null) {
                        best.put(key, f);
                        if (mode != null) bestMode.put(key, mode); else bestMode.remove(key); // no mark = unknown = stale
                        finds.putIfAbsent(key, noted(f, SEEN + ": saved on an earlier visit, scored " + f.score() + (mode == null || mode != strict ? WAITING : "")));
                    } else if (fresh(key) && mode != null && mode == strict && f.score() > cur.score()) { // the first scans came before the file: the higher one is the best
                        best.put(key, f);
                        finds.put(key, noted(f, SEEN + ": saved on an earlier visit, scored " + f.score()));
                    }
                    Boolean announced = o.has("announced") ? o.get("announced").getAsBoolean() : null;
                    if (announced != null) { if (announced) pinged.add(key); } // announced then, not again; one never announced gets its ping when its chunk is scanned
                    else if (f.score() >= threshold.i() && pingFor(typeOfFind(f))) pinged.add(key); // an old file without the flag: the old rule
                    n++;
                } catch (Exception ex) { dev.vantage.core.Vantage.LOG.warn("base-finds entry unreadable, skipped: {}", ex.getClass().getSimpleName()); }
            }
        } catch (Exception e) {
            dev.vantage.core.Vantage.LOG.warn("base-finds ledger has a wrong shape, ignored: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * The writer thread leaves one text per save here (only with -Dvantage.bfdiag); the client thread writes the lines (the leak log is not thread safe). A queue, not one slot:
     * two saves between two ticks give two lines. Each text carries its own save time and dimension, so a line that comes out late still says when and where.
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> LEDGER_DIAG = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.time.format.DateTimeFormatter DIAG_TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Writes the queued ledger lines. One cheap isEmpty when the diagnostic is off (nothing is ever queued then). */
    private void ledgerDiagDrain() {
        if (LEDGER_DIAG.isEmpty()) return;
        String t;
        while ((t = LEDGER_DIAG.poll()) != null) {
            if (BFDIAG) try { dev.vantage.core.LeakLog.addDiag("BFDIAG-LEDGER", 0, 0, 0, t); } catch (Throwable x) { /* a diagnostic never stops anything */ }
        }
    }

    private java.util.concurrent.Future<?> saveLedger(String trigger) {
        return saveLedger(false, trigger);
    }

    private java.util.concurrent.Future<?> saveLedgerNow(String trigger) {
        return saveLedger(true, trigger);
    }

    /**
     * Builds this dimension's entry (the LEDGER_MAX strongest finds and the dismissed chunks) here, and writes it in the background, merged into the file and moved over it.
     * `trigger` is a fixed tag for the diagnostic line: tick, leave, exit, v2-off, rig or other.
     */
    private java.util.concurrent.Future<?> saveLedger(boolean ignoreSwitch, String trigger) {
        if (ledgerServer != null && ledgerDirty && loadFailed && !skipLogged) { skipLogged = true; skipLogs++; dev.vantage.core.Vantage.LOG.warn("base-finds ledger save skipped: the file could not be read this visit"); } // once per visit
        if (ledgerServer == null || !ledgerDirty || loadFailed || !(ignoreSwitch || v2())) return null;
        final boolean mergeOld = loading != null; // the read is still running: the save is queued behind it and keeps what the file holds
        ledgerDirty = false;
        ledgerSavedAt = System.currentTimeMillis();
        final String server = ledgerServer, dim = ledgerDim;
        com.google.gson.JsonObject d = new com.google.gson.JsonObject();
        List<Map.Entry<Long, Find>> top = new ArrayList<>(best.entrySet());
        top.sort((a, b) -> b.getValue().score() - a.getValue().score());
        com.google.gson.JsonArray fa = new com.google.gson.JsonArray();
        for (int i = 0; i < Math.min(LEDGER_MAX, top.size()); i++) fa.add(toJson(top.get(i).getKey(), top.get(i).getValue(), bestMode.get(top.get(i).getKey()), pinged.contains(top.get(i).getKey())));
        d.add("finds", fa);
        com.google.gson.JsonArray da = new com.google.gson.JsonArray();
        List<Long> dl = new ArrayList<>(dismissed);
        for (long k : dl.subList(Math.max(0, dl.size() - DISMISSED_MAX), dl.size())) da.add(k);
        d.add("dismissed", da);
        final Set<Long> removedNow = new HashSet<>(removed);
        final boolean clearedNow = clearedAll;
        final String why = ignoreSwitch ? "save now" : mergeOld ? "save behind a running read" : "save";
        final int entryCount = fa.size(), dismissedCount = da.size();
        return LEDGER_IO.submit(() -> {
            final long t0 = System.nanoTime();
            String outcome = "failed";
            var wg = writeGate; // rig only
            if (wg != null) try { wg.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { /* test */ }
            try {
                com.google.gson.JsonObject root = readLedger(server);
                if (root == null) { // busy file: one more try after a short wait (a world change or the exit has nothing later to retry it)
                    try { Thread.sleep(300); } catch (InterruptedException e) { /* test */ }
                    root = readLedger(server);
                }
                if (root == null) { ledgerRetry = true; if (!giveUpLogged) { giveUpLogged = true; giveUpLogs++; dev.vantage.core.Vantage.LOG.warn("base-finds ledger save given up: the file stayed unreadable (kept for the next tick if the visit goes on)"); } outcome = "given up, file unreadable"; return; }
                com.google.gson.JsonObject dims = root.has("dims") && root.get("dims").isJsonObject() ? root.getAsJsonObject("dims") : new com.google.gson.JsonObject();
                if (mergeOld && dims.has(dim) && dims.get(dim).isJsonObject()) mergeOldFinds(dims.getAsJsonObject(dim), d, removedNow, clearedNow);
                dims.add(dim, d);
                root.addProperty("v", 1);
                root.add("dims", dims);
                Path f = ledgerFile(server);
                Files.createDirectories(f.getParent());
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                Files.writeString(tmp, new com.google.gson.Gson().toJson(root));
                try { Files.move(tmp, f, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                giveUpLogged = false; // the save worked: a later give-up is a new problem and is logged again
                outcome = "written";
            } catch (Exception e) {
                dev.vantage.core.Vantage.LOG.warn("base-finds ledger not saved: {}", e.getClass().getSimpleName());
            } finally {
                if (BFDIAG) try { // built here, written by the client thread; the dimension id only, never the server key or a file name
                    if (LEDGER_DIAG.size() < 500) LEDGER_DIAG.add("ledger " + why + ": " + outcome + "; trigger " + trigger + "; saved at " + java.time.LocalTime.now().format(DIAG_TIME) + "; dim " + dim
                            + "; entries " + entryCount + "; dismissed " + dismissedCount + "; took " + (System.nanoTime() - t0) / 1_000_000 + " ms; thread " + Thread.currentThread().getName());
                } catch (Throwable x) { /* a diagnostic never stops a save */ }
            }
        });
    }

    /** A save queued behind a slow read: the finds and dismissed chunks the file already held stay unless this snapshot has them. */
    private static void mergeOldFinds(com.google.gson.JsonObject old, com.google.gson.JsonObject d, Set<Long> removedNow, boolean clearedNow) {
        int badFinds = 0, badDismissed = 0;
        try {
            java.util.Set<Long> have = new HashSet<>(), gone = new HashSet<>(removedNow);
            for (var e : d.getAsJsonArray("finds")) have.add(e.getAsJsonObject().get("key").getAsLong());
            for (var e : d.getAsJsonArray("dismissed")) gone.add(e.getAsLong());
            List<com.google.gson.JsonObject> all = new ArrayList<>();
            for (var e : d.getAsJsonArray("finds")) all.add(e.getAsJsonObject());
            if (old.has("finds") && !clearedNow) for (var e : old.getAsJsonArray("finds")) {
                try { // one bad entry is skipped, the rest of the file is kept
                    long k = e.getAsJsonObject().get("key").getAsLong();
                    e.getAsJsonObject().get("score").getAsInt();
                    if (!have.contains(k) && !gone.contains(k)) all.add(e.getAsJsonObject());
                } catch (Exception bad) { badFinds++; }
            }
            all.sort((a, b) -> b.get("score").getAsInt() - a.get("score").getAsInt());
            com.google.gson.JsonArray fa = new com.google.gson.JsonArray();
            for (int i = 0; i < Math.min(LEDGER_MAX, all.size()); i++) fa.add(all.get(i));
            d.add("finds", fa);
            // dismissed, oldest first: the file's older entries, then this visit's; the cap cuts the OLDEST so the newest dismissal always stays
            java.util.LinkedHashSet<Long> order = new java.util.LinkedHashSet<>();
            if (old.has("dismissed")) for (var e : old.getAsJsonArray("dismissed")) { try { order.add(e.getAsLong()); } catch (Exception bad) { badDismissed++; } }
            List<Long> mine = new ArrayList<>();
            for (var e : d.getAsJsonArray("dismissed")) mine.add(e.getAsLong());
            order.removeAll(mine); // an entry dismissed again this visit counts as new
            order.addAll(mine);
            List<Long> merged = new ArrayList<>(order);
            com.google.gson.JsonArray da = new com.google.gson.JsonArray();
            for (long k : merged.subList(Math.max(0, merged.size() - DISMISSED_MAX), merged.size())) da.add(k);
            d.add("dismissed", da);
        } catch (Exception e) { dev.vantage.core.Vantage.LOG.warn("base-finds merge with the old file failed, saved without it: {}", e.getClass().getSimpleName()); }
        if (badFinds + badDismissed > 0) {
            lastMergeSummary = "base-finds merge: skipped " + badFinds + " malformed old finds and " + badDismissed + " malformed dismissed entries"; // counts only: no keys, no coordinates
            dev.vantage.core.Vantage.LOG.warn(lastMergeSummary);
        }
    }

    /** Rig only: a saved find for a made-up chunk, to fill the ledger past its size cap. */
    public void testPutBest(long key, int score) {
        testPutBest(key, score, strict);
    }

    /** mode null = a file entry without the ore-mode mark. */
    public void testPutBest(long key, int score, Boolean mode) {
        if (mode == null) bestMode.remove(key); else bestMode.put(key, mode);
        best.put(key, new Find(0, 0, 0, score, "test", null, null, false, false, 0, 0, 0, 0, score, 0, 0, 0, List.of(new Reason("test", score, 1)), null, Ev.UNKNOWN));
        ledgerDirty = true;
    }

    public String dirtyWhy = "";

    public boolean ledgerPending() { return ledgerDirty; }

    /** The ledger of this server and dimension is loaded (or there is none) and nothing is being read. */
    public boolean ledgerReady() { return ledgerServer != null && loading == null; }

    public int heldPings() { return holdPing.size(); }

    public static String ledgerNameFor(String rawKey) { return ledgerFile(rawKey).getFileName().toString(); }

    /** Rig: a ping held since startedAt, with an extra chunk that counts as still queued next to it. */
    public void holdForTest(long key, long startedAt, long fakeQueuedNeighbour) {
        holdPing.put(key, startedAt);
        queued.add(fakeQueuedNeighbour);
    }

    public void unqueueForTest(long key) { queued.remove(key); }

    public boolean isHeld(int cx, int cz) { return holdPing.containsKey(ChunkPos.asLong(cx, cz)); }

    public boolean hasBestKey(long key) { return best.containsKey(key); }

    /** Rig: the first reason line (the note) of the find in this chunk, or "". */
    public String findNote(long key) {
        Find f = finds.get(key);
        return f == null || f.reasons().isEmpty() ? "" : f.reasons().get(0).text();
    }

    public boolean ledgerLoadFailed() { return loadFailed; }

    public boolean hasBest(int cx, int cz) { return best.containsKey(ChunkPos.asLong(cx, cz)); }

    public boolean hasDismissed() { return !dismissed.isEmpty(); }

    public String pillTextFor(BaseSites.Site s) { return pillText(s); }

    /** Rig: the flip as the tick would run it, with the mode given (the real flip reads FakeOres). */
    public void flipForTest(boolean toStrict) {
        strict = toStrict;
        if (v2()) markStale(); else { finds.clear(); pinged.clear(); }
    }

    public boolean strictMode() { return strict; }

    /** Rig: write the ledger the way leaving the game does. */
    public void flushOnExit() {
        if (!v2() || !v2Was && ledgerServer == null) { ledgerDiagDrain(); return; } // lines queued by an earlier save are still written; beta locked, Scoring v2 off, or v2 never ran: nothing to do
        long end = System.currentTimeMillis() + 2000; // one limit for the whole hook
        finishLoad(Math.max(1, end - System.currentTimeMillis()));
        saveLedger("exit");
        // also waits for a save that leaving the world queued a moment ago: everything queued before this runs first
        try { LEDGER_IO.submit(() -> {}).get(Math.max(1, end - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception e) { /* the game is closing */ }
        ledgerDiagDrain(); // after the wait on the writer, before the caller flushes the leak log: the lines of the saves above are in the file
    }

    /** Rig: the text of the last merge summary line. */
    public static volatile String lastMergeSummary = "";
    /** Rig (fix-list 6 T1): pretend v2 ran on a ledger (and left a find dirty), so a hook without its beta/v2 gate would have something to wait for. */
    public void plantV2RanForTest() {
        v2Was = true;
        ledgerServer = rawServerKey();
        ledgerDim = dev.vantage.core.ChunkTracker.dim();
        ledgerDirty = true;
    }

    private static volatile java.util.concurrent.CountDownLatch loadGate, writeGate;
    private static volatile int readFailsLeft;
    private static volatile String serverOverride;

    /** RIG ONLY: the next n reads of the ledger file fail as if the file were busy. */
    public static void failReadsForTest(int n) { readFailsLeft = n; }
    /** RIG ONLY: a task on the ledger thread that waits for this latch, so a drain wait can be seen. */
    public static void blockWriterForTest(java.util.concurrent.CountDownLatch l) { LEDGER_IO.submit(() -> { try { l.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { /* test */ } }); }
    /** RIG ONLY. */
    public void hiddenSeenAddForTest(long key) { hiddenSeen.add(key); }
    /** RIG ONLY: pretend to be on this server (null = the real one). */
    public static void serverForTest(String raw) { serverOverride = raw; }
    /** RIG ONLY: the ledger file of a server key. */
    public static Path ledgerPathFor(String raw) { return ledgerFile(raw); }
    /** RIG ONLY: the chunk's last scan saw it as griefed (the shown find carries a grief verdict or the best does not). */
    public boolean sawGrief(int cx, int cz) { return griefSeen.contains(ChunkPos.asLong(cx, cz)); }
    /** RIG ONLY: is this chunk marked as holding build or storage evidence for digging. */
    public boolean hasEvidence(int cx, int cz) { return evidence.contains(ChunkPos.asLong(cx, cz)); }

    /** RIG ONLY: how often the "given up" line was logged. */
    public int giveUpLogCount() { return giveUpLogs; }
    /** RIG ONLY: marks kept for a pending read (removed keys, a cleared mark). */
    public int visitMarks() { return removed.size() + (clearedAll ? 1000 : 0) + premark.size(); }
    /** RIG ONLY: v2-only bookkeeping that a beta-locked client must not carry. */
    public int v2StateSize() { return removed.size() + griefSeen.size() + holdJudge.size() + hiddenSeen.size() + best.size() + noPing.size(); }
    /** RIG ONLY: how many chunks wait in the live-block-update set for the once-a-second drain. */
    public int touchedSize() { return touched.size(); }
    /** RIG ONLY: how many chunks remember a hold that ended without a ping. */
    public int noPingSize() { return noPing.size(); }
    /** RIG ONLY: how many holds the below-Y-0 bonus re-arm has started. */
    public int bonusRearmTotal() { return bonusRearms; }
    /** RIG ONLY: does a hold judge exist for this chunk? */
    public boolean holdJudgeHas(int cx, int cz) { return holdJudge.containsKey(ChunkPos.asLong(cx, cz)); }
    /** RIG ONLY: a no-ping memory entry for a chunk (maybe without a find). */
    public void noPingPutForTest(int cx, int cz) { noPing.put(ChunkPos.asLong(cx, cz), "test"); }
    /** RIG ONLY: switch the hold diagnostic on or off. */
    public static void setDiagForTest(boolean on) { BFDIAG = on; }
    /** RIG ONLY: the switch as it is now (a scene puts it back to what it was). */
    public static boolean diagForTest() { return BFDIAG; }
    /** RIG ONLY: makes the 60 s mark of the bonus re-arm of this chunk 61 s old. */
    public void ageBonusMarkForTest(int cx, int cz) { long k = ChunkPos.asLong(cx, cz); if (bonusAt.containsKey(k)) bonusAt.put(k, System.currentTimeMillis() - 61_000); }
    /** RIG ONLY: a chunk that counts as still queued (so holds next to it wait) or not. */
    public void fakeQueuedForTest(int cx, int cz, boolean on) { long k = ChunkPos.asLong(cx, cz); if (on) queued.add(k); else queued.remove(k); }
    /** Rig: end a running hold the way a cancel does (writes the cancel line when the diagnostic is on). */
    public void cancelHoldForTest(int cx, int cz) { cancelHold(ChunkPos.asLong(cx, cz), "rig"); }

    /** Rig: the chunk list the diagnostic lines end in, cut at `limit` characters. */
    public String chunkListForTest(int cx, int cz, int limit) { StringBuilder sb = new StringBuilder(); appendChunks(sb, ChunkPos.asLong(cx, cz), limit); return sb.toString(); }

    /** Rig: the next ledger save is not skipped as unchanged. */
    public void dirtyLedgerForTest() { ledgerDirty = true; }

    /** RIG ONLY: forget the latest-scan record of a chunk (as for a find that only came from the saved file). */
    public void forgetLastScanForTest(int cx, int cz) { lastScan.remove(ChunkPos.asLong(cx, cz)); }
    /** RIG ONLY: does this chunk remember a hold that ended without a ping? */
    public boolean noPingHas(int cx, int cz) { return noPing.containsKey(ChunkPos.asLong(cx, cz)); }
    /** RIG ONLY: the signature stored when this chunk's last hold ended without a ping, or null. */
    public String holdSigOf(int cx, int cz) { return noPing.get(ChunkPos.asLong(cx, cz)); }
    /** RIG ONLY: how often the "save skipped" warning was logged. */
    public int skipLogCount() { return skipLogs; }
    /** RIG ONLY: the merge a save queued behind a slow read does, on made-up JSON. */
    public static void mergeForTest(com.google.gson.JsonObject old, com.google.gson.JsonObject d, Set<Long> removedNow, boolean clearedNow) { mergeOldFinds(old, d, removedNow, clearedNow); }

    /** RIG ONLY: the ledger reader / writer wait for this latch (null = no wait). */
    public static void holdLoadForTest(java.util.concurrent.CountDownLatch l) { loadGate = l; }
    public static void holdWriteForTest(java.util.concurrent.CountDownLatch l) { writeGate = l; }
    public boolean isLoading() { return loading != null; }
    public Boolean modeOf(long key) { return bestMode.get(key); }
    public boolean hiddenSeenHas(long key) { return hiddenSeen.contains(key); }
    /** RIG ONLY: sets the ore-mode mark of a saved find (null = no mark). */
    public void setModeForTest(long key, Boolean m) { if (m == null) bestMode.remove(key); else bestMode.put(key, m); }

    /** RIG ONLY. */
    public boolean isCardPending(int siteId) { return cardPending.contains(siteId); }
    /** RIG ONLY. */
    public void markPendingForTest(int siteId) { cardPending.add(siteId); }

    /** Rig only: waits until everything queued on the ledger thread has run. */
    public void awaitWriter() {
        try { LEDGER_IO.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { dev.vantage.core.Vantage.LOG.warn("awaitWriter: {}", e.getClass().getSimpleName()); }
    }

    /** Rig: scan this chunk again. */
    public void rescan(int cx, int cz) { enqueue(new ChunkPos(cx, cz)); }

    /** Rig: a live player signal in a chunk, as if somebody had been seen digging there. */
    public void signalForTest(int x, int y, int z) { signal(x, y, z, null); }

    /** Rig: write a changed ledger now and wait until it is on disk (also waits for a load that is still running). */
    public void flushLedger() {
        try {
            if (loading != null) { loading.get(); applyLoaded(); }
            var f = saveLedger("rig");
            LEDGER_IO.submit(() -> {}).get();
            if (f != null) f.get();
        } catch (Exception e) {
            dev.vantage.core.Vantage.LOG.warn("flushLedger: {}", e.getClass().getSimpleName());
        }
    }

    /** The player's decision: this chunk's find is gone and stays gone (this session and the ledger) until "Clear saved finds". */
    public void dismiss(long key) {
        if (v2()) dismissed.remove(key); // a re-dismissed chunk moves to the newest end: the cap cuts the oldest first (v2 only: v2-off order stays as it was)
        dismissed.add(key);
        while (dismissed.size() > DISMISSED_MAX) dismissed.remove(dismissed.iterator().next());
        finds.remove(key);
        best.remove(key);
        lastScan.remove(key);
        markRemoved(key);
        ledgerDirty = true;
    }

    private int dismissNearest() {
        if (!v2() || mc.player == null) return 0;
        long nearest = 0;
        double bd = Double.MAX_VALUE;
        for (var e : finds.entrySet()) {
            double d = Math.pow(e.getValue().x() - mc.player.getX(), 2) + Math.pow(e.getValue().z() - mc.player.getZ(), 2);
            if (d < bd) { bd = d; nearest = e.getKey(); }
        }
        if (bd == Double.MAX_VALUE) return 0;
        dismiss(nearest);
        return 1;
    }

    /** Forgets the saved finds and the dismissed list of this server: the file goes, and so do the finds in memory (live chunks find their own again). */
    public void clearLedger() {
        best.clear();
        bestMode.clear();
        dismissed.clear();
        finds.clear();
        pinged.clear();
        holdPing.clear(); holdJudge.clear(); noPing.clear(); bonusAt.clear(); lastScan.clear();
        ledgerDirty = false;
        loading = null;
        ledgerServer = null; ledgerDim = null;
        try {
            LEDGER_IO.submit(() -> {
                try { Files.deleteIfExists(ledgerFile(rawServerKey())); } catch (Exception e) { dev.vantage.core.Vantage.LOG.warn("base-finds ledger not deleted: {}", e.getClass().getSimpleName()); }
            }).get();
        } catch (Exception e) { dev.vantage.core.Vantage.LOG.warn("base-finds ledger not deleted: {}", e.getClass().getSimpleName()); }
        if (mc.level != null && mc.player != null) onEnable();
    }

    /** True when the saved ledger for this server exists on disk. For the rig. */
    public static boolean ledgerExists() {
        return Files.exists(ledgerFile(rawServerKey()));
    }

    public static Path ledgerPath() {
        return ledgerFile(rawServerKey());
    }

    /** Declutter is in charge: Base Finder on (and not muted for a rig picture) with its Declutter switch on. */
    public static boolean declutter() {
        BaseFinder bf = dev.vantage.core.Modules.get(BaseFinder.class);
        return bf != null && bf.on() && bf.declutter.on();
    }

    // ---- R3.Policy: what the world drawing layer asks of Base Finder ----
    @Override
    public boolean declutterOn() {
        return on() && declutter.on();
    }

    @Override
    public double labelRange() {
        return labelRange.get();
    }

    @Override
    public int labelCap() {
        return maxLabels.i();
    }

    @Override
    public Set<Long> focusChunks() {
        BaseSites.Site s = focusSite();
        return s == null ? null : s.chunks();
    }

    @Override
    public double columnFade() {
        return columnFade.get();
    }

    /** One entry per base, strongest first. */
    public List<BaseSites.Site> sites() {
        return sites;
    }

    /** The base (not a guessed area) covering this block column, or null. */
    public BaseSites.Site siteAt(int x, int z) {
        return chunkSite.get(ChunkPos.asLong(x >> 4, z >> 4));
    }

    /** A base Base Finder already marks covers this spot and is within Tag range: other markers over it would say the same thing. */
    public boolean siteNear(double x, double z) {
        BaseSites.Site s = siteAt((int) Math.floor(x), (int) Math.floor(z));
        return s != null && s.dist() <= tagRange.get();
    }

    public Focus focus() {
        return focus;
    }

    public BaseSites.Site focusSite() {
        return focus == null ? null : site(focus.siteId());
    }

    public BaseSites.Site site(int id) {
        for (BaseSites.Site s : sites) if (s.id() == id) return s;
        return null;
    }

    /** A flagged chunk (not a guessed area) at or next to this chunk. */
    public boolean viewNear(int cx, int cz) {
        for (View v : views) if (!v.label().equals("Likely area") && Math.abs(v.cx() - cx) <= 1 && Math.abs(v.cz() - cz) <= 1) return true;
        return false;
    }

    /** One line per find, for the screenshot test log. */
    public List<String> lines() {
        List<String> l = new ArrayList<>();
        for (Find f : real()) l.add("BASE " + f.score() + " " + f.x() + " " + f.z() + (f.deep() ? " UNDERGROUND" : "") + (f.grief() == null ? " live" : " GRIEFED " + f.grief()) + " [" + f.why() + "]");
        return l;
    }

    private List<Find> real() {
        return finds.values().stream().filter(f -> f.score() >= threshold.i() && !hidden(f)).toList();
    }

    /** A surface find while Underground only is on in the overworld: kept for its clues, never shown or pinged. Nothing in the Nether or End is "under the ground". */
    private boolean hidden(Find f) {
        return deepOnly.on() && !f.deep() && mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
    }

    public View hovered() {
        return hovered;
    }

    public int pending() {
        return queue.size();
    }

    /** Average scan time per chunk in microseconds, for the test log. */
    public long scanMicros() {
        return scans == 0 ? 0 : scanNanos / scans / 1000;
    }

    /** Forget live signals, for the test rig. */
    public void clearLive() {
        live.clear();
    }

    public int ungrownCount() {
        return ungrown.size();
    }

    public boolean isUngrown(int cx, int cz) {
        return ungrown.contains(ChunkPos.asLong(cx, cz));
    }

    public int liveCount() {
        return live.size();
    }

    public String liveList() {
        StringBuilder b = new StringBuilder();
        live.forEach((k, l) -> b.append(l.x).append(' ').append(l.y).append(' ').append(l.z).append(" n=").append(l.count).append(l.who == null ? "" : " " + l.who).append("; "));
        return b.toString();
    }

    /** What the panel holds, for the test log. */
    public List<View> views() {
        return views;
    }

    /** Fresh live signal in a chunk, or null: Base ESP uses this as evidence that a base is in use. */
    public static boolean redstone(Block b) {
        return REDSTONE.contains(b);
    }

    public boolean liveAt(int x, int z) {
        Live l = live.get(ChunkPos.asLong(x >> 4, z >> 4));
        return l != null && System.currentTimeMillis() - l.last < LIVE_RECENT_MS;
    }

    public String griefAt(int x, int z) {
        Find f = finds.get(ChunkPos.asLong(x >> 4, z >> 4));
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        return f != null ? griefOf(key, f) : wreckNear(x >> 4, z >> 4); // one predicate: griefOf has the same test this method used to copy
    }

    private String wreckNear(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            String w = wrecked.get(ChunkPos.asLong(cx + dx, cz + dz));
            if (w != null) return w;
        }
        return null;
    }

    /** Blocks that only show up when a player places them, with how much each section holding one adds. */
    private Map<Block, Integer> palette() {
        if (palette != null) return palette;
        Map<Block, Integer> m = new HashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            String n = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (n.endsWith("_concrete")) m.put(b, 4);
            else if (n.endsWith("_concrete_powder")) m.put(b, 3);
            else if (n.endsWith("_stained_glass") || n.endsWith("_stained_glass_pane")) m.put(b, 3);
            else if (n.endsWith("_glazed_terracotta")) m.put(b, 3);
            else if (n.endsWith("_carpet") && !n.equals("moss_carpet")) m.put(b, 2);
            else if (n.endsWith("_wool")) m.put(b, 1);
            else if (n.endsWith("anvil")) m.put(b, 4);
        }
        m.put(Blocks.CRAFTING_TABLE, 2);
        m.put(Blocks.OBSIDIAN, 2);
        m.put(Blocks.CRYING_OBSIDIAN, 1);
        m.put(Blocks.NETHER_PORTAL, 5);
        m.put(Blocks.RESPAWN_ANCHOR, 3);
        m.put(Blocks.GLASS, 2);
        m.put(Blocks.TINTED_GLASS, 4);
        m.put(Blocks.SEA_LANTERN, 2);
        m.put(Blocks.REDSTONE_LAMP, 3);
        m.put(Blocks.OBSERVER, 3);
        m.put(Blocks.PISTON, 2);
        m.put(Blocks.STICKY_PISTON, 3);
        m.put(Blocks.REPEATER, 2);
        m.put(Blocks.COMPARATOR, 3);
        m.put(Blocks.SMITHING_TABLE, 2);
        m.put(Blocks.GRINDSTONE, 2);
        m.put(Blocks.NETHERITE_BLOCK, 10);
        m.put(Blocks.DIAMOND_BLOCK, 8);
        m.put(Blocks.EMERALD_BLOCK, 4);
        m.put(Blocks.GOLD_BLOCK, 3);
        m.put(Blocks.IRON_BLOCK, 2);
        m.put(Blocks.FARMLAND, 1);
        m.put(Blocks.REDSTONE_WIRE, 2);
        m.put(Blocks.REDSTONE_TORCH, 1);
        m.put(Blocks.REDSTONE_WALL_TORCH, 1);
        m.put(Blocks.REDSTONE_BLOCK, 3);
        for (Block b : CRAFTED_DEEPSLATE) m.put(b, 3);
        return palette = m;
    }

    @Override
    protected void onDisable() {
        kelpWatch.unloadAll(); // unloads are not heard while off, so what was held then is unknown
    }

    @Override
    protected void onEnable() {
        if (mc.level == null || mc.player == null) return;
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos pc = mc.player.chunkPosition();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) enqueue(new ChunkPos(pc.x + x, pc.z + z));
    }

    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    /** Coming from no world there is nothing to drop (forgetWorld ran when the last one was left); only the quiet time starts again. */
    @Override
    public void onJoinWorld() {
        startQuietTime();
    }

    private void startQuietTime() {
        quietUntil = System.currentTimeMillis() + 4000;
    }

    private void forgetWorld() {
        if (v2()) { finishLoad(); saveLedger("leave"); }
        giveUpLogged = false; ledgerServer = null; ledgerDim = null; loading = null; holdPing.clear(); cardPending.clear(); premark.clear(); removed.clear(); clearedAll = false; holdJudge.clear(); noPing.clear(); bonusAt.clear(); lastScan.clear(); griefSeen.clear();
        queue.clear();
        queued.clear();
        kelpWatch.clear();
        absorbed.clear(); countedOver.clear(); villageCentres.clear();
        if (v2()) touched.clear(); // stale entries of the last world could delay a hold by up to 4 s (v2 only: a player with Scoring v2 off sees no change)
        bells.clear(); markers.clear(); rules.clear(); digCapped.clear(); evidence.clear(); digOk.clear(); best.clear(); bestMode.clear(); hiddenSeen.clear(); dismissed.clear();
        finds.clear();
        pinged.clear();
        announceLog.clear();
        live.clear();
        digs.clear();
        changed.clear();
        wrecked.clear();
        noiseUntil = 0;
        cardUntil.clear(); cardPending.clear(); knownSites.clear(); startQuietTime();
        zoneList = List.of();
        zonesSeen.clear();
        ungrown.clear();
        views = List.of();
        hovered = null;
        sites = List.of();
        chunkSite = Map.of();
        focus = null;
    }

    @Override
    public void onChunkUnload(int cx, int cz) {
        kelpWatch.unloaded(ChunkPos.asLong(cx, cz));
        if (!v2()) return;
        hiddenSeen.remove(ChunkPos.asLong(cx, cz)); // a chunk that comes back (or gets a second container) can re-queue the capped digging next to it again
    }

    @Override
    public void onChunkLoad(LevelChunk chunk) {
        enqueue(chunk.getPos());
    }

    /** How much of the grown kelp seen this session grew under the user's own nose (it does not count). */
    private KelpWatch kelpWatch = new KelpWatch(new KelpMemory().in(dev.vantage.core.ChunkTracker::dim));

    /** Rig: start the kelp memory again from what is on disk, the way a restarted game does. */
    public void relogKelpForTest() {
        kelpWatch = new KelpWatch(new KelpMemory().in(dev.vantage.core.ChunkTracker::dim));
    }

    /** Split piles: the chunk that scores one -> the other chunks whose containers it counts. */
    private final Map<Long, Set<Long>> absorbed = new HashMap<>();
    /** Split piles: the scoring chunk -> the containers of other chunks it counted at its last scan. */
    private final Map<Long, Set<BlockPos>> countedOver = new HashMap<>();
    /** Meeting points of villages seen (released scoring): the finds around a new one are looked at again. */
    private final Set<Long> villageCentres = new HashSet<>();

    /** Within the user's own simulation distance (one chunk of slack): what grows there can be the user's doing. */
    private boolean tickedByUser(ChunkPos c) {
        if (mc.player == null) return false;
        ChunkPos me = mc.player.chunkPosition();
        return Math.max(Math.abs(c.x - me.x), Math.abs(c.z - me.z)) <= mc.level.getServerSimulationDistance() + 1;
    }

    /** Chunks with block changes, rescanned once a second: on Donut a base next to you arrives as block updates, not as a chunk packet. */
    private final Set<Long> touched = new HashSet<>();
    private long touchedAt;

    @Override
    public void onBlockUpdate(BlockPos pos, BlockState state) {
        touched.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    @Override
    public void onSectionBurst(net.minecraft.core.SectionPos sp, int count, BlockPos first, BlockState firstState) {
        touched.add(ChunkPos.asLong(sp.x(), sp.z()));
    }

    @Override
    public void onTick() {
        ledgerDiagDrain(); // outside the v2 branch: a save at Scoring v2 off is written by this tick too
        if (dev.vantage.core.FakeOres.detected() != strict) {
            strict = dev.vantage.core.FakeOres.detected();
            if (v2()) markStale(); // nothing is deleted: a find from the other mode stays (and stays in the file) until its chunk is scanned again, then the new result replaces it
            else { finds.clear(); pinged.clear(); }
            onEnable();
        }
        if (v2()) {
            ledgerTick(); releasePings();
            long nowMs = System.currentTimeMillis();
            if (nowMs - hiddenCheck > 2000 && mc.level != null) { // a container the server leaks late backs the digging around it: capped chunks are scored again
                hiddenCheck = nowMs;
                for (long k : dev.vantage.core.HiddenStorage.chunks()) {
                    if (!hiddenSeen.add(k)) continue;
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        long nk = ChunkPos.asLong(ChunkPos.getX(k) + dx, ChunkPos.getZ(k) + dz);
                        if (digCapped.remove(nk)) enqueue(new ChunkPos(nk));
                    }
                }
            }
        } else if (v2Was) dropV2State();
        if (!touched.isEmpty() && System.currentTimeMillis() - touchedAt > 1000) {
            touchedAt = System.currentTimeMillis();
            // at most a handful per second, nearest first: a server un-filling a slab touches dozens of chunks at once
            int r = mc.options.getEffectiveRenderDistance();
            List<Long> near = new ArrayList<>();
            for (long k : touched) if (Math.max(Math.abs(ChunkPos.getX(k) - mc.player.chunkPosition().x), Math.abs(ChunkPos.getZ(k) - mc.player.chunkPosition().z)) <= r) near.add(k);
            near.sort(java.util.Comparator.comparingInt(k -> Math.max(Math.abs(ChunkPos.getX(k) - mc.player.chunkPosition().x), Math.abs(ChunkPos.getZ(k) - mc.player.chunkPosition().z))));
            for (int i = 0; i < Math.min(8, near.size()); i++) { long k = near.get(i); enqueue(new ChunkPos(ChunkPos.getX(k), ChunkPos.getZ(k))); touched.remove(k); }
            if (touched.size() > 256) touched.clear(); // a backlog this size is a sweep, not work
        }
        long t0 = System.nanoTime();
        int sb = 0;
        while (!queue.isEmpty() && System.nanoTime() - t0 < 700_000 && dev.vantage.core.ScanBudget.go(sb++)) { // half a frame at 700 fps: a scan tick must never be a visible dip
            ChunkPos cp = queue.poll();
            queued.remove(cp.toLong());
            if (mc.level.getChunkSource().getChunk(cp.x, cp.z, ChunkStatus.FULL, false) instanceof LevelChunk c) {
                long b0 = System.nanoTime();
                scan(c);
                dev.vantage.core.ScanBudget.spend(System.nanoTime() - b0);
            }
        }
        if (--viewTimer > 0) return;
        viewTimer = 10;
        if (livePlayers.on()) scanPlayers();
        rebuildViews();
    }

    private int surfaceAt(int x, int z) {
        return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private final Map<Long, Dig> digs = new HashMap<>();
    private final Map<Long, String> wrecked = new HashMap<>();
    private final Map<Long, Integer> belowZero = new HashMap<>();
    private final Map<Long, Long> changed = new HashMap<>();
    private long noiseUntil;
    private static final int UNCLEAR_FLOOR = 40;
    private int zoneTimer;
    private Set<BaseLogic.Clue> weakClues = new HashSet<>();
    private List<BaseLogic.Zone> zoneList = List.of();
    private final Set<Long> zonesSeen = new HashSet<>();
    /** Chunks with enough kelp or a geode that nothing has grown in: nobody stayed near these. */
    private final Set<Long> ungrown = new HashSet<>();

    private void signal(int x, int y, int z, String who) {
        long now = System.currentTimeMillis();
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        Live l = live.computeIfAbsent(key, k -> new Live());
        l.count++;
        l.last = now;
        l.x = x; l.y = y; l.z = z;
        if (who != null) l.who = who;
        if (now - l.toast < LIVE_TOAST_MS) return;
        l.toast = now;
        if (!pingFor(T_SOUNDS)) return; // v2: a player walking around is "player sounds", off by default
        dev.vantage.core.Toasts.warn("BaseFinder  live player", x + " " + z + "  " + (l.who != null ? (dev.vantage.core.Streamer.hidesOthers() ? "A player" : l.who) + " underground" : "live player signal " + l.count));
    }

    /** Anyone the client can see who is under the ground right now. */
    private void scanPlayers() {
        for (var p : mc.level.players()) {
            if (p == mc.player || dev.vantage.core.Friends.is(p)) continue;
            int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
            if (mc.level.dimensionType().hasCeiling() || surfaceAt(x, z) - y < LIVE_DEPTH) continue;
            signal(x, y, z, p.getName().getString());
        }
    }

    /**
     * Solid blocks going to air one after the other deep down, away from you, mean somebody is digging there, seen or not. Gravel and
     * sand fall by themselves and fluids, plants and leaves change all the time, so only rock and dirt count. Only single block packets
     * get here: servers with anti-xray reveal hidden caves in bulk as you move, which looks like digging one block at a time doesn't.
     */
    public void blockChanged(BlockPos pos, BlockState before, BlockState after) {
        if (!on() || !livePlayers.on() || !after.isAir() || mc.player == null || mc.level.dimensionType().hasCeiling()) return;
        if (before.getBlock() instanceof net.minecraft.world.level.block.FallingBlock || !before.getFluidState().isEmpty() || !before.isSolidRender()) return;
        if (pos.distToCenterSqr(mc.player.position()) < LIVE_MIN_DIST * LIVE_MIN_DIST) return;
        if (surfaceAt(pos.getX(), pos.getZ()) - pos.getY() < LIVE_DEPTH) return;
        long now = System.currentTimeMillis();
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        changed.put(key, now);
        changed.values().removeIf(t -> now - t > NOISE_WINDOW_MS);
        if (changed.size() >= NOISE_CHUNKS) {
            if (now >= noiseUntil) dev.vantage.core.Vantage.LOG.info("Base Finder: {} chunks changed at once, ignoring block changes for a while", changed.size());
            noiseUntil = now + NOISE_HOLD_MS;
            digs.clear();
            live.values().removeIf(l -> l.who == null);
        }
        if (now < noiseUntil) return;
        Dig d = digs.computeIfAbsent(key, k -> new Dig());
        long tick = mc.level.getGameTime();
        if (d.breaks > 0 && d.tick == tick) return;
        boolean chain = d.breaks > 0 && now - d.last < DIG_MS && Math.abs(pos.getX() - d.x) <= DIG_REACH && Math.abs(pos.getY() - d.y) <= DIG_REACH
                && Math.abs(pos.getZ() - d.z) <= DIG_REACH;
        d.breaks = chain ? d.breaks + 1 : 1;
        d.x = pos.getX(); d.y = pos.getY(); d.z = pos.getZ(); d.last = now; d.tick = tick;
        if (d.breaks >= DIG_BREAKS) signal(pos.getX(), pos.getY(), pos.getZ(), null);
    }

    private static String heardLabel(String title) {
        return title.isEmpty() ? "Heard activity" : Character.toUpperCase(title.charAt(0)) + title.substring(1);
    }

    private static int pct(int score) {
        return Math.min(99, (int) Math.round(100 * (1 - Math.exp(-score / 60.0))));
    }

    /** For the rig: the v1 label of a find. */
    public static String labelOf(Find f) { return labelFor(f); }

    private static String labelFor(Find f) {
        if (f.grief() != null) return "Griefed";
        Reason top = null;
        for (Reason r : f.reasons()) if (top == null || r.points() > top.points()) top = r;
        if (top != null && top.text().equals("storage") && top.points() * 10 >= f.score() * 7) return f.storage() != null ? f.storage() : "Storage";
        for (Reason r : f.reasons()) if (r.text().endsWith("not a base")) return "Build";
        if (f.score() >= 70) return "Probable base";
        if (top == null) return "Unknown";
        // nothing built, dug or stored: the points are all growth clues (grown kelp, bare dirt, changed blocks), so say that and not "Unknown"
        if (f.growth() > 0 && f.growth() * 2 >= f.score() && f.build() <= 0 && f.dug() <= 0 && f.farm() < FARM_POINTS) return "Growth signs";
        if (f.dug() * 2 >= f.score()) return f.dug() >= 18 ? "Excavated" : "Dug Shaft";
        if (f.farm() >= FARM_POINTS) return "Farm";
        int obsidian = 0, stored = 0;
        for (Reason r : f.reasons()) {
            if (r.text().equals("obsidian")) obsidian = r.count();
            if (r.text().equals("storage")) stored = r.count();
        }
        if (f.deep() && obsidian >= VAULT_OBSIDIAN && stored > 0) return "Vault";
        if (top.text().equals("storage")) return f.storage() != null ? f.storage() : "Storage";
        for (Reason r : f.reasons()) if (r.text().equals("redstone parts") && r.count() >= 4) return "Redstone";
        return "Unknown";
    }

    /** Containers and light the server hides, per chunk, from what it let slip. */
    private Map<Long, Extra> extras() {
        Map<Long, Extra> out = new HashMap<>();
        for (long key : dev.vantage.core.HiddenStorage.chunks()) {
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            if (!(mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c)) continue;
            for (var h : dev.vantage.core.HiddenStorage.in(cx, cz)) {
                if (c.getBlockEntity(h.pos()) != null) continue;
                Extra e = out.computeIfAbsent(key, k -> new Extra());
                if (h.heard()) e.heard++; else e.containers++;
                e.x = h.pos().getX(); e.y = h.pos().getY(); e.z = h.pos().getZ();
                dev.vantage.core.LeakLog.add("CONTAINER", e.x, e.y, e.z, net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(h.type()).getPath());
            }
        }
        LightScan ls = dev.vantage.core.Modules.get(LightScan.class);
        if (ls.on()) for (LightScan.Find lf : ls.finds()) {
            if (lf.level() < 4 || lf.natural()) continue; // magma, lichen and other faint natural glow
            BlockPos p = lf.peak();
            Extra e = out.computeIfAbsent(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), k -> new Extra());
            e.light = Math.max(e.light, lf.level());
            if (e.containers == 0) { e.x = p.getX(); e.y = p.getY(); e.z = p.getZ(); }
        }
        SkyFingerprint sky = dev.vantage.core.Modules.get(SkyFingerprint.class);
        if (sky.on()) for (SkyFingerprint.Hit h : sky.hits()) {
            Extra e = out.computeIfAbsent(ChunkPos.asLong(h.cx(), h.cz()), k -> new Extra());
            e.sky = Math.max(e.sky, h.base() ? 2 : 1);
            if (e.containers == 0 && e.heard == 0 && e.light == 0) { e.x = (h.cx() << 4) + 8; e.y = 24; e.z = (h.cz() << 4) + 8; }
        }
        SimulationRing ring = dev.vantage.core.Modules.get(SimulationRing.class);
        if (ring.on() && !dev.vantage.core.util.Alone.now()) for (SimulationRing.Hit h : ring.hits()) {
            if (!(mc.level.getChunkSource().getChunk(h.cx(), h.cz(), ChunkStatus.FULL, false) instanceof LevelChunk)) continue; // no ground to read a height from
            Extra e = out.computeIfAbsent(ChunkPos.asLong(h.cx(), h.cz()), k -> new Extra());
            e.ring = Math.max(e.ring, (int) h.score());
            if (e.containers == 0 && e.heard == 0 && e.light == 0 && e.sky == 0) {
                e.x = (h.cx() << 4) + 8; e.z = (h.cz() << 4) + 8;
                e.y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, e.x, e.z);
            }
        }
        SoundDetector snd = dev.vantage.core.Modules.get(SoundDetector.class);
        int fedNow = 0;
        if (snd != null && snd.on() && useSound.on()) {
            for (var se : snd.evidence(System.currentTimeMillis()).entrySet()) {
                Extra e = out.computeIfAbsent(se.getKey(), k -> new Extra());
                var ev = se.getValue();
                e.sound = ev.points;
                e.soundTitle = ev.title;
                e.soundLines = ev.lines;
                if (e.containers == 0 && e.heard == 0 && e.light == 0 && e.sky == 0 && e.ring == 0) { e.x = ev.x; e.y = ev.y; e.z = ev.z; }
                fedNow++;
            }
        }
        if (snd != null) snd.fed = fedNow;
        OwedGrowth owed = dev.vantage.core.Modules.get(OwedGrowth.class);
        if (owed.on()) for (long k : owed.flagged()) {
            // a gap on its own is a tile at most; it only joins a square that something else already flagged
            Extra e = out.get(k);
            if (e == null && !finds.containsKey(k) && !live.containsKey(k)) continue;
            if (e == null) e = out.computeIfAbsent(k, kk -> new Extra());
            e.owed = true;
            if (e.x == 0 && e.z == 0) { e.x = (ChunkPos.getX(k) << 4) + 8; e.y = 63; e.z = (ChunkPos.getZ(k) << 4) + 8; }
        }
        // two separate leaks agreeing on one spot: hidden light beside hidden containers
        for (var en : out.entrySet()) {
            if (en.getValue().containers == 0) continue;
            int cx = ChunkPos.getX(en.getKey()), cz = ChunkPos.getZ(en.getKey());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                Extra o = out.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (o != null && o.light > 0 && o.light < 15) en.getValue().room = true;
            }
        }
        return out;
    }

    /** Containers the server hid from this chunk's block entities but admitted to elsewhere (chunk packet, sounds): storage evidence. */
    private boolean hiddenContainersIn(int cx, int cz) {
        var hits = dev.vantage.core.HiddenStorage.in(cx, cz);
        if (hits.isEmpty()) return false;
        if (!(mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c)) return false;
        for (var h : hits) if (c.getBlockEntity(h.pos()) == null) return true;
        return false;
    }

    /** v2: build or storage evidence (scanned, or hidden containers the server leaked) in this chunk or the 8 around it. */
    private boolean buildNear(int cx, int cz, Map<Long, Extra> extras) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long k = ChunkPos.asLong(cx + dx, cz + dz);
            if (evidence.contains(k) || hiddenContainersIn(cx + dx, cz + dz)) return true;
        }
        return false;
    }

    private List<BaseLogic.Clue> clues(Map<Long, Extra> extras) {
        List<BaseLogic.Clue> out = new ArrayList<>();
        weakClues = new HashSet<>();
        final boolean v2 = v2();
        final boolean alone = dev.vantage.core.util.Alone.now(); // singleplayer: plants grow and chunks tick because the user is there, not somebody else
        finds.forEach((key, f) -> {
            if (f.grief() != null) return; // a wrecked base is the answer to a question nobody is asking any more
            boolean hid = hidden(f); // Underground only: a surface find only lends its growth clues (somebody stays nearby), nothing built or dug
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            for (Reason r : f.reasons()) {
                BaseLogic.Kind k = BaseLogic.kindOf(r.text());
                if (k == null || (hid && !BaseLogic.guessOnly(k))) continue;
                if (alone && BaseLogic.generatable(k) && BaseLogic.guessOnly(k)) continue; // only the user can have kept it loaded
                if (v2 && (k == BaseLogic.TUNNEL || k == BaseLogic.SHAFT || k == BaseLogic.PATCHED) && !digOk.contains(key)) continue; // digging with no build or storage around it is a mine
                out.add(new BaseLogic.Clue(k, cx, cz, f.x(), f.y(), f.z()));
            }
            if (!hid && f.reasons().stream().anyMatch(r -> r.text().equals(SHULKER_REASON))) {
                var packed = new BaseLogic.Clue(BaseLogic.SHULKERS, cx, cz, f.x(), f.y(), f.z());
                out.add(packed);
                if (alone) weakClues.add(packed);
            }
            if (f.build() > 0 && !hid) {
                var built = new BaseLogic.Clue(BaseLogic.BUILT, cx, cz, f.x(), f.y(), f.z());
                out.add(built);
                boolean structureNear = f.reasons().stream().anyMatch(r -> r.text().contains(" nearby: ")); // "ancient city nearby: ... not counted"
                if (alone || f.score() < threshold.i() || structureNear || !playerOnly(f)) weakClues.add(built); // blocks below the real-find line, or in a world with one player: worldgen structures look like this too
            }
        });
        extras.forEach((key, e) -> {
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            if (e.containers > 0) out.add(new BaseLogic.Clue(BaseLogic.HIDDEN_STORAGE, cx, cz, e.x, e.y, e.z));
            if (e.light > 0 && e.light < 15) out.add(new BaseLogic.Clue(BaseLogic.HIDDEN_LIGHT, cx, cz, e.x, e.y, e.z));
        });
        BaseFinder2 f2 = dev.vantage.core.Modules.get(BaseFinder2.class);
        if (f2.on()) for (BlockPos p : f2.touched()) {
            var touched = new BaseLogic.Clue(BaseLogic.TOUCHED, p.getX() >> 4, p.getZ() >> 4, p.getX(), p.getY(), p.getZ());
            out.add(touched);
            if (alone) weakClues.add(touched);
        }
        DeepLeaks dl = dev.vantage.core.Modules.get(DeepLeaks.class);
        if (dl.on()) for (DeepLeaks.Find f : dl.finds()) {
            if (f.kind().equals("activity") && f.count() < 2) continue; // one stray change proves nothing, a section that keeps changing does
            boolean item = f.kind().equals("entity") && f.text().startsWith("Item ");
            BaseLogic.Kind k = switch (f.kind()) { case "entity" -> item ? BaseLogic.HIDDEN_ITEM : BaseLogic.HIDDEN_ENTITY; case "dig" -> BaseLogic.MINER; default -> BaseLogic.HIDDEN_ACTIVITY; };
            BlockPos p = f.pos();
            if (v2 && k == BaseLogic.MINER && !buildNear(p.getX() >> 4, p.getZ() >> 4, extras)) continue;
            out.add(new BaseLogic.Clue(k, p.getX() >> 4, p.getZ() >> 4, p.getX(), p.getY(), p.getZ()));
        }
        SkyFingerprint sky = dev.vantage.core.Modules.get(SkyFingerprint.class);
        if (sky.on()) for (SkyFingerprint.Hit h : sky.hits()) {
            if (h.base()) out.add(new BaseLogic.Clue(BaseLogic.SKY, h.cx(), h.cz(), (h.cx() << 4) + 8, 24, (h.cz() << 4) + 8));
        }
        SimulationRing ring = dev.vantage.core.Modules.get(SimulationRing.class);
        if (ring.on() && !alone) for (SimulationRing.Hit h : ring.hits()) {
            int y = mc.level == null ? 64 : mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (h.cx() << 4) + 8, (h.cz() << 4) + 8);
            out.add(new BaseLogic.Clue(BaseLogic.SIMULATED, h.cx(), h.cz(), (h.cx() << 4) + 8, y, (h.cz() << 4) + 8));
        }
        SoundDetector sd = dev.vantage.core.Modules.get(SoundDetector.class);
        if (sd != null && sd.on()) for (SoundDetector.Cluster c : sd.finds()) {
            Vec3 p = c.pos();
            out.add(new BaseLogic.Clue(SoundDetector.HEARD, c.cx, c.cz, (int) p.x, (int) p.y, (int) p.z));
        }
        if (alone) weakClues.addAll(out); // nobody else exists here: whatever a clue is, it cannot be somebody else's base, so no clue anchors a zone past 50 %
        return out;
    }

    /** Blocks a generated structure can place (mineshaft planks, rails, torches) are not player-only: a stored container or a working farm is. */
    private static boolean playerOnly(Find f) {
        if (f.farm() >= FARM_POINTS) return true;
        for (Reason r : f.reasons()) if (r.text().equals("storage") && r.count() > 0) return true;
        return false;
    }

    /** Which blocks made the "player blocks" clue of this zone, from the finds inside it: "oak planks x2, fence x1". */
    private String builtNames(BaseLogic.Zone zn) {
        Map<String, Integer> names = new LinkedHashMap<>();
        for (var e : finds.entrySet()) {
            Find f = e.getValue();
            if (f.build() <= 0 || f.why() == null || f.why().isEmpty()) continue;
            int cx = ChunkPos.getX(e.getKey()), cz = ChunkPos.getZ(e.getKey());
            if (Math.abs((cx << 4) + 8 - zn.x()) > zn.range() + 24 || Math.abs((cz << 4) + 8 - zn.z()) > zn.range() + 24) continue;
            for (String part : f.why().split(", ")) {
                int sp = part.indexOf(' ');
                if (sp <= 0) continue;
                int n;
                try { n = Integer.parseInt(part.substring(0, sp)); } catch (NumberFormatException ex) { continue; }
                names.merge(part.substring(sp + 1), n, Integer::sum);
            }
        }
        List<String> out = new ArrayList<>();
        for (var e : names.entrySet()) { if (out.size() >= 4) break; out.add(e.getKey() + " x" + e.getValue()); }
        return String.join(", ", out);
    }

    private View zoneView(BaseLogic.Zone zn, Vec3 me) {
        List<Reason> rs = new ArrayList<>();
        String built = null;
        for (BaseLogic.Line l : zn.lines()) {
            String text = l.text();
            if (text.startsWith("player blocks")) { if (built == null) built = builtNames(zn); if (!built.isEmpty()) text = "player blocks: " + built + (zn.anchored() ? "" : " (not player-only)"); } // name what counted, so a screenshot shows the case
            rs.add(new Reason(text, l.points(), l.count()));
        }
        int dist = (int) Math.sqrt(Math.pow(zn.x() - me.x, 2) + Math.pow(zn.z() - me.z, 2));
        String conf = zn.pct() >= 60 ? "HIGH" : zn.pct() >= 35 ? "MEDIUM" : "LOW";
        return new View(zn.x() >> 4, zn.z() >> 4, zn.x(), zn.y(), zn.z(), zn.pct(), "Likely area", Ui.TEAL, dist, Compass.name(zn.x() - me.x, zn.z() - me.z),
                "Y " + Math.max(mc.level.getMinY(), zn.minY()) + ".." + Math.min(mc.level.getMaxY(), zn.maxY()), zn.clues() + " clues", rs,
                zn.grown() >= 2 ? "worked out from " + zn.grown() + " grown chunks, within " + zn.range() + " blocks" : zn.kinds() + " clue types, base probably inside the ring", conf, 0, Math.max(mc.level.getMinY(), zn.minY()), Math.min(mc.level.getMaxY(), zn.maxY()), T_ZONE);
    }

    private View view(long key, Find f, Live l, Extra ex, Vec3 me, long now) {
        ChunkPos cp = new ChunkPos(key);
        int flagged = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if ((dx != 0 || dz != 0) && nextDoor(key, ChunkPos.asLong(cp.x + dx, cp.z + dz))) flagged++;
        }
        List<Reason> rs = new ArrayList<>();
        int score = 0;
        if (flagged > 0) {
            int n = Math.min(flagged, 2) * 3 + 3;
            rs.add(new Reason((flagged == 1 ? "1 chunk" : flagged + " chunks") + " next door flagged too", n, 0));
            score += n;
        }
        if (f != null) { rs.addAll(f.reasons()); score += f.score(); }
        int below = f == null ? 0 : belowZero.getOrDefault(key, 0);
        if (below > 0) {
            int n = Math.min(8, below);
            rs.add(new Reason("items and mobs below Y 0", n, below));
            score += n;
        }
        if (ex != null) {
            if (ex.containers > 0) {
                int n = Math.min(30, ex.containers * 3);
                rs.add(new Reason("containers the server hides from you", n, ex.containers));
                score += n;
                if (ex.containers >= STASH_ALONE) { rs.add(new Reason("enough containers to be a stash by itself", 12, 0)); score += 12; }
            }
            if (ex.light > 0) {
                int n = ex.light == 15 ? 3 : 8; // 15 is mostly lava, anything else in rock was placed or grew there
                rs.add(new Reason(ex.light == 15 ? "light 15 inside rock, could be lava" : "light " + ex.light + " inside rock, something hidden glows", n, 0));
                score += n;
            }
            if (ex.room) { rs.add(new Reason("hidden room: hidden light and containers agree", 15, 0)); score += 15; }
            if (ex.heard > 0) {
                int n = Math.min(12, ex.heard * 3);
                rs.add(new Reason("containers heard opening where nothing shows", n, ex.heard));
                score += n;
            }
            if (ex.sky == 2) { rs.add(new Reason("sky light fingerprint: the light engine touched rock nobody could reach", 20, 0)); score += 20; }
            else if (ex.sky == 1) { rs.add(new Reason("sky light fingerprint with an innocent excuse", 4, 0)); score += 4; }
            if (ex.ring > 0) {
                int n = Math.min(24, 10 + ex.ring / 2);
                rs.add(new Reason("someone keeps this chunk ticking from outside your range", n, ex.ring));
                score += n;
            }
            if (ex.owed) { rs.add(new Reason("gap in the land where growth is overdue all around", 4, 0)); score += 4; }
            if (ex.sound > 0) {
                for (Object[] ln : ex.soundLines) { rs.add(new Reason((String) ln[0], (Integer) ln[1], 0)); score += (Integer) ln[1]; }
                // heard AND built (or leaked) is the same place telling you twice: clearly above either alone
                boolean other = f != null && f.score() > 0 || ex.containers > 0 || ex.light > 0 || ex.sky > 0 || ex.ring > 0 || ex.heard > 0;
                if (other) { rs.add(new Reason("heard and built here: the sounds agree with the blocks", 10, 0)); score += 10; }
            }
        }
        boolean fresh = l != null && now - l.last < LIVE_RECENT_MS;
        int x = f != null ? f.x() : l != null ? l.x : ex.x, y = f != null ? f.y() : l != null ? l.y : ex.y, z = f != null ? f.z() : l != null ? l.z : ex.z;
        if (l != null) {
            rs.add(0, new Reason(l.who != null ? (dev.vantage.core.Streamer.hidesOthers() ? "A player" : l.who) + " is under the ground here" : "live player signal", Math.min(l.count, 6) * 2, l.who != null ? 0 : l.count));
            if (fresh) rs.add(new Reason("someone was here just now", 6, 0));
            score += Math.min(l.count, 6) * 2 + (fresh ? 6 : 0);
        }
        int pct = l != null && l.who != null ? 99 : pct(score);
        String wreck = f == null ? null : griefOf(key, f); // kelp beside a shipwreck is still just kelp
        boolean grief = wreck != null;
        int color = grief ? Ui.GREY : l != null ? Ui.BLUE : pct >= 50 ? Ui.GREEN : Ui.YELLOW;
        String label0 = grief ? "Griefed" : ex != null && ex.room ? "Hidden room" : f != null ? (v2() ? labelV2(key, f) : labelFor(f)) : ex == null ? "Unknown"
                : ex.containers > 0 ? "Hidden stash" : ex.ring > 0 ? "Someone here" : ex.sky == 2 ? "Touched rock" : ex.heard > 0 ? "Heard storage" : ex.light > 0 ? "Hidden light" : ex.sky == 1 ? "Rock suspect" : ex.sound > 0 ? heardLabel(ex.soundTitle) : "Quiet gap";
        // a hall with work stations, glass or redstone around its chests is lived in, whichever scoring is on
        String label = f != null && grief == false && ("Stash".equals(label0) || "Storage".equals(label0)) && furnishedAt(cp.x, cp.z) ? "Probable base" : label0;
        int dist = (int) Math.sqrt(Math.pow(x - me.x, 2) + Math.pow(z - me.z, 2));
        String yr = f != null ? "Y " + f.minY() + ".." + f.maxY() : "Y " + y;
        String sec = f != null ? f.sections() + "/" + f.allSections() + " sections" : l != null ? "live" : "leak";
        String foot = l != null ? (l.who != null ? "player seen " : l.count + " blocks changed, last ") + (now - l.last) / 1000 + "s ago"
                : f == null ? "the server is hiding something here" : "build " + pct(f.build()) + "%" + (f.dug() > 0 ? " / dug " + pct(f.dug()) + "%" : "") + (f.growth() > 0 ? " / growth " + pct(f.growth()) + "%" : "") + (f.farm() > 0 ? " / farm " + pct(f.farm()) + "%" : "");
        if (grief) foot = "looks wrecked: " + wreck;
        else if (f != null && v2() && placeOf(key, f).farm()) foot = FARM_FOOT + placeOf(key, f).why(); // v2: a working farm says why it is one, no damage wording, no percent pair
        else if (f != null && v2() && !placeOf(key, f).known()) foot = OLD_FOOT + "judged again when its chunk is seen"; // v2: a find from an old file has no evidence yet
        else if (f != null && v2() && placeOf(key, f).silenced() != null) foot = placeOf(key, f).silenced(); // v2: machines that storage next door explains, no "farm NN%" line
        else if (f != null && f.damage() != null) foot = "some damage: " + f.damage();
        String conf = l != null && l.who != null || score >= 60 ? "HIGH" : score >= 25 ? "MEDIUM" : "LOW";
        int lo = mc.level.getMinY(), hi = mc.level.getMaxY(); // a height can never be outside the world, whatever a guess around it says
        y = Math.max(lo, Math.min(hi, y));
        int y0 = Math.max(lo, f != null ? f.minY() : y - 8), y1 = Math.min(hi, f != null ? f.maxY() : y + 8);
        if (f != null) yr = "Y " + y0 + ".." + y1; else yr = "Y " + y;
        return new View(cp.x, cp.z, x, y, z, pct, label, color, dist, Compass.name(x - me.x, z - me.z), yr, sec, rs, foot, conf, score, y0, y1, typeOf(key, f, l, ex));
    }

    /** v2: drawn at all? A type whose Show switch is off is not, and neither is a chunk that is only suspicious unless Show: suspicious is on. */
    private boolean hiddenV2(View v) {
        if (v.type().equals(T_ZONE)) return false;
        return !showType(v.type()) || !showSus.on() && v.points() < threshold.i() && v.color() != Ui.BLUE;
    }

    /** v2 type of a real find: farm points of 8 or more make it a farm whatever else is in it (a farm inside a base is a farm); else whichever of building, digging and growth gave the most. */
    public static String typeOfFind(Find f) {
        if (f.farm() >= FARM_POINTS) return T_FARM;
        int b = f.build(), d = f.dug(), g = f.growth();
        if (d > b && d >= g) return T_MINE;
        if (g > b && g > d) return T_SIGNS;
        return T_STORAGE;
    }

    /**
     * v2: what a place is. A place is a connected group (8 neighbours) of found chunks, computed from `finds` once per change (so it does not depend on Show
     * switches or the draw range). Every chunk of a group gets the same answer and the group pings once. known=false: this chunk has no evidence yet (a find from an
     * old file): no place type, no veto. size: chunks in the group. There is no size cap: the work is linear in the found chunks and runs once per change.
     * A BaseSites "site" is a group of drawn VIEWS (it also follows Show switches, hidden finds and the Base ESP boxes), so a site can hold several places or a
     * place can be split into several sites; the ping, the type and the card's why line follow the place, the card's frame follows the site.
     */
    public record Place(boolean farm, String why, boolean storage, boolean known, int size, String silenced) {}
    /** Thresholds. Each is a count a player would call a machine, not a house: */
    static final int EV_HOPPERS = 8;      // eight machines (hoppers, dispensers, droppers, crafters) in one place
    static final int EV_MACHINE = 2;      // two machines are the least any farm has
    static final int EV_REDSTONE = 20;    // twenty redstone parts (no machines, no lamps) around them
    static final int EV_FARMLAND = 20;    // twenty tilled blocks with a machine in the same or a touching chunk
    static final int EV_PORTAL = 8;       // a portal frame's worth of portal blocks with redstone and nothing stored
    static final int EV_BEACONS = 4;      // a beacon hall is a showpiece, not a machine

    private int groupVersion = -1;
    private final Map<Long, Place> placeCache = new HashMap<>();
    private final Map<Long, List<Long>> groupCache = new HashMap<>();
    private final Set<Long> baseCache = new HashSet<>();

    /** Reasons that say somebody furnished the place: work stations, glass, a crafting table, an anvil. Hoppers and chests are not furnishing. */
    private static int furnishKind(String reason) {
        return switch (reason) {
            case "furnace", "blast furnace", "smoker" -> 1;
            case "enchanting table", "brewing stand" -> 2;
            case "crafting table" -> 3;
            case "anvil", "chipped anvil", "damaged anvil" -> 4;
            case "glass" -> 5;
            case "beds, tables", "bed" -> 6;
            default -> 0;
        };
    }

    /** A pile of containers with at least three different kinds of furnishing in the same place (and redstone counts as one): a base, not a stash. */
    private boolean furnishedBase(List<Long> g) {
        int containers = 0, redstone = 0;
        Set<Integer> kinds = new HashSet<>();
        Set<Long> area = new HashSet<>(g);
        // the furnishing of a hall often lands in a find of its own a chunk or two away: look at every find within 3 chunks of the group
        for (long k : g) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            long n = ChunkPos.asLong(ChunkPos.getX(k) + dx, ChunkPos.getZ(k) + dz);
            if (finds.containsKey(n)) area.add(n);
        }
        for (long k : area) {
            Find f = finds.get(k);
            if (f.ev().known()) redstone += f.ev().redstone();
            for (Reason r : f.reasons()) { if (r.text().equals("storage")) containers += r.count(); int fk = furnishKind(r.text()); if (fk > 0) kinds.add(fk); } // old scoring has no Ev: count the storage reason
        }
        if (redstone >= 3) kinds.add(7);
        return containers >= BASE_CONTAINERS && kinds.size() >= BASE_FURNISH_KINDS;
    }
    static final int BASE_CONTAINERS = 12, BASE_FURNISH_KINDS = 3;

    /** The percentage the panel shows for the find in this chunk (its row if drawn, else worked out from the find the same way), or null without a find. One number per stash: Stash Finder says this one too. */
    public Integer shownPctAt(int cx, int cz) {
        for (View v : views) if (v.cx() == cx && v.cz() == cz && !v.type().equals(T_ZONE)) return v.pct();
        long key = ChunkPos.asLong(cx, cz);
        Find f = finds.get(key);
        return f == null ? null : pct(shownPoints(key, f));
    }

    /** Rig: is this chunk part of a furnished chest hall (labelled Probable base)? */
    public boolean furnishedAt(int cx, int cz) {
        rebuildGroups();
        return baseCache.contains(ChunkPos.asLong(cx, cz));
    }

    private void rebuildGroups() {
        if (groupVersion == finds.version) return;
        groupVersion = finds.version;
        placeCache.clear(); groupCache.clear(); baseCache.clear();
        Set<Long> seen = new HashSet<>();
        List<Long> keys = new ArrayList<>(finds.keySet());
        Collections.sort(keys); // the answer never depends on the scan order
        for (long k : keys) {
            if (seen.contains(k)) continue;
            List<Long> g = new ArrayList<>();
            ArrayDeque<Long> todo = new ArrayDeque<>();
            todo.add(k); seen.add(k);
            while (!todo.isEmpty()) {
                long c = todo.poll(); g.add(c);
                int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    long n = ChunkPos.asLong(cx + dx, cz + dz);
                    if (finds.containsKey(n) && seen.add(n)) todo.add(n);
                }
            }
            Collections.sort(g);
            Place pl = judgeGroup(g);
            boolean furnished = furnishedBase(g);
            for (long c : g) { placeCache.put(c, pl); groupCache.put(c, g); if (furnished) baseCache.add(c); }
        }
    }

    /** The chunks of the place this chunk belongs to (just itself when it has no find). */
    private List<Long> groupKeys(long key) {
        rebuildGroups();
        List<Long> g = groupCache.get(key);
        return g != null ? g : List.of(key);
    }

    /** Containers (chest, trapped chest, barrel, shulker box: one per block, a double chest is 2; ender chests and hoppers not counted) in ONE Sorter chunk from which it is storage. */
    private static final int SORTER_STORAGE_MIN = 32; // census 2026-11-13: farms with a sorter hold 5 to 9 containers in their Sorter chunk, storage systems 61 to 156 (any limit from 12 to 60 gives the same result)

    /**
     * Does this chunk's Sorter verdict count as storage evidence for its place? Only when a lot is stored in the chunk: at least SORTER_STORAGE_MIN containers.
     * The census showed that the ratio does not separate them (a real storage system has about 1.4 machines per container, 156 containers with 225 machines),
     * the amount stored does. The one-line fall-back to the fix 7 behaviour is `return "Sorter".equals(f.storage());`.
     */
    private static boolean sorterCounts(Find f) {
        return "Sorter".equals(f.storage()) && f.ev().containers() >= SORTER_STORAGE_MIN;
    }

    private Place judgeGroup(List<Long> g) {
        int containers = 0, portals = 0, beacons = 0, kelp = 0, redstoneAll = 0, knownN = 0;
        boolean sorter = false, hall = false;
        for (long k : g) {
            Find f = finds.get(k);
            Ev e = f.ev();
            if (!e.known()) continue;
            knownN++;
            containers += e.containers(); portals += e.portals(); beacons += e.beacons(); kelp += e.kelp(); redstoneAll += e.redstone();
            if (sorterCounts(f)) sorter = true;
            if (e.hall()) hall = true;
        }
        if (knownN == 0) return new Place(false, "", false, false, g.size(), null);
        // storage evidence: a sorter-sized Sorter chunk, a stash by itself, a beacon hall
        boolean storage = sorter || hall || beacons >= EV_BEACONS;
        // farm-only evidence beats storage evidence: a farm with its own sorter or a chest wall next door is still a farm
        // (the chunks are walked in key order, so the first match, and with it the why line, never depends on the scan order)
        for (long k : g) {
            Ev e = finds.get(k).ev();
            if (!e.known()) continue;
            int near = window(k, 0);
            if (e.spawners() > 0 && near >= EV_MACHINE) return new Place(true, "mob spawners with " + near + " machines next to them", storage, true, g.size(), null);
            if (e.farmland() >= EV_FARMLAND && near >= 1) return new Place(true, "farmland with a hopper next to it", storage, true, g.size(), null);
        }
        if (kelp >= FARM_POINTS) return new Place(true, "kelp growing under a roof", storage, true, g.size(), null);
        if (portals >= EV_PORTAL && redstoneAll >= 4 && containers == 0) return new Place(true, "portals and redstone, nothing stored", storage, true, g.size(), null);
        // plain machines, counted inside a 3x3 window (so a base with one hopper per chunk is not a farm; a farm split over two or three chunks is)
        long best = Long.MIN_VALUE; int bm = -1;
        for (long k : g) { if (!finds.get(k).ev().known()) continue; int m = window(k, 0); if (m > bm) { bm = m; best = k; } } // g is sorted: the first chunk with the largest window
        long bestR = Long.MIN_VALUE; int br = -1;
        for (long k : g) if (finds.get(k).ev().known() && window(k, 0) >= EV_MACHINE) { int r = window(k, 2); if (r > br) { br = r; bestR = k; } }
        boolean plainMachines = bm >= EV_HOPPERS, plainRedstone = br >= EV_REDSTONE;
        if (!storage) { // storage evidence switches these two off (a hall full of hoppers is storage)
            if (plainMachines) { int c = window(best, 1); return new Place(true, bm + " hoppers/dispensers/crafters" + (c > 0 ? " with " + c + " containers" : ""), false, true, g.size(), null); }
            if (plainRedstone) return new Place(true, window(bestR, 0) + " machines inside " + br + " redstone parts", false, true, g.size(), null);
            return new Place(false, "", false, true, g.size(), null);
        }
        // the card says why a machine-heavy place is not a farm
        String silenced = plainMachines || plainRedstone ? NOTFARM_FOOT + (sorter ? "sorter" : hall ? "chest hall" : "beacon hall") + " close by counts as storage" : null;
        return new Place(false, "", true, true, g.size(), silenced);
    }

    /** Sum of one kind of evidence over the 3x3 chunks around k (only found chunks with evidence): 0 machines, 1 containers, 2 redstone. */
    private int window(long k, int kind) {
        int n = 0, cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Find f = finds.get(ChunkPos.asLong(cx + dx, cz + dz));
            if (f == null || !f.ev().known()) continue;
            n += kind == 0 ? f.ev().machines() : kind == 1 ? f.ev().containers() : f.ev().redstone();
        }
        return n;
    }

    private Place placeOf(long key, Find self) {
        rebuildGroups();
        Find own = finds.get(key);
        if (own == null) own = self;
        // a chunk whose own evidence is unknown (an old file, not scanned again) never takes its place's Farm type or title: it waits for its own scan
        if (!own.ev().known()) return new Place(false, "", false, false, 1, null);
        Place p = placeCache.get(key);
        if (p != null) return p;
        return new Place(false, "", false, true, 1, null); // a find that is not (yet) in `finds`: no group
    }

    /** One grief answer for the card, the type, the ping gate and farmAt: the find's own grief text, else a wreck next door (a find that is intact or has no building does not count). */
    private String griefOf(long key, Find f) {
        if (f.grief() != null) return f.grief();
        if (f.intact() || f.build() <= 0) return null;
        for (Reason r : f.reasons()) if (r.text().equals("storage") && StructureRules.looseContainersOnly(f.build(), r.points(), r.count())) return null; // a shipwreck's chest is not a wrecked base
        ChunkPos cp = new ChunkPos(key);
        return wreckNear(cp.x, cp.z);
    }

    /** v2 type of the place a find belongs to: farm by real evidence, else the chunk's own type (a hopper-heavy chunk that failed the farm test is storage). */
    private String placeType(long key, Find f) {
        if (placeOf(key, f).farm()) return T_FARM;
        String t = typeOfFind(f);
        return t.equals(T_FARM) ? T_STORAGE : t; // a chunk whose place is not a farm (or has no evidence yet) is never called a farm by its hopper count
    }

    /** v2 card title: a farm place says Farm, whatever its score; Griefed still wins; a hopper-heavy chunk that is not a farm is storage. */
    private String labelV2(long key, Find f) {
        String l = labelFor(f);
        if (griefOf(key, f) != null) return "Griefed";
        if (placeOf(key, f).farm()) return "Farm";
        if (l.equals("Farm")) return f.storage() != null ? f.storage() : "Storage";
        return l;
    }

    /** The prefix of a card foot that says why a place is a farm (BaseSites shows it as the card's condition line). */
    public static final String FARM_FOOT = "farm: ";
    /** Card foot prefixes that say why (BaseSites shows such a foot as the card's condition line): a place that is not a farm although its machines say so, and an old find. */
    public static final String NOTFARM_FOOT = "not a farm: ", OLD_FOOT = "old find: ";

    public static boolean whyFoot(String foot) {
        return foot != null && (foot.startsWith(FARM_FOOT) || foot.startsWith(NOTFARM_FOOT) || foot.startsWith(OLD_FOOT));
    }

    /** v2: is the chunk at x/z part of a farm place (Base ESP: no floating or water damage wording there). */
    public boolean farmAt(int x, int z) {
        if (!v2()) return false;
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        Find f = finds.get(key);
        return f != null && griefOf(key, f) == null && placeOf(key, f).farm();
    }

    /** Rig: the v2 place type and the card's number of the find at a chunk (null / -1 when there is none). */
    public String placeTypeAt(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        Find f = finds.get(key);
        return f == null ? null : placeType(key, f);
    }

    public int shownAt(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        Find f = finds.get(key);
        return f == null ? -1 : shownPoints(key, f);
    }

    /** The number a card shows for a find: its own points plus the next-door bonus plus items below Y 0. Pings use this number. */
    private int shownPoints(long key, Find f) {
        return f.score() + flaggedBonus(key) + Math.min(8, belowZero.getOrDefault(key, 0));
    }

    private static final int MAX_BONUS = 9 + 8;
    /** Rig: the last announce, as "chat word | toast title and number | why line". */
    public volatile String lastAnnounce = "";
    /** Rig: the same text per chunk key, one entry per announce. */
    public final Map<Long, String> announceLog = new LinkedHashMap<>();
    private static final int ANNOUNCE_LOG_MAX = 300;

    /** A flagged chunk next door that tells something this one has not already counted. */
    private boolean nextDoor(long key, long other) {
        return finds.containsKey(other) && !absorbed.getOrDefault(key, Set.of()).contains(other);
    }

    private int flaggedBonus(long key) {
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key), flagged = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if ((dx != 0 || dz != 0) && nextDoor(key, ChunkPos.asLong(cx + dx, cz + dz))) flagged++;
        }
        return flagged > 0 ? Math.min(flagged, 2) * 3 + 3 : 0;
    }

    /** v2 type of a Sound Detector title: any farm is a farm, storage in use is storage, everything else is somebody being around. */
    public static String typeOfSound(String title) {
        String t = title == null ? "" : title;
        if (t.contains("farm") || t.contains("animal pen")) return T_FARM;
        if (t.contains("storage") || t.contains("sorting")) return T_STORAGE;
        return T_SOUNDS;
    }

    private String typeOf(long key, Find f, Live l, Extra ex) {
        if (f != null) { // the find's own type wins: a player heard or seen in a farm does not turn the farm into "player sounds"
            boolean typeless = f.farm() <= 0 && f.build() <= 0 && f.dug() <= 0 && f.growth() <= 0; // a find with nothing of its own to say may take the sound's type
            if (typeless && ex != null && ex.sound > 0) return typeOfSound(ex.soundTitle);
            return v2() ? placeType(key, f) : typeOfFind(f);
        }
        if (l != null) return T_SOUNDS;
        if (ex == null) return T_SIGNS;
        boolean leaks = ex.containers > 0 || ex.heard > 0 || ex.light > 0 || ex.sky > 0 || ex.ring > 0;
        if (ex.sound > 0 && !leaks) return typeOfSound(ex.soundTitle);
        return ex.containers > 0 || ex.heard > 0 ? T_STORAGE : T_SIGNS;
    }

    /** May a find of this type ping (chat, sound, toast, card)? Always, with Scoring v2 off. */
    public boolean pingFor(String type) {
        if (!v2()) return true;
        return switch (type) {
            case T_FARM -> pingFarms.on();
            case T_STORAGE -> pingStorage.on();
            case T_MINE -> pingMines.on();
            case T_SOUNDS -> pingSounds.on();
            case T_SIGNS -> pingSigns.on();
            default -> true;
        };
    }

    private boolean showType(String type) {
        return switch (type) {
            case T_FARM -> showFarms.on();
            case T_STORAGE -> showStorage.on();
            case T_MINE -> showMines.on();
            case T_SOUNDS -> showSounds.on();
            case T_SIGNS -> showSigns.on();
            default -> true;
        };
    }

    /** Sound Detector asks before it pings (v2): its type for a cluster title or a sound group. */
    public static boolean soundPingOk(String type) {
        BaseFinder bf = dev.vantage.core.Modules.get(BaseFinder.class);
        return bf == null || !bf.on() || bf.pingFor(type); // the Ping switches gate Sound Detector only while Base Finder is on
    }

    /** Base Finder is on with Scoring v2 on. */
    public static boolean v2Active() {
        BaseFinder bf = dev.vantage.core.Modules.get(BaseFinder.class);
        return bf != null && bf.on() && bf.v2();
    }

    public static String typeOfGroup(SoundDetector.Group g) {
        return switch (g) {
            case FARM -> T_FARM;
            case STORAGE, BUILD, CRAFT, BASE -> T_STORAGE;
            case GATHER -> T_MINE;
            default -> T_SOUNDS;
        };
    }

    /** A chunk that only reached the score through leaks never went through the scan's ping. */
    private void leakPing(View v, Map<Long, Extra> ex) {
        long key = ChunkPos.asLong(v.cx(), v.cz());
        if (!ex.containsKey(key) || v.points() < threshold.i() || v.color() == Ui.GREY) return;
        if (holdPing.containsKey(key)) return; // a held ping goes out once, through the normal path, with the final score
        if (v2()) { // v2 (fix-list 6 C7): the same gates as the scan path, so a hold that a later scan cancelled cannot ping through here
            Find own = finds.get(key);
            if (own != null && (hidden(own) || griefOf(key, own) != null && !pingGriefed.on())) return;
        }
        dev.vantage.core.LeakLog.add("BASE", v.x(), v.y(), v.z(), v.label() + " " + v.pct() + "%");
        Extra only = ex.get(key);
        boolean soundOnly = only.sound > 0 && only.containers == 0 && only.heard == 0 && only.light == 0 && only.sky == 0 && only.ring == 0 && !finds.containsKey(key);
        if (!pingFor(v.type())) return;
        if (!pinged.add(key)) return;
        if (soundOnly) return; // Sound Detector already said it in chat
        if (v2()) { // one ping per place on this path too: the group, not the 3x3
            for (long k : groupKeys(key)) if (k != key && pinged.contains(k)) return;
        } else {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && pinged.contains(ChunkPos.asLong(v.cx() + dx, v.cz() + dz))) return;
            }
        }
        pings++;
        if (BFDIAG) diagLeak(key, v);
        dev.vantage.module.cosmetic.FindEvents.marked("Base", v.label() + " " + v.pct() + "%", v.x(), v.y(), v.z());
        if (notify.on()) Chat.alert(Component.literal(v.label().toUpperCase() + " ").withColor(color.argb())
                .append(Component.literal(v.pct() + "%  " + Chat.coords(v.x(), v.y(), v.z()) + "  " + v.dist() + "m").withColor(Theme.TEXT)), 1.2f);
    }

    private void rebuildViews() {
        long now = System.currentTimeMillis();
        live.values().removeIf(l -> now - l.last > LIVE_MS);
        Vec3 me = mc.player.position();
        belowZero.clear();
        for (var e : mc.level.entitiesForRendering()) {
            boolean dropped = e instanceof net.minecraft.world.entity.item.ItemEntity;
            boolean kept = e instanceof net.minecraft.world.entity.animal.Animal || e instanceof net.minecraft.world.entity.npc.villager.Villager;
            if ((dropped || kept) && e.getY() < 0) belowZero.merge(ChunkPos.asLong(e.getBlockX() >> 4, e.getBlockZ() >> 4), 1, Integer::sum);
        }
        if (v2() && !noPing.isEmpty()) bonusRearm(now);
        Map<Long, Extra> ex = extras();
        List<View> out = new ArrayList<>();
        for (var e : finds.entrySet()) {
            Find f = hidden(e.getValue()) ? null : e.getValue();
            if (f == null && live.get(e.getKey()) == null && ex.get(e.getKey()) == null) continue; // hidden surface find with nothing else here
            out.add(view(e.getKey(), f, live.get(e.getKey()), ex.get(e.getKey()), me, now));
        }
        for (var e : live.entrySet()) if (!finds.containsKey(e.getKey())) out.add(view(e.getKey(), null, e.getValue(), ex.get(e.getKey()), me, now));
        for (var e : ex.entrySet()) if (!finds.containsKey(e.getKey()) && !live.containsKey(e.getKey())) out.add(view(e.getKey(), null, null, e.getValue(), me, now));
        for (View v : out) leakPing(v, ex);
        if (--zoneTimer <= 0) {
            zoneTimer = 4;
            int r = Math.max(3, Math.min(8, mc.level.getServerSimulationDistance()));
            List<int[]> notGrown = new ArrayList<>();
            for (long k : ungrown) notGrown.add(new int[] {ChunkPos.getX(k), ChunkPos.getZ(k)});
            zoneList = zones.on() ? BaseLogic.zones(clues(ex), notGrown, r, v2(), weakClues) : List.of();
            zoneRuns++;
            for (BaseLogic.Zone zn : zoneList) {
                dev.vantage.core.LeakLog.add("ZONE", zn.x(), zn.y(), zn.z(), zn.pct() + "% " + zn.kinds() + " types " + zn.clues() + " clues " + zn.lines().stream().map(BaseLogic.Line::text).toList() + " " + zn.detail());
                if (zn.pct() >= 40 && zonesSeen.add(ChunkPos.asLong(zn.x() >> 7, zn.z() >> 7))) dev.vantage.core.Toasts.ok("Likely base area", zn.pct() + "% around " + dev.vantage.core.Streamer.coords(zn.x() + " " + zn.z()));
            }
        }
        if (v2()) zoneList = zoneList.stream().filter(z -> z.anchored() || z.pct() >= UNCLEAR_FLOOR).toList(); // a guess with no player-only clue behind it is not worth a row below this
        List<BaseSites.ZoneIn> zoneIns = new ArrayList<>();
        List<View> plain = new ArrayList<>(out);
        plain.removeIf(v -> v.dist() > SHOW_RANGE);
        if (v2()) plain.removeIf(this::hiddenV2);
        for (BaseLogic.Zone zn : zoneList) {
            View zv = zoneView(zn, me);
            out.add(zv);
            if (zv.dist() <= SHOW_RANGE) zoneIns.add(new BaseSites.ZoneIn(zv, zn.range(), !zn.anchored()));
        }
        out.removeIf(v -> v.dist() > SHOW_RANGE);
        if (v2()) out.removeIf(this::hiddenV2);
        out.sort((a, b) -> a.pct() != b.pct() ? b.pct() - a.pct() : a.dist() - b.dist());
        views = out;
        // the same finds once more, joined into one entry per base (built in both looks, the rig reads them in the old one too)
        long ts = System.nanoTime();
        var esp = dev.vantage.core.Modules.get(dev.vantage.module.donut.BaseEsp.class);
        List<dev.vantage.module.donut.BaseEsp.Group> groups = esp != null && esp.on() ? esp.groups() : List.of();
        sites = BaseSites.build(plain, zoneIns, groups, sites, nextSiteId, me.x, me.z);
        chunkSite = BaseSites.byChunk(sites);
        if (v2()) trackNewSites(now);
        dev.vantage.core.Perf.add("Base Finder sites", System.nanoTime() - ts);
    }

    private static int weight(BlockEntity be, boolean storage, boolean v2) {
        if (be instanceof BeaconBlockEntity) return 20;
        if (be instanceof ConduitBlockEntity) return 12;
        if (be instanceof EnchantingTableBlockEntity) return 8;
        if (be instanceof ShulkerBoxBlockEntity) return storage ? (v2 ? 8 : 5) : 0; // v2: packed loot is rarer than a wall of chests, so a stash with shulkers stands clear of a set of bare containers
        if (be instanceof EnderChestBlockEntity) return 2;
        if (be instanceof BannerBlockEntity) return 5;
        if (be instanceof SignBlockEntity) return 1; // hanging signs too
        if (be instanceof BrewingStandBlockEntity) return 4;
        if (be instanceof CrafterBlockEntity) return 4;
        if (be instanceof JukeboxBlockEntity) return 3;
        if (be instanceof SkullBlockEntity) return 3;
        if (be instanceof BedBlockEntity) return 2;
        if (be instanceof AbstractFurnaceBlockEntity) return 2;
        if (be instanceof HopperBlockEntity) return 2;
        if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) return storage ? 1 : 0;
        if (be instanceof DispenserBlockEntity) return 1;
        return 0;
    }

    private static String label(BlockEntity be) {
        String n = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).getPath();
        return n.replace('_', ' ');
    }

    private static boolean griefSign(SignBlockEntity sign) {
        for (boolean front : new boolean[] {true, false}) {
            StringBuilder text = new StringBuilder();
            for (Component line : sign.getText(front).getMessages(false)) text.append(line.getString()).append(' ');
            if (GRIEF_SIGN.matcher(text).find()) return true;
        }
        return false;
    }

    /** The shape of what's left. Nothing here reads a chest's contents, only blocks the server sends anyway. */
    private record Damage(int holes, int ragged, int floor, int floating, int scraps, int pieces, int blocks, boolean lava, boolean water) {
        boolean brokenFloor() {
            return floor >= FLOOR_MIN && (holes >= HOLES_MIN || holes + ragged >= RAGGED_MIN && (holes + ragged) * 4 >= floor);
        }
    }

    private record Verdict(String grief, String damage, boolean intact, boolean spreads) {}

    private boolean built(BlockState st, boolean skipObsidian) {
        Block b = st.getBlock();
        if (b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock || b instanceof HopperBlock) return true;
        if (skipObsidian && FIGHT_BLOCKS.contains(b)) return false;
        return st.isSolidRender() && (palette().containsKey(b) || BUILDING.contains(b) || st.is(BlockTags.PLANKS));
    }

    private Damage damage(LevelChunk chunk, boolean skipObsidian) {
        int minY = mc.level.getMinY(), height = mc.level.getHeight(), ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ(), total = 0;
        boolean[] built = new boolean[height << 8];
        LevelChunkSection[] secs = chunk.getSections();
        for (int si = 0; si < secs.length; si++) {
            LevelChunkSection s = secs[si];
            if (s.hasOnlyAir() || !s.getStates().maybeHas(st -> built(st, skipObsidian))) continue;
            int base = (mc.level.getSectionYFromSectionIndex(si) << 4) - minY;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (built(s.getBlockState(x, y, z), skipObsidian)) { built[(base + y) << 8 | z << 4 | x] = true; total++; }
            }
        }
        if (total == 0) return new Damage(0, 0, 0, 0, 0, 0, 0, false, false);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean[] seen = new boolean[built.length];
        int[] stack = new int[total];
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int floating = 0, scraps = 0, pieces = 0;
        boolean lava = false, water = false;
        for (int start = 0; start < built.length; start++) {
            if (!built[start] || seen[start]) continue;
            int top = 0, size = 0;
            boolean edge = false, held = false;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int c = stack[--top], x = c & 15, z = c >> 4 & 15, y = c >> 8;
                size++;
                if (x == 0 || x == 15 || z == 0 || z == 15) edge = true;
                for (int[] d : sides) {
                    int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                    if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < 0 || ny >= height) continue;
                    int n = ny << 8 | nz << 4 | nx;
                    if (built[n]) {
                        if (!seen[n]) { seen[n] = true; stack[top++] = n; }
                        continue;
                    }
                    BlockState st = chunk.getBlockState(p.set(ox + nx, minY + ny, oz + nz));
                    var fluid = st.getFluidState();
                    if (!fluid.isEmpty() && !fluid.isSource()) { if (fluid.is(FluidTags.LAVA)) lava = true; else water = true; }
                    if (solid(st)) held = true;
                }
            }
            pieces++;
            if (edge) continue; // goes on in the next chunk, so nothing can be said about it from here
            if (size <= SCRAP_SIZE) scraps++;
            if (!held && size <= FLOATING_SIZE) floating++;
        }
        int holes = 0, ragged = 0, floor = 0;
        for (int y = 1; y < height - 1; y++) for (int z = 1; z < 15; z++) for (int x = 1; x < 15; x++) {
            int i = y << 8 | z << 4 | x;
            if (built[i]) { if (!built[i + 256]) floor++; continue; }
            int around = 0;
            for (int n : new int[] {i + 1, i - 1, i + 16, i - 16}) if (built[n] && !built[n + 256]) around++;
            if (around < 2 || built[i - 256]) continue;
            if (!chunk.getBlockState(p.set(ox + x, minY + y, oz + z)).isAir() || !chunk.getBlockState(p.set(ox + x, minY + y + 1, oz + z)).isAir()) continue;
            if (around >= 3) holes++; else ragged++;
        }
        return new Damage(holes, ragged, floor, floating, scraps, pieces, total, lava, water);
    }

    /** Players within 96 blocks of the chunk, you not counted. */
    private int crowdAround(ChunkPos c) {
        int n = 0;
        double cx = c.getMiddleBlockX(), cz = c.getMiddleBlockZ();
        for (var p : mc.level.players()) {
            if (p == mc.player) continue;
            double dx = p.getX() - cx, dz = p.getZ() - cz;
            if (dx * dx + dz * dz <= 96 * 96) n++;
        }
        return n;
    }

    private int storageAround(ChunkPos c) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (!(mc.level.getChunkSource().getChunk(c.x + dx, c.z + dz, ChunkStatus.FULL, false) instanceof LevelChunk near)) continue;
            for (BlockEntity be : near.getBlockEntities().values()) {
                if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity) n++;
            }
        }
        return n;
    }

    /**
     * Loud signs (a grief sign, fire, TNT, lava on the walls) count anywhere. Signs in the shape of the place (holes, floating bits, nothing to store
     * things in) don't count against a base that still has its containers: it gets pinged, with the damage named on its card.
     */
    private Verdict grief(LevelChunk chunk, boolean fire, boolean tnt, boolean sign, int storage, int floatingStorage, boolean builtHere, boolean waterOnPurpose) {
        // water on lava leaves obsidian in scraps and holed sheets all over the deep overworld; the Nether has no water to make it
        Damage d = damage(chunk, !mc.level.dimensionType().hasCeiling() || dev.vantage.core.FakeOres.detected());
        int kept = storageAround(chunk.getPos());
        boolean intact = kept >= INTACT_STORAGE && floatingStorage * 10 <= Math.max(storage, 1);
        int points = 0, shape = 0;
        List<String> why = new ArrayList<>(), harm = new ArrayList<>();
        if (sign) { points += 3; why.add("grief sign"); }
        if (d.lava()) { points += 2; why.add("lava on the walls"); }
        if (fire) { points += 2; why.add("fire"); }
        if (tnt) { points += 2; why.add("tnt"); }
        if (d.water() && !waterOnPurpose) { points += 1; why.add("water on the walls"); } // v2: farms carry water on purpose
        ChunkPos p = chunk.getPos();
        AABB box = new AABB(p.getMinBlockX(), mc.level.getMinY(), p.getMinBlockZ(), p.getMaxBlockX() + 1, mc.level.getMaxY(), p.getMaxBlockZ() + 1);
        int items = mc.level.getEntitiesOfClass(ItemEntity.class, box).size();
        if (items >= GRIEF_ITEMS) { points += 1; why.add(items + " dropped items"); }
        if (d.brokenFloor()) { shape += 2; harm.add((d.holes() + d.ragged()) + " holes in the floor"); }
        if (floatingStorage >= 2 && floatingStorage * 3 >= storage) { shape += 2; harm.add(floatingStorage + " containers left floating"); }
        else if (d.floating() >= FLOATING_MIN) { shape += 2; harm.add(d.floating() + " pieces left floating"); }
        boolean spreads = shape >= 2;
        if (d.scraps() >= SCRAPS_MIN && d.scraps() * 2 >= d.pieces()) { shape += 1; harm.add("broken into " + d.scraps() + " scraps"); }
        // nothing to store things in only says something much when the place is damaged too: farms look like that as well
        if (kept == 0) { shape += spreads && d.blocks() >= STRIPPED_BLOCKS ? 2 : 1; harm.add("no storage left"); }
        // a chunk only flagged for its plants has no base in it to be wrecked: that shape is a shipwreck or a ruin
        if (!intact && builtHere) { points += shape; why.addAll(harm); }
        if (points >= GRIEF_MIN) return new Verdict(String.join(", ", why), null, intact, spreads && !intact);
        return new Verdict(null, spreads ? String.join(", ", harm) : null, intact, false);
    }

    /** A hopper above, under or beside this block. */
    private boolean hopperNear(BlockPos p) {
        for (Direction d : Direction.values()) if (mc.level.getBlockState(p.relative(d)).getBlock() instanceof HopperBlock) return true;
        return false;
    }

    private static boolean solid(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty();
    }

    /** One cell of a 1 wide, 2 high tunnel: open, with rock above, below and on both sides. */
    private static boolean tunnelCell(LevelChunk c, BlockPos.MutableBlockPos p, int x, int y, int z, boolean alongX) {
        if (!c.getBlockState(p.set(x, y, z)).isAir() || !c.getBlockState(p.set(x, y + 1, z)).isAir()) return false;
        if (!solid(c.getBlockState(p.set(x, y - 1, z))) || !solid(c.getBlockState(p.set(x, y + 2, z)))) return false;
        int sx = alongX ? 0 : 1, sz = alongX ? 1 : 0;
        for (int dy = 0; dy <= 1; dy++) {
            if (!solid(c.getBlockState(p.set(x + sx, y + dy, z + sz))) || !solid(c.getBlockState(p.set(x - sx, y + dy, z - sz)))) return false;
        }
        return true;
    }

    /** 1x1 vertical holes through solid rock: nothing natural is that straight and narrow for this long. */
    private int shafts(LevelChunk chunk, int surface) {
        int found = 0, ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int low = mc.level.getMinY() + 1;
        for (int dx = 1; dx < 15; dx++) for (int dz = 1; dz < 15; dz++) {
            int x = ox + dx, z = oz + dz;
            // a shaft of 10 always covers a sample every 5 blocks, so only those get looked at closely
            for (int y = surface - 2; y >= low; y -= SHAFT_MIN / 2) {
                if (!shaftCell(chunk, p, x, y, z)) continue;
                int top = y, bottom = y;
                while (top < surface && shaftCell(chunk, p, x, top + 1, z)) top++;
                while (bottom > low && shaftCell(chunk, p, x, bottom - 1, z)) bottom--;
                y = bottom;
                if (top - bottom + 1 < SHAFT_MIN) continue;
                digY = bottom;
                if (++found >= SHAFT_CAP) return found;
                break;
            }
        }
        return found;
    }

    private static boolean blob(BlockState st) {
        return st.is(Blocks.GRANITE) || st.is(Blocks.DIORITE) || st.is(Blocks.ANDESITE) || st.is(Blocks.TUFF) || st.is(Blocks.GRAVEL);
    }

    private static boolean patch(BlockState st) {
        return st.is(Blocks.STONE) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.DEEPSLATE) || st.is(Blocks.COBBLED_DEEPSLATE);
    }

    /** Height of the stone column at x, z walled by one blob block on all four sides, counted from y upwards; 0 when y isn't one. */
    private static boolean patchCell(LevelChunk c, BlockPos.MutableBlockPos p, int x, int y, int z, Block wall) {
        if (!patch(c.getBlockState(p.set(x, y, z)))) return false;
        return c.getBlockState(p.set(x + 1, y, z)).is(wall) && c.getBlockState(p.set(x - 1, y, z)).is(wall)
                && c.getBlockState(p.set(x, y, z + 1)).is(wall) && c.getBlockState(p.set(x, y, z - 1)).is(wall);
    }

    /** Stone columns running through a granite, diorite, andesite, tuff or gravel blob: a shaft dug through the blob and filled back with stone. Blobs are solid, so worldgen never leaves a column like that. */
    private int patchedShafts(LevelChunk chunk, int surface) {
        int found = 0, ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int low = mc.level.getMinY() + 1, high = Math.min(surface - 2, 64);
        // only sections that hold a blob block at all are worth walking
        boolean[] has = new boolean[chunk.getSections().length];
        boolean any = false;
        for (int i = 0; i < has.length; i++) { has[i] = !chunk.getSections()[i].hasOnlyAir() && chunk.getSections()[i].maybeHas(BaseFinder::blob); any |= has[i]; }
        if (!any) return 0;
        for (int dx = 1; dx < 15; dx++) for (int dz = 1; dz < 15; dz++) {
            int x = ox + dx, z = oz + dz;
            for (int y = low; y <= high; y++) {
                int si = chunk.getSectionIndex(y);
                if (si < 0 || si >= has.length) break;
                if (!has[si]) { y = (chunk.getSectionYFromSectionIndex(si) + 1) * 16 - 1; continue; }
                BlockState east = chunk.getBlockState(p.set(x + 1, y, z));
                if (!blob(east) || !patchCell(chunk, p, x, y, z, east.getBlock())) continue;
                Block wall = east.getBlock();
                int top = y;
                while (top < high && patchCell(chunk, p, x, top + 1, z, wall)) top++;
                int h = top - y + 1;
                boolean goesOn = patch(chunk.getBlockState(p.set(x, y - 1, z))) && patch(chunk.getBlockState(p.set(x, top + 1, z)));
                y = top;
                if (h < PATCH_MIN || !goesOn) continue;
                digY = y;
                found++;
                break;
            }
        }
        return found;
    }

    private static boolean shaftCell(LevelChunk c, BlockPos.MutableBlockPos p, int x, int y, int z) {
        return c.getBlockState(p.set(x, y, z)).isAir() && solid(c.getBlockState(p.set(x + 1, y, z))) && solid(c.getBlockState(p.set(x - 1, y, z)))
                && solid(c.getBlockState(p.set(x, y, z + 1))) && solid(c.getBlockState(p.set(x, y, z - 1)));
    }

    /** Enough tall plants to count on their own. */
    public static boolean kelpCountsAsGrowth(int grownPlants) { return grownPlants >= KELP_MIN_PLANTS; }

    /** Do too few plants to count on their own still count here? Only when the chunk is a find without them. */
    public static boolean loneKelpJoins(int grownPlants, int scoreWithoutKelp, int findLine) {
        return grownPlants > 0 && !kelpCountsAsGrowth(grownPlants) && scoreWithoutKelp >= findLine;
    }

    /** Worldgen kelp keeps growing up to the water surface wherever the water is deep, so a head at the surface (open air above it) says nothing about growth. */
    public static boolean kelpIsGrowthSign(int height, boolean waterAbove) {
        return waterAbove && height >= KELP_NATURAL;
    }

    /** Plants in a section at least min long, walking from the growing tip back along its stem. */
    private int stalks(LevelChunk chunk, int oy, Block tip, Block stem, Direction toRoot, int min) {
        return stalks(chunk, oy, tip, stem, toRoot, min, false);
    }

    private int stalks(LevelChunk chunk, int oy, Block tip, Block stem, Direction toRoot, int min, boolean kelp) {
        int n = 0, ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) {
            if (!chunk.getBlockState(p.set(ox + x, oy + y, oz + z)).is(tip)) continue;
            int h = 1;
            while (chunk.getBlockState(p.set(ox + x, oy + y + toRoot.getStepY() * h, oz + z)).is(stem)) h++;
            if (kelp) { if (kelpIsGrowthSign(h, chunk.getBlockState(p.set(ox + x, oy + y + 1, oz + z)).getFluidState().is(net.minecraft.tags.FluidTags.WATER))) n++; }
            else if (h >= min) n++;
        }
        return n;
    }

    /** Dripstone in a section longer than worldgen makes it. Joined columns don't count, those happen naturally. */
    private int dripstone(LevelChunk chunk, int oy) {
        int n = 0, ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) {
            BlockState st = chunk.getBlockState(p.set(ox + x, oy + y, oz + z));
            if (!st.is(Blocks.POINTED_DRIPSTONE) || st.getValue(PointedDripstoneBlock.THICKNESS) != DripstoneThickness.TIP) continue;
            Direction dir = st.getValue(PointedDripstoneBlock.TIP_DIRECTION);
            int h = 1;
            while (true) {
                BlockState next = chunk.getBlockState(p.set(ox + x, oy + y - dir.getStepY() * h, oz + z));
                if (!next.is(Blocks.POINTED_DRIPSTONE) || next.getValue(PointedDripstoneBlock.TIP_DIRECTION) != dir) break;
                h++;
            }
            if (h >= (dir == Direction.DOWN ? STALACTITE_GROWN : STALAGMITE_GROWN)) n++;
        }
        return n;
    }

    /** Kelp only generates on the sea floor under open water, so a stalk under a solid roof was planted. */
    private int roofedKelp(LevelChunk chunk, int oy) {
        int n = 0, ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) {
            if (!chunk.getBlockState(p.set(ox + x, oy + y, oz + z)).is(Blocks.KELP)) continue;
            int up = oy + y + 1;
            while (chunk.getBlockState(p.set(ox + x, up, oz + z)).is(Blocks.WATER)) up++;
            BlockState roof = chunk.getBlockState(p.set(ox + x, up, oz + z));
            if (roof.isSolidRender() && !roof.is(net.minecraft.tags.BlockTags.ICE)) n++;
        }
        return n;
    }

    /** A chunk's claim by a generated structure changed: it is scored again from a clean slate. */
    private void forgetChunk(long nk) {
        int x = ChunkPos.getX(nk), z = ChunkPos.getZ(nk);
        if (!(mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) instanceof LevelChunk)) return;
        lastScan.remove(nk);
        boolean lost = false;
        if (best.remove(nk) != null) { bestMode.remove(nk); ledgerDirty = true; lost = true; }
        lost |= finds.remove(nk) != null;
        if (v2() && (lost || loading != null)) { markRemoved(nk); ledgerDirty = true; }
        enqueue(new ChunkPos(nk));
    }

    /** A bell or marker block turned up: the chunks around it are scored again with the penalty, from a clean slate (no finds, no best-ever). */
    private void forgetAround(ChunkPos c) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long nk = ChunkPos.asLong(c.x + dx, c.z + dz);
            if ((dx | dz) != 0 && !(mc.level.getChunkSource().getChunk(c.x + dx, c.z + dz, ChunkStatus.FULL, false) instanceof LevelChunk)) continue; // not loaded: it cannot be scanned again, so its saved find stays
            boolean lost = false;
            lastScan.remove(nk);
            if (best.remove(nk) != null) { bestMode.remove(nk); ledgerDirty = true; lost = true; }
            if ((dx | dz) != 0) lost |= finds.remove(nk) != null;
            if (v2() && (lost || loading != null)) { markRemoved(nk); ledgerDirty = true; } // v2 state only: a chunk that lost a find, or (while the read is pending) any loaded neighbour, whose find may exist only in the file
            if ((dx | dz) == 0) continue;
            enqueue(new ChunkPos(nk));
        }
    }

    /** One growth clue: v1 notes it at once; v2 holds it back so the family (amethyst, kelp, dripstone, vines) is added up once. */
    private static void grow(List<Object[]> family, String reason, int w, int count, boolean v2, Map<String, int[]> rs) {
        family.add(new Object[] {reason, w, count});
        if (!v2) note(rs, reason, w, count);
    }

    /** v2: the strongest member gets its points plus 2 per further member (at most grownPoints' own cap of 16); the rest are listed at 0, counted with it. */
    private static int resolveFamily(List<Object[]> family, Map<String, int[]> rs) {
        if (family.isEmpty()) return 0;
        Object[] top = family.get(0);
        for (Object[] g : family) if ((Integer) g[1] > (Integer) top[1]) top = g;
        int total = familyTotal((Integer) top[1], family.size());
        for (Object[] g : family) note(rs, (String) g[0], g == top ? total : 0, (Integer) g[2]);
        return total;
    }

    /**
     * One or two tall kelp plants joining a find the other clues already made: notes them and returns the points added. v1 adds them like any growth clue;
     * v2 adds what {@link #resolveFamily} would have given with kelp in the family (the points sit on the strongest member).
     */
    private static int joinKelp(List<Object[]> family, int count, boolean v2, Map<String, int[]> rs) {
        int w = grownPoints(count);
        Object[] top = null;
        for (Object[] g : family) if (top == null || (Integer) g[1] > (Integer) top[1]) top = g;
        if (!v2 || top == null) { note(rs, KELP_GROWN_REASON, w, count); return w; }
        int best = (Integer) top[1];
        int was = familyTotal(best, family.size()), now = familyTotal(Math.max(best, w), family.size() + 1);
        if (w > best) {
            rs.get((String) top[0])[0] -= was;
            note(rs, KELP_GROWN_REASON, now, count);
        } else {
            rs.get((String) top[0])[0] += now - was;
            note(rs, KELP_GROWN_REASON, 0, count);
        }
        return now - was;
    }

    /** v2: the strongest member's points plus 2 per further member, at most grownPoints' own cap of 16. */
    private static int familyTotal(int strongest, int members) {
        return Math.min(16, strongest + 2 * (members - 1));
    }

    private static int grownPoints(int count) {
        return Math.min(16, 6 + count * 2);
    }

    /** Lowest of five spots, so a chunk on a hillside or in a crater doesn't call what's on the ground "deep". */
    /** The lowest heightmap in this chunk and the eight around it: the terrain, even under a build that covers the chunk. */
    private int groundAround(LevelChunk chunk) {
        boolean seabed = v2();
        int low = lowestGround(chunk, seabed);
        ChunkPos c = chunk.getPos();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if ((dx | dz) != 0 && mc.level.getChunkSource().getChunk(c.x + dx, c.z + dz, ChunkStatus.FULL, false) instanceof LevelChunk near) low = Math.min(low, lowestGround(near, seabed));
        }
        return low;
    }

    private static int lowestGround(LevelChunk chunk) {
        return lowestGround(chunk, false);
    }

    /** v2 (seabed): the motion-blocking heightmap stops at the water surface, so under a lake or the sea "ground" would be the waves and a seabed chest 12 down would read as deep. */
    private static int ground(LevelChunk chunk, int x, int z, boolean seabed) {
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (!seabed) return y;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int ox = chunk.getPos().getMinBlockX() + x, oz = chunk.getPos().getMinBlockZ() + z, min = chunk.getMinY();
        while (y > min && !chunk.getBlockState(p.set(ox, y, oz)).getFluidState().isEmpty()) y--;
        return y;
    }

    private static int lowestGround(LevelChunk chunk, boolean seabed) {
        int low = ground(chunk, 8, 8, seabed);
        for (int x = 0; x <= 15; x += 15) for (int z = 0; z <= 15; z += 15) low = Math.min(low, ground(chunk, x, z, seabed));
        return low;
    }

    /** Surface dirt nothing has grown over yet: fresh digging, grass spreads back within a while. */
    /** Is the middle of this chunk, low down, in the deep dark biome? (Ancient cities only generate there.) */
    private boolean deepDarkChunk(LevelChunk chunk) {
        int x = chunk.getPos().getMiddleBlockX(), z = chunk.getPos().getMiddleBlockZ();
        for (int y = mc.level.getMinY() + 8; y < mc.level.getMinY() + 56; y += 16) {
            if (mc.level.getBiome(new BlockPos(x, y, z)).is(net.minecraft.world.level.biome.Biomes.DEEP_DARK)) return true;
        }
        return false;
    }

    private int bareDirt(LevelChunk chunk) {
        int n = 0, ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (chunk.getBlockState(p.set(ox + x, y, oz + z)).is(Blocks.DIRT)) n++;
        }
        return n;
    }

    private static void note(Map<String, int[]> rs, String reason, int points, int count) {
        int[] v = rs.computeIfAbsent(reason, k -> new int[2]);
        v[0] += points;
        v[1] += count;
    }

    private static boolean looseContainersOnly(int buildPoints, int[] storage) {
        return storage != null && StructureRules.looseContainersOnly(buildPoints, storage[0], storage[1]);
    }

    /** Points and counts of what a generated village can have placed in this chunk, per reason and per why-label. */
    private static final class Furniture {
        final Map<String, int[]> reasons = new HashMap<>();
        final Map<String, Integer> labels = new HashMap<>();

        void add(String reason, String label, int points, int count, int entries) {
            note(reasons, reason, points, count);
            labels.merge(label, entries, Integer::sum);
        }
    }

    /** The block entities of the village structure templates. */
    private static boolean villageThing(BlockEntity be) {
        return be instanceof BedBlockEntity || be instanceof AbstractFurnaceBlockEntity || be instanceof BrewingStandBlockEntity || be instanceof BannerBlockEntity
                || be instanceof SignBlockEntity || be instanceof net.minecraft.world.level.block.entity.LecternBlockEntity || be instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity
                || be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity;
    }

    /** A village's meeting point seen for the first time: what was scored around it before is looked at again. */
    private void noteVillage(ChunkPos c) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long k = ChunkPos.asLong(c.x + dx, c.z + dz);
            if (villageCentres.contains(k) || !Villages.centre(mc.level, c.x + dx, c.z + dz)) continue;
            villageCentres.add(k);
            for (int x = -Villages.RADIUS; x <= Villages.RADIUS; x++) for (int z = -Villages.RADIUS; z <= Villages.RADIUS; z++) {
                ChunkPos near = new ChunkPos(c.x + dx + x, c.z + dz + z);
                if (!near.equals(c) && finds.containsKey(near.toLong())) enqueue(near);
            }
        }
    }

    /**
     * The containers of other chunks this chunk counts because it holds most of a pile split by a chunk border. A chunk that sees such
     * a pile scored elsewhere (and not yet with this many) queues that one chunk; the scoring chunk queues nobody for it.
     */
    private List<BlockPos> splitStash(long key, int storageCount) {
        Set<Long> before = absorbed.remove(key);
        countedOver.remove(key);
        List<BlockPos> other = new ArrayList<>();
        if (storageCount == 0) return other;
        var containers = ChunkPile.in(mc.level, be -> true);
        for (var pile : ChunkPile.crossing(key, containers)) {
            Long owner = ChunkPile.owner(pile, containers, p -> 1, STASH_ALONE);
            for (long k : pile.byChunk().keySet()) {
                // a chunk that counted containers of this pile before and no longer should
                if (k != key && !java.util.Objects.equals(owner, k) && pile.byChunk().values().stream().anyMatch(l -> l.stream().anyMatch(countedOver.getOrDefault(k, Set.of())::contains))) enqueue(new ChunkPos(k));
            }
            if (owner == null) continue;
            if (owner == key) {
                other.addAll(pile.outside(key));
                for (long k : pile.byChunk().keySet()) if (k != key) absorbed.computeIfAbsent(key, x -> new HashSet<>()).add(k);
            } else if (!countedOver.getOrDefault(owner, Set.of()).containsAll(pile.outside(owner))) enqueue(new ChunkPos(owner));
        }
        if (!other.isEmpty()) countedOver.put(key, new HashSet<>(other));
        else if (before != null) for (long k : before) enqueue(new ChunkPos(k));
        return other;
    }

    /** This chunk's block entities, plus the containers and machines of the chunks whose share of a pile it counts. */
    private List<BlockEntity> withPile(LevelChunk chunk) {
        List<BlockEntity> all = new ArrayList<>(chunk.getBlockEntities().values());
        for (long k : absorbed.getOrDefault(chunk.getPos().toLong(), Set.of()))
            if (mc.level.getChunkSource().getChunk(ChunkPos.getX(k), ChunkPos.getZ(k), ChunkStatus.FULL, false) instanceof LevelChunk c) all.addAll(c.getBlockEntities().values());
        return all;
    }

    private static String group(Block b) {
        if (b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN) return "obsidian";
        if (REDSTONE.contains(b)) return "redstone parts";
        if (CRAFTED_DEEPSLATE.contains(b)) return "deepslate somebody crafted (bricks, tiles)";
        String n = BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (n.contains("concrete")) return "concrete";
        if (n.contains("glass")) return "glass";
        if (n.endsWith("_carpet") || n.endsWith("_wool")) return "wool and carpet";
        return n.replace('_', ' ');
    }

    private static String group(BlockEntity be) {
        if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity || be instanceof EnderChestBlockEntity) return "storage";
        if (be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity || be instanceof CrafterBlockEntity) return "redstone parts";
        if (be instanceof BedBlockEntity) return "beds, tables";
        return label(be);
    }

    private int tunnels(LevelChunk chunk, int surface) {
        int found = 0, ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] secs = chunk.getSections();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int si = 0; si < secs.length && found < TUNNEL_CAP; si++) {
            int oy = mc.level.getSectionYFromSectionIndex(si) << 4;
            if (oy + 16 > surface - DEEP || secs[si].hasOnlyAir()) continue;
            for (int y = oy; y < oy + 16 && found < TUNNEL_CAP; y++) {
                for (int line = 1; line < 15; line++) for (int axis = 0; axis < 2; axis++) {
                    boolean alongX = axis == 0;
                    // a run of 12 in a line of 16 always crosses the middle, so most lines end with this one check
                    if (!tunnelCell(chunk, p, ox + (alongX ? 7 : line), y, oz + (alongX ? line : 7), alongX)) continue;
                    int run = 0;
                    for (int i = 0; i < 16 && run < TUNNEL_MIN; i++) {
                        run = tunnelCell(chunk, p, ox + (alongX ? i : line), y, oz + (alongX ? line : i), alongX) ? run + 1 : 0;
                    }
                    if (run >= TUNNEL_MIN) { found++; digY = y; }
                }
            }
        }
        return Math.min(found, TUNNEL_CAP);
    }

    /** Rig only: how often each chunk was scanned, and how often the Likely areas were worked out. */
    public static boolean countScans;
    private final Map<Long, Integer> scansOf = new HashMap<>();
    public int zoneRuns;
    /** Base pings announced this session (chat, sound, toast, card), for the rig. */
    public int pings;

    public int scannedTimes(int cx, int cz) {
        return scansOf.getOrDefault(ChunkPos.asLong(cx, cz), 0);
    }

    private void scan(LevelChunk chunk) {
        if (countScans) scansOf.merge(chunk.getPos().toLong(), 1, Integer::sum);
        long scanStart = System.nanoTime();
        try {
            scanChunk(chunk);
        } finally {
            scanNanos += System.nanoTime() - scanStart;
            scans++;
        }
    }

    /** End cities and ships are built from purpur and end stone bricks, full of chests, banners and glass. Nobody builds a base out of those in the End often enough to pay for the false pings. */
    public static boolean endCity(LevelChunk chunk) {
        if (chunk.getLevel().dimension() != net.minecraft.world.level.Level.END) return false;
        for (LevelChunkSection s : chunk.getSections()) {
            if (s.hasOnlyAir() || s.getStates().maybeHas(st -> false)) continue; // a global palette says yes to anything
            if (s.getStates().maybeHas(st -> st.is(Blocks.PURPUR_BLOCK) || st.is(Blocks.PURPUR_PILLAR) || st.is(Blocks.PURPUR_STAIRS) || st.is(Blocks.PURPUR_SLAB) || st.is(Blocks.END_STONE_BRICKS))) return true;
        }
        return false;
    }

    private void scanChunk(LevelChunk chunk) {
        if (endCity(chunk)) { lastScan.remove(chunk.getPos().toLong()); boolean lost = finds.remove(chunk.getPos().toLong()) != null; if (v2()) { Find gone = best.remove(chunk.getPos().toLong()); if (lost || gone != null) { markRemoved(chunk.getPos().toLong()); ledgerDirty = true; } } return; }
        final boolean v2 = v2();
        digY = Integer.MIN_VALUE;
        int score = 0, deepPoints = 0;
        // no real surface under a bedrock roof: nothing in the Nether counts as underground
        int surface = mc.level.dimensionType().hasCeiling() ? mc.level.getMinY() : lowestGround(chunk, v2);
        long sx = 0, sy = 0, sz = 0, n = 0;
        Map<String, Integer> why = new HashMap<>();
        Map<String, int[]> rs = new LinkedHashMap<>();
        int dugPoints = 0, growthPoints = 0;
        int lowY = Integer.MAX_VALUE, highY = Integer.MIN_VALUE, filled = 0;
        Set<Integer> hitSections = new HashSet<>();
        boolean bell = false, sign = false;
        int softPts = 0, enderPoints = 0, storageCount = 0, floatingStorage = 0, enderChests = 0, signs = 0, beds = 0, farmPoints = 0, banners = 0, spawnerCount = 0, machineCount = 0, beaconCount = 0, redstoneOther = 0, farmlandCount = 0, portalCount = 0, kelpPts = 0;
        boolean hall = false;
        int shulkerBoxes = 0;
        Furniture village = new Furniture();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof ShulkerBoxBlockEntity) shulkerBoxes++;
            if (be instanceof BellBlockEntity) bell = true;
            if (be instanceof SignBlockEntity s && griefSign(s)) sign = true;
            if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity) {
                storageCount++;
                // v2: a container with a hopper beside, above or under it hangs there on purpose (a farm's collector), it is not floating
                if (chunk.getBlockState(be.getBlockPos().below()).isAir() && !(v2 && hopperNear(be.getBlockPos()))) floatingStorage++;
            }
            if (be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity) farmPoints++;
            if (be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity || be instanceof CrafterBlockEntity) machineCount++;
            if (be instanceof BeaconBlockEntity) beaconCount++;
            if (be instanceof SpawnerBlockEntity) spawnerCount++;
            int w = weight(be, storage.on(), v2);
            if (be instanceof EnderChestBlockEntity && ++enderChests > ENDER_CAP) continue;
            if (v2 && be instanceof EnderChestBlockEntity) { // v2: ender chests are scattered everywhere; together they stay under Sus score and get no deep bonus
                int room = Math.max(0, susScore.i() - 1) - enderPoints;
                w = Math.min(w, room);
                enderPoints += w;
                softPts += w;
                if (w > 0) { score += w; note(rs, group(be), w, 1); why.merge(label(be), 1, Integer::sum); }
                continue;
            }
            if (be instanceof SignBlockEntity && ++signs > SIGN_CAP) continue;
            if (be instanceof BedBlockEntity && ++beds > BED_CAP) continue;
            if (v2 && be instanceof BannerBlockEntity && ++banners > BANNER_CAP) continue;
            if (w == 0) continue;
            BlockPos p = be.getBlockPos();
            if (p.getY() < surface - DEEP) { w += (w + 1) / 2; deepPoints += w; }
            score += w;
            if (be instanceof EnderChestBlockEntity || be instanceof SignBlockEntity || be instanceof BannerBlockEntity) softPts += w; // scattered everywhere: no evidence of a base
            why.merge(label(be), 1, Integer::sum);
            note(rs, group(be), w, 1);
            if (villageThing(be) && p.getY() >= surface - DEEP) village.add(group(be), label(be), w, 1, 1);
            lowY = Math.min(lowY, p.getY()); highY = Math.max(highY, p.getY());
            hitSections.add(p.getY() >> 4);
            sx += p.getX(); sy += p.getY(); sz += p.getZ(); n++;
        }
        Map<Block, Integer> pal = palette();
        LevelChunkSection[] secs = chunk.getSections();
        int topSection = Integer.MIN_VALUE;
        boolean fire = false, tnt = false, structure = false;
        int kelp = 0, dripstone = 0, vines = 0, roofedKelp = 0;
        boolean lavaAround = false;
        int[] geode = new int[2];
        // generated structures: what the chunk holds of each kind's marker blocks and building palette; the scored blocks wait until the claim is known
        final StructureRules.Kind[] kinds = StructureRules.Kind.values();
        boolean[] stMark = new boolean[kinds.length], stPal = new boolean[kinds.length];
        int sculkBlocks = 0;
        boolean ceiling = mc.level.dimensionType().hasCeiling();
        List<Object[]> deferred = new ArrayList<>();
        final boolean deepDarkEarly = deepDarkChunk(chunk); // any scoring: an ancient city is not a base
        Set<Block> utility = new HashSet<>();
        Map<String, Integer> remembered = new HashMap<>();
        int[] highDeep = new int[1], kelpHeads = new int[1];
        for (int si = 0; si < secs.length; si++) {
            LevelChunkSection s = secs[si];
            if (s.hasOnlyAir()) continue;
            filled++;
            dev.vantage.core.FakeOres.look(s);
            Set<Block> hits = new HashSet<>();
            // returns false on purpose so every palette entry is looked at once; huge global palettes are skipped
            boolean[] seen = new boolean[13];
            Map<Block, Integer> sb = structBits();
            int[] secBits = new int[1];
            Set<Block> buildHere = new HashSet<>();
            int buildMask = buildMask();
            s.getStates().maybeHas(st -> {
                Integer bb = sb.get(st.getBlock());
                if (bb != null) { secBits[0] |= bb; if ((bb & buildMask) != 0) buildHere.add(st.getBlock()); }
                if (pal.containsKey(st.getBlock())) hits.add(st.getBlock());
                if (UTILITY.contains(st.getBlock())) utility.add(st.getBlock());
                if (st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE)) seen[2] = true;
                if (st.is(Blocks.TNT)) seen[3] = true;
                if (STRUCTURE.contains(st.getBlock())) seen[4] = true;
                if (st.is(Blocks.DEEPSLATE) && st.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y) seen[5] = true;
                if (st.is(Blocks.KELP)) seen[6] = true;
                if (st.is(Blocks.BUDDING_AMETHYST)) seen[7] = true;
                if (st.is(Blocks.POINTED_DRIPSTONE)) seen[8] = true;
                if (st.is(Blocks.CAVE_VINES)) seen[9] = true;
                if (st.is(Blocks.WEEPING_VINES)) seen[10] = true;
                if (st.is(Blocks.TWISTING_VINES)) seen[11] = true;
                if (st.is(Blocks.LAVA)) seen[12] = true;
                return false;
            });
            structure |= seen[4];
            lavaAround |= seen[12];
            if (dev.vantage.core.FakeOres.detected()) { hits.remove(Blocks.OBSIDIAN); hits.remove(Blocks.CRYING_OBSIDIAN); } // on the fake list
            int sectionY = mc.level.getSectionYFromSectionIndex(si) << 4;
            if (growth.on()) {
                if (seen[6]) s.getStates().count((st, c) -> { if (st.is(Blocks.KELP)) kelpHeads[0] += c; });
                if (seen[6]) kelp += stalks(chunk, sectionY, Blocks.KELP, Blocks.KELP_PLANT, Direction.DOWN, KELP_NATURAL, true);
                if (seen[6]) roofedKelp += roofedKelp(chunk, sectionY);
                if (seen[8]) dripstone += dripstone(chunk, sectionY);
                if (seen[9]) vines += stalks(chunk, sectionY, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Direction.UP, CAVE_VINES_GROWN);
                if (seen[10]) vines += stalks(chunk, sectionY, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Direction.UP, WEEPING_GROWN);
                if (seen[11]) vines += stalks(chunk, sectionY, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Direction.DOWN, TWISTING_GROWN);
                if (seen[7]) s.getStates().count((st, c) -> {
                    if (st.is(Blocks.BUDDING_AMETHYST)) geode[0] += c;
                    else if (st.is(Blocks.AMETHYST_CLUSTER)) geode[1] += c;
                });
            }
            if (!v2 && secBits[0] != 0 && StructureRules.placeOk(StructureRules.Kind.MONUMENT, sectionY, ceiling) && buildHere.stream().anyMatch(bk -> (sb.get(bk) & bit(StructureRules.Kind.MONUMENT, 4)) != 0)) {
                // the released scoring claims monuments too, on a stricter sign: flooded sections only (a player's dry rooms keep every point)
                int[] c = new int[2];
                s.getStates().count((st, count) -> {
                    if (st.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) c[0] += count;
                    Integer bb = sb.get(st.getBlock());
                    if (bb != null && (bb & bit(StructureRules.Kind.MONUMENT, 4)) != 0) c[1] += count;
                });
                int i = StructureRules.Kind.MONUMENT.ordinal();
                if (StructureRules.monumentFlooded(c[0])) {
                    stPal[i] = true;
                    if (StructureRules.monumentWaterMarker(c[0], c[1], buildHere.contains(Blocks.PRISMARINE), buildHere.contains(Blocks.PRISMARINE_BRICKS), buildHere.contains(Blocks.DARK_PRISMARINE), buildHere.contains(Blocks.SEA_LANTERN))) stMark[i] = true;
                }
            }
            if (v2 && secBits[0] != 0) {
                for (StructureRules.Kind k : kinds) {
                    if (!StructureRules.placeOk(k, sectionY, ceiling)) continue;
                    if ((secBits[0] & bit(k, 2)) != 0) stMark[k.ordinal()] = true;
                    if (k != StructureRules.Kind.CITY && (secBits[0] & bit(k, 4)) != 0) {
                        int d = 0;
                        for (Block bk : buildHere) { Integer x = sb.get(bk); if (x != null && (x & bit(k, 4)) != 0) d++; }
                        if (StructureRules.paletteSection(k, d)) stPal[k.ordinal()] = true;
                        if (k == StructureRules.Kind.MONUMENT && StructureRules.monumentMarkerSection(d, buildHere.contains(Blocks.SEA_LANTERN))) stMark[k.ordinal()] = true;
                    }
                }
                if ((secBits[0] & 1) != 0 && StructureRules.placeOk(StructureRules.Kind.CITY, sectionY, ceiling)) {
                    int[] sc = new int[1];
                    s.getStates().count((st, c) -> { Integer bb = sb.get(st.getBlock()); if (bb != null && (bb & 1) != 0) sc[0] += c; });
                    sculkBlocks += sc[0];
                }
            }
            fire |= seen[2]; tnt |= seen[3];
            if (sectionY >= HIGH_DEEPSLATE_Y) s.getStates().count((st, c) -> { if (st.is(Blocks.DEEPSLATE) || st.is(Blocks.COBBLED_DEEPSLATE)) highDeep[0] += c; });
            if (hits.isEmpty() && !seen[5]) continue;
            boolean deepSection = sectionY + 16 < surface - DEEP;
            Map<Block, Integer> counts = new HashMap<>();
            int[] sideways = new int[1];
            s.getStates().count((st, c) -> {
                if (hits.contains(st.getBlock())) counts.merge(st.getBlock(), c, Integer::sum);
                if (st.is(Blocks.DEEPSLATE) && st.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y) sideways[0] += c;
            });
            counts.forEach((b, c) -> remembered.merge(BuiltInRegistries.BLOCK.getKey(b).getPath(), c, Integer::sum));
            for (Block b : hits) {
                if (FIGHT_BLOCKS.contains(b) && !deepSection) continue;
                int w = pal.get(b);
                int cnt = counts.getOrDefault(b, 0);
                Integer gen = structBits().get(b);
                if (gen != null) {
                    int mask = 0;
                    for (StructureRules.Kind k : kinds) if ((v2 || k == StructureRules.Kind.MONUMENT) && (gen & bit(k, 1)) != 0 && StructureRules.placeOk(k, sectionY, ceiling)) mask |= 1 << k.ordinal();
                    if (mask != 0) { deferred.add(new Object[] {b, w, cnt, deepSection, mask}); continue; } // scored once the chunk's claim is known
                }
                if (deepDarkEarly && !v2 && (CRAFTED_DEEPSLATE.contains(b) || group(b).equals("wool and carpet"))) continue; // v2 holds the city palette by its mask below; the old scoring has no masks
                if (deepDarkEarly && (REDSTONE.contains(b) || b == Blocks.SCULK_SENSOR || b == Blocks.CALIBRATED_SCULK_SENSOR)) continue; // an ancient city's own redstone room and sensors
                if (REDSTONE.contains(b) && b != Blocks.REDSTONE_LAMP) redstoneOther += cnt; // machines are block entities and lamps are decoration: neither counts as redstone
                if (b == Blocks.FARMLAND) farmlandCount += cnt;
                if (b == Blocks.NETHER_PORTAL) portalCount += cnt;
                if (deepSection) { w += (w + 1) / 2; deepPoints += w; }
                String reason = group(b);
                if (COUNTED.contains(reason)) { note(rs, reason, 0, counts.getOrDefault(b, 0)); continue; }
                score += w;
                why.merge(BuiltInRegistries.BLOCK.getKey(b).getPath().replace('_', ' '), 1, Integer::sum);
                note(rs, reason, w, counts.getOrDefault(b, 0));
                if (Villages.places(b) && !deepSection) village.add(reason, BuiltInRegistries.BLOCK.getKey(b).getPath().replace('_', ' '), w, counts.getOrDefault(b, 0), 1);
            }
            if (sideways[0] > 0) {
                int w = Math.min(10, 2 + sideways[0]);
                if (deepSection) deepPoints += w;
                score += w;
                why.merge("rotated deepslate", 1, Integer::sum);
                note(rs, "deepslate placed sideways", w, sideways[0]);
            }
            hitSections.add(sectionY >> 4);
            lowY = Math.min(lowY, sectionY); highY = Math.max(highY, sectionY + 15);
            topSection = sectionY;
        }
        // generated structures: record what this chunk is, learn which chunks around it changed their claim, then score what was held back
        List<StructureRules.Kind> claimedKinds = List.of();
        if (v2) {
            long ck = chunk.getPos().toLong();
            boolean cityPal = false;
            for (Object[] d : deferred) if (((int) d[4] & (1 << StructureRules.Kind.CITY.ordinal())) != 0 && (int) d[2] > 0) cityPal = true;
            stMark[StructureRules.Kind.CITY.ordinal()] |= StructureRules.cityMarkerChunk(stMark[StructureRules.Kind.CITY.ordinal()], sculkBlocks, cityPal);
            stPal[StructureRules.Kind.CITY.ordinal()] = cityPal;
            for (StructureRules.Kind k : kinds) for (long nk : rules.update(k, ck, stMark[k.ordinal()], stPal[k.ordinal()])) if (skipStructures.on()) forgetChunk(nk);
            if (skipStructures.on()) claimedKinds = rules.claims(ck);
        } else {
            long ck = chunk.getPos().toLong();
            int i = StructureRules.Kind.MONUMENT.ordinal();
            for (long nk : rules.update(StructureRules.Kind.MONUMENT, ck, stMark[i], stPal[i])) if (skipStructures.on()) forgetChunk(nk);
            if (skipStructures.on() && stPal[i] && rules.claimed(StructureRules.Kind.MONUMENT, ck)) claimedKinds = List.of(StructureRules.Kind.MONUMENT);
        }
        int[] heldBack = new int[kinds.length];
        final boolean deepDark = v2 && deepDarkEarly; // the whole deep dark is ancient city country: its palette is worldgen's
        for (Object[] d : deferred) {
            Block b = (Block) d[0];
            int mask = (int) d[4], cnt = (int) d[2];
            StructureRules.Kind by = null;
            if (deepDark && (mask & (1 << StructureRules.Kind.CITY.ordinal())) != 0) { heldBack[StructureRules.Kind.CITY.ordinal()] += cnt; continue; }
            for (StructureRules.Kind k : claimedKinds) if ((mask & (1 << k.ordinal())) != 0) { by = k; break; }
            if (by != null) { heldBack[by.ordinal()] += cnt; continue; }
            int w = (int) d[1];
            if ((boolean) d[3]) { w += (w + 1) / 2; deepPoints += w; }
            String reason = group(b);
            if (COUNTED.contains(reason)) { note(rs, reason, 0, cnt); continue; }
            score += w;
            why.merge(BuiltInRegistries.BLOCK.getKey(b).getPath().replace('_', ' '), 1, Integer::sum);
            note(rs, reason, w, cnt);
        }
        for (StructureRules.Kind k : kinds) if (heldBack[k.ordinal()] > 0) note(rs, k.name + " nearby: " + k.notCounted + " not counted", 0, heldBack[k.ordinal()]);
        for (String reason : COUNTED) {
            int[] v = rs.get(reason);
            if (v == null || v[1] == 0) continue;
            int w = reason.startsWith("deepslate") ? Math.min(10, 2 + v[1] / 2) : Math.min(10, v[1]);
            // water running over lava makes obsidian with nobody there
            if (reason.equals("obsidian") && lavaAround) w = Math.min(w, 3);
            v[0] += w;
            score += w;
            why.merge(reason, v[1], Integer::sum);
        }
        long key = chunk.getPos().toLong();
        // v2: a bell or a structure marker block lowers this chunk AND the 8 around it. A neighbour scanned before the bell was seen is
        // forgotten (its best-ever too, or sticky finds would undo the penalty) and queued again, so it is re-scored with the penalty on
        boolean bellNear = false, markerNear = false;
        if (v2) {
            if (bell && bells.add(key) && skipVillages.on()) forgetAround(chunk.getPos());
            if (structure && markers.add(key) && skipStructures.on()) forgetAround(chunk.getPos());
            if (!bell && bells.remove(key) && skipVillages.on()) forgetAround(chunk.getPos()); // a bell that is gone lifts its penalty: the 8 around are scored again
            if (!structure && markers.remove(key) && skipStructures.on()) forgetAround(chunk.getPos());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                long nk = ChunkPos.asLong(chunk.getPos().x + dx, chunk.getPos().z + dz);
                if ((dx | dz) == 0) continue;
                bellNear |= bells.contains(nk);
                markerNear |= markers.contains(nk);
            }
        }
        // v2: what this chunk holds that is not digging, not growth: the evidence that digging here belongs to a base and is not just a strip mine
        boolean ownEvidence = v2 && (score - softPts >= DIG_EVIDENCE || storageCount > 0 || hiddenContainersIn(chunk.getPos().x, chunk.getPos().z)); // one rule (buildNear uses it too): build or storage points, not ender chests, signs or banners; hidden containers the server leaked count
        boolean digEvidence = true;
        if (v2) {
            if (ownEvidence) {
                if (evidence.add(key)) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { // a neighbour that scanned first may have capped its digging: it gets another look
                    long nk = ChunkPos.asLong(chunk.getPos().x + dx, chunk.getPos().z + dz);
                    if ((dx | dz) != 0 && digCapped.remove(nk)) enqueue(new ChunkPos(nk));
                }
            } else if (evidence.remove(key)) { // evidence went away: the neighbours that counted their digging in full are scored again
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    long nk = ChunkPos.asLong(chunk.getPos().x + dx, chunk.getPos().z + dz);
                    if ((dx | dz) != 0 && digOk.contains(nk)) enqueue(new ChunkPos(nk));
                }
            }
            digEvidence = ownEvidence;
            for (int dx = -1; dx <= 1 && !digEvidence; dx++) for (int dz = -1; dz <= 1; dz++) {
                if ((dx | dz) != 0 && (evidence.contains(ChunkPos.asLong(chunk.getPos().x + dx, chunk.getPos().z + dz)) || hiddenContainersIn(chunk.getPos().x + dx, chunk.getPos().z + dz))) { digEvidence = true; break; }
            }
        }
        if (tunnels.on()) {
            // v2 without evidence: all digging together stays under Sus score (a strip mine is a mine); with it, the old points in full
            int budget = v2 && !digEvidence ? Math.max(0, susScore.i() - 1) : Integer.MAX_VALUE;
            boolean capped = false;
            int t = tunnels(chunk, surface);
            if (t > 0) {
                int pts = Math.min(t * TUNNEL_POINTS, budget);
                capped |= pts < t * TUNNEL_POINTS;
                budget -= pts;
                score += pts;
                deepPoints += pts;
                why.put("tunnel", t);
                dugPoints += pts;
                note(rs, "hand-dug tunnel", pts, t);
            }
            int shaft = shafts(chunk, surface);
            if (shaft > 0) {
                int pts = Math.min(shaft * SHAFT_POINTS, budget);
                capped |= pts < shaft * SHAFT_POINTS;
                budget -= pts;
                score += pts;
                deepPoints += pts;
                dugPoints += pts;
                why.put("shaft", shaft);
                note(rs, "hand-dug shaft", pts, 0);
            }
            int patched = patchedShafts(chunk, surface);
            if (patched > 0) {
                int pts = Math.min(PATCH_POINTS, budget);
                capped |= pts < PATCH_POINTS;
                score += pts;
                deepPoints += pts;
                dugPoints += pts;
                why.put("patched shaft", patched);
                note(rs, "patched shaft", pts, patched);
            }
            if (v2) {
                if (capped) digCapped.add(key); else digCapped.remove(key);
                if (digEvidence && (t > 0 || shaft > 0 || patched > 0)) digOk.add(key); else digOk.remove(key);
            }
        } else if (v2) { digCapped.remove(key); digOk.remove(key); }
        List<Object[]> family = new ArrayList<>();
        final int kelpSeen = kelp; // every grown plant, for the "nothing grew here" vote below
        if (growth.on()) {
            kelp = kelpWatch.count(chunk.getPos().toLong(), kelp, kelpHeads[0] > 0, tickedByUser(chunk.getPos()));
            // geode with nearly every budding block covered in full clusters has been grown out for days
            // fresh geodes measured at most 0.38 full clusters per budding block, so anything past 0.45 grew
            if (geode[0] >= 8 && geode[1] * 100 >= geode[0] * 45) {
                int w = geode[1] >= geode[0] ? 12 : geode[1] * 10 >= geode[0] * 6 ? 8 : 4;
                grow(family, "amethyst grown out", w, geode[1], v2, rs);
            }
            if (roofedKelp > 0) { int w = grownPoints(roofedKelp); growthPoints += w; farmPoints += w; kelpPts += w; note(rs, "kelp growing underground", w, roofedKelp); }
            if (kelpCountsAsGrowth(kelp)) grow(family, KELP_GROWN_REASON, grownPoints(kelp), kelp, v2, rs);
            if (dripstone > 0) grow(family, "dripstone grown past worldgen length", grownPoints(dripstone), dripstone, v2, rs);
            if (vines > 0) grow(family, "cave and nether vines grown long", grownPoints(vines), vines, v2, rs);
            if (v2) growthPoints += resolveFamily(family, rs);
            else for (Object[] g : family) growthPoints += (Integer) g[1];
            int dirt = bareDirt(chunk);
            if (dirt >= 12) { int w = Math.min(8, dirt / 3); growthPoints += w; note(rs, "bare dirt, grass hasn't grown back", w, dirt); }
            score += growthPoints;
        }
        // the released scoring: a generated village's own furniture is no find. A few containers at most, no shulker box; deep blocks stay
        boolean inVillage = false;
        if (!v2 && skipVillages.on()) {
            noteVillage(chunk.getPos());
            if (storageCount <= Villages.STORAGE_MAX && shulkerBoxes == 0 && (!village.reasons.isEmpty() || utility.size() >= UTILITY_KINDS) && Villages.near(mc.level, chunk.getPos().x, chunk.getPos().z)) {
                inVillage = true;
                int held = 0;
                for (var e : village.reasons.entrySet()) {
                    int[] have = rs.get(e.getKey());
                    if (have == null) continue;
                    have[0] -= e.getValue()[0];
                    have[1] -= e.getValue()[1];
                    score -= e.getValue()[0];
                    held += e.getValue()[1];
                    if (have[0] <= 0 && have[1] <= 0) rs.remove(e.getKey());
                }
                for (var e : village.labels.entrySet()) why.computeIfPresent(e.getKey(), (k, had) -> had > e.getValue() ? had - e.getValue() : null);
                if (held > 0) note(rs, "village here: its own furniture not counted", 0, held);
            }
        }
        // a stash lying across a chunk border: the chunk holding most of it counts the containers next door too
        int over = 0, overMachines = 0;
        if (storage.on()) {
            List<BlockPos> other = splitStash(chunk.getPos().toLong(), storageCount);
            for (BlockPos p : other) {
                BlockEntity be = mc.level.getBlockEntity(p);
                if (be == null) continue;
                int w = weight(be, true, v2);
                if (p.getY() < surface - DEEP) { w += (w + 1) / 2; deepPoints += w; }
                score += w;
                note(rs, group(be), w, 1);
                why.merge(label(be), 1, Integer::sum);
                if (be instanceof ShulkerBoxBlockEntity) shulkerBoxes++;
                over++;
            }
            if (over > 0) {
                note(rs, "of these containers across the chunk border", 0, over);
                for (long k : absorbed.get(chunk.getPos().toLong())) {
                    if (mc.level.getChunkSource().getChunk(ChunkPos.getX(k), ChunkPos.getZ(k), ChunkStatus.FULL, false) instanceof LevelChunk c)
                        for (BlockEntity be : c.getBlockEntities().values()) if (be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity) overMachines++;
                }
            }
        }
        // a farm's output is a pile too, so the size alone only counts with nothing to explain it
        if (storageCount + over >= STASH_ALONE && (farmPoints + overMachines) * 2 < storageCount + over) { hall = true; score += 12; note(rs, "enough containers to be a stash by itself", 12, storageCount + over); }
        if (utility.size() >= UTILITY_KINDS && !bell && !bellNear && !inVillage) { score += 8; note(rs, "utility blocks only a player places", 8, utility.size()); }
        if (highDeep[0] >= HIGH_DEEPSLATE_MIN) { int w = Math.min(10, 4 + highDeep[0] / 4); score += w; note(rs, "deepslate this high, worldgen stops at Y 8", w, highDeep[0]); }
        String memoryKey = dev.vantage.core.ChunkTracker.dim() + ":" + chunk.getPos().x + ":" + chunk.getPos().z;
        // no container counts in here: anti-ESP shows and hides those, which would read as someone moving things
        boolean fakes = dev.vantage.core.FakeOres.detected() // fake ores change between sends, so counts mean nothing
                || DeepLeaks.anyFill(chunk.getPos().x, chunk.getPos().z); // a chunk half fill, half real changes with every step you take, not with anyone's work
        // with one player in the world the game itself changes blocks (sculk, fluids, growth) and nobody is "still working here"
        int moved = fakes || dev.vantage.core.util.Alone.now() ? -1 : dev.vantage.core.ScanMemory.changed(memoryKey, remembered);
        if (moved >= CHANGED_MIN) {
            int w = Math.min(12, 2 + moved / 4);
            score += w;
            growthPoints += w;
            note(rs, "+" + moved + " blocks here changed since your last scan", w, moved);
        }
        if ((bell || bellNear) && skipVillages.on()) score -= bell ? 25 : VILLAGE_PENALTY; // v1 never sets bellNear
        if (structure && skipStructures.on()) score -= STRUCTURE_PENALTY;
        else if ((markerNear || v2 && !claimedKinds.isEmpty()) && skipStructures.on()) { // the released scoring only holds the monument's own blocks back, it takes nothing else away
            score -= STRUCTURE_NEIGHBOUR_PENALTY;
            if (!markerNear) note(rs, claimedKinds.get(0).name + " nearby: chests and beds here are mostly generated, score lowered by " + STRUCTURE_NEIGHBOUR_PENALTY, 0, 0);
        }
        // last, so every other clue and penalty is in: one or two tall plants add to a find, they never make one
        if (growth.on() && loneKelpJoins(kelp, score, Math.min(susScore.i(), threshold.i()))) {
            int w = joinKelp(family, kelp, v2, rs);
            score += w;
            growthPoints += w;
        }
        boolean deep = deepPoints * 2 >= score;
        // a grief sign or TNT says what happened to the chunks around it too, even with nothing else here
        // proof nobody stayed: plenty of kelp, none tall, or a geode with worldgen's share of clusters. A geode with next to none was harvested, so that's no proof
        boolean freshKelp = kelpHeads[0] >= UNGROWN_KELP && kelpSeen == 0 && roofedKelp == 0;
        boolean freshGeode = geode[0] >= 8 && geode[1] * 100 >= geode[0] * 5 && geode[1] * 100 < geode[0] * 38;
        if (growth.on() && (freshKelp || freshGeode)) ungrown.add(key); else ungrown.remove(key);
        if (sign || tnt) wrecked.put(key, sign ? "grief sign nearby" : "tnt nearby");
        else wrecked.remove(key);
        if (v2 && dismissed.contains(key)) { finds.remove(key); best.remove(key); lastScan.remove(key); return; }
        if (score < Math.min(susScore.i(), threshold.i())) {
            // v2: a chunk that once scored keeps that find, marked; a re-sent chunk or a refilled one must not erase a base
            if (v2) { cancelHold(key, "low"); lastScan.remove(key); } // this scan would not ping
            Find was = v2 && fresh(key) ? best.get(key) : null;
            if (was != null) finds.put(key, noted(was, SEEN + ": best scan scored " + was.score() + ", the last one " + score));
            else { boolean lost = finds.remove(key) != null; if (v2) { Find gone = best.remove(key); if (gone != null) { bestMode.remove(key); ledgerDirty = true; } if (lost || gone != null || loading != null) { markRemoved(key); if (loading != null) ledgerDirty = true; } } } // a chunk that lost a find counts as removed; while a read is still pending any empty chunk may stand for a find that is in the file // nothing here now, and the old one is from the other mode (or from no scan this session)
            return;
        }
        if (!fakes) dev.vantage.core.ScanMemory.remember(memoryKey, remembered);
        int x, y, z;
        if (n > 0) { x = (int) (sx / n); y = (int) (sy / n); z = (int) (sz / n); }
        else { x = chunk.getPos().getMiddleBlockX(); y = topSection != Integer.MIN_VALUE ? topSection + 8 : digY != Integer.MIN_VALUE ? digY : surface; z = chunk.getPos().getMiddleBlockZ(); }
        // showpieces and hubs: a statue, a lobby, a spawn build. Everything above the ground is always real (a fill server only hides
        // what is under the surface), so "no storage anywhere" and "a crowd around" can be trusted up here and never touch deep finds.
        // the build itself raises the heightmap, so the ground is the lowest heightmap in the 3x3 chunks around (a neighbour is usually terrain);
        // the find's centre (the average of its player blocks) above that = an above-ground build. Farms, growth and digging are never showpieces.
        boolean aboveGround = !mc.level.dimensionType().hasCeiling() && y >= groundAround(chunk) - 2 && deepPoints == 0;
        boolean onlyBuild = farmPoints == 0 && growthPoints == 0 && dugPoints == 0;
        if (aboveGround && onlyBuild && skipShowpieces.on() && storageCount == 0 && enderChests == 0 && storageAround(chunk.getPos()) == 0 && score > susScore.i()) {
            note(rs, "no storage here or next door: a build, not a base", 0, 0);
            score = susScore.i();
        }
        int crowd = aboveGround && onlyBuild && skipCrowds.on() ? crowdAround(chunk.getPos()) : 0;
        if (crowd >= CROWD && score > susScore.i()) {
            note(rs, crowd + " players around: a spawn or hub, not a base", 0, 0);
            score = susScore.i();
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(why.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(4, top.size()); i++) parts.add(top.get(i).getValue() + " " + top.get(i).getKey());
        if (shulkerBoxes > 0 && storage.on()) note(rs, SHULKER_REASON, 0, shulkerBoxes);
        StashLogic.Verdict pile = over == 0 ? StashLogic.judge(mc.level, chunk, surface, 0) : StashLogic.judge(mc.level, withPile(chunk), surface, 0, true);
        if (pile != null && !pile.why().isEmpty()) note(rs, pile.kind() + ": " + pile.why().get(0), 0, 0);
        List<Reason> reasons = new ArrayList<>();
        rs.forEach((text, v) -> reasons.add(new Reason(text, v[0], v[1])));
        reasons.sort((a, b) -> b.points() - a.points());
        if (digY != Integer.MIN_VALUE) { lowY = Math.min(lowY, digY); highY = Math.max(highY, digY); hitSections.add(digY >> 4); }
        if (lowY == Integer.MAX_VALUE) { lowY = y; highY = y; }
        Verdict v = grief(chunk, fire, tnt, sign, storageCount, floatingStorage, score - dugPoints - growthPoints > 0 && !looseContainersOnly(score - dugPoints - growthPoints, rs.get("storage")) && !(deepDarkEarly || v2 && dev.vantage.core.util.Alone.now()), v2 && farmPoints >= FARM_WATER_MIN); // a ruin nobody wrecked: the deep dark, or a world with one player
        if (v2) { if (v.grief() != null) griefSeen.add(key); else griefSeen.remove(key); } // rig: what the last scan said (v2 state only)
        if (v.spreads() && !wrecked.containsKey(key)) wrecked.put(key, "wrecked next door");
        Find f = new Find(x, y, z, score, String.join(", ", parts), v.grief(), v.damage(), v.intact(), deep,
                lowY, highY, hitSections.size(), Math.max(filled, hitSections.size()), score - dugPoints - growthPoints, dugPoints, growthPoints, farmPoints, reasons, pile == null ? null : pile.kind(),
                v2 ? new Ev(true, machineCount, spawnerCount, storageCount, redstoneOther, farmlandCount, portalCount, beaconCount, hall, kelpPts) : Ev.UNKNOWN);
        // one line per find (kind + every reason with its points) so a row nobody can explain is diagnosable from the leak log
        dev.vantage.core.LeakLog.add("FIND", x, y, z, "kind " + typeOfFind(f) + " label " + labelFor(f) + " score " + score + " build " + f.build() + " dug " + f.dug() + " growth " + f.growth() + " farm " + f.farm()
                + " hits " + hitSections.size() + " Y " + f.minY() + ".." + f.maxY() + " | " + reasons.stream().map(r -> "+" + r.points() + " " + r.text() + (r.count() > 1 ? " x" + r.count() : "")).collect(java.util.stream.Collectors.joining("; ")));
        if (v2) {
            Find was = fresh(key) ? best.get(key) : null; // a find from the other ore mode never outranks a fresh scan
            if (was != null && f.score() < was.score()) { // lower than before: the best stays, saying so
                Find shown = withEvidence(was, f); // the score stays the best one, the evidence is always the fresh scan's
                if (!shown.equals(was)) { best.put(key, shown); ledgerDirty = true; dirtyWhy = "evidence of " + key + " refreshed"; }
                finds.put(key, noted(shown, SEEN + ": best scan scored " + was.score() + ", the last one " + f.score()));
                lastScan.put(key, f); // the re-arm judges this fresh scan, not the kept best
                requeueGroup(key);
                holdPingFor(key, f); // a never announced find still gets its one ping, judged on the fresh scan (score, griefed, hidden, type)
                return;
            }
            if (!f.equals(was)) { ledgerDirty = true; dirtyWhy = was == null ? "new " + key + " mode " + strict + " fresh " + fresh(key) : "changed " + was.reasons() + " -> " + f.reasons(); } // an unchanged find is not a reason to rewrite the file
            best.put(key, f);
            bestMode.put(key, strict);
        }
        finds.put(key, f);
        if (v2) lastScan.put(key, f);
        requeueGroup(key);
        if (score >= threshold.i()) {
            // the whole case for the flag, every reason with its points, so the log says why and not just that
            String caseLine = f.reasons() == null ? "" : f.reasons().stream()
                    .map(r -> "+" + r.points() + " " + r.text() + (r.count() > 1 ? " x" + r.count() : "")).collect(java.util.stream.Collectors.joining("; "));
            dev.vantage.core.LeakLog.add("BASE", x, y, z, (v2 ? labelV2(key, f) : labelFor(f)) + " score " + score + " " + f.why() + (caseLine.isEmpty() ? "" : " | " + caseLine));
        }
        // suspicious is marked and listed, only a real find pings; v2 judges the number the card shows (own points + next-door bonus), at release
        if (v2 ? score + MAX_BONUS < threshold.i() : score < threshold.i()) { if (v2) cancelHold(key, "points"); return; }
        if (hidden(f)) { if (v2) cancelHold(key, "hidden"); return; } // Underground only: a surface base is logged and feeds the likely areas, nothing more
        if ((v2 ? griefOf(key, f) : f.grief()) != null && !pingGriefed.on()) { if (v2) cancelHold(key, "grief"); return; }
        if (!pingFor(v2 ? placeType(key, f) : typeOfFind(f))) { if (v2) cancelHold(key, "type"); return; } // v2: only the types that were asked for ping; the find itself is kept and drawn per its Show switch
        if (v2) { // v2: wait until the chunks around have been scanned (a bell next door may not have been seen yet), then ping if the find still stands
            armHold(key, f); // the latest scan is the judge
            return;
        }
        announce(key, f);
    }

    /** The scan-time gate for a held ping, for a find that stays as it was. */
    /** A scan that would not ping takes back a hold queued by an earlier scan of the same chunk (the chunk was re-scored, e.g. a bell seen later). */
    /** The best find with the evidence (counts and storage verdict) of the fresh scan: the evidence is never kept from an older scan. */
    private static Find withEvidence(Find was, Find fresh) {
        return new Find(was.x(), was.y(), was.z(), was.score(), was.why(), was.grief(), was.damage(), was.intact(), was.deep(), was.minY(), was.maxY(), was.sections(), was.allSections(),
                was.build(), was.dug(), was.growth(), fresh.farm(), was.reasons(), fresh.storage(), fresh.ev());
    }

    /**
     * v2: a scan that adds or changes a find re-opens the question for its place: every found chunk of the group that no ping has covered gets a fresh hold,
     * judged at release on the card's number. Still one ping per group (announce looks at the whole group).
     */
    private void requeueGroup(long key) {
        if (!v2()) return;
        List<Long> g = groupKeys(key);
        if (g.size() < 2) return;
        for (long k : g) if (pinged.contains(k)) return; // the place has had its ping
        for (long k : g) {
            if (k == key) continue;
            Find o = finds.get(k);
            if (o == null || !o.ev().known() || holdPing.containsKey(k)) continue; // an old-file find waits for its own scan
            // the same gates as holdPingFor: no hold for a chunk that releasePings would drop at once (a wreck with Ping wrecked bases off, a type that may not ping, too few points)
            if (o.score() + MAX_BONUS < threshold.i() || hidden(o) || griefOf(k, o) != null && !pingGriefed.on() || !pingFor(placeType(k, o))) continue;
            armHold(k, o);
        }
    }

    /**
     * The inputs a release verdict depends on. Equal before and after means a new hold would end the same way. The shown points enter only as "reaches Min score or not":
     * the verdict cannot tell 22 from 23, and a score that flickers under Min score (a moving piston changes the census) must not change the signature every rescan.
     */
    private String holdSig(long key, Find f) {
        boolean enough = shownPoints(key, f) >= threshold.i();
        String sig = enough + "|" + (griefOf(key, f) != null) + "|" + pingGriefed.on() + "|" + hidden(f) + "|" + threshold.i();
        // type, farm flag and the type switch only count when the points are enough: a chunk under Min score fails on its points whatever its type does, so a type that flips
        // between rescans must not arm a hold each time; a chunk that fails ONLY on the type gate (enough points) still gets a new hold when its type changes
        if (enough) { String type = placeType(key, f); sig += "|" + type + "|" + pingFor(type) + "|" + placeOf(key, f).farm(); }
        return sig;
    }

    /**
     * Starts (or keeps) the hold of a chunk that may ping. A chunk whose last hold ended without a ping gets no new one while the inputs of that verdict are
     * unchanged: rescans that change nothing (a site's block updates re-queue the same chunks every second) must not arm a hold again and again. Anything that
     * changes the verdict (points, grief, type, switch, group evidence) makes the signature differ and the hold starts as before, so the place still pings, once.
     */
    private void armHold(long key, Find f) {
        if (pinged.contains(key)) return;
        if (!holdPing.containsKey(key)) {
            if (holdSig(key, f).equals(noPing.get(key))) return;
            holdPing.put(key, System.currentTimeMillis());
        }
        holdJudge.put(key, f);
    }

    /**
     * The below-Y-0 bonus (items and animals) arrives without a scan. A chunk whose last hold ended without a ping because it was a few points short gets one new hold when
     * the bonus makes its card reach Min score. Gates tested by hand (never holdPingFor: it would cancel a running hold); at most one such re-arm per chunk per minute;
     * a place that has pinged, a chunk without a find and a chunk with a running hold are left alone.
     */
    private void bonusRearm(long now) {
        for (long key : belowZero.keySet()) {
            if (!noPing.containsKey(key) || holdPing.containsKey(key) || pinged.contains(key)) continue;
            if (finds.get(key) == null) continue;
            Find j = lastScan.get(key); // the latest scan of this chunk; none (a find that only came from the saved file) waits for its own scan
            if (j == null) continue;
            boolean handled = false;
            for (long k : groupKeys(key)) if (pinged.contains(k)) { handled = true; break; }
            if (handled) continue;
            Long last = bonusAt.get(key);
            if (last != null && now - last < 60_000) continue;
            if (j.score() + MAX_BONUS < threshold.i() || hidden(j) || griefOf(key, j) != null && !pingGriefed.on() || !pingFor(placeType(key, j)) || shownPoints(key, j) < threshold.i()) continue;
            if (bonusAt.size() > 512) bonusAt.clear();
            bonusAt.put(key, now);
            armHold(key, j);
            if (holdPing.containsKey(key)) bonusRearms++; // counted only when a hold really started
        }
    }

    /** The four gates (1 = the gate blocks: points, hidden, grief, type) with the shown points and Min score, as the line prints them. */
    private String gateInfo(long key, Find j) {
        int shown = shownPoints(key, j);
        boolean gp = shown < threshold.i(), gh = hidden(j), gg = griefOf(key, j) != null && !pingGriefed.on(), gt = !pingFor(placeType(key, j));
        return "gates " + (gp ? 1 : 0) + (gh ? 1 : 0) + (gg ? 1 : 0) + (gt ? 1 : 0) + " shown " + shown + " min " + threshold.i();
    }

    /**
     * Diagnostic (default off, -Dvantage.bfdiag=true): one leak log line per hold release or cancel: the rule that made the place a farm (or that it is not), per group chunk the fill
     * sections (count and Y sections), containers, machines and the Sorter flag, how long the hold lasted in ticks, how it ended, the size of `touched`. A distinct kind per phase
     * because LeakLog drops repeats of one kind and chunk for a minute. No names. It can never stop a ping.
     * `gates` is the text of gateInfo taken before the ping (announce changes what the place shows); null = take it now. A CANCEL line (`cause` = a fixed tag) has no chunk list.
     */
    private void diag(String phase, long key, Find j, String how, long heldMs, boolean timeLimit, String gates, String cause) {
        if (!BFDIAG) return;
        try {
            Place p = placeOf(key, j);
            StringBuilder sb = new StringBuilder();
            // the four gates, 1 = the gate blocks (points, hidden, grief, type), the shown points and Min score as numbers; "unknown" when the evidence is unknown
            sb.append("rule=").append(p.farm() ? p.why() : p.silenced() != null ? p.silenced() : !p.known() ? "unknown" : "not a farm").append("; ended=").append(how).append(timeLimit ? " (time limit)" : "")
                    .append(cause != null ? "; cause " + cause : "")
                    .append("; ").append(gates != null ? gates : gateInfo(key, j))
                    .append("; hold ").append(heldMs / 50).append(" ticks; touched ").append(touched.size());
            if (cause == null) { sb.append("; chunks"); appendChunks(sb, key, 900); } // a cancel line has no chunk list: a flood of them costs no fill-map walks
            dev.vantage.core.LeakLog.addDiag(phase, j.x(), j.y(), j.z(), sb.toString());
        } catch (Throwable t) { /* a diagnostic never stops a ping */ }
    }

    /** The chunks of the place, the chunk the line is about first; cut at `limit` characters with " +N more". Counts only, no names. */
    private void appendChunks(StringBuilder sb, long key, int limit) {
        List<Long> order = new ArrayList<>(); // only chunks that have a find: "+N more" counts those
        if (finds.get(key) != null) order.add(key);
        for (long k : groupKeys(key)) if (k != key && finds.get(k) != null) order.add(k);
        int shown = 0;
        for (long k : order) {
            Find f = finds.get(k);
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            String one = " [" + cx + "," + cz + " fill " + DeepLeaks.fillSectionsOf(cx, cz) + " cont " + f.ev().containers() + " mach " + f.ev().machines() + " sorter " + "Sorter".equals(f.storage()) + "]";
            if (sb.length() + one.length() > limit) { sb.append(" +").append(order.size() - shown).append(" more"); return; }
            sb.append(one);
            shown++;
        }
    }

    /** Leak-path ping (no hold): one diagnostic line with the label and percent, and the chunk fields when a find exists for the chunk. */
    private void diagLeak(long key, View v) {
        if (!BFDIAG) return;
        try {
            StringBuilder sb = new StringBuilder("leak ping: ").append(v.label()).append(' ').append(v.pct()).append("%; touched ").append(touched.size()).append("; chunks");
            Find own = finds.get(key);
            if (own != null) appendChunks(sb, key, 900);
            dev.vantage.core.LeakLog.addDiag("BFDIAG-LEAK", v.x(), v.y(), v.z(), sb.toString());
        } catch (Throwable t) { /* a diagnostic never stops a ping */ }
    }

    /** Ends a running hold. `cause` is a short fixed tag (lower case, no spaces) that the diagnostic line prints: stale, low, points, hidden, grief, type, recheck, rig. */
    private void cancelHold(long key, String cause) {
        if (BFDIAG) {
            try { // the scan that was judged, else the shown find
                Long since = holdPing.get(key);
                Find f = holdJudge.get(key);
                if (f == null) f = finds.get(key);
                if (since != null && f != null) diag("BFDIAG-CANCEL", key, f, "cancelled", System.currentTimeMillis() - since, false, null, cause);
            } catch (Throwable t) { /* a diagnostic never stops a cancel */ }
        }
        holdPing.remove(key);
        holdJudge.remove(key);
    }

    private void holdPingFor(long key, Find f) {
        if (f.score() + MAX_BONUS < threshold.i() || hidden(f) || griefOf(key, f) != null && !pingGriefed.on() || !pingFor(placeType(key, f))) { cancelHold(key, "recheck"); return; }
        armHold(key, f);
    }

    /** v2: pings that waited for the 3x3 around them to be scanned go out now, when none of the 8 neighbours (or the chunk) is still queued, or after PING_HOLD_MS. */
    private void releasePings() {
        if (holdPing.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (var it = holdPing.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            long key = e.getKey();
            boolean waiting = false;
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1 && !waiting; dx++) for (int dz = -1; dz <= 1; dz++) {
                long nk = ChunkPos.asLong(cx + dx, cz + dz);
                if (queued.contains(nk) || touched.contains(nk)) { waiting = true; break; }
            }
            if (waiting && now - e.getValue() < PING_HOLD_MS) continue;
            it.remove();
            Find f = finds.get(key);
            if (f == null) {
                holdJudge.remove(key);
                if (BFDIAG) try { dev.vantage.core.LeakLog.addDiag("BFDIAG-GONE", cx * 16 + 8, 0, cz * 16 + 8, "hold ended: its find is gone; hold " + (now - e.getValue()) / 50 + " ticks; touched " + touched.size()); } catch (Throwable t) { /* a diagnostic never stops a ping */ }
                continue;
            }
            // C10, on purpose: the fresh scan decides (a wrecked older best does not mute a place that is clean now, a wreck now is not pinged for an older clean best);
            // what gets announced is the shown find, whose own grief text is part of the chat line
            // one judge: the fresh scan that queued the ping (kept in holdJudge); the shown find (maybe an older best) is only what gets announced
            Find j = holdJudge.remove(key);
            if (j == null) j = f;
            // the judge is the fresh scan, the number is what the card shows now (neighbours scanned during the hold count), the type is the place's
            // the old order and the old short-circuit: points, hidden, grief, type; the first that fails stops the chain. `no` is null when the place pings.
            String no = shownPoints(key, j) < threshold.i() ? "under Min score" : hidden(j) ? "hidden" : griefOf(key, j) != null && !pingGriefed.on() ? "grief gate" : !pingFor(placeType(key, j)) ? "type gate" : null;
            if (no != null) {
                if (BFDIAG) diag(no.equals("under Min score") ? "BFDIAG-NO-POINTS" : no.equals("hidden") ? "BFDIAG-NO-HIDDEN" : no.equals("grief gate") ? "BFDIAG-NO-GRIEF" : "BFDIAG-NO-TYPE", key, j, no, now - e.getValue(), waiting, null, null);
                if (noPing.size() > 2048) { noPing.clear(); bonusAt.clear(); lastScan.clear(); } // bounded: forgetting only costs one more hold
                noPing.put(key, holdSig(key, j)); // no ping for these inputs: no new hold until one of them changes
                continue;
            }
            int pingsBefore = pings;
            String gatesBefore = null; // what the gates said when the ping was decided, not after announce; "gates n/a" if reading them failed: no diagnostic call may stop a ping
            if (BFDIAG) { try { gatesBefore = gateInfo(key, j); } catch (Throwable t) { gatesBefore = "gates n/a"; } }
            announce(key, f);
            if (BFDIAG) diag("BFDIAG-PING", key, j, pings > pingsBefore ? "ping" : "ping suppressed", now - e.getValue(), waiting, gatesBefore, null); // after announce: it says whether a ping really went out
        }
    }

    /** The ping of one real find: chat, sound, card toast, waypoint. Neighbouring chunks of the same base only ping once. */
    private void announce(long key, Find f) {
        if (!pinged.add(key)) return;
        boolean v2n = v2();
        if (v2n) { // one ping per place: any chunk of the group that was handled already
            for (long k : groupKeys(key)) if (k != key && pinged.contains(k)) return;
        } else {
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && pinged.contains(ChunkPos.asLong(cx + dx, cz + dz))) return;
            }
        }
        int x = f.x(), y = f.y(), z = f.z(), score = v2n ? shownPoints(key, f) : f.score();
        Place place = v2n ? placeOf(key, f) : null;
        String g = v2n ? griefOf(key, f) : f.grief(); // locked or v2 off: exactly the find's own grief text, as in round 5
        boolean farm = place != null && place.farm() && g == null;
        String title = v2n ? labelV2(key, f) : labelFor(f);
        pings++;
        if (v2n && f.score() < threshold.i()) { // no BASE line was logged at scan time: the card's number is what reached Min score
            dev.vantage.core.LeakLog.add("BASE", x, y, z, title + " score " + score + " " + f.why() + (farm ? " | " + place.why() : ""));
        }
        if (v2n) { // rig mirror, v2 only, at most ANNOUNCE_LOG_MAX entries (oldest out)
            lastAnnounce = (farm ? (f.deep() ? "UNDERGROUND FARM" : "FARM") : f.deep() ? "UNDERGROUND BASE" : "BASE") + " | " + title + " " + score + " | " + (farm ? place.why() : f.why());
            announceLog.put(key, lastAnnounce);
            while (announceLog.size() > ANNOUNCE_LOG_MAX) announceLog.remove(announceLog.keySet().iterator().next());
        }
        dev.vantage.module.cosmetic.FindEvents.marked("Base", title + " " + score, x, y, z);
        if (notify.on()) {
            int dist = (int) Math.sqrt(mc.player.distanceToSqr(x, y, z));
            Chat.alert(Component.literal(farm ? (f.deep() ? "UNDERGROUND FARM " : "FARM ") : f.deep() ? "UNDERGROUND BASE " : "BASE ").withColor(color.argb()).append(Component.literal(
                    "score " + score + "  " + Chat.coords(x, y, z) + "  " + dist + "m  " + (farm ? place.why() : f.why())
                            + (g == null ? "" : "  GRIEFED: " + g)).withColor(Theme.TEXT)), 1.2f);
        }
        if (waypoint.on()) Waypoints.get().add((farm ? "Farm " : "Base ") + score, x, y, z, color.argb());
    }

    @Override
    public String info() {
        return views.isEmpty() ? null : String.valueOf(views.size());
    }

    @Override
    public void onRender3D(R3 r) {
        updateFocus();
        if (columns.on()) drawColumns(r);
        if (!show.on()) return;
        if (declutter()) { drawSites(r); return; }
        for (BaseLogic.Zone zn : zoneList) {
            double rad = zn.range();
            for (int i = 0; i < 48; i++) {
                double a = i * Math.PI / 24, b = (i + 1) * Math.PI / 24;
                r.line(zn.x() + Math.cos(a) * rad, zn.y(), zn.z() + Math.sin(a) * rad, zn.x() + Math.cos(b) * rad, zn.y(), zn.z() + Math.sin(b) * rad, Ui.TEAL | 0xFF000000, EspStyle.WEIGHT, true);
            }
            r.label(new Vec3(zn.x(), zn.y() + 3, zn.z()), "Likely base area " + zn.pct() + "%", Ui.TEAL);
        }
        for (View v : views) {
            double x0 = v.cx() << 4, z0 = v.cz() << 4, y = v.y() + 0.05;
            int c = v.color() & 0xFFFFFF;
            boolean hot = hovered != null && hovered.cx() == v.cx() && hovered.cz() == v.cz();
            EspStyle.zone(r, x0, z0, x0 + 16, z0 + 16, y, c | 0xFF000000, true, false, true);
            r.flat(x0, z0, x0 + 16, z0 + 16, y, EspStyle.fill(c, true, hot ? 2f : 1f), true); // hovering doubles the fill
            double cx = x0 + 8, cz = z0 + 8, d = 0.45;
            r.flat(cx - d, cz - d, cx + d, cz + d, y, c | 0xFF000000, true);
            if (hot) for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8, b = (i + 1) * Math.PI / 8;
                r.line(cx + Math.cos(a) * 1.7, y, cz + Math.sin(a) * 1.7, cx + Math.cos(b) * 1.7, y, cz + Math.sin(b) * 1.7, 0xFFFFFFFF, EspStyle.WEIGHT, true);
            }
        }
    }

    private void drawColumns(R3 r) {
        int minY = mc.level.getMinY(), maxY = mc.level.getMaxY();
        for (Find f : real()) {
            String g = v2() ? griefOf(ChunkPos.asLong(f.x() >> 4, f.z() >> 4), f) : f.grief(); // v2 only: locked columns stay as they were
            int c = g == null ? color.argb() : GRIEF_COLOR;
            double cx = (f.x() >> 4 << 4) + 8, cz = (f.z() >> 4 << 4) + 8;
            r.column(new AABB(cx - 8, minY, cz - 8, cx + 8, maxY, cz + 8), c | 0xFF000000, EspStyle.WEIGHT, 0, cx, cz);
            Vec3 lp = new Vec3(f.x() + 0.5, f.y() + 2, f.z() + 0.5);
            if (lp.distanceTo(r.cam) < 512) r.label(lp, (g != null ? "GRIEFED " : f.deep() ? "UNDERGROUND " : "BASE ") + f.score(), c);
        }
    }

    /** The chunk whose dot the crosshair is on. */
    private View aimed() {
        var cam = mc.gameRenderer.getMainCamera();
        Vec3 eye = cam.position();
        var fwd = cam.forwardVector();
        View best = null;
        double bestMiss = Double.MAX_VALUE;
        for (View v : views) {
            double dx = (v.cx() << 4) + 8 - eye.x, dy = v.y() - eye.y, dz = (v.cz() << 4) + 8 - eye.z;
            double along = dx * fwd.x() + dy * fwd.y() + dz * fwd.z();
            if (along <= 0) continue;
            double px = dx - fwd.x() * along, py = dy - fwd.y() * along, pz = dz - fwd.z() * along;
            double miss = Math.sqrt(px * px + py * py + pz * pz) / along; // about the angle in radians
            if (miss < bestMiss) { bestMiss = miss; best = v; }
        }
        return bestMiss < 0.035 ? best : null;
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        hovered = hover.on() && !Hud.hidden() ? aimed() : null;
        lastCardRect = null;
        if (declutter()) {
            if (!Hud.hidden()) submitCallouts();
            if (panel.on()) {
                if (sites.isEmpty()) drawEmpty(g);
                else drawSitePanel(g);
            }
            if (livePlayers.on() && !Hud.hidden()) drawTags(g);
            return;
        }
        Ui.begin(g);
        if (hovered != null) drawCard(g, hovered);
        Ui.end(g);
        if (panel.on()) {
            if (views.isEmpty()) drawEmpty(g);
            else drawPanel(g);
        }
        if (livePlayers.on() && !Hud.hidden()) drawTags(g);
    }

    private void drawEmpty(GuiGraphics g) {
        final float w = 325, h = 62;
        float s = Ui.scale();
        float[] at = Hud.slot("basefinder", "Top Left", w, h);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        Ui.panel(g, x, y, w, h);
        Ui.text(g, "BaseFinder", x + 9, y + 7, Ui.TITLE, 1.1f, false);
        String tag = "Scanning";
        float pw = Ui.width(tag, 0.95f, false) + 12;
        Ui.rr(g, x + w - pw - 9, y + 6, pw, 16, 4, 0x30808CFF, 0);
        Ui.text(g, tag, x + w - pw - 3, y + 10, Ui.TITLE, 0.95f, false);
        Ui.text(g, "Looking for strong signals", x + 9, y + 30, Ui.TEXT, 0.95f, false);
        Ui.text(g, "Findings appear here as you explore.", x + 9, y + 45, Ui.SUB, 0.87f, false);
        Ui.end(g);
    }

    /** Name, distance, health and what's in their hand, on anyone under the ground. */
    private void drawTags(GuiGraphics g) {
        Ui.begin(g);
        for (var p : mc.level.players()) {
            if (p == mc.player || dev.vantage.core.Friends.is(p) || mc.level.dimensionType().hasCeiling() || surfaceAt(p.getBlockX(), p.getBlockZ()) - p.getBlockY() < LIVE_DEPTH) continue;
            float[] at = Ui.project(p.position().add(0, p.getBbHeight() + 0.4, 0));
            if (at == null) continue;
            String name = dev.vantage.core.Streamer.name(p.getName().getString()), dist = (int) p.distanceTo(mc.player) + "m";
            float w = Math.max(70, Ui.width(name, 0.95f, false) + Ui.width(dist, 0.75f, false) + 24), h = 26;
            float x = at[0] - w / 2, y = at[1] - h;
            dev.vantage.core.Callouts.reserve(x, y - 18, w, h + 18);
            Ui.rr(g, x, y, w, h, 5, Ui.GLASS, Ui.BORDER);
            Ui.text(g, name, x + 8, y + 6, Ui.TEXT, 0.95f, false);
            Ui.right(g, dist, x + w - 8, y + 7, Ui.SUB, 0.75f, false);
            float hp = Math.max(0, Math.min(1, p.getHealth() / p.getMaxHealth()));
            Ui.rect(g, x + 8, y + 19, w - 16, 2, 0x30FFFFFF);
            Ui.rect(g, x + 8, y + 19, (w - 16) * hp, 2, Ui.ACCENT);
            var held = p.getMainHandItem();
            if (!held.isEmpty()) {
                float size = 16 / Ui.scale() * 0.9f;
                g.pose().pushMatrix();
                g.pose().translate(at[0] - size / 2, y - size - 3);
                g.pose().scale(size / 16, size / 16);
                g.renderItem(held, 0, 0);
                g.pose().popMatrix();
            }
        }
        Ui.end(g);
    }

    private void drawPanel(GuiGraphics g) {
        final float w = 325, row = 36.5f;
        int rows = Math.min(5, views.size());
        boolean more = views.size() > rows;
        float h = 34 + rows * row + (more ? 16 : 2);
        float s = Ui.scale();
        float[] at = Hud.slot("basefinder", "Top Left", w, h);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        Ui.panel(g, x, y, w, h);
        Ui.text(g, "BaseFinder", x + 9, y + 7, Ui.TITLE, 1.1f, false);
        String near = views.size() + " nearby";
        float pw = Ui.width(near, 0.95f, false) + 12;
        Ui.rr(g, x + w - pw - 9, y + 6, pw, 16, 4, 0x30808CFF, 0);
        Ui.text(g, near, x + w - pw - 3, y + 10, Ui.TITLE, 0.95f, false);
        for (int i = 0; i < rows; i++) {
            View v = views.get(i);
            float ry = y + 30 + i * row;
            if (i == 0 || v.pct() >= 99 && i < 2) {
                Ui.rr(g, x + 6, ry, w - 12, row - 4, 4, 0x18FFFFFF, 0);
                Ui.rect(g, x + 7, ry + 4, 1.5f, row - 12, v.color());
            }
            Ui.text(g, String.valueOf(i + 1), x + 13, ry + 5, i == 0 ? v.color() : Ui.FAINT, 0.85f, false);
            Ui.text(g, v.label(), x + 30, ry + 5, Ui.TEXT, 1f, false);
            Ui.right(g, v.pct() + "%", x + w - 10, ry + 5, v.color(), 1f, false);
            String line = v.dir() + " " + v.dist() + "m  /  " + v.yRange() + "  /  " + v.sections();
            Ui.text(g, line, x + 30, ry + 17, Ui.SUB, 0.87f, false);
            float bw = w - 42;
            Ui.rect(g, x + 30, ry + 28, bw, 1, 0x30FFFFFF);
            Ui.rect(g, x + 30, ry + 27.5f, bw * v.pct() / 100f, 1.5f, v.color());
        }
        if (more) {
            Ui.hline(g, x + 9, y + h - 21, w - 18, Ui.LINE);
            Ui.text(g, "+" + (views.size() - rows) + " more nearby", x + 9, y + h - 14, Ui.FAINT, 0.85f, false);
        }
        Ui.end(g);
    }

    private void drawCard(GuiGraphics g, View v) {
        float w = 302;
        int lines = Math.min(6, v.reasons().size());
        float h = 56 + lines * 16.4f + (v.foot().isEmpty() ? 0 : 20) + 22;
        float[] at = Ui.project(new Vec3((v.cx() << 4) + 8, v.y(), (v.cz() << 4) + 8));
        float x = at == null ? Ui.W() / 2 + 20 : at[0] + 20, y = at == null ? Ui.H() / 2 - 40 : at[1] - 60;
        x = Math.max(8, Math.min(x, Ui.W() - w - 8));
        y = Math.max(8, Math.min(y, Ui.H() - h - 40));
        lastCardRect = new float[] {x, y, w, h};
        Ui.rr(g, x, y, w, h, 7, Ui.GLASS, Ui.BORDER);
        Ui.text(g, v.label(), x + 9, y + 8, Ui.TITLE, 1.1f, false);
        String pct = v.pct() + "%";
        float pw = Ui.width(pct, 1f, false) + 10;
        Ui.rr(g, x + w - pw - 9, y + 6, pw, 16, 4, 0x18FFFFFF, 0);
        Ui.text(g, pct, x + w - pw - 4, y + 10, v.color(), 1f, false);
        Ui.text(g, v.dir() + "  " + v.dist() + "m", x + 9, y + 30, Ui.SUB, 0.9f, false);
        Ui.right(g, v.yRange() + "  " + v.sections(), x + w - 9, y + 30, Ui.SUB, 0.9f, false);
        Ui.hline(g, x + 9, y + 45, w - 18, Ui.LINE);
        float ly = y + 53;
        for (int i = 0; i < lines; i++) {
            Reason r = v.reasons().get(i);
            Ui.rr(g, x + 11, ly + 2.5f, 3, 3, 1.5f, v.color(), 0);
            String t = "+" + r.points() + "  " + r.text() + (r.count() > 0 ? " " + r.count() : "");
            Ui.text(g, Ui.fit(t, w - 42, 0.95f), x + 24, ly, Ui.TEXT, 0.95f, false);
            ly += 16.4f;
        }
        if (!v.foot().isEmpty()) {
            Ui.text(g, Ui.fit(v.foot(), w - 18, 0.87f), x + 9, ly + 4, Ui.FAINT, 0.87f, false);
            ly += 20;
        }
        Ui.hline(g, x + 9, ly + 2, w - 18, Ui.LINE);
        Ui.text(g, v.points() + " points  confidence " + v.conf(), x + 9, ly + 9, Ui.SUB, 0.87f, false);
    }

    // ---- Declutter: one card, small tags, edge arrows, one footprint per base ---------------------------------------

    /** How much a base deserves the screen: sure and live first, wrecked, guessed and far ones last. */
    private static double priority(BaseSites.Site s) {
        return s.pct() + (s.live() ? 30 : 0) - (s.griefed() ? 45 : 0) - (s.zone() ? 20 : 0) - Math.min(s.dist(), 256) / 8.0;
    }

    /**
     * Close enough for a tag, or too important to leave out. Uses the same rule as the edge arrows, so a base that has an arrow
     * while it is off screen still gets a tag when you turn to look at it.
     */
    public boolean eligible(BaseSites.Site s) {
        if (s.dist() > SHOW_RANGE) return false;
        return s.dist() <= tagRange.get() || s.pct() >= 90 || s.live() || (s.pct() >= 50 && !s.griefed() && s.dist() <= 256);
    }

    private static Vec3 anchor(BaseSites.Site s) {
        return new Vec3(s.x(), s.y(), s.z());
    }

    /** About the angle in radians between the view ray and a point, or MAX_VALUE when it is behind. */
    private static double miss(Vec3 eye, org.joml.Vector3fc fwd, double x, double y, double z) {
        double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
        double along = dx * fwd.x() + dy * fwd.y() + dz * fwd.z();
        if (along <= 0) return Double.MAX_VALUE;
        double px = dx - fwd.x() * along, py = dy - fwd.y() * along, pz = dz - fwd.z() * along;
        return Math.sqrt(px * px + py * py + pz * pz) / along;
    }

    private BaseSites.Site siteOf(View v) {
        if (v.label().equals("Likely area")) {
            for (BaseSites.Site s : sites) if (s.zone() && !s.views().isEmpty() && s.views().get(0).cx() == v.cx() && s.views().get(0).cz() == v.cz()) return s;
            return null;
        }
        return chunkSite.get(ChunkPos.asLong(v.cx(), v.cz()));
    }

    /** Once per frame, before anything draws: which base gets the card, which get tags, which get edge arrows. */
    /** v2 card rule for the surest base on screen when you are not aiming at anything: only for new finds, or always, or never (When I look). v1: the Auto card switch. */
    private int autoForm(BaseSites.Site sel, long now) {
        if (!autoCard.on()) return -1;
        if (!v2() || sel == null || cardMode.is("Always")) return dev.vantage.core.Callouts.BRIEF;
        if (cardMode.is("When I look")) return -1;
        Long until = cardUntil.get(sel.id());
        return until != null && now < until ? dev.vantage.core.Callouts.BRIEF : -1;
    }

    /** v2: which bases are new since the world loaded; each gets a card for Card seconds. Bases that appear in the first seconds (a ledger load, the first scan) are old news. */
    private void trackNewSites(long now) {
        for (BaseSites.Site s : sites) {
            if (s.zone() || !knownSites.add(s.id())) continue;
            if (now > quietUntil) cardPending.add(s.id()); // the timer starts on the first frame it is on screen (updateFocus)
        }
    }

    private final Map<Integer, Long> cardUntil = new HashMap<>();
    private final Set<Integer> cardPending = new HashSet<>();
    private final Set<Integer> knownSites = new HashSet<>();
    private long quietUntil = System.currentTimeMillis() + 4000;

    /** Rig: what the new-find card rule has recorded. */
    public boolean cardActive(int siteId) {
        Long u = cardUntil.get(siteId);
        return u != null && System.currentTimeMillis() < u;
    }

    private void updateFocus() {
        if (!declutter()) {
            focus = null;
            tagSites = List.of();
            arrowSites = List.of();
            tagsCut = 0;
            onScreen = new HashSet<>();
            return;
        }
        long now = System.currentTimeMillis();
        float W = Ui.W(), H = Ui.H();
        Set<Integer> nowOn = new HashSet<>();
        for (BaseSites.Site s : sites) {
            float[] p = Ui.project(anchor(s));
            if (p == null) continue;
            // comes on 24 in from the edge, goes off only once it really leaves, so a base at the edge does not flicker
            boolean in = onScreen.contains(s.id()) ? p[0] >= 0 && p[0] <= W && p[1] >= 0 && p[1] <= H
                    : p[0] >= 24 && p[0] <= W - 24 && p[1] >= 24 && p[1] <= H - 24;
            if (in) nowOn.add(s.id());
        }
        onScreen = nowOn;
        var cam = mc.gameRenderer.getMainCamera();
        BaseSites.Site aimedSite = null;
        if (hover.on() && !Hud.hidden()) {
            View v = aimed();
            if (v != null) aimedSite = siteOf(v);
            if (aimedSite == null) {
                Vec3 eye = cam.position();
                var fwd = cam.forwardVector();
                double best = 0.035;
                for (BaseSites.Site s : sites) {
                    double m = miss(eye, fwd, s.x(), s.y(), s.z());
                    for (var grp : s.groups()) {
                        Vec3 c = grp.box().getCenter();
                        m = Math.min(m, miss(eye, fwd, c.x, c.y, c.z));
                    }
                    if (m < best) { best = m; aimedSite = s; }
                }
            }
        }
        BaseSites.Site sel;
        int form;
        BaseSites.Site held = heldId < 0 ? null : site(heldId);
        if (aimedSite != null) {
            sel = aimedSite;
            form = dev.vantage.core.Callouts.FULL;
            cardPending.remove(sel.id()); // it has had its full card: no short card for it later
            cardUntil.remove(sel.id()); // a short card that already started (a frame ran before the aim arrived) ends too: the full card replaces it
            heldId = autoId = sel.id();
            holdUntil = now + (v2() ? 1500 : 600); // v2: a wobbling aim keeps the card a little longer instead of flickering it
        } else if (held != null && now < holdUntil && onScreen.contains(held.id())) {
            sel = held;
            form = dev.vantage.core.Callouts.FULL;
            cardPending.remove(sel.id());
            cardUntil.remove(sel.id());
        } else {
            heldId = -1;
            BaseSites.Site cur = autoId < 0 ? null : site(autoId), best = null;
            for (BaseSites.Site s : sites) if (onScreen.contains(s.id()) && eligible(s) && (best == null || priority(s) > priority(best))) best = s;
            boolean curOk = cur != null && onScreen.contains(cur.id()) && eligible(cur);
            sel = !curOk ? best : best != null && best != cur && priority(best) >= priority(cur) + 10 ? best : cur;
            autoId = sel == null ? -1 : sel.id();
            if (v2() && sel != null && cardPending.remove(sel.id())) cardUntil.put(sel.id(), now + cardSeconds.i() * 1000L); // the timer starts for the site that really gets the card; the others wait their turn
            form = autoForm(sel, now);
        }
        focus = sel == null ? null : new Focus(sel.id(), form);
        List<BaseSites.Site> others = new ArrayList<>();
        for (BaseSites.Site s : sites) if (s != sel && onScreen.contains(s.id()) && eligible(s)) others.add(s);
        if (v2() && form < 0 && sel != null && onScreen.contains(sel.id())) others.add(sel); // v2: the surest base without its card keeps its small tag
        others.sort((a, b) -> Double.compare(priority(b), priority(a)));
        int cap = v2() ? Math.max(maxTags.i(), 6) : maxTags.i(); // v2: every drawn base on screen gets its tag, not just the nearest three
        tagSites = others.size() > cap ? List.copyOf(others.subList(0, cap)) : others;
        tagsCut = Math.max(0, others.size() - cap);
        arrowDir.clear();
        if (!edgeArrows.on()) { arrowSites = List.of(); return; }
        List<BaseSites.Site> arrows = new ArrayList<>();
        for (BaseSites.Site s : sites) {
            if (onScreen.contains(s.id()) || s.pct() < 50 || s.griefed()) continue;
            if (s.dist() > (s.pct() >= 90 || s.live() ? 512 : 256)) continue;
            arrows.add(s);
        }
        arrows.sort((a, b) -> Double.compare(priority(b), priority(a)));
        if (arrows.size() > 3) arrows = new ArrayList<>(arrows.subList(0, 3));
        Vec3 eye = cam.position();
        var left = cam.leftVector();
        var up = cam.upVector();
        for (BaseSites.Site s : arrows) {
            double dx = s.x() - eye.x, dy = s.y() - eye.y, dz = s.z() - eye.z;
            arrowDir.put(s.id(), new float[] {(float) -(dx * left.x() + dy * left.y() + dz * left.z()), (float) -(dx * up.x() + dy * up.y() + dz * up.z())});
        }
        arrowSites = arrows;
    }

    private void ring(R3 r, double x, double y, double z, double rad, int segments, int color, float width) {
        for (int i = 0; i < segments; i++) {
            double a = i * 2 * Math.PI / segments, b = (i + 1) * 2 * Math.PI / segments;
            r.line(x + Math.cos(a) * rad, y, z + Math.sin(a) * rad, x + Math.cos(b) * rad, y, z + Math.sin(b) * rad, color, width, true);
        }
    }

    /** Declutter's world marks: one outline round each base's chunks, one dot, a small blue ring where someone is right now. */
    /** Plain words for what a base is, from its strongest view, with how sure: "farm 72%", "storage 45%", "player sounds 30%". */
    public String siteTag(BaseSites.Site s) {
        View top = null;
        for (View v : s.views()) if (top == null || v.points() > top.points()) top = v;
        String t = top == null ? T_SIGNS : top.type();
        String word = switch (t) { case T_FARM -> "farm"; case T_STORAGE -> "storage"; case T_MINE -> "mine"; case T_SOUNDS -> "player sounds"; case T_ZONE -> "likely area"; default -> "growth signs"; };
        if (s.griefed()) word = "wrecked " + word;
        return word + " " + s.pct() + "%";
    }

    private void drawSites(R3 r) {
        long now = System.currentTimeMillis();
        Focus f = focus;
        for (BaseSites.Site s : sites) {
            if (s.zone()) { ring(r, s.x(), s.y(), s.z(), s.zoneRange(), 48, Ui.TEAL | 0xFF000000, EspStyle.WEIGHT); continue; }
            if (s.dist() > SHOW_RANGE) continue;
            boolean foc = f != null && f.siteId() == s.id();
            int c = s.color() & 0xFFFFFF;
            double y = s.y() + 0.05;
            int lc = c | (foc ? 0xFF000000 : 0x90000000); // the focused base is the solid one
            float lw = EspStyle.WEIGHT;
            if (v2()) { // strength is the line, not another colour: a real find (Min score or more) is drawn heavy and solid, a suspicious one thin and faint
                boolean real = s.points() >= threshold.i();
                lw = EspStyle.WEIGHT * (real ? 1.8f : 0.8f);
                lc = c | (foc || real ? 0xFF000000 : 0x60000000);
            }
            Set<Long> in = s.chunks();
            for (long k : in) {
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                double x0 = cx << 4, z0 = cz << 4;
                if (!in.contains(ChunkPos.asLong(cx, cz - 1))) r.line(x0, y, z0, x0 + 16, y, z0, lc, lw, true);
                if (!in.contains(ChunkPos.asLong(cx + 1, cz))) r.line(x0 + 16, y, z0, x0 + 16, y, z0 + 16, lc, lw, true);
                if (!in.contains(ChunkPos.asLong(cx, cz + 1))) r.line(x0, y, z0 + 16, x0 + 16, y, z0 + 16, lc, lw, true);
                if (!in.contains(ChunkPos.asLong(cx - 1, cz))) r.line(x0, y, z0, x0, y, z0 + 16, lc, lw, true);
                if (foc) r.flat(x0, z0, x0 + 16, z0 + 16, y, EspStyle.fill(c, true, 1f), true);
            }
            r.flat(s.x() - 0.45, s.z() - 0.45, s.x() + 0.45, s.z() + 0.45, y, c | 0xFF000000, true);
            if (foc && f.form() == dev.vantage.core.Callouts.FULL) ring(r, s.x(), y, s.z(), 1.7, 16, 0xFFFFFFFF, EspStyle.WEIGHT);
            for (Live l : live.values()) {
                if (now - l.last >= LIVE_RECENT_MS || !in.contains(ChunkPos.asLong(l.x >> 4, l.z >> 4))) continue;
                ring(r, l.x + 0.5, l.y, l.z + 0.5, 2, 16, Ui.BLUE | 0xFF000000, EspStyle.WEIGHT);
            }
        }
    }

    private static final float CARD_W = 300, BRIEF_H = 118, ROW = 16.4f;

    private float fullHeight(BaseSites.Site s) {
        return 84 + Math.min(cardReasons.i(), s.reasons().size()) * ROW + 26;
    }

    private static String pillText(BaseSites.Site s) {
        BaseFinder bf = dev.vantage.core.Modules.get(BaseFinder.class);
        if (bf != null && bf.v2()) return bf.siteTag(s) + " · " + s.dist() + "m"; // v2: plain words for what it is
        return s.label() + " " + s.pctText() + " · " + s.dist() + "m";
    }

    private static float pillWidth(BaseSites.Site s) {
        return Math.min(220, Ui.width(pillText(s), 0.9f, false) + 26);
    }

    /** Hands the card, the tags and the arrows to Callouts, which places them all at once after every module has drawn. */
    private void submitCallouts() {
        Focus f = focus;
        BaseSites.Site fs = f == null ? null : site(f.siteId());
        if (fs != null && f.form() >= 0) {
            float[] at = Ui.project(anchor(fs));
            if (at != null) {
                List<dev.vantage.core.Callouts.Form> forms = new ArrayList<>();
                if (f.form() == dev.vantage.core.Callouts.FULL) forms.add(new dev.vantage.core.Callouts.Form(dev.vantage.core.Callouts.FULL, CARD_W, fullHeight(fs)));
                forms.add(new dev.vantage.core.Callouts.Form(dev.vantage.core.Callouts.BRIEF, CARD_W, BRIEF_H));
                forms.add(new dev.vantage.core.Callouts.Form(dev.vantage.core.Callouts.PILL, pillWidth(fs), 20));
                dev.vantage.core.Callouts.submit("site:" + fs.id(), at[0], at[1], 1000, fs.color(), forms, (g, kind, x, y, w, h) -> drawSite(g, fs, kind, x, y, w, h));
            }
        }
        for (BaseSites.Site s : tagSites) {
            float[] at = Ui.project(anchor(s));
            if (at == null) continue;
            dev.vantage.core.Callouts.submit("site:" + s.id(), at[0], at[1], (float) (500 + priority(s)), s.color(),
                    List.of(new dev.vantage.core.Callouts.Form(dev.vantage.core.Callouts.PILL, pillWidth(s), 20)), (g, kind, x, y, w, h) -> drawSite(g, s, kind, x, y, w, h));
        }
        for (BaseSites.Site s : arrowSites) {
            float[] d = arrowDir.get(s.id());
            if (d == null) continue;
            dev.vantage.core.Callouts.edge("arrow:" + s.id(), d[0], d[1], (float) priority(s), s.color(), s.pctText() + " · " + s.dist() + "m");
        }
    }

    private static int stateColor(BaseSites.Site s) {
        return switch (s.state()) { case "Griefed" -> Ui.GREY; case "Active" -> s.live() ? Ui.BLUE : Ui.GREEN; case "Likely intact" -> Ui.GREEN; default -> Ui.SUB; };
    }

    private static View strongest(BaseSites.Site s) {
        View top = null;
        for (View v : s.views()) if (top == null || v.pct() > top.pct() || v.pct() == top.pct() && v.points() > top.points()) top = v;
        return top;
    }

    private static String reasonLine(Reason r) {
        if (r.points() == 0 && r.text().startsWith("inside a likely area")) return "·  " + r.text();
        return "+" + r.points() + "  " + r.text() + (r.count() > 0 ? " " + r.count() : "");
    }

    /** One base as a card (full or short) or a one-line tag, in the same glass look as the old hover card. */
    private void drawSite(GuiGraphics g, BaseSites.Site s, int kind, float x, float y, float w, float h) {
        int col = s.griefed() ? Ui.GREY : s.color();
        if (kind == dev.vantage.core.Callouts.PILL) {
            Ui.rr(g, x, y, w, h, 5, Ui.GLASS, Ui.BORDER);
            Ui.rr(g, x + 8, y + 7, 6, 6, 3, col, 0);
            Ui.text(g, Ui.fit(pillText(s), w - 26, 0.9f), x + 19, y + 6, s.griefed() ? Ui.GREY : Ui.TEXT, 0.9f, false);
            return;
        }
        Ui.shadow(g, x, y, w, h, 7);
        Ui.rr(g, x, y, w, h, 7, Ui.GLASS, Ui.BORDER);
        Ui.text(g, s.label(), x + 9, y + 8, Ui.TITLE, 1.1f, false);
        float lw = Ui.width(s.label(), 1.1f, false);
        String pct = s.pctText();
        float pw = Ui.width(pct, 1f, false) + 10;
        Ui.rr(g, x + 9 + lw + 7, y + 6, pw, 16, 4, Ui.fade(col, 0.16f), 0);
        Ui.text(g, pct, x + 9 + lw + 12, y + 10, col, 1f, false);
        Ui.right(g, s.dist() + "m", x + w - 9, y + 10, Ui.SUB, 0.9f, false);
        Ui.text(g, Ui.fit(s.subtitle(), w - 18, 0.9f), x + 9, y + 28, Ui.SUB, 0.9f, false);
        String st = s.state();
        float sw = Ui.width(st, 0.9f, false);
        Ui.text(g, st, x + 9, y + 44, stateColor(s), 0.9f, false);
        if (!s.condition().isEmpty()) Ui.text(g, Ui.fit(" · " + s.condition(), w - 18 - sw, 0.9f), x + 9 + sw, y + 44, Ui.SUB, 0.9f, false);
        boolean full = kind == dev.vantage.core.Callouts.FULL;
        float ly;
        if (full) {
            View top = strongest(s);
            String row4 = !s.groups().isEmpty() ? s.stats() : top != null ? top.sections() : "";
            Ui.text(g, Ui.fit(row4, w - 18, 0.87f), x + 9, y + 60, Ui.SUB, 0.87f, false);
            Ui.hline(g, x + 9, y + 76, w - 18, Ui.LINE);
            ly = y + 84;
        } else {
            Ui.hline(g, x + 9, y + 60, w - 18, Ui.LINE);
            ly = y + 66;
        }
        int lines = Math.min(full ? cardReasons.i() : 2, s.reasons().size());
        for (int i = 0; i < lines; i++) {
            Reason r = s.reasons().get(i);
            Ui.rr(g, x + 11, ly + 2.5f, 3, 3, 1.5f, col, 0);
            Ui.text(g, Ui.fit(reasonLine(r), w - 42, 0.95f), x + 24, ly, Ui.TEXT, 0.95f, false);
            ly += ROW;
        }
        if (full) {
            lastCardReasons = lines;
            Ui.hline(g, x + 9, ly + 2, w - 18, Ui.LINE);
            Ui.text(g, s.points() + " points · confidence " + s.conf() + (tagsCut > 0 ? "  ·  +" + tagsCut + " more" : ""), x + 9, ly + 9, Ui.SUB, 0.87f, false);
        } else {
            Ui.text(g, "look at the dot for details", x + 9, y + h - 15, Ui.FAINT, 0.8f, false);
        }
    }

    /** The panel with one row per base instead of one per chunk. */
    private void drawSitePanel(GuiGraphics g) {
        final float w = 325, row = 36.5f;
        int rows = Math.min(5, sites.size());
        boolean more = sites.size() > rows;
        float h = 34 + rows * row + (more ? 16 : 2);
        float[] at = Hud.slot("basefinder", "Top Left", w, h);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        Ui.panel(g, x, y, w, h);
        Ui.text(g, "BaseFinder", x + 9, y + 7, Ui.TITLE, 1.1f, false);
        String near = sites.size() + (sites.size() == 1 ? " place" : " places");
        float pw = Ui.width(near, 0.95f, false) + 12;
        Ui.rr(g, x + w - pw - 9, y + 6, pw, 16, 4, 0x30808CFF, 0);
        Ui.text(g, near, x + w - pw - 3, y + 10, Ui.TITLE, 0.95f, false);
        for (int i = 0; i < rows; i++) {
            BaseSites.Site s = sites.get(i);
            float ry = y + 30 + i * row;
            if (i == 0 || s.pct() >= 99 && i < 2) {
                Ui.rr(g, x + 6, ry, w - 12, row - 4, 4, 0x18FFFFFF, 0);
                Ui.rect(g, x + 7, ry + 4, 1.5f, row - 12, s.color());
            }
            Ui.text(g, String.valueOf(i + 1), x + 13, ry + 5, i == 0 ? s.color() : Ui.FAINT, 0.85f, false);
            Ui.text(g, s.label(), x + 30, ry + 5, Ui.TEXT, 1f, false);
            Ui.right(g, s.pctText(), x + w - 10, ry + 5, s.color(), 1f, false);
            Ui.text(g, Ui.fit(s.subtitle(), w - 42, 0.87f), x + 30, ry + 17, Ui.SUB, 0.87f, false);
            float bw = w - 42;
            Ui.rect(g, x + 30, ry + 28, bw, 1, 0x30FFFFFF);
            Ui.rect(g, x + 30, ry + 27.5f, bw * s.pct() / 100f, 1.5f, s.color());
        }
        if (more) {
            Ui.hline(g, x + 9, y + h - 21, w - 18, Ui.LINE);
            Ui.text(g, "+" + (sites.size() - rows) + " more places", x + 9, y + h - 14, Ui.FAINT, 0.85f, false);
        }
        Ui.end(g);
    }

    @Override
    public int labelClass() {
        return LABEL_SUMMARY;
    }
}
