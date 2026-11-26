package dev.vantage.core.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** A hook into another mod that did not fit is noticed from the transformed class alone. */
class OptionalHooksTest {
    private static ClassNode target(String name) {
        ClassNode c = new ClassNode();
        c.name = name;
        return c;
    }

    private static MethodNode method(ClassNode c, String name, String... calls) {
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE, name, "()V", null, null);
        for (String call : calls) m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, c.name, call, "()V", false));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        c.methods.add(m);
        return m;
    }

    @Test
    void aHandlerThatIsCalledFromTheTargetIsHookedIn() {
        ClassNode c = target("other/mod/BlockRenderer");
        method(c, "renderModel", "handler$abc000$vantage$skipBlock"); // the injected call
        method(c, "handler$abc000$vantage$skipBlock");
        assertEquals(List.of(), OptionalHooks.unhooked(c));
        assertEquals(List.of("skipBlock"), OptionalHooks.handlers(c));
    }

    @Test
    void aHandlerNothingCallsWasNotHookedIn() {
        ClassNode c = target("other/mod/BlockRenderer");
        method(c, "renderModelRenamed"); // the method the hook wanted is gone, so no call was injected
        method(c, "handler$abc000$vantage$skipBlock");
        assertEquals(List.of("skipBlock"), OptionalHooks.unhooked(c));
    }

    @Test
    void aHelperCalledOnlyByOurOwnHandlerIsNotReported() {
        ClassNode c = target("other/mod/Context");
        method(c, "isFaceCulled", "handler$abc000$vantage$allFaces");
        method(c, "handler$abc000$vantage$allFaces", "vantage$besideHidden");
        method(c, "vantage$besideHidden");
        assertEquals(List.of(), OptionalHooks.unhooked(c));
    }

    @Test
    void aCallToAMethodOfTheSameNameInAnotherClassDoesNotCount() {
        ClassNode c = target("other/mod/A");
        MethodNode m = method(c, "run");
        m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "other/mod/B", "handler$abc000$vantage$x", "()V", false));
        method(c, "handler$abc000$vantage$x");
        assertEquals(List.of("x"), OptionalHooks.unhooked(c));
    }

    @Test
    void ofTwoAlternativesOneFittingIsEnoughAndNeitherFittingIsReported() {
        ClassNode fits = target("other/mod/AbstractBlockRenderContext");
        method(fits, "isFaceCulled", "handler$a$vantage$allFaces");
        method(fits, "handler$a$vantage$allFaces");
        method(fits, "handler$b$vantage$drawSide"); // shouldDrawSide does not exist in this version
        int before = OptionalHooks.missing().size();
        OptionalHooks.inspect("dev.vantage.mixin.sodium.AbstractBlockRenderContextMixin", fits);
        assertEquals(before, OptionalHooks.missing().size(), "one of the two fits: nothing to report");

        ClassNode neither = target("other/mod/AbstractBlockRenderContext");
        method(neither, "somethingElse");
        method(neither, "handler$a$vantage$allFaces");
        method(neither, "handler$b$vantage$drawSide");
        OptionalHooks.inspect("dev.vantage.mixin.sodium.AbstractBlockRenderContextMixin", neither);
        List<String> now = OptionalHooks.missing();
        assertEquals(before + 2, now.size());
        assertTrue(now.contains("Sodium: AbstractBlockRenderContext.allFaces"), now.toString());
        assertTrue(now.contains("Sodium: AbstractBlockRenderContext.drawSide"), now.toString());
    }

    @Test
    void anExpressionHookIsNotJudgedFromTheClassButByWhetherItRuns() {
        // what the plugin really sees for these (checked on Sodium 0.8.14): the handler is merged, the call to it is not woven in yet
        ClassNode c = target("other/mod/ChunkBuilderMeshingTask");
        method(c, "execute");
        method(c, "modifyExpressionValue$zff000$vantage$lateProbe");
        assertEquals(List.of(), OptionalHooks.unhooked(c), "no false alarm");
        int before = OptionalHooks.missing().size();
        OptionalHooks.inspect("dev.vantage.mixin.sodium.LateProbeMixin", c);
        assertEquals(before, OptionalHooks.missing().size());
        assertTrue(OptionalHooks.lateMissing().contains("Sodium: LateProbe.lateProbe"), "silent so far");
        OptionalHooks.ran("Sodium: LateProbe.lateProbe");
        OptionalHooks.ran("Sodium: LateProbe.lateProbe");
        assertTrue(!OptionalHooks.lateMissing().contains("Sodium: LateProbe.lateProbe"), "it ran, so it is hooked in");
        OptionalHooks.ran("Sodium: Unknown.hook"); // a name nobody registered is ignored
    }

    @Test
    void voxyHooksAreNamedAsVoxy() {
        ClassNode c = target("me/cortex/voxy/client/core/VoxyRenderSystem");
        method(c, "renderOpaqueRenamed");
        method(c, "handler$a$vantage$baseView");
        OptionalHooks.inspect("dev.vantage.mixin.voxy.VoxyRenderSystemMixin", c);
        assertTrue(OptionalHooks.missing().contains("Voxy: VoxyRenderSystem.baseView"), OptionalHooks.missing().toString());
    }
}
