package dev.vantage.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A line of text, typed straight into the menu (click the box, type, Enter or Esc to finish). */
public class TextSetting extends Setting<String> {
    public static final int MAX = 160;

    public TextSetting(String name, String description, String def) {
        super(name, description, def);
    }

    public String text() {
        return value;
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }
    /** Longer text is cut to MAX characters, and line breaks are dropped: the value is one line in the menu and in chat. */
    @Override protected String validate(String v) {
        if (v == null) throw new Invalid("no text");
        String one = v.replace("\r", "").replace("\n", " ");
        return one.length() > MAX ? one.substring(0, MAX) : one;
    }

    @Override protected String parse(JsonElement e) {
        if (!e.isJsonPrimitive()) throw new Invalid("expected text");
        return e.getAsString();
    }
}
