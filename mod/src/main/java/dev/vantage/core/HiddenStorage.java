package dev.vantage.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Containers the server told us about that the client threw away. LevelChunk.replaceWithPacketData drops a block entity
 * entry when the block at that spot can't hold one, so a chest sent as stone never reaches getBlockEntities(). This keeps
 * the raw entries from chunk packets, block entity packets and block updates. Read-only: nothing here is ever sent.
 */
public final class HiddenStorage {
    private record Entry(BlockEntityType<?> type, boolean fromChunk, boolean heard) {}
    /** heard: no block entity was ever sent, only a container sound or level event at that block. */
    public record Hit(BlockPos pos, BlockEntityType<?> type, boolean heard) {}

    private static final List<BlockEntityType<?>> TYPES = List.of(BlockEntityType.CHEST, BlockEntityType.TRAPPED_CHEST, BlockEntityType.BARREL,
            BlockEntityType.SHULKER_BOX, BlockEntityType.ENDER_CHEST, BlockEntityType.HOPPER, BlockEntityType.DISPENSER, BlockEntityType.DROPPER,
            BlockEntityType.CRAFTER, BlockEntityType.FURNACE, BlockEntityType.SMOKER, BlockEntityType.BLAST_FURNACE, BlockEntityType.BREWING_STAND,
            BlockEntityType.DECORATED_POT, BlockEntityType.CHISELED_BOOKSHELF);

    private static final Map<Long, Map<BlockPos, Entry>> CHUNKS = new HashMap<>();

