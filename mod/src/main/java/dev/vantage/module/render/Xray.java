package dev.vantage.module.render;

import java.util.List;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.BlockListSetting;
import net.minecraft.world.level.block.state.BlockState;

/** Only the listed blocks get meshed. The chunk mesher runs on worker threads, hence the volatile flag. */
public class Xray extends Module {
    public static final List<String> DEFAULT = List.of(
            "diamond_ore", "deepslate_diamond_ore", "ancient_debris", "emerald_ore", "deepslate_emerald_ore",
            "gold_ore", "deepslate_gold_ore", "nether_gold_ore", "iron_ore", "deepslate_iron_ore",
            "redstone_ore", "deepslate_redstone_ore", "lapis_ore", "deepslate_lapis_ore", "copper_ore",
            "deepslate_copper_ore", "coal_ore", "deepslate_coal_ore", "nether_quartz_ore",
            "chest", "trapped_chest", "barrel", "ender_chest", "spawner", "trial_spawner", "shulker_box",
            "white_shulker_box", "orange_shulker_box", "magenta_shulker_box", "light_blue_shulker_box",
            "yellow_shulker_box", "lime_shulker_box", "pink_shulker_box", "gray_shulker_box",
            "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box", "blue_shulker_box",
            "brown_shulker_box", "green_shulker_box", "red_shulker_box", "black_shulker_box",
            "bedrock", "obsidian", "crying_obsidian", "nether_portal", "end_portal_frame");

    /** Natural terrain hidden by the freecam base view; everything else (player blocks, structures) stays. */
    public static final List<String> NATURAL = List.of(
            "stone", "deepslate", "tuff", "granite", "diorite", "andesite", "calcite", "smooth_basalt", "dirt",
            "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "mud", "clay", "gravel", "sand", "red_sand",
            "sandstone", "red_sandstone", "terracotta", "white_terracotta", "orange_terracotta", "yellow_terracotta",
            "brown_terracotta", "red_terracotta", "light_gray_terracotta", "bedrock", "netherrack", "basalt",
            "blackstone", "soul_sand", "soul_soil", "magma_block", "end_stone", "dripstone_block", "pointed_dripstone",
            "moss_block", "moss_carpet", "pale_moss_block", "pale_moss_carpet", "glow_lichen", "ice", "packed_ice", "blue_ice", "snow", "snow_block",
            "powder_snow", "amethyst_block", "budding_amethyst", "small_amethyst_bud", "medium_amethyst_bud",
            "sculk", "sculk_vein", "sculk_sensor", "sculk_shrieker", "sculk_catalyst", "short_grass", "tall_grass",
            "fern", "large_fern", "dead_bush", "vine", "cave_vines", "cave_vines_plant", "hanging_roots", "spore_blossom",
            "azalea", "flowering_azalea", "big_dripleaf", "big_dripleaf_stem", "small_dripleaf", "seagrass",
            "tall_seagrass", "kelp", "kelp_plant", "infested_stone", "infested_deepslate", "warped_nylium",
            "crimson_nylium", "nether_sprouts", "warped_roots", "crimson_roots", "weeping_vines", "weeping_vines_plant",
            "twisting_vines", "twisting_vines_plant", "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves",
            "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "cherry_leaves", "azalea_leaves",
            "flowering_azalea_leaves", "pale_oak_leaves", "bush", "firefly_bush", "short_dry_grass", "tall_dry_grass", "leaf_litter",
            "large_amethyst_bud", "amethyst_cluster", "ancient_debris", "mangrove_roots", "muddy_mangrove_roots",
            "brown_mushroom", "red_mushroom", "brown_mushroom_block", "red_mushroom_block", "mushroom_stem",
            "crimson_fungus", "warped_fungus", "nether_wart_block", "warped_wart_block", "shroomlight",
            "suspicious_sand", "suspicious_gravel", "sugar_cane", "bamboo", "cactus", "lily_pad", "pumpkin", "melon",
            "sweet_berry_bush", "sea_pickle", "cobbled_deepslate", "infested_cobblestone");

    private static volatile java.util.Set<net.minecraft.world.level.block.Block> natural;
    /** Set by Freecam while the camera is underground with Base view on. */
    private static volatile boolean override;

    private static volatile boolean active;
    private static Xray instance;
    private boolean savedSmartCull = true;

    public final BlockListSetting blocks = add(new BlockListSetting("Blocks", "Blocks that stay visible", DEFAULT)
            .onChange(this::rebuild));

    public Xray() {
        super("Xray", "See ores and storage through the ground", Category.RENDER);
        instance = this;
    }

    public static boolean active() {
        return active || override;
    }

    public static boolean shows(BlockState state) {
        if (active) return instance.blocks.contains(state.getBlock());
        var n = natural;
        return n != null && !n.contains(state.getBlock());
    }

    /** Freecam base view: hide natural terrain without touching the Xray module itself. */
    public static void setOverride(boolean v) {
        if (override == v) return;
        if (v && natural == null) {
            java.util.Set<net.minecraft.world.level.block.Block> s = new java.util.HashSet<>();
            for (String id : NATURAL) {
                var key = net.minecraft.resources.Identifier.withDefaultNamespace(id);
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(key).ifPresent(s::add);
            }
            // whole families: trunks and ore veins were left floating in the air
            for (var b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
                if (n.endsWith("_ore") || (n.endsWith("_log") && !n.startsWith("stripped_")) || n.endsWith("_stem") && !n.startsWith("stripped_")
                        || n.endsWith("_coral") || n.endsWith("_coral_block") || n.endsWith("_coral_fan") || n.endsWith("_coral_wall_fan")
                        || b.defaultBlockState().is(net.minecraft.tags.BlockTags.FLOWERS)) s.add(b);
            }
            natural = s;
        }
        override = v;
        if (!active && instance != null) instance.rebuild();
    }

    @Override
    protected void onEnable() {
        savedSmartCull = mc.smartCull || FreeLook.holdingCull(); // Free Look may have it off for a see-through look: that is not the value to give back
        mc.smartCull = false; // vanilla cave culling would hide sections behind stone
        active = true;
        rebuild();
    }

    @Override
    protected void onDisable() {
        mc.smartCull = savedSmartCull;
        active = false;
        rebuild();
    }

    private void rebuild() {
        if (mc.levelRenderer != null && mc.level != null) mc.levelRenderer.allChanged();
    }

    @Override
    public void onJoinWorld() {
        hideCullingForNewWorld();
    }

    @Override
    public void onDimensionChange() {
        hideCullingForNewWorld();
    }

    private void hideCullingForNewWorld() {
        if (active && mc.level != null) mc.smartCull = false;
    }

    static Xray get() {
        return Modules.get(Xray.class);
    }
}
