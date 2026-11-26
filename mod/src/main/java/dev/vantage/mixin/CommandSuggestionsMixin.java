package dev.vantage.mixin;

import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tab completion of client commands: after the game worked out its own suggestions for the chat box, ours replace them when the line starts with the command prefix. */
@Mixin(CommandSuggestions.class)
public class CommandSuggestionsMixin {
    @Shadow @Final EditBox input;
    @Shadow private CompletableFuture<Suggestions> pendingSuggestions;

    @Inject(method = "updateCommandInfo", at = @At("RETURN"))
    private void vantage$commands(CallbackInfo ci) {
        CompletableFuture<Suggestions> ours = dev.vantage.core.ChatCompletion.forLine(input.getValue(), input.getCursorPosition());
        if (ours != null) pendingSuggestions = ours;
    }
}
