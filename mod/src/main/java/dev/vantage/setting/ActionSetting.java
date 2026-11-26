package dev.vantage.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A button row in the GUI, e.g. "Open waypoints" or "Clear finds". Not saved. */
public class ActionSetting extends Setting<Boolean> {
    public final Runnable action;

    public ActionSetting(String name, String description, Runnable action) {
        super(name, description, false);
        this.action = action;
    }

    @Override public JsonElement save() { return JsonNull.INSTANCE; }
    @Override protected Boolean parse(JsonElement e) { return value; }
}
