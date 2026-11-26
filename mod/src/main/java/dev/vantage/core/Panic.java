package dev.vantage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.vantage.gui.ClickGui;
import dev.vantage.module.client.ClickGuiModule;
import dev.vantage.module.client.PanicKey;
import dev.vantage.module.client.StreamerMode;
import net.minecraft.client.Minecraft;

/**
 * The panic key's state (beta). Pressing the key switches off every module (so nothing renders, scans or acts: this includes the
 * HUD, which is a module), closes the menu and makes the menu key and every other Vantage key dead; pressing it again switches the
 * remembered set back on, in the same order. The state is static, so a relog inside one game session keeps it. Exempt from the
 * switch-off: the menu module itself (only its key is blocked), Streamer Mode (turning the privacy filter off would reveal what
 * it hides) and the panic key.
 *
 * Config.save writes the remembered set as "enabled" while panic is on, so a game closed in panic mode starts with everything
 * back, not off.
 */
public final class Panic {
    private static boolean active;
    private static final List<String> SAVED = new ArrayList<>();

    private Panic() {}

    public static boolean active() {
        return active;
    }

    /** The set that panic switched off and will bring back. */
    public static List<String> saved() {
        return Collections.unmodifiableList(SAVED);
    }

    private static boolean exempt(Module m) {
        return m instanceof ClickGuiModule || m instanceof PanicKey || m instanceof StreamerMode;
    }

    /** Switches everything off, remembers what was on, closes the menu. */
    public static void engage() {
        if (active) return;
        SAVED.clear();
        for (Module m : Modules.all()) {
            if (m.owner != null || exempt(m) || !m.isEnabled()) continue;
            SAVED.add(m.name);
            m.setEnabled(false);
        }
        active = true;
        for (Module m : Modules.all()) if (!m.parts.isEmpty()) m.syncParts(); // parts started by hand stop too (their flag stays, so a restart in panic brings them back)
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof ClickGui) mc.setScreen(null);
        Toasts.clear();
        Vantage.LOG.info("Panic: {} modules switched off", SAVED.size());
    }

    /** Brings back exactly the remembered set. */
    public static void restore() {
        if (!active) return;
        active = false;
        for (String n : SAVED) {
            Module m = Modules.byName(n);
            if (m != null) m.setEnabled(true);
        }
        for (Module m : Modules.all()) if (!m.parts.isEmpty()) m.syncParts();
        Vantage.LOG.info("Panic over: {} modules back on", SAVED.size());
        SAVED.clear();
    }

    /** What Config.save writes as a module's "enabled": the remembered set counts as on while panic is on; the key itself is never saved on. */
    static boolean persisted(Module m) {
        if (m instanceof PanicKey) return false;
        return m.on() || m.faulted() || (active && SAVED.contains(m.name)); // a faulted module is off for the session only
    }
}
