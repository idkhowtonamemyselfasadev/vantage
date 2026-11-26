package dev.vantage.setting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * A set of blocks, edited in the block picker. Read from render/mesher threads, so the
 * resolved set is swapped whole, never edited in place.
 */
public class BlockListSetting extends Setting<List<String>> {
    private volatile Set<Block> blocks = Set.of();
    /** Optional: per-block colours and on/off, and quick presets, both used by the block picker. */
    public BlockColorSetting colors;
    public final java.util.Map<String, java.util.function.Predicate<String>> presets = new java.util.LinkedHashMap<>();

    public BlockListSetting withColors(BlockColorSetting c) {
        colors = c;
        return this;
    }

    public BlockListSetting preset(String name, java.util.function.Predicate<String> ids) {
        presets.put(name, ids);
        return this;
    }

    public BlockListSetting(String name, String description, Collection<String> def) {
        super(name, description, List.copyOf(def));
        resolve();
    }

    public boolean contains(Block b) {
        return blocks.contains(b);
    }

    public Set<Block> blocks() {
        return blocks;
    }

    public void toggle(String id) {
        List<String> l = new ArrayList<>(value);
        if (!l.remove(id)) l.add(id);
        set(List.copyOf(l));
    }

    @Override
    protected void applied() {
        resolve();
    }

    private void resolve() {
        Set<Block> s = new HashSet<>();
        for (String id : value) {
            Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (rl != null) BuiltInRegistries.BLOCK.getOptional(rl).ifPresent(s::add);
        }
        blocks = Set.copyOf(s);
    }

    @Override public JsonElement save() {
        JsonArray a = new JsonArray();
        value.forEach(a::add);
        return a;
    }

    @Override protected List<String> parse(JsonElement e) {
        if (!e.isJsonArray()) throw new Invalid("expected a list of block ids");
        List<String> l = new ArrayList<>();
        e.getAsJsonArray().forEach(x -> l.add(x.getAsString()));
        return List.copyOf(l);
    }
}
