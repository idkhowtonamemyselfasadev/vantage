package dev.vantage.module.cosmetic;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

/** A sparkle on the chest, barrel or shulker box as you open it. Starts from your own right click, shown once the container screen really opens; particles only. */
public class LootSparkle extends Module {
    public final ModeSetting colour = add(Cos.mode());
    public final ColorSetting manual = add(Cos.manual()).visibleIf(() -> colour.is("Manual"));
    public final NumberSetting amount = add(new NumberSetting("Amount", "Sparkles per opening", 20, 4, 60, 1));
    public final BoolSetting chests = add(new BoolSetting("Chests", "Chests, trapped chests and ender chests", true));
    public final BoolSetting barrels = add(new BoolSetting("Barrels", "Barrels", true));
    public final BoolSetting shulkers = add(new BoolSetting("Shulker boxes", "Shulker boxes", true));
    public static int sparkles, spawned;
    private BlockPos pending;
    private long pendingAt;

    public LootSparkle() {
        super("Loot Sparkle", "A sparkle on a chest, barrel or shulker box as you open it", Category.COSMETIC);
        beta();
    }

    public static LootSparkle active() {
        LootSparkle l = Modules.get(LootSparkle.class);
        return l != null && l.on() ? l : null;
    }

    @Override
    protected void onDisable() { pending = null; }

    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    private void forgetWorld() {
        pending = null;
    }

    /** Your right click on a block (called as it goes out): remembers it when it is a container this module covers. */
    public void clicked(BlockPos pos) {
        if (mc.level == null) return;
        BlockState st = mc.level.getBlockState(pos);
        var b = st.getBlock();
        boolean ok = b instanceof ChestBlock || b instanceof EnderChestBlock ? chests.on() : b instanceof BarrelBlock ? barrels.on() : b instanceof ShulkerBoxBlock && shulkers.on();
        if (!ok) return;
        pending = pos.immutable();
        pendingAt = mc.level.getGameTime();
    }

    @Override
    public void onTick() {
        if (pending == null || mc.level == null) return;
        if (mc.screen instanceof AbstractContainerScreen<?> && mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            sparkle(pending);
            pending = null;
        } else if (mc.level.getGameTime() - pendingAt > 40) pending = null;
    }

    /** The sparkle itself; public so the rig can fire one. */
    public void sparkle(BlockPos pos) {
        var rnd = mc.level.random;
        int col = Cos.pick(colour, manual) & 0xFFFFFF;
        var dust = new DustParticleOptions(col, 1.1f);
        double cx = pos.getX() + 0.5, cy = pos.getY() + 1.0, cz = pos.getZ() + 0.5;
        int n = amount.i();
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 0.15 + rnd.nextDouble() * 0.4;
            mc.level.addParticle(dust, true, true, cx + Math.cos(a) * r, cy + rnd.nextDouble() * 0.15, cz + Math.sin(a) * r, Math.cos(a) * 0.02, 0.05 + rnd.nextDouble() * 0.07, Math.sin(a) * 0.02);
        }
        for (int i = 0; i < (Cos.calm() ? 0 : Math.max(2, n / 5)); i++) { // calm: no bright rods
            double a = rnd.nextDouble() * Math.PI * 2, r = rnd.nextDouble() * 0.4;
            mc.level.addParticle(ParticleTypes.END_ROD, true, true, cx + Math.cos(a) * r, cy + 0.05, cz + Math.sin(a) * r, 0, 0.03 + rnd.nextDouble() * 0.04, 0);
        }
        spawned += n;
        sparkles++;
    }
}
