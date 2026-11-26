package dev.vantage.core.hooks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Notices when a hook into another mod (Sodium, Voxy) did not fit the installed version of that mod.
 *
 * Hooks into the game itself are required: a wrong target stops the start, which the check script catches before a release. Hooks
 * into other mods cannot be required, because those mods change on their own schedule and the client must still start. Until now
 * such a hook simply did nothing when its target was gone, and nobody was told. Here every hook class is looked at once, right after
 * it was applied: a handler method of ours that was merged into the target class but is called from nowhere in it was not hooked
 * in. The result is logged at once and shown to the user when they join a world.
 *
 * Two of the hooks cannot be judged that way: handlers that change an expression (MixinExtras) are woven in after the moment this
 * class gets to look, so at that moment none of them is called yet (checked against Sodium 0.8.14 by exporting the final classes).
 * Those are judged by whether they have ever run: each calls {@link #ran} when it does, and one that is still silent ten seconds
 * into a world, with chunks being built and drawn all the time, was not hooked in.
 *
 * This class is used while classes are still being transformed, so it must not touch any game class.
 */
public final class OptionalHooks {
    private OptionalHooks() {}

    /** Every handler of ours has this in its name (the methods are named vantage$something). */
    private static final String MARK = "vantage$";

    /** Hooks of which only one fits any given version of the other mod: the group is fine when at least one member is hooked in. */
    private static final Map<String, Set<String>> ALTERNATIVES = Map.of(
            "AbstractBlockRenderContextMixin", Set.of("allFaces", "drawSide")); // Sodium renamed isFaceCulled to shouldDrawSide and back between versions

    private static final List<String> MISSING = new ArrayList<>();
    /** Hooks that are judged by whether they run: name, and whether it has been seen running. */
    private static final Map<String, java.util.concurrent.atomic.AtomicBoolean> LATE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Called for each optional hook class after it was applied to its target. */
    public static synchronized void inspect(String mixinClassName, ClassNode target) {
        String mixin = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        String modName = mixinClassName.contains(".voxy.") ? "Voxy" : "Sodium";
        for (MethodNode m : target.methods) {
            if (m.name.contains(MARK) && !judgedNow(m.name)) LATE.putIfAbsent(modName + ": " + mixin.replace("Mixin", "") + "." + shortName(m.name), new java.util.concurrent.atomic.AtomicBoolean());
        }
        List<String> notHooked = unhooked(target);
        Set<String> alternatives = ALTERNATIVES.getOrDefault(mixin, Set.of());
        if (!alternatives.isEmpty()) {
            List<String> ours = handlers(target);
            boolean oneFits = alternatives.stream().anyMatch(a -> ours.contains(a) && !notHooked.contains(a));
            if (oneFits) notHooked.removeAll(alternatives);
        }
        for (String hook : notHooked) MISSING.add(modName + ": " + mixin.replace("Mixin", "") + "." + hook);
    }

    /**
     * Handlers of the ordinary kind (named handler$... once merged) and our own helper methods are woven in by the time the class
     * is looked at. Everything else carrying our mark (modifyExpressionValue$... and the like) is woven in later.
     */
    private static boolean judgedNow(String mergedName) {
        return mergedName.startsWith("handler$") || mergedName.startsWith(MARK);
    }

    /** Called by a late-woven handler every time it runs; the name is the one {@link #lateMissing} reports, e.g. "Sodium: MeshingTask.notOpaque". */
    public static void ran(String hook) {
        java.util.concurrent.atomic.AtomicBoolean seen = LATE.get(hook);
        if (seen != null && !seen.get()) seen.set(true); // the read is what runs on the hot path; the write happens once
    }

    /** The late-woven hooks that have not run so far. Meaningful once a world has been on screen for a few seconds. */
    public static List<String> lateMissing() {
        List<String> out = new ArrayList<>();
        LATE.forEach((name, seen) -> { if (!seen.get()) out.add(name); });
        java.util.Collections.sort(out);
        return out;
    }

    /** How many hooks are judged by running, for the log line at start. */
    public static int lateCount() {
        return LATE.size();
    }

    /** "Sodium: BlockRenderer.skipBlock" for every hook that did not fit; empty when all did. */
    public static synchronized List<String> missing() {
        return List.copyOf(MISSING);
    }

    public static int handlerCount(ClassNode target) {
        return handlers(target).size();
    }

    /** The short names of our handler methods in a class (the part after vantage$). */
    static List<String> handlers(ClassNode target) {
        List<String> out = new ArrayList<>();
        for (MethodNode m : target.methods) if (m.name.contains(MARK)) out.add(shortName(m.name));
        return out;
    }

    /** Our handler methods that nothing in the class calls: their injection did not happen. */
    static List<String> unhooked(ClassNode target) {
        Set<String> called = new HashSet<>();
        for (MethodNode m : target.methods) {
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof MethodInsnNode call && call.owner.equals(target.name)) called.add(call.name);
            }
        }
        List<String> out = new ArrayList<>();
        for (MethodNode m : target.methods) if (m.name.contains(MARK) && judgedNow(m.name) && !called.contains(m.name)) out.add(shortName(m.name));
        return out;
    }

    private static String shortName(String merged) {
        return merged.substring(merged.lastIndexOf(MARK) + MARK.length());
    }
}
