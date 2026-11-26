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
import net.minecraft.world.entity.EntityType;

/**
 * A set of entity types, stored as ids ("minecraft:villager"; the namespace may be left out). Built like {@link BlockListSetting}:
 * the resolved set is swapped whole, never edited in place, so another thread may read it. Ids the game does not know are kept in
 * the list (a config written with another mod set must not lose them) but match nothing.
 */
public class EntityListSetting extends Setting<List<String>> {
    private volatile Set<EntityType<?>> types = Set.of();

    public EntityListSetting(String name, String description, Collection<String> def) {
        super(name, description, List.copyOf(def));
        resolve();
    }

    public boolean contains(EntityType<?> t) {
        return types.contains(t);
    }

    public Set<EntityType<?>> types() {
        return types;
    }

    public void toggle(String id) {
        List<String> l = new ArrayList<>(value);
        if (!l.remove(id)) l.add(id);
        set(List.copyOf(l));
    }

    @Override
    protected List<String> validate(List<String> v) {
        if (v == null) throw new Invalid("no list");
        for (String id : v) if (id == null || Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id) == null) throw new Invalid("'" + id + "' is not an entity id");
        return List.copyOf(v);
    }

    @Override
    protected void applied() {
        resolve();
    }

    private void resolve() {
        Set<EntityType<?>> s = new HashSet<>();
        for (String id : value) {
            Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (rl != null) BuiltInRegistries.ENTITY_TYPE.getOptional(rl).ifPresent(s::add);
        }
        types = Set.copyOf(s);
    }

    @Override public JsonElement save() {
        JsonArray a = new JsonArray();
        value.forEach(a::add);
        return a;
    }

    @Override protected List<String> parse(JsonElement e) {
        if (!e.isJsonArray()) throw new Invalid("expected a list of entity ids");
        List<String> l = new ArrayList<>();
        e.getAsJsonArray().forEach(x -> l.add(x.getAsString()));
        return l;
    }
}
