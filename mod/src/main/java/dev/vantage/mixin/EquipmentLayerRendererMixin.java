package dev.vantage.mixin;

import dev.vantage.module.cosmetic.HitColor;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hit Colour, armour too: vanilla submits every armour layer, glint and trim with no overlay, so armour never flashes. While a
 * wearer's overlay is remembered (HumanoidArmorLayerMixin, CapeLayerMixin), the pieces take it instead. The long overload is the
 * one with the three reads; the short one only forwards.
 */
@Mixin(EquipmentLayerRenderer.class)
public class EquipmentLayerRendererMixin {
    @Redirect(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/texture/OverlayTexture;NO_OVERLAY:I"), expect = 3)
    private int vantage$overlay() {
        return HitColor.overlayFor(OverlayTexture.NO_OVERLAY);
    }
}
