package dev.vantage.setting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Per-block extras for a block list: a colour per block id and a per-block off switch. Edited in the block picker
 * (right click = next colour, middle click = on/off), never shown as a row of its own. The maps are swapped whole.
 */
public class BlockColorSetting extends Setting<Map<String, Integer>> {
    /** What a right click cycles through. */
    public static final int[] PALETTE = {0xFF4AEDD9, 0xFF17DD62, 0xFFFFD84A, 0xFFE0A040, 0xFFE77C56, 0xFFFF4A4A,
            0xFFFF7FC8, 0xFF9A3CFF, 0xFF3F6BE8, 0xFFB8C0C8, 0xFFFFFFFF, 0xFF8CD7A0};

    private volatile Set<String> off = Set.of();

    public BlockColorSetting() {
        super("Block colors", "Colour and on/off per block (set in the block picker)", Map.of());
        visibleIf(() -> false);
    }

    /** The colour picked for this block, or 0 when none. */
    public int colorOr0(String id) {
        Integer c = value.get(id);
        return c == null ? 0 : c;
    }

    public boolean off(String id) {
        return off.contains(id);
    }

    public Set<String> offSet() {
        return off;
    }

    public void setColor(String id, int argb) {
        Map<String, Integer> m = new HashMap<>(value);
        m.put(id, argb);
        set(Map.copyOf(m));
    }

    public void clearColor(String id) {
        if (!value.containsKey(id)) return;
        Map<String, Integer> m = new HashMap<>(value);
        m.remove(id);
        set(Map.copyOf(m));
    }

    /** Next palette colour after the current one (starting from {@code current}); ends back on "default" after the last. */
    public void cycle(String id, int current) {
        int i = -1;
        for (int k = 0; k < PALETTE.length; k++) if (PALETTE[k] == (current | 0xFF000000)) i = k;
        if (i == PALETTE.length - 1) clearColor(id);
        else setColor(id, PALETTE[i + 1]);
    }

    public void toggleOff(String id) {
        Set<String> s = new HashSet<>(off);
        if (!s.remove(id)) s.add(id);
        off = Set.copyOf(s);
        dev.vantage.core.Config.markDirty();
    }

    @Override public JsonElement save() {
        JsonObject o = new JsonObject();
        JsonObject c = new JsonObject();
        value.forEach((k, v) -> c.addProperty(k, String.format("#%08X", v)));
        o.add("colors", c);
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        off.stream().sorted().forEach(a::add);
        o.add("off", a);
        return o;
    }

    /** Also reads the "off" set, which this setting keeps next to its value. */
    @Override protected Map<String, Integer> parse(JsonElement e) {
        if (!e.isJsonObject()) throw new Invalid("expected block colours");
        JsonObject o = e.getAsJsonObject();
        Map<String, Integer> m = new HashMap<>();
        if (o.has("colors") && o.get("colors").isJsonObject()) {
            for (var en : o.getAsJsonObject("colors").entrySet()) {
                try { m.put(en.getKey(), (int) Long.parseLong(en.getValue().getAsString().replace("#", ""), 16)); } catch (RuntimeException ignored) {}
            }
        }
        Set<String> s = new HashSet<>();
        if (o.has("off") && o.get("off").isJsonArray()) o.getAsJsonArray("off").forEach(x -> s.add(x.getAsString()));
        off = Set.copyOf(s);
        return Map.copyOf(m);
    }

    @Override public void reset() {
        value = Map.of();
        off = Set.of();
        dev.vantage.core.Config.markDirty();
    }
}
