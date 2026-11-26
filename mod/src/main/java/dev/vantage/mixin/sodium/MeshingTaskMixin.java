package dev.vantage.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.vantage.module.render.Xray;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Sodium's cave culling: with xray on no block counts as opaque, so sections behind stone still draw. */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public class MeshingTaskMixin {
    @ModifyExpressionValue(require = 0,
            method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender()Z", remap = true))
    private boolean vantage$notOpaque(boolean solid) {
        dev.vantage.core.hooks.OptionalHooks.ran("Sodium: MeshingTask.notOpaque"); // this kind of hook can only be judged by whether it runs
        return solid && !Xray.active();
    }
}
