package dev.vantage.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A whole number inside a range. For numbers with decimals and a step use {@link NumberSetting}. */
public class IntSetting extends Setting<Integer> {
    public final int min, max;

    public IntSetting(String name, String description, int def, int min, int max) {
        super(name, description, def);
        if (min > max || def < min || def > max) throw new IllegalArgumentException(name + ": the default must lie inside min..max");
        this.min = min;
        this.max = max;
    }

    public int i() {
        return value;
    }

    /** Kept inside the range, whichever way the value arrives. */
    @Override
    protected Integer validate(Integer v) {
        if (v == null) throw new Invalid("not a number");
        return Math.max(min, Math.min(max, v));
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override protected Integer parse(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new Invalid("expected a whole number");
        double d = e.getAsDouble();
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) throw new Invalid("expected a whole number");
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, d));
    }
}
