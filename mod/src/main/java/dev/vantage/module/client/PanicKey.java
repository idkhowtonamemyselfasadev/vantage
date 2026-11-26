package dev.vantage.module.client;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Panic;

/**
 * Panic key (beta). A module because the Keybinds page and the key polling (Keys) already work on modules: its bind is set there
 * like any other, unbound by default. Its on/off state IS the panic state: pressing the key (or its switch) toggles it, on = every
 * other module is off. See {@link Panic}.
 */
public class PanicKey extends Module {
    public PanicKey() {
        super("Panic Key", "One key switches everything off (modules, HUD, menu) and the same key brings back exactly what was on. Bind it in Keybinds; unbound by default", Category.CLIENT);
        beta();
    }

    @Override
    protected void onEnable() {
        Panic.engage();
    }

    @Override
    protected void onDisable() {
        Panic.restore();
    }

    @Override
    public String info() {
        return Panic.active() ? "panic" : null;
    }
}
