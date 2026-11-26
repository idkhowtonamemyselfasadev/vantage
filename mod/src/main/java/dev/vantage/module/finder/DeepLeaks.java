package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import dev.vantage.core.Category;
import dev.vantage.core.LeakLog;
import dev.vantage.core.Module;
import dev.vantage.core.Toasts;
import dev.vantage.core.util.Cooldown;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What a server lets slip about the rock it fills in. Anti-ESP servers rebuild deep sections as one solid block, but the world keeps
 * running underneath: sounds, level events, block changes and entities still come with their real position. Anything of that
 * kind sitting inside a block the client sees as solid cannot be natural, so it marks a hidden room, farm or player.
 *
 * Kinds: sound (a block or mob heard inside rock), activity (a block or level event changed something inside rock), entity (a mob,
 * frame or item inside rock), dig (a block broken or being mined with nobody in sight). Reveals, the moment a filled section is
 * replaced by its real blocks, are logged with the distance, so the fill radius of a server can be read off the leak log.
 */
public class DeepLeaks extends Module {
    private static final int C_SOUND = 0xFFE59A5C, C_ACT = 0xFF62D6B0, C_ENTITY = 0xFFB1D069, C_DIG = 0xFFE5C05C;
    private static final long HOLD_MS = 180_000, REVEAL_WINDOW_MS = 2000;
    private static final int REVEAL_HITS = 8, NEAR = 8;
    private static final String GRASS = BuiltInRegistries.BLOCK.getKey(Blocks.GRASS_BLOCK).toString();
    /** Blocks that change on their own inside untouched rock: growth, fire, ore that lights up when a mob steps on it, sculk, dripstone. */
    private static final java.util.Set<Block> NATURAL_CHANGE = java.util.Set.of(Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD,
            Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT,
            Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.KELP, Blocks.KELP_PLANT, Blocks.GLOW_LICHEN, Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.POINTED_DRIPSTONE, Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SCULK_CATALYST,
            Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER, Blocks.WATER, Blocks.LAVA, Blocks.MOSS_BLOCK, Blocks.SPORE_BLOSSOM, Blocks.HANGING_ROOTS,
            Blocks.BIG_DRIPLEAF, Blocks.SMALL_DRIPLEAF, Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.DRIPSTONE_BLOCK, Blocks.BUDDING_AMETHYST,
            // the surface inside a section the fill rule counts as underground: grass spreading, snow, ice, leaves, mushrooms, nylium, fungus
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.SNOW, Blocks.ICE, Blocks.FROSTED_ICE, Blocks.FARMLAND, Blocks.BROWN_MUSHROOM,
            Blocks.RED_MUSHROOM, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.NETHERRACK, Blocks.CRIMSON_FUNGUS, Blocks.WARPED_FUNGUS, Blocks.NETHER_SPROUTS,
            Blocks.SOUL_SOIL, Blocks.MAGMA_BLOCK, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.DEAD_BUSH, Blocks.VINE, Blocks.LILY_PAD, Blocks.SEAGRASS,
            Blocks.TALL_SEAGRASS, Blocks.SEA_PICKLE, Blocks.COCOA, Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.SWEET_BERRY_BUSH,
            Blocks.NETHER_WART, Blocks.CHORUS_FLOWER, Blocks.CHORUS_PLANT, Blocks.CRIMSON_ROOTS, Blocks.WARPED_ROOTS, Blocks.NETHER_WART_BLOCK);
    /** Mobs whose noises come out of any cave or Nether cavern a fill covers; a villager, an animal or a player heard in the rock is the find. */
    private static final java.util.Set<String> CAVE_VOICES = java.util.Set.of("zombie", "skeleton", "creeper", "spider", "cave_spider", "enderman", "ghast",
            "blaze", "magma_cube", "slime", "silverfish", "witch", "drowned", "husk", "stray", "piglin", "piglin_brute", "zombified_piglin", "hoglin", "zoglin",
            "wither_skeleton", "phantom", "bat", "warden", "guardian", "elder_guardian", "endermite", "bogged", "breeze", "vex", "squid", "glow_squid",
            "strider", "axolotl", "tropical_fish", "cod", "salmon", "pufferfish", "generic", "player", "item");

    public final BoolSetting sounds = add(new BoolSetting("Sounds", "Blocks and mobs heard inside solid rock", true));
    public final BoolSetting events = add(new BoolSetting("Events", "Jukeboxes, anvils, crafters, dispensers and more firing inside rock", true));
    public final BoolSetting entities = add(new BoolSetting("Entities", "Mobs, frames and items the client holds inside solid blocks", true));
    public final BoolSetting updates = add(new BoolSetting("Block changes", "Single blocks changing inside a section the server filled solid", true));
    public final BoolSetting digging = add(new BoolSetting("Digging", "Blocks broken or being mined with nobody in sight", true));
    public final BoolSetting toast = add(new BoolSetting("Toast", "Card for each new find", true));
    public final NumberSetting range = add(new NumberSetting("Range", "Draw finds this close", 128, 32, 512, 16));

