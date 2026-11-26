package dev.vantage.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.vantage.core.util.Fmt;

/** A slider. step 1 + decimals 0 behaves as an integer setting. */
public class NumberSetting extends Setting<Double> {
    public final double min, max, step;
    public final int decimals;

    public NumberSetting(String name, String description, double def, double min, double max, double step) {
        super(name, description, def);
        this.min = min;
        this.max = max;
        this.step = step;
        this.decimals = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
        if (!(step > 0) || !(min <= max)) throw new IllegalArgumentException(name + ": step must be above 0 and min not above max");
    }

    private Double legacy;

    /** A config that still holds the old default exactly was never changed by hand, so it gets the new default. */
    public NumberSetting legacy(double old) {
        legacy = old;
        return this;
    }

    public int i() {
        return (int) Math.round(value);
    }

    public float f() {
        return value.floatValue();
    }

    /** Snapped to the step and kept inside the range, whichever way the value arrives. Not-a-number and infinity are refused. */
    @Override
    protected Double validate(Double v) {
        if (v == null || v.isNaN() || v.isInfinite()) throw new Invalid("not a number");
        double s = Math.round(v / step) * step;
        return Math.max(min, Math.min(max, s));
    }

    public String text() {
        return decimals == 0 ? String.valueOf(i()) : Fmt.fixed(value, decimals);
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override protected Double parse(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new Invalid("expected a number");
        double v = e.getAsDouble();
        if (legacy != null && Math.abs(v - legacy) < 1e-9) return value; // untouched old default: keep the new one
        return v;
    }
}
