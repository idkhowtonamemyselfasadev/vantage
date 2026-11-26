package dev.vantage.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A section title between settings on a module's page. Holds nothing and is never saved. */
public class Header extends Setting<String> {
    public Header(String name) {
        super(name, "", name);
    }

    @Override public JsonElement save() { return JsonNull.INSTANCE; }

    @Override protected String parse(JsonElement e) { return value; }
}