    public record Find(String kind, BlockPos pos, String text, long time, int count) {}

    /** By kind and block, so a block broken and then changed keeps both stories. */
    private final Map<String, Find> finds = new HashMap<>();
    private final Map<UUID, Find> living = new HashMap<>();
    /** Sections the server sent as one solid block, by section key, with that block. */
    private final Map<Long, BlockState> fill = new HashMap<>();
    private static DeepLeaks instance;
    /** Chunks that arrived with two or more one-block sections below Y 0 this session: past a few dozen this is a fill server, which changes
     *  what other clues mean. Natural one-block sections exist (solid deepslate), but not two per chunk in dozens of chunks within minutes;
     *  a test world's stone cubes never reach it either. */
    private static final java.util.Set<Long> fillChunks = new java.util.HashSet<>();
    /** One-block sections per chunk, so "is any of this chunk fill" is a lookup, not a walk over every section. */
    private final Map<Long, Integer> fillPerChunk = new HashMap<>();
    /** Mob drops that lie around in any cave; a dropped item is a player's only when it is something else. */
    private static final java.util.Set<String> NATURAL_DROPS = java.util.Set.of("rotten_flesh", "bone", "arrow", "string", "spider_eye", "gunpowder",
            "ender_pearl", "phantom_membrane", "kelp", "seagrass", "ink_sac", "glow_ink_sac", "slime_ball", "magma_cream", "ghast_tear", "feather",
            "leather", "beef", "porkchop", "chicken", "mutton", "rabbit", "rabbit_hide", "cod", "salmon", "tropical_fish", "pufferfish", "egg",
            "bamboo", "sugar_cane", "cactus", "stick", "sapling", "glow_berries", "sweet_berries", "wheat_seeds", "copper_ingot", "iron_ingot", "gold_nugget",
            "snowball", "clay_ball", "nautilus_shell", "scute", "turtle_scute", "armadillo_scute", "wool", "white_wool", "frog_spawn", "slime_block",
            "gravel", "sand", "red_sand", "flint"); // what a falling block leaves when it cannot land
    /** Sections whose fill was only guessed from a single change, not seen in a chunk packet. */
    private final java.util.Set<Long> guessedFill = new java.util.HashSet<>();
    /** Distinct blocks changed per filled section inside the window: a reveal rewrites hundreds of different blocks, a clock or farm
     *  flips the same few over and over, so only distinct positions count towards a reveal. */
    private record Hits(java.util.Set<Long> positions, long start) {}
    private final Map<Long, Hits> sectionHits = new HashMap<>();
    /** Anything found inside fill waits this long before it counts, so the first blocks of a reveal never read as activity. Right after
     *  joining the wait is longer: Donut sends your own base filled first and swaps in the real blocks a couple of seconds later, and
     *  the crafters and dispensers in it fire their level events in between. */
    private static final long WAIT_MS = 3000, JOIN_WAIT_MS = 6000, JOIN_MS = 15_000;
    /** needFill: a block change, which only counts while its section is still fill; otherwise the block itself must still read as hidden rock. */
    private record Pending(String kind, BlockPos pos, String text, long at, boolean needFill) {}
    private final Map<String, Pending> pending = new HashMap<>();
    private final List<Integer> reveals = new ArrayList<>();
    /** Where the server un-fills around you, learned from reveals: [flat chunks, sections up or down] per reveal. Kept per server in
     *  config/vantage/leaks/<server>-front.json, so the guard is right from the first second after a join. */
    private final List<int[]> front = new ArrayList<>();
    private String frontServer = "";
    private int frontSaved;
    private long worldStart;
    /** Blocks a server fills sections with. A level event or sound sitting in one of these, or in any section the server sent as one
     *  block, is hidden; a dispenser you can see firing is not, even though a dispenser is a full solid cube. */
    private static final java.util.Set<Block> ROCK = java.util.Set.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.END_STONE, Blocks.TUFF,
            Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.CALCITE, Blocks.BLACKSTONE, Blocks.BASALT, Blocks.DIRT, Blocks.GRAVEL, Blocks.SAND,
            Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.TERRACOTTA, Blocks.BEDROCK, Blocks.COBBLED_DEEPSLATE, Blocks.COBBLESTONE);
    private final Cooldown toastGate = Cooldown.wall(3000);
    private int timer;

    public DeepLeaks() {
        super("Deep Leaks", "Sounds, events, block changes and entities inside the rock a server fills in", Category.FINDER);
        listen(dev.vantage.core.event.PacketHandledEvent.class, e -> {
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket p) onSound(p);
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundLevelEventPacket p) onLevelEvent(p);
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket p) onDestroy(p);
        });
        instance = this;
    }

    /** True once the server has sent a few dozen sections as one solid block: the rock around you is fill, not rock. */
    public static boolean fillServer() {
        return fillChunks.size() >= 32;
    }

    /** DIAGNOSTIC / RIG, read-only: the fill sections of a chunk counted from the real fill map (not fillPerChunk): "n[y,y,...]", or "n/a" without an instance. */
    public static String fillSectionsOf(int cx, int cz) {
        DeepLeaks d = instance;
        if (d == null || !d.on()) return "n/a"; // Deep Leaks off: the fill map is not kept, "0[]" would read as measured
        int n = 0;
        StringBuilder ys = new StringBuilder();
        for (long k : d.fill.keySet()) { // read in place, no copy (single thread)
            if (SectionPos.x(k) != cx || SectionPos.z(k) != cz) continue;
            n++;
            if (ys.length() > 0) ys.append(',');
            ys.append(SectionPos.y(k));
        }
        return n + "[" + ys + "]";
    }

    /** Whether any section of this chunk is fill right now (the client's view of it is not the real one). */
    public static boolean anyFill(int cx, int cz) {
        return instance != null && instance.fillPerChunk.getOrDefault(ChunkPos.asLong(cx, cz), 0) > 0;
    }

    @Override
    public String info() {
        int n = finds.size() + living.size();
        if (reveals.isEmpty()) return String.valueOf(n);
        List<Integer> s = new ArrayList<>(reveals);
        s.sort(null);
        return n + " · reveal ~" + s.get(s.size() / 2) + " · front " + frontChunks() + "c " + frontSections() + "s · real Y " + slabLow() + ".." + (slabLow() + 15);
    }

    /** The bottom of the section you stand in: on Donut that section is real within 8 chunks, everything above and below it out
     *  there is fill, so a base hunt is a walk at base depth. Measured 2026-11-07 (90% of far reveals within 8 blocks of the player's Y). */
    public int slabLow() {
        return mc.player == null ? 0 : (mc.player.getBlockY() >> 4) << 4;
    }

    /** Rig only: a find the world did not produce. */
    public void putForTest(Find f) {
        finds.put(f.kind() + f.pos().asLong(), f);
    }

    public List<Find> finds() {
        List<Find> out = new ArrayList<>(finds.values());
        out.addAll(living.values());
        return out;
    }

    public List<Integer> reveals() {
        return new ArrayList<>(reveals);
    }

    /** The front only learns from reveals this close: Donut re-sends natural rock real at 6-7 chunks too, and a front that wide would
     *  blind Deep Leaks within 128 blocks. Its own un-fill next to you is 2 chunks and 1-2 sections (the base you log in at turned real
     *  at 37 blocks, dy 8), so 2 is the most the guard ever covers. */
    public static final int FRONT_MAX = 2;

    /** How many chunks out the server swaps fill for real blocks: the 90th percentile of the near reveals seen, at least 1 (Donut: 2). */
    public int frontChunks() { return frontOf(front, 0); }
    /** The same up and down, in sections (Donut: 2). */
    public int frontSections() { return frontOf(front, 1); }

    public static int frontOf(List<int[]> reveals, int i) {
        List<Integer> s = new ArrayList<>();
        for (int[] r : reveals) if (r[0] <= FRONT_MAX && r[1] <= FRONT_MAX) s.add(r[i]);
        if (s.isEmpty()) return 1;
        s.sort(null);
        return Math.max(1, s.get(Math.max(0, (int) Math.ceil(s.size() * 0.9) - 1)));
    }

    /** Inside the reveal cube the server is busy swapping fill for real blocks, single blocks at a time near its edge: a block changing or
     *  an item sitting there is the reveal front, not something running inside the rock. No margin: one chunk more would hide a base 50
     *  blocks off, and that is exactly where its leaks are worth the most. */
    public static boolean insideFront(int dcx, int dcz, int dsy, int frontChunks, int frontSections) {
        return Math.max(dcx, dcz) <= frontChunks && dsy <= frontSections;
    }

    private boolean insideFront(BlockPos pos) {
        if (mc.player == null) return false;
        int dcx = Math.abs((pos.getX() >> 4) - mc.player.chunkPosition().x), dcz = Math.abs((pos.getZ() >> 4) - mc.player.chunkPosition().z);
        int dsy = Math.abs((pos.getY() >> 4) - (mc.player.getBlockY() >> 4));
        return insideFront(dcx, dcz, dsy, frontChunks(), frontSections());
    }

    // ---- what counts as rock ------------------------------------------------------------------------------------------------

    /** Caves have their own life: bats, monsters, squid in flooded caves, and a mineshaft's chest minecarts sit inside the fill on any
     *  server that hides natural structures with block entities. Animals, villagers, frames, stands, items and anything named are what
     *  a player leaves behind. */
    public static boolean tellsOfPlayers(Entity e) {
        if (e instanceof net.minecraft.world.entity.item.FallingBlockEntity) return false; // sand and gravel fall on their own, wherever the server hides rock
        if (e.hasCustomName()) return true;
        if (e instanceof net.minecraft.world.entity.item.ItemEntity it) {
            String id = BuiltInRegistries.ITEM.getKey(it.getItem().getItem()).getPath();
            return !NATURAL_DROPS.contains(id); // what a mob leaves behind is cave litter; a dropped pickaxe or a diamond is a player's
        }
        switch (e.getType().getCategory()) {
            case MONSTER, AMBIENT, WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> { return false; }
            default -> {}
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        return !id.contains("minecart") && !id.contains("boat") && !id.equals("strider"); // striders wade the lava seas a Nether fill covers
    }

    private boolean solidAt(BlockPos p) {
        if (mc.level == null || !mc.level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return false;
        BlockState st = mc.level.getBlockState(p);
        return !st.isAir() && st.canOcclude() && st.isSolidRender();
    }

    /** Is a mob or item at this spot really inside rock? In a section the server filled, yes. Elsewhere every side must be solid too: a villager
     *  shoved half into a house wall, or an animal clipping a step, has open air beside it and says nothing. */
    public static boolean buried(boolean fillSection, int solidSides) {
        return fillSection || solidSides == 6;
    }

    private boolean entombed(BlockPos p) {
        if (!solidAt(p)) return false;
        int sides = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) if (solidAt(p.relative(d))) sides++;
        return buried(fill.containsKey(SectionPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4)), sides);
    }

    /** Solid, and rock the server could have put there: fill the client knows about, or a natural filler block. A visible dispenser,
     *  crafter or jukebox is solid too but fires its own events and sounds, so solid alone proves nothing. */
    private boolean hiddenAt(BlockPos p) {
        if (!solidAt(p)) return false;
        if (fill.containsKey(SectionPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4))) return true;
        return ROCK.contains(mc.level.getBlockState(p).getBlock());
    }

    private void defer(String kind, BlockPos pos, String text, boolean needFill) {
        // a block flipping every tick (a clock, a dispenser firing) must not restart its own wait, or it would never count
        String k = kind + ":" + pos.asLong();
        Pending old = pending.get(k);
        pending.put(k, new Pending(kind, pos.immutable(), text, old == null ? System.currentTimeMillis() : old.at(), needFill));
    }

    private long waitMs() {
        return System.currentTimeMillis() - worldStart < JOIN_MS ? JOIN_WAIT_MS : WAIT_MS;
    }

    // ---- the learned front, per server ---------------------------------------------------------------------------------------

    private java.nio.file.Path frontFile(String server) {
        return dev.vantage.core.Config.dir().resolve("leaks").resolve(server + "-front.json");
    }

    /** A bare list of [chunks, sections] pairs, as the file has always been (no v field). On JsonStore: written on the storage thread. */
    private final dev.vantage.core.store.JsonStore frontStore = new dev.vantage.core.store.JsonStore("leak front",
            () -> frontServer.isEmpty() ? null : frontFile(frontServer), 1, () -> {
                com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                for (int[] r : front) {
                    com.google.gson.JsonArray pair = new com.google.gson.JsonArray();
                    pair.add(r[0]);
                    pair.add(r[1]);
                    a.add(pair);
                }
                return a;
            }, (root, v) -> {
                front.clear();
                for (com.google.gson.JsonElement e : root.getAsJsonArray()) {
                    if (e.isJsonArray() && e.getAsJsonArray().size() == 2) front.add(new int[] {e.getAsJsonArray().get(0).getAsInt(), e.getAsJsonArray().get(1).getAsInt()});
                }
            }).unversioned();

    private void loadFront() {
        String key = dev.vantage.core.Config.serverKey();
        if (key.equals(frontServer)) return;
        saveFront();
        frontServer = key; // from here the store's path is this server's file; the old server's unsaved front was handed over by saveFront and load
        if (!frontStore.load()) front.clear();
        frontSaved = front.size();
    }

    private void saveFront() {
        if (frontServer.isEmpty() || front.isEmpty() || front.size() == frontSaved) return;
        frontSaved = front.size();
        frontStore.markDirty();
    }

    private boolean someoneNear(Vec3 pos, double r) {
        for (Player p : mc.level.players()) if (p.position().distanceToSqr(pos) <= r * r) return true;
        return false;
    }

    /** The one block a section is made of, when the server sent it as nothing else; null otherwise. */
    private static BlockState fillOf(LevelChunkSection s) {
        if (s.hasOnlyAir()) return null;
        BlockState[] only = new BlockState[1];
        int[] kinds = new int[1];
        s.getStates().count((st, n) -> { kinds[0]++; only[0] = st; });
        if (kinds[0] != 1) return null;
        BlockState st = only[0];
        return !st.isAir() && st.canOcclude() ? st : null;
    }

    /** Sections whose "is the rest of it fill" count ran lately: the count walks 4096 cells, and a reveal sweep sends thousands of
     *  single changes per section, so it runs at most once per section per second (and not at all for 3 s after a reveal). */
    private final Map<Long, Long> guessChecked = new HashMap<>();
    private static final long GUESS_EVERY_MS = 1000, GUESS_AFTER_REVEAL_MS = 3000;

    /** The block the rest of the section is made of, when this one change is the only other thing in it. */
    private BlockState fillBeside(BlockPos pos, BlockState changed) {
        long sk = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        long now = System.currentTimeMillis();
        Long last = guessChecked.get(sk);
        if (last != null && now - last < GUESS_EVERY_MS) return null;
        guessChecked.put(sk, now);
        if (!(mc.level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) instanceof LevelChunk c)) return null;
        int idx = c.getSectionIndex(pos.getY());
        if (idx < 0 || idx >= c.getSections().length) return null;
        BlockState[] big = new BlockState[1];
        int[] most = new int[1];
        c.getSections()[idx].getStates().count((st, n) -> { if (n > most[0]) { most[0] = n; big[0] = st; } });
        // a handful of changes in a tick still arrive as one packet, so the rest of the section must only be nearly all one block
        if (most[0] < 4090 || big[0] == null || big[0] == changed || big[0].isAir() || !big[0].canOcclude()) return null;
        return big[0];
    }

    private void mark(String kind, BlockPos pos, String text) {
        // block changes merge per section, so a farm ticking away reads as one find with a count, not a wall of labels
        String key = kind.equals("activity") ? kind + ":" + SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4) : kind + ":" + pos.asLong();
        Find old = finds.get(key);
        long now = System.currentTimeMillis();
        finds.put(key, new Find(kind, old == null ? pos : old.pos(), old == null ? text : old.text(), now, old == null ? 1 : old.count() + 1));
        if (old == null) {
            LeakLog.add("DEEP-" + kind.toUpperCase(Locale.ROOT), pos.getX(), pos.getY(), pos.getZ(), text);
            if (toast.on() && !kind.equals("activity") && toastGate.tryUse()) {
                Toasts.warn("Deep Leaks", text + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
        }
    }

    // ---- taps -------------------------------------------------------------------------------------------------------------

    public void onSound(ClientboundSoundPacket p) {
        if (!sounds.on() || mc.level == null) return;
        BlockPos at = BlockPos.containing(p.getX(), p.getY(), p.getZ());
        if (!hiddenAt(at)) return;
        String path = p.getSound().value().location().getPath();
        // placing and breaking play at the block itself, which is solid right after: not a leak
        if (path.endsWith(".place") || path.endsWith(".break") || path.endsWith(".hit") || path.endsWith(".step") || path.endsWith(".fall")) return;
        if (path.startsWith("entity.") && !path.startsWith("entity.player.")) {
            String mob = path.split("\\.")[1];
            if (CAVE_VOICES.contains(mob)) return; // cave and Nether life sounds off inside the fill all the time; it says nothing about players
        }
        String what = path.startsWith("entity.player.") ? "Player heard" : path.startsWith("entity.") ? "Mob heard (" + path.split("\\.")[1] + ")"
                : path.startsWith("block.") ? "Block heard (" + path.substring(6) + ")" : path;
        defer("sound", at, what + " inside solid rock", false);
    }

    private static String eventName(int type) {
        return switch (type) {
            case 1000, 1001, 1002, 2000 -> "dispenser";
            case 1010, 1011 -> "jukebox";
            case 1020, 1021, 1022, 1029, 1030, 1031 -> "anvil";
            case 1035 -> "brewing stand";
            case 1042 -> "grindstone";
            case 1043 -> "book";
            case 1044 -> "smithing table";
            case 1049, 1050, 2010 -> "crafter";
            case 1036, 1037 -> "iron trapdoor";
            case 1500 -> "composter";
            case 1502 -> "redstone torch burnout";
            case 1505, 2005 -> "bone meal";
            case 2001 -> "block broken";
            default -> null;
        };
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> OWN_BREAKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Called when this client sends a block break; only that exact block is ignored, for about 3 s, so a hidden digger beside you still shows. */
    public static void noteOwnBreak(BlockPos pos) {
        long now = System.currentTimeMillis();
        if (OWN_BREAKS.size() > 64) OWN_BREAKS.values().removeIf(t -> now - t > 3000);
        OWN_BREAKS.put(pos.asLong(), now);
    }

    private static boolean isOwnBreak(BlockPos pos) {
        Long t = OWN_BREAKS.get(pos.asLong());
        return t != null && System.currentTimeMillis() - t < 3000;
    }

    /** Blocks that break by themselves when the world around them settles: not a miner. */
    public static boolean fallsOnItsOwn(Block b) {
        return b instanceof net.minecraft.world.level.block.FallingBlock || b instanceof net.minecraft.world.level.block.BrushableBlock;
    }

    public void onLevelEvent(ClientboundLevelEventPacket p) {
        if (mc.level == null || p.isGlobalEvent()) return;
        String name = eventName(p.getType());
        if (name == null) return;
        BlockPos at = p.getPos();
        if (p.getType() == 2001) {
            if (!digging.on()) return;
            BlockState broken = Block.stateById(p.getData());
            Block b = broken.getBlock();
            if (BuiltInRegistries.BLOCK.getKey(b).toString().equals(GRASS)) return; // sheep
            if (fallsOnItsOwn(b)) return; // sand, gravel and suspicious sand/gravel (ocean ruins, desert wells) fall the moment their chunk loads
            if (!broken.canOcclude()) return; // vines, kelp, crops and the like break in chains on their own when a support goes; a miner breaks rock
            if (someoneNear(at.getCenter(), NEAR)) return;
            if (isOwnBreak(at)) return; // a block this client itself just broke: the server echoes it back as a break event
            if (b instanceof net.minecraft.world.level.block.LeavesBlock || b instanceof net.minecraft.world.level.block.SnowLayerBlock) return; // decay and melting
            mark("dig", at, "Block broken (" + BuiltInRegistries.BLOCK.getKey(b).getPath() + "), nobody in sight");
            return;
        }
        if (!events.on() || !hiddenAt(at)) return;
        defer("activity", at, name + " used inside solid rock", false);
    }

    /** Break progress goes to everyone within 32 blocks, with the miner's entity id: a miner the client has no entity for is hidden. */
    public void onDestroy(ClientboundBlockDestructionPacket p) {
        if (!digging.on() || mc.level == null || p.getProgress() < 0 || p.getProgress() > 9) return;
        Entity e = mc.level.getEntity(p.getId());
        if (e == mc.player) return;
        // a miner the client has an entity for counts only when the client sees rock where that player STANDS (feet and eye block both rock the server
        // could have put there): that is the fill-server case. hiddenAt is false for a block that is not solid or whose chunk is not loaded, so a player in
        // water, in a tunnel, on a slab or beside an unloaded chunk is simply a visible miner and gets no find. (The mined block itself is always solid.)
        if (e != null && !(hiddenAt(e.blockPosition()) && hiddenAt(BlockPos.containing(e.getEyePosition())))) return;
        mark("dig", p.getPos(), e == null ? "Unseen player mining" : e instanceof Player ? "A player mining inside solid rock" : "A mob breaking a block inside solid rock"); // no name: the text goes to the toast, the world label and the leak log file
    }

    @Override
    public void onChunkData(LevelChunk chunk) {
        if (mc.level == null) return;
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        LevelChunkSection[] secs = chunk.getSections();
        int deepOneBlock = 0, inChunk = 0;
        for (int i = 0; i < secs.length; i++) {
            int sy = mc.level.getSectionYFromSectionIndex(i);
            long key = SectionPos.asLong(cx, sy, cz);
            BlockState now = fillOf(secs[i]);
            BlockState was = fill.get(key);
            if (was != null && now == null) reveal(cx, sy, cz, "chunk packet", was);
            if (now != null) { fill.put(key, now); inChunk++; if (sy < 0) deepOneBlock++; } else fill.remove(key);
            guessedFill.remove(key);
        }
        fillPerChunk.put(ChunkPos.asLong(cx, cz), inChunk);
        if (deepOneBlock >= 2) fillChunks.add(ChunkPos.asLong(cx, cz));
    }

    private void reveal(int cx, int sy, int cz, String how, BlockState was) {
        long key = SectionPos.asLong(cx, sy, cz);
        boolean guessed = guessedFill.remove(key);
        guessChecked.put(key, System.currentTimeMillis() + GUESS_AFTER_REVEAL_MS - GUESS_EVERY_MS); // the rest of the sweep must not re-guess it
        if (fill.remove(key) != null) fillPerChunk.merge(ChunkPos.asLong(cx, cz), -1, Integer::sum);
        sectionHits.remove(key);
        // the section is real now: changes in it were the reveal, and anything that sits in plain air was in a cave, not inside rock
        finds.values().removeIf(f -> SectionPos.asLong(f.pos().getX() >> 4, f.pos().getY() >> 4, f.pos().getZ() >> 4) == key && (f.kind().equals("activity") || !solidAt(f.pos())));
        living.values().removeIf(f -> SectionPos.asLong(f.pos().getX() >> 4, f.pos().getY() >> 4, f.pos().getZ() >> 4) == key && !solidAt(f.pos()));
        pending.values().removeIf(p -> SectionPos.asLong(p.pos().getX() >> 4, p.pos().getY() >> 4, p.pos().getZ() >> 4) == key);
        if (mc.player == null) return;
        Vec3 me = mc.player.position();
        double mx = (cx << 4) + 8, my = (sy << 4) + 8, mz = (cz << 4) + 8;
        int dist = (int) Math.sqrt(Math.pow(mx - me.x, 2) + Math.pow(my - me.y, 2) + Math.pow(mz - me.z, 2));
        int flat = (int) Math.max(Math.abs(mx - me.x), Math.abs(mz - me.z));
        if (guessed) return; // a section we only assumed was filled: nothing to learn from it turning out normal
        reveals.add(dist);
        if (reveals.size() > 200) reveals.remove(0);
        int fc = Math.max(Math.abs(cx - mc.player.chunkPosition().x), Math.abs(cz - mc.player.chunkPosition().z)), fs = Math.abs(sy - (mc.player.getBlockY() >> 4));
        if (fc <= FRONT_MAX && fs <= FRONT_MAX) {
            front.add(new int[] {fc, fs});
            if (front.size() > 200) { front.remove(0); frontSaved = Math.max(0, frontSaved - 1); }
            if (front.size() - frontSaved >= 10) saveFront();
        }
        LeakLog.add("REVEAL", (int) mx, (int) my, (int) mz, "filled " + BuiltInRegistries.BLOCK.getKey(was.getBlock()).getPath() + " section turned real by " + how + " at " + dist + " blocks (flat " + flat + ", dy " + (int) (my - me.y) + ")");
    }

    @Override
    public void onSectionBurst(SectionPos sp, int count, BlockPos first, BlockState firstState) {
        if (!updates.on() || mc.level == null) return;
        long key = SectionPos.asLong(sp.x(), sp.y(), sp.z());
        BlockState f = fill.get(key);
        if (f == null) {
            f = fillBeside(first, firstState);
            if (f == null) return;
            fill.put(key, f);
            guessedFill.add(key);
        }
        if (count >= REVEAL_HITS) reveal(sp.x(), sp.y(), sp.z(), "block updates", f); // a whole section pouring in at once
    }

    @Override
    public void onBlockUpdate(BlockPos pos, BlockState state) {
        if (!updates.on() || mc.level == null) return;
        long key = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        BlockState f = fill.get(key);
        if (f == null) {
            f = fillBeside(pos, state); // a section that became solid after its chunk packet, now with one block changed in it
            if (f == null) return;
            fill.put(key, f);
            guessedFill.add(key);
        }
        long now = System.currentTimeMillis();
        Hits h = sectionHits.get(key);
        if (h == null || now - h.start() > REVEAL_WINDOW_MS) sectionHits.put(key, h = new Hits(new java.util.HashSet<>(), now));
        h.positions().add(pos.asLong());
        if (h.positions().size() >= REVEAL_HITS) { reveal(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4, "block updates", f); return; } // the real blocks pouring in
        if (state == f || mc.player == null) return;
        if (NATURAL_CHANGE.contains(state.getBlock())) return; // a bud growing or a vine lengthening in a filled cave is the cave, not a player
        // around you the server is busy swapping fill for real blocks, out to the reveal front it taught us: only changes past that can be something running there
        if (insideFront(pos)) return;
        defer("activity", pos, "Block changed inside solid rock (" + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath() + ")", true);
    }

    @Override
    public void onTick() {
        if (mc.level == null) return;
        long now = System.currentTimeMillis();
        if (worldStart == 0) worldStart = now;
        loadFront();
        long wait = waitMs();
        for (var it = pending.values().iterator(); it.hasNext();) {
            Pending p = it.next();
            if (now - p.at() < wait) continue;
            it.remove();
            if (insideFront(p.pos())) continue; // you just walked up to it: the server is swapping fill for real blocks there
            long sk = SectionPos.asLong(p.pos().getX() >> 4, p.pos().getY() >> 4, p.pos().getZ() >> 4);
            // still hidden after the wait, so this was not the reveal: the section is still fill, or the block still reads as rock
            if (p.needFill() ? fill.containsKey(sk) : hiddenAt(p.pos())) mark(p.kind(), p.pos(), p.text());
        }
        if (--timer > 0) return;
        timer = 10;
        if (entities.on()) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof Player || e.isRemoved() || !tellsOfPlayers(e)) continue;
                BlockPos at = BlockPos.containing(e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ());
                if (!entombed(at) || insideFront(at)) continue; // near the front the "rock" is fill the server is about to replace: a cave, not a room
                Find old = living.get(e.getUUID());
                String what = e.getType().getDescription().getString() + " inside solid rock";
                if (e instanceof net.minecraft.world.entity.item.ItemEntity it) what = "Item (" + BuiltInRegistries.ITEM.getKey(it.getItem().getItem()).getPath() + ") inside solid rock";
                if (e.hasCustomName()) what = e.getType().getDescription().getString() + " \"" + e.getCustomName().getString() + "\" inside solid rock";
                living.put(e.getUUID(), new Find("entity", at, what, now, old == null ? 1 : old.count() + 1));
                if (old == null) {
                    LeakLog.add("DEEP-ENTITY", at.getX(), at.getY(), at.getZ(), what);
                    if (toast.on() && toastGate.tryUse()) { Toasts.warn("Deep Leaks", what + " at " + at.getX() + " " + at.getY() + " " + at.getZ()); }
                }
            }
        }
        living.values().removeIf(f -> now - f.time() > 30_000 || insideFront(f.pos()));
        finds.values().removeIf(f -> now - f.time() > HOLD_MS);
    }

    @Override
    public void onChunkUnload(int cx, int cz) {
        fillPerChunk.remove(ChunkPos.asLong(cx, cz));
        guessChecked.keySet().removeIf(k -> SectionPos.x(k) == cx && SectionPos.z(k) == cz);
        fill.keySet().removeIf(k -> SectionPos.x(k) == cx && SectionPos.z(k) == cz);
        guessedFill.removeIf(k -> SectionPos.x(k) == cx && SectionPos.z(k) == cz);
        sectionHits.keySet().removeIf(k -> SectionPos.x(k) == cx && SectionPos.z(k) == cz);
    }

    private void reset() {
        finds.clear();
        living.clear();
        fill.clear();
        fillPerChunk.clear();
        guessChecked.clear();
        guessedFill.clear();
        sectionHits.clear();
        pending.clear();
        reveals.clear();
        saveFront();
    }

    @Override public void onDisable() { reset(); }
    @Override
    public void onJoinWorld() {
        startWorld();
    }

    @Override
    public void onLeaveWorld() {
        startWorld();
        fillChunks.clear(); // leaving a server forgets "this is a fill server"; a dimension change keeps it, it is still the same server
    }

    @Override
    public void onDimensionChange() {
        startWorld();
    }

    private void startWorld() {
        reset();
        worldStart = System.currentTimeMillis();
    }

    @Override
    public void onRender3D(R3 r) {
        double max = range.get() * range.get();
        for (Find f : finds()) {
            Vec3 mid = f.pos().getCenter();
            double d2 = mid.distanceToSqr(r.cam);
            if (d2 > max) continue;
            int c = switch (f.kind()) { case "sound" -> C_SOUND; case "activity" -> C_ACT; case "entity" -> C_ENTITY; default -> C_DIG; };
            EspStyle.box(r, new AABB(f.pos()), c, true);
            if (f.kind().equals("activity") && d2 > 48 * 48) continue; // far activity is a box only
            r.label(mid.add(0, 0.8, 0), f.text() + (f.count() > 1 ? " x" + f.count() : ""), c);
        }
    }

    @Override
    public int labelClass() {
        return LABEL_EVIDENCE;
    }

    @Override
    public boolean groupsBoxes() {
        return true;
    }
}
