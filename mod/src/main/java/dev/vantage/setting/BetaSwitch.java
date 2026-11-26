package dev.vantage.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import dev.vantage.core.Modules;
import dev.vantage.core.Toasts;

/**
 * The "Beta features" switch on the settings page. It is not a stored value: it shows and changes whether the file
 * config/vantage/beta exists (the wanted state), never what this game start loaded. Switching writes or deletes the file and says
 * plainly that the change applies at the next start, because modules are registered once at startup. Never saved to config.json,
 * profiles or share codes (so nobody can switch it on for you with a code).
 */
public class BetaSwitch extends BoolSetting {
    public BetaSwitch(String name, String description) {
        super(name, description, false);
        storedIf(() -> false);
        value = Modules.betaFileExists();
    }

    @Override public boolean on() { return Modules.betaFileExists(); }

    @Override public Boolean get() { return on(); }

    @Override public void toggle() { set(!on()); }

    @Override
    public String trySet(Boolean v) {
        if (v == null) return "no value";
        if (v == on()) return null;
        String why = Modules.setBetaFile(v);
        if (why != null) {
            Toasts.warn("Beta features", "Not changed: " + why);
            return why;
        }
        value = v;
        Toasts.ok("Beta features " + (v ? "on" : "off"), Modules.betaNeedsRestart() ? "Restart the game to apply" : "Same as this start, no restart needed");
        return null;
    }

    @Override public JsonElement save() { return JsonNull.INSTANCE; }

    @Override public boolean isDefault() { return !on(); }
}
