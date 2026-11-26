package dev.vantage.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BoolSetting extends Setting<Boolean> {
    public BoolSetting(String name, String description, boolean def) {
        super(name, description, def);
    }

    public boolean on() {
        return value;
    }

    public void toggle() {
        set(!value);
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override protected Boolean parse(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new Invalid("expected true or false");
        return e.getAsBoolean();
    }
}
