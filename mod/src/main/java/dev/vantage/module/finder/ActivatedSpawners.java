package dev.vantage.module.finder;

import java.util.HashMap;
import java.util.Map;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Module;
import dev.vantage.core.chunk.Chunks;
import dev.vantage.core.notify.Level;
import dev.vantage.core.notify.Notify;
import dev.vantage.core.notify.Sound;
import dev.vantage.mixin.BaseSpawnerAccessor;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A dungeon spawner starts with a spawn delay of 20 and only changes it once a player has been within
 * 16 blocks. The server sends that delay with the chunk, so a spawner that arrives with any other delay
 * has been activated by someone. Torches right next to a spawner are another human tell.
 */
public class ActivatedSpawners extends Module {
    public final BoolSetting torches = add(new BoolSetting("Torch check", "Also flag spawners with torches around them", true));
    public final BoolSetting trial = add(new BoolSetting("Trial spawners", "Flag trial spawners that were triggered", true));
    public final BoolSetting notify = add(new BoolSetting("Chat alert", "Message + sound for each find", true));
    public final BoolSetting waypoint = add(new BoolSetting("Auto waypoint", "Drop a waypoint on each find", false));
    public final NumberSetting maxDraw = add(new NumberSetting("Draw range", "Blocks around you to draw", 256, 32, 1024, 32));
    public final ColorSetting color = add(new ColorSetting("Color", "Activated spawner colour", 0xFFDE6F4D));
    public final ColorSetting torchColor = add(new ColorSetting("Torch color", "Torched spawner colour", 0xFFE0BD62));

    private record Hit(BlockPos pos, String why, boolean torch) {}

    private final Map<BlockPos, Hit> hits = new HashMap<>();
    private final Map<BlockPos, Boolean> seen = new HashMap<>();

    public ActivatedSpawners() {
        super("Activated Spawners", "Spawners a player has already been near", Category.FINDER);
    }

    @Override
    protected void onEnable() {
        if (mc.level == null || mc.player == null) return;
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos pc = mc.player.chunkPosition();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int cx = pc.x + x, cz = pc.z + z;
                if (Chunks.generation(cx, cz) < 1) continue; // not loaded, or announced without data: its data event scans it
                Chunks.submit(this, "enable-scan", cx, cz, budget -> {
                    if (mc.level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) instanceof LevelChunk c) scan(c);
                    return true;
                });
            }
        }
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
        hits.clear();
        seen.clear();
    }

    @Override
    public String info() {
        return hits.isEmpty() ? null : String.valueOf(hits.size());
    }

    /**
     * Read the delay right when the chunk's data arrives (the load event came just before), before our own client ticks it down. The
     * chunk is scanned once: a second pass from the load event used to follow and found nothing, because every spawner was already seen.
     * The queue is for chunks that were already loaded when the module was switched on.
     */
    @Override
    public void onChunkData(LevelChunk chunk) {
        scan(chunk);
    }

    private void scan(LevelChunk chunk) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getBlockPos().immutable();
            String why = null;
            if (be instanceof SpawnerBlockEntity s) {
                if (seen.containsKey(pos)) continue;
                seen.put(pos, true);
                int delay = ((BaseSpawnerAccessor) s.getSpawner()).vantage$spawnDelay();
                if (delay != 20) why = "spawner delay " + delay;
            } else if (trial.on() && be.getBlockState().getBlock() instanceof TrialSpawnerBlock) {
                if (seen.containsKey(pos)) continue;
                seen.put(pos, true);
                TrialSpawnerState st = be.getBlockState().getValue(TrialSpawnerBlock.STATE);
                if (st != TrialSpawnerState.INACTIVE && st != TrialSpawnerState.WAITING_FOR_PLAYERS) why = "trial spawner " + st.getSerializedName();
            } else continue;
            boolean torch = torches.on() && torchesAround(pos);
            if (why == null && torch) why = "torches around spawner";
            if (why == null) continue;
            Hit h = new Hit(pos, why, why.startsWith("torches"));
            hits.put(pos, h);
            if (notify.on()) {
                // one line per spawner (the key is its position); a chunk full of spawners shows five and then one "held back" line
                Notify.post(this, Level.WARNING, "spawner:" + pos.asLong(), -1, "SPAWNER " + Chat.coords(pos.getX(), pos.getY(), pos.getZ()) + "  " + why
                        + "  " + (int) Math.sqrt(mc.player.blockPosition().distSqr(pos)) + "m", new Sound(0.8f));
            }
            if (waypoint.on()) Waypoints.get().add("Spawner", pos.getX(), pos.getY(), pos.getZ(), color.argb());
        }
    }

    private boolean torchesAround(BlockPos p) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, -1, -2), p.offset(2, 2, 2))) {
            BlockState s = mc.level.getBlockState(q);
            if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.SOUL_TORCH) || s.is(Blocks.SOUL_WALL_TORCH)
                    || s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_LANTERN)) n++;
        }
        return n >= 1;
    }

    @Override
    public void onRender3D(R3 r) {
        double max2 = maxDraw.get() * maxDraw.get();
        for (Hit h : hits.values()) {
            Vec3 c = Vec3.atCenterOf(h.pos);
            if (c.distanceToSqr(r.cam) > max2) continue;
            int col = h.torch ? torchColor.argb() : color.argb();
            AABB b = new AABB(h.pos);
            r.kind("spawner");
            EspStyle.box(r, b, col, true, true, EspStyle.STD, 1f, true);
            r.label(c.add(0, 1, 0), h.why, col);
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
