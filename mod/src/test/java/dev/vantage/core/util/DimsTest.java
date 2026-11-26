package dev.vantage.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DimsTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void idsOfTheThreeDimensionsAndOfNoWorld() {
        assertEquals(Dims.OVERWORLD, Dims.id(Level.OVERWORLD));
        assertEquals(Dims.NETHER, Dims.id(Level.NETHER));
        assertEquals(Dims.END, Dims.id(Level.END));
        assertEquals(Dims.NONE, Dims.id((Level) null));
        assertEquals("", Dims.id((net.minecraft.resources.ResourceKey<Level>) null));
    }

    @Test
    void nameChecksTakeFullAndShortIds() {
        assertTrue(Dims.isNether("minecraft:the_nether"));
        assertTrue(Dims.isNether("the_nether"));
        assertFalse(Dims.isNether("minecraft:overworld"));
        assertTrue(Dims.isOverworld("minecraft:overworld"));
        assertTrue(Dims.isOverworld("overworld"));
        assertFalse(Dims.isOverworld(""));
    }

    @Test
    void exactConversionBothWaysWithNegativesAndNoRounding() {
        assertEquals(12.5, Dims.overworldToNether(100), 0);
        assertEquals(-12.5, Dims.overworldToNether(-100), 0);
        assertEquals(0.125, Dims.overworldToNether(1), 0);
        assertEquals(-800, Dims.netherToOverworld(-100), 0);
        assertEquals(4.0, Dims.netherToOverworld(0.5), 0);
        assertEquals(-7.3, Dims.overworldToNether(Dims.netherToOverworld(-7.3)), 1e-12);
    }

    @Test
    void blockConversionFloorsLikeThePortal() {
        assertEquals(0, Dims.overworldToNetherBlock(0));
        assertEquals(0, Dims.overworldToNetherBlock(7));
        assertEquals(1, Dims.overworldToNetherBlock(8));
        assertEquals(-1, Dims.overworldToNetherBlock(-1));
        assertEquals(-1, Dims.overworldToNetherBlock(-8));
        assertEquals(-2, Dims.overworldToNetherBlock(-9));
        assertEquals(24, Dims.netherToOverworldBlock(3));
        assertEquals(-8, Dims.netherToOverworldBlock(-1));
    }

    @Test
    void fileKeyReplacesTheColonOnly() {
        assertEquals("minecraft_the_nether", Dims.fileKey("minecraft:the_nether"));
        assertEquals("mod_a-b.c_d", Dims.fileKey("mod:a-b.c/d"));
        assertEquals("", Dims.fileKey(Dims.NONE));
    }
}
