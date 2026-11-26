package dev.vantage.setting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The two list settings need the game's block and entity registries, which start without a game window. */
class ListSettingsTest {
    @BeforeAll
    static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void blockLists() {
        BlockListSetting b = new BlockListSetting("Blocks", "", List.of("minecraft:chest", "barrel"));
        assertTrue(b.contains(Blocks.CHEST));
        assertTrue(b.contains(Blocks.BARREL), "the namespace may be left out");
        assertFalse(b.contains(Blocks.STONE));
        b.toggle("minecraft:stone");
        assertTrue(b.contains(Blocks.STONE));
        JsonArray a = new JsonArray();
        a.add("minecraft:hopper");
        a.add("some_mod:unknown_block");
        assertNull(b.tryLoad(a));
        assertTrue(b.contains(Blocks.HOPPER));
        assertFalse(b.contains(Blocks.CHEST));
        assertEquals(2, b.get().size(), "an id the game does not know stays in the list");
        assertEquals(1, b.blocks().size());
        assertNotNull(b.tryLoad(new JsonPrimitive("chest")));
        assertTrue(b.contains(Blocks.HOPPER), "a refused value changes nothing");
    }

    @Test
    void entityLists() {
        EntityListSetting e = new EntityListSetting("Entities", "", List.of("minecraft:villager", "item_frame"));
        assertTrue(e.contains(EntityType.VILLAGER));
        assertTrue(e.contains(EntityType.ITEM_FRAME));
        assertFalse(e.contains(EntityType.COW));
        e.toggle("minecraft:cow");
        assertTrue(e.contains(EntityType.COW));
        e.toggle("minecraft:cow");
        assertFalse(e.contains(EntityType.COW));
        assertNotNull(e.trySet(List.of("Not An Id!")));
        JsonArray a = new JsonArray();
        a.add("minecraft:armor_stand");
        assertNull(e.tryLoad(a));
        assertEquals(1, e.types().size());
        assertTrue(e.contains(EntityType.ARMOR_STAND));
        assertEquals(a, e.save());
        assertNotNull(e.tryLoad(new JsonPrimitive(3)));
    }
}
