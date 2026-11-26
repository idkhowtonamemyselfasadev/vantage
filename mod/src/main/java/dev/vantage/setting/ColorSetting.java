package dev.vantage.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.vantage.core.util.Colors;

/** ARGB colour. The GUI edits it with a hue bar and an alpha bar. */
public class ColorSetting extends Setting<Integer> {
    public ColorSetting(String name, String description, int argb) {
        super(name, description, argb);
    }

    public int argb() {
        return value;
    }

    /** Same colour with a different alpha (0-255). */
    public int withAlpha(int a) {
        return Colors.withAlpha(value, a);
    }

    @Override public JsonElement save() { return new JsonPrimitive(Colors.hex(value)); }

    @Override protected Integer parse(JsonElement e) {
        if (!e.isJsonPrimitive()) throw new Invalid("expected a colour like #FF3366CC");
        String hex = e.getAsString().replace("#", "");
        if (!hex.matches("[0-9a-fA-F]{1,8}")) throw new Invalid("expected a colour like #FF3366CC");
        return Colors.parse(hex);
    }
}
