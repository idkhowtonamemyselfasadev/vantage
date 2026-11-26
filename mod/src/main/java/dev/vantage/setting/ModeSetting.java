package dev.vantage.setting;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Pick one of a few words. Left click goes forward, right click back. */
public class ModeSetting extends Setting<String> {
    public final List<String> modes;

    public ModeSetting(String name, String description, String def, String... modes) {
        super(name, description, def);
        this.modes = List.of(modes);
    }

    public boolean is(String m) {
        return value.equals(m);
    }

    public void cycle(int dir) {
        int i = modes.indexOf(value);
        set(modes.get(Math.floorMod(i + dir, modes.size())));
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override protected String validate(String v) {
        if (v == null || !modes.contains(v)) throw new Invalid("must be one of: " + String.join(", ", modes));
        return v;
    }

    @Override protected String parse(JsonElement e) {
        if (!e.isJsonPrimitive()) throw new Invalid("expected one of: " + String.join(", ", modes));
        return e.getAsString();
    }
}
