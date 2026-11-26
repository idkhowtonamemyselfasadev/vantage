package dev.vantage.mixin;

import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Skips the Sodium and Voxy hooks when those mods aren't installed, and reports the ones that did not fit when they are. */
public class MixinPlugin implements IMixinConfigPlugin {
    private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
    private static final boolean VOXY = FabricLoader.getInstance().isModLoaded("voxy");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".voxy.")) return VOXY;
        return !mixinClassName.contains(".sodium.") || SODIUM;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) {}
    /** A hook into another mod that did not fit that mod's version is noticed here, instead of silently doing nothing. */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
        if (!mixinClassName.contains(".sodium.") && !mixinClassName.contains(".voxy.")) return;
        int before = dev.vantage.core.hooks.OptionalHooks.missing().size();
        dev.vantage.core.hooks.OptionalHooks.inspect(mixinClassName, targetClass);
        var now = dev.vantage.core.hooks.OptionalHooks.missing();
        // one line per hook class, so the log shows that the check ran and what it saw
        org.slf4j.LoggerFactory.getLogger("Vantage").info("Hooks into {}: {} handler(s) of {} looked at, {} did not fit", mixinClassName.contains(".voxy.") ? "Voxy" : "Sodium",
                dev.vantage.core.hooks.OptionalHooks.handlerCount(targetClass), mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1), now.size() - before);
        for (int k = before; k < now.size(); k++) org.slf4j.LoggerFactory.getLogger("Vantage").warn("A hook did not fit the installed version of the other mod and is inactive: {}", now.get(k));
    }
}
