package dev.vantage.core;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vantage.gui.ClickGui;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Module keys, polled straight from GLFW. No vanilla KeyMappings are registered, so nothing of this mod
 * shows up in Options > Controls or in the game's translation table.
 */
public final class Keys {
    /** Last seen state per bound key/button, so a press fires once. */
    private static final java.util.Map<Integer, Boolean> last = new java.util.HashMap<>();

    /** Is this bind held right now. */
    public static boolean down(Minecraft mc, int bind) {
        return isDown(mc, bind);
    }

    /** Test hook: pretend this bind is held (true) or not (false); null removes the override. */
    private static final java.util.Map<Integer, Boolean> TEST = new java.util.HashMap<>();

    public static void testDown(int bind, Boolean down) {
        if (down == null) TEST.remove(bind); else TEST.put(bind, down);
    }

    private static boolean isDown(Minecraft mc, int bind) {
        Boolean t = TEST.get(bind);
        if (t != null) return t;
        long win = mc.getWindow().handle();
        if (bind <= -100) {
            int btn = -100 - bind;
            return btn >= 0 && btn <= GLFW.GLFW_MOUSE_BUTTON_LAST && GLFW.glfwGetMouseButton(win, btn) == GLFW.GLFW_PRESS;
        }
        return bind >= GLFW.GLFW_KEY_SPACE && bind <= GLFW.GLFW_KEY_LAST && InputConstants.isKeyDown(mc.getWindow(), bind);
    }

    public static void tick(Minecraft mc) {
        if (Terms.off()) { last.clear(); return; } // the terms gate: no Vantage key does anything
        boolean free = mc.screen == null; // only when no screen/chat is open
        boolean panic = Panic.active();
        java.util.Map<Integer, Boolean> now = new java.util.HashMap<>();
        for (Module m : Modules.all()) {
            int b = m.bind;
            if (b == -1 || (m.owner != null && !m.holdKey() && !m.keepsStandalone)) continue; // a hold key like the wheel keeps working inside its owner
            boolean panicKey = m instanceof dev.vantage.module.client.PanicKey;
            if (panic && !panicKey) continue; // panic: only the panic key works, not even the menu key
            if (Modules.paused() && !panic && !(m instanceof dev.vantage.module.client.ClickGuiModule)) continue; // master switch off: only the menu key works
            boolean d = now.computeIfAbsent(b, k -> isDown(mc, k));
            boolean was = last.getOrDefault(b, d); // a key bound while held doesn't fire
            // the panic key also works with the menu open, but the menu's search box takes every typed character: a key that
            // produces one types instead (and while a key is being bound it only binds); F-keys, Insert, Pause... always fire
            boolean inMenu = panicKey && mc.screen instanceof ClickGui cg && !cg.capturingKey() && !producesCharacter(b);
            if (d && !was && (free || inMenu)) {
                if (m instanceof dev.vantage.module.client.ClickGuiModule) mc.setScreen(new ClickGui());
                else if (m.holdKey()) { if (m.on()) m.onBindPress(); }
                else if (m.keepsStandalone && m.owner != null) { for (Module.Part p : m.owner.parts) if (p.module() == m) p.keyToggle(); } // a part that kept its old key: the key runs it by hand
                else m.toggle();
            }
        }
        last.clear();
        last.putAll(now);
    }

    /** Does pressing this key type something (letters, digits, space, punctuation, keypad)? Mouse buttons and the rest do not. */
    public static boolean producesCharacter(int key) {
        if (key <= -100) return false;
        return key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_APOSTROPHE || (key >= GLFW.GLFW_KEY_COMMA && key <= GLFW.GLFW_KEY_9)
                || key == GLFW.GLFW_KEY_SEMICOLON || key == GLFW.GLFW_KEY_EQUAL || (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_RIGHT_BRACKET)
                || key == GLFW.GLFW_KEY_GRAVE_ACCENT || key == GLFW.GLFW_KEY_WORLD_1 || key == GLFW.GLFW_KEY_WORLD_2
                || (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_EQUAL && key != GLFW.GLFW_KEY_KP_ENTER);
    }

    public static String name(int bind) {
        if (bind == -1) return "none";
        if (bind <= -100) return "MOUSE" + (-100 - bind + 1);
        String n = GLFW.glfwGetKeyName(bind, 0);
        if (n != null) return n.toUpperCase();
        return switch (bind) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
            case GLFW.GLFW_KEY_INSERT -> "INSERT";
            case GLFW.GLFW_KEY_DELETE -> "DELETE";
            case GLFW.GLFW_KEY_HOME -> "HOME";
            case GLFW.GLFW_KEY_END -> "END";
            case GLFW.GLFW_KEY_PAGE_UP -> "PGUP";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PGDN";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPS";
            case GLFW.GLFW_KEY_UP -> "UP";
            case GLFW.GLFW_KEY_DOWN -> "DOWN";
            case GLFW.GLFW_KEY_LEFT -> "LEFT";
            case GLFW.GLFW_KEY_RIGHT -> "RIGHT";
            default -> bind >= GLFW.GLFW_KEY_F1 && bind <= GLFW.GLFW_KEY_F25 ? "F" + (bind - GLFW.GLFW_KEY_F1 + 1)
                    : bind >= GLFW.GLFW_KEY_KP_0 && bind <= GLFW.GLFW_KEY_KP_9 ? "NUM" + (bind - GLFW.GLFW_KEY_KP_0) : "KEY" + bind;
        };
    }

    private Keys() {}
}