    private static Map<BlockPos, Entry> at(BlockPos p) {
        return CHUNKS.computeIfAbsent(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), k -> new HashMap<>());
    }

    public static void onChunk(int cx, int cz, ClientboundLevelChunkPacketData data) {
        Map<BlockPos, Entry> m = CHUNKS.get(ChunkPos.asLong(cx, cz));
        java.util.Set<BlockPos> listed = new java.util.HashSet<>();
        data.getBlockEntitiesTagsConsumer(cx, cz).accept((pos, type, tag) -> {
            if (TYPES.contains(type)) { listed.add(pos.immutable()); at(pos).put(pos.immutable(), new Entry(type, true, false)); }
        });
        if (m == null) return;
        // a resend is the new truth, unless it is fill: a container the server once listed and now covers with stone or deepslate is
        // still there (Donut drops the block entities of the sections it fills), so it stays as a hint
        var level = net.minecraft.client.Minecraft.getInstance().level;
        m.entrySet().removeIf(e -> {
            if (!e.getValue().fromChunk() || listed.contains(e.getKey())) return false;
            if (level == null) return true;
            BlockState st = level.getBlockState(e.getKey());
            return !(st.is(Blocks.STONE) || st.is(Blocks.DEEPSLATE));
        });
        for (var e : m.entrySet()) if (e.getValue().fromChunk() && !listed.contains(e.getKey())) e.setValue(new Entry(e.getValue().type(), false, true));
    }

    public static void onBlockEntity(BlockPos pos, BlockEntityType<?> type) {
        if (TYPES.contains(type)) at(pos).put(pos.immutable(), new Entry(type, false, false));
    }

    /** A whole-section packet: only blocks that are containers, or sit where a known container was, get the full check. */
    public static void onBurst(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket p) {
        p.runUpdates((pos, state) -> {
            if (state.hasBlockEntity()) { onBlock(pos.immutable(), state); return; }
            Map<BlockPos, Entry> m = CHUNKS.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            if (m != null && !m.isEmpty() && m.containsKey(pos)) onBlock(pos.immutable(), state);
        });
    }

    public static void onBlock(BlockPos pos, BlockState state) {
        for (BlockEntityType<?> t : TYPES) {
            if (t.isValid(state)) { at(pos).put(pos.immutable(), new Entry(t, false, false)); return; }
        }
        Map<BlockPos, Entry> m = CHUNKS.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (m == null) return;
        Entry e = m.get(pos);
        if (e == null) return;
        // a cover block (stone) may be a fake over the chest; air or a see-through block means it really went
        if (!state.canOcclude()) m.remove(pos);
        // a heard container can only hide behind air, stone or deepslate; anything else shown there is the truth
        else if (e.heard() && !state.is(Blocks.STONE) && !state.is(Blocks.DEEPSLATE)) m.remove(pos);
    }

    private static final double CENTRE_TOLERANCE = 0.07;

    /** The blocks a container sound can belong to: Y at a block centre, X and Z each at the centre or on an edge (an edge is a double chest). */
    public static List<BlockPos> soundBlocks(double x, double y, double z) {
        double fy = y - Math.floor(y);
        if (Math.abs(fy - 0.5) > CENTRE_TOLERANCE) return List.of();
        List<Integer> xs = axis(x), zs = axis(z);
        List<BlockPos> out = new ArrayList<>();
        for (int bx : xs) for (int bz : zs) out.add(new BlockPos(bx, (int) Math.floor(y), bz));
        return out;
    }

    private static List<Integer> axis(double v) {
        double f = v - Math.floor(v);
        int b = (int) Math.floor(v);
        if (Math.abs(f - 0.5) <= CENTRE_TOLERANCE) return List.of(b);
        if (f <= CENTRE_TOLERANCE) return List.of(b - 1, b);
        if (f >= 1 - CENTRE_TOLERANCE) return List.of(b, b + 1);
        return List.of();
    }

    /** What a sound path says is there, or null: block.chest.open, block.barrel.close, block.shulker_box.open, block.ender_chest.open. */
    public static BlockEntityType<?> typeOfSound(String path) {
        if (!path.startsWith("block.")) return null;
        String[] parts = path.split("\\.");
        if (parts.length < 3) return null;
        return switch (parts[1]) {
            case "chest", "copper_chest" -> BlockEntityType.CHEST;
            case "barrel" -> BlockEntityType.BARREL;
            case "shulker_box" -> BlockEntityType.SHULKER_BOX;
            case "ender_chest" -> BlockEntityType.ENDER_CHEST;
            case "chiseled_bookshelf" -> BlockEntityType.CHISELED_BOOKSHELF;
            case "decorated_pot" -> BlockEntityType.DECORATED_POT;
            case "vault" -> BlockEntityType.VAULT;
            case "trial_spawner" -> BlockEntityType.TRIAL_SPAWNER;
            default -> null;
        };
    }

    /** What a level event id says fired: dispensers and crafters make level events the client gets with the block position. */
    public static BlockEntityType<?> typeOfLevelEvent(int type) {
        return switch (type) {
            case 1000, 1001, 1002, 2000 -> BlockEntityType.DISPENSER;
            case 1035 -> BlockEntityType.BREWING_STAND;
            case 1049, 1050, 2010 -> BlockEntityType.CRAFTER;
            default -> null;
        };
    }

    /** A block event (chest lid, shulker lid, ender chest) names its block: through fill it is the only word of a container being used. */
    public static BlockEntityType<?> typeOfBlockEvent(net.minecraft.world.level.block.Block b) {
        if (b == Blocks.CHEST) return BlockEntityType.CHEST;
        if (b == Blocks.TRAPPED_CHEST) return BlockEntityType.TRAPPED_CHEST;
        if (b == Blocks.ENDER_CHEST) return BlockEntityType.ENDER_CHEST;
        if (b instanceof net.minecraft.world.level.block.ShulkerBoxBlock) return BlockEntityType.SHULKER_BOX;
        return null;
    }

    /** A container was heard or seen working at a block the client shows as something else: remember it as a hint. */
    public static void heard(BlockPos pos, BlockEntityType<?> type) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null || type == null || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
        BlockState st = level.getBlockState(pos);
        for (BlockEntityType<?> t : TYPES) if (t.isValid(st)) return; // a real container is there: nothing hidden
        if (!st.isAir() && !st.is(Blocks.STONE) && !st.is(Blocks.DEEPSLATE)) return; // only fill blocks can be a cover
        Map<BlockPos, Entry> m = at(pos);
        Entry old = m.get(pos);
        if (old != null && !old.heard()) return; // a sent block entity beats a guess
        m.put(pos.immutable(), new Entry(type, false, true));
    }

    public static void onSound(double x, double y, double z, String path) {
        BlockEntityType<?> type = typeOfSound(path);
        if (type == null) return;
        for (BlockPos p : soundBlocks(x, y, z)) heard(p, type);
    }

    /** Entries in a chunk; the caller keeps the ones the level has no block entity for. */
    public static List<Hit> in(int cx, int cz) {
        Map<BlockPos, Entry> m = CHUNKS.get(ChunkPos.asLong(cx, cz));
        if (m == null || m.isEmpty()) return List.of();
        List<Hit> out = new ArrayList<>(m.size());
        m.forEach((p, e) -> out.add(new Hit(p, e.type(), e.heard())));
        return out;
    }

    /** Chunks holding entries. */
    public static List<Long> chunks() {
        return new ArrayList<>(CHUNKS.keySet());
    }

    public static void unload(int cx, int cz) {
        CHUNKS.remove(ChunkPos.asLong(cx, cz));
    }

    public static void clear() {
        CHUNKS.clear();
    }

    private HiddenStorage() {}
}
