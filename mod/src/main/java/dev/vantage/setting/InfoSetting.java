package dev.vantage.setting;

import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A read-only line on a settings page: the name on the left, a live text on the right. Holds nothing and is never saved. */
public class InfoSetting extends Setting<String> {
    private final Supplier<String> text;

    public InfoSetting(String name, String description, Supplier<String> text) {
        super(name, description, "");
        this.text = text;
    }

    /** The text as of now. */
    public String text() {
        try {
            String t = text.get();
            return t == null ? "" : t;
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override public JsonElement save() { return JsonNull.INSTANCE; }

    @Override protected String parse(JsonElement e) { return value; }
}
