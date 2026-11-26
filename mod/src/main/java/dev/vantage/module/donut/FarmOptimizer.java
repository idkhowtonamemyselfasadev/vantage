package dev.vantage.module.donut;

import java.util.HashMap;
import java.util.Map;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Stops a big farm from eating your frames: hides its entities, animations, explosions, redstone and repeated sounds. */
public class FarmOptimizer extends Module {
    private static final long SOUND_WINDOW_MS = 1500;

    private static FarmOptimizer instance;
    /** Counted for the test rig. */
    public static int particlesSkipped, pistonsSkipped;
    /** Read by the chunk mesher threads. */
    private static volatile int redstoneLevel;

    public final BoolSetting items = add(new BoolSetting("Items", "Hides dropped items once there are a lot of them around you.", true));
    public final dev.vantage.setting.IntSetting itemLimit = add(new dev.vantage.setting.IntSetting("Item limit", "Dropped items stay visible until more than this many lie within 48 blocks of you. 0 hides every dropped item", 40, 0, 500)).visibleIf(items::on);
    public final BoolSetting orbs = add(new BoolSetting("XP Orbs", "Hides experience orbs.", true));
    public final BoolSetting falling = add(new BoolSetting("Falling Blocks", "Hides falling sand, gravel and concrete powder.", true));
    public final BoolSetting tnt = add(new BoolSetting("TNT", "Hides activated TNT.", true));
    public final BoolSetting explosions = add(new BoolSetting("Explosions", "Hides explosion particles.", true));
    public final BoolSetting sounds = add(new BoolSetting("Sounds", "Mutes sounds that repeat too often. Sounds play again after they stop repeating.", true));
    public final NumberSetting soundSpam = add(new NumberSetting("Sound Spam", "Sets how many repeated sounds trigger muting. Lower values mute sounds sooner.", 4, 2, 20, 1)).visibleIf(sounds::on);
    public final BoolSetting pistons = add(new BoolSetting("Pistons", "Skips piston animations and moves pushed blocks straight to their new position.", true));
    public final ModeSetting redstone = add(new ModeSetting("Redstone", "Hides redstone dust or dust and components. Levers, buttons and lamps stay visible.", "Off", "Off", "Dust", "All"))
            .onChange(this::syncRedstone);

    private final Map<Identifier, long[]> heard = new HashMap<>();
    private int muted;
    /** Dropped items within 48 blocks, counted twice a second: a few drops are loot to pick up, a pile is a farm. */
    private int itemsNear, itemsAge;
    /** Rig: the last count. */
    public int itemsNear() { return itemsNear; }

    public FarmOptimizer() {
        super("Farm FPS Optimizer", "Reduces lag around farms", Category.DONUT);
        instance = this;
    }

    @Override public String info() { return muted > 0 ? muted + " muted" : null; }

    @Override protected void onEnable() { syncRedstone(); }
    @Override protected void onDisable() { syncRedstone(); }

    private void syncRedstone() {
        int want = !on() ? 0 : redstone.is("All") ? 2 : redstone.is("Dust") ? 1 : 0;
        if (want == redstoneLevel) return;
        redstoneLevel = want;
        if (mc.levelRenderer != null && mc.level != null) mc.levelRenderer.allChanged();
    }

    private static FarmOptimizer active() {
        return instance != null && instance.on() ? instance : null;
    }

    public static boolean hides(Entity e) {
        FarmOptimizer f = active();
        if (f == null) return false;
        return e instanceof ItemEntity ? f.items.on() && (f.itemLimit.i() == 0 || f.itemsNear > f.itemLimit.i()) : e instanceof ExperienceOrb ? f.orbs.on()
                : e instanceof FallingBlockEntity ? f.falling.on() : e instanceof PrimedTnt && f.tnt.on();
    }

    public static boolean hidesParticle(ParticleOptions p) {
        FarmOptimizer f = active();
        if (f == null || !f.explosions.on()) return false;
        var t = p.getType();
        return t == ParticleTypes.EXPLOSION || t == ParticleTypes.EXPLOSION_EMITTER;
    }

    public static boolean hidesPistonMotion() {
        FarmOptimizer f = active();
        return f != null && f.pistons.on();
    }

    /** Called from the chunk mesher. */
    public static boolean hidesBlock(BlockState s) {
        int level = redstoneLevel;
        if (level == 0) return false;
        if (s.is(Blocks.REDSTONE_WIRE)) return true;
        return level == 2 && (s.is(Blocks.REPEATER) || s.is(Blocks.COMPARATOR) || s.is(Blocks.REDSTONE_TORCH) || s.is(Blocks.REDSTONE_WALL_TORCH)
                || s.is(Blocks.OBSERVER) || s.is(Blocks.PISTON) || s.is(Blocks.STICKY_PISTON) || s.is(Blocks.PISTON_HEAD)
                || s.is(Blocks.DISPENSER) || s.is(Blocks.DROPPER) || s.is(Blocks.HOPPER) || s.is(Blocks.REDSTONE_BLOCK));
    }

    /** True when this sound has now repeated past the limit inside the window. It plays again once it goes quiet. */
    public static boolean muteSound(Identifier id) {
        FarmOptimizer f = active();
        if (f == null || !f.sounds.on()) return false;
        long now = System.currentTimeMillis();
        long[] v = f.heard.computeIfAbsent(id, k -> new long[2]);
        v[0] = now - v[1] > SOUND_WINDOW_MS ? 1 : v[0] + 1;
        v[1] = now;
        if (v[0] <= f.soundSpam.i()) return false;
        f.muted++;
        return true;
    }

    @Override
    public void onTick() {
        if (items.on() && itemLimit.i() > 0 && mc.player != null && mc.level != null && --itemsAge <= 0) {
            itemsAge = 10;
            itemsNear = mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(48)).size();
        }
        if (mc.level.getGameTime() % 40 == 0) muted = 0;
        if (heard.size() > 256) heard.clear();
    }
}
