package dev.vantage.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * A key or mouse button. The value is a GLFW key code, {@link #NONE} for no key, or a mouse button stored as
 * {@code MOUSE_BASE - button} (so the left button is -100, the right one -101), the same numbers the module keys have always used.
 */
public class KeySetting extends Setting<Integer> {
    public static final int NONE = -1, MOUSE_BASE = -100;
    /** GLFW's lowest and highest key codes (space is 32, GLFW_KEY_LAST is 348) and its 8 mouse buttons. */
    private static final int KEY_MIN = 32, KEY_MAX = 348, MOUSE_BUTTONS = 8;

    public KeySetting(String name, String description, int def) {
        super(name, description, def);
        if (!usable(def)) throw new IllegalArgumentException(name + ": " + def + " is not a key");
    }

    public static boolean usable(int code) {
        return code == NONE || (code >= KEY_MIN && code <= KEY_MAX) || (code <= MOUSE_BASE && code > MOUSE_BASE - MOUSE_BUTTONS);
    }

    public static int mouse(int button) {
        return MOUSE_BASE - button;
    }

    public boolean isSet() {
        return value != NONE;
    }

    public boolean isMouse() {
        return value <= MOUSE_BASE;
    }

    /** The mouse button number, for a value that {@link #isMouse}. */
    public int button() {
        return MOUSE_BASE - value;
    }

    public void clear() {
        set(NONE);
    }

    @Override
    protected Integer validate(Integer v) {
        if (v == null || !usable(v)) throw new Invalid("not a key");
        return v;
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override protected Integer parse(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new Invalid("expected a key code");
        return e.getAsInt();
    }
}
