package dev.vantage.mixin;

import dev.vantage.module.cosmetic.DigBurst;
import dev.vantage.module.cosmetic.LootSparkle;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dig Burst (the block you are breaking) and Loot Sparkle (the container you right click), both read as your own click goes out; nothing about either action changes. */
@Mixin(MultiPlayerGameMode.class)
public class CosmeticHooksMixin {
    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"))
    private void vantage$dig(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        dev.vantage.module.finder.DeepLeaks.noteOwnBreak(pos);
        DigBurst d = DigBurst.active();
        if (d != null) d.begin(pos);
    }

    /** Only a break that really happened counts (creative with a sword, or a refused break, returns false). */
    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", at = @At("RETURN"))
    private void vantage$dug(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        DigBurst d = DigBurst.active();
        if (d != null) d.end(cir.getReturnValueZ());
    }

    @Inject(method = "useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"))
    private void vantage$use(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        LootSparkle l = LootSparkle.active();
        if (l != null) l.clicked(hit.getBlockPos());
    }
}
