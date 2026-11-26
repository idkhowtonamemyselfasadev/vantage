package dev.vantage.mixin;

import java.util.Collection;
import java.util.List;

import dev.vantage.core.Streamer;
import net.minecraft.client.gui.components.debug.DebugEntryLookingAtBlock;
import net.minecraft.client.gui.components.debug.DebugEntryLookingAtFluid;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.client.gui.components.debug.DebugEntrySectionPosition;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Streamer mode: the F3 entries that print positions (XYZ, block, chunk, section, targeted block/fluid) draw "hidden" instead of the numbers. */
@Mixin({DebugEntryPosition.class, DebugEntrySectionPosition.class, DebugEntryLookingAtBlock.class, DebugEntryLookingAtFluid.class})
public class StreamerDebugMixin {
    @ModifyVariable(method = "display", at = @At("HEAD"), argsOnly = true)
    private DebugScreenDisplayer vantage$hide(DebugScreenDisplayer real) {
        if (!Streamer.hidesCoords()) return real;
        return new DebugScreenDisplayer() {
            @Override public void addPriorityLine(String s) { real.addPriorityLine(Streamer.debugLine(s)); }
            @Override public void addLine(String s) { real.addLine(Streamer.debugLine(s)); }
            @Override public void addToGroup(Identifier id, Collection<String> lines) {
                real.addToGroup(id, List.copyOf(lines).stream().map(Streamer::debugLine).toList());
            }
            @Override public void addToGroup(Identifier id, String s) { real.addToGroup(id, Streamer.debugLine(s)); }
        };
    }
}
