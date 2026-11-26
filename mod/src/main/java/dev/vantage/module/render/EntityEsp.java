package dev.vantage.module.render;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Players, mobs, items and vehicles: boxes, the vanilla glow outline or the shader outline (render/EspOutline), tracers and name tags. */
public class EntityEsp extends Module implements dev.vantage.render.EspOutline.Source {
    public final ModeSetting mode = add(new ModeSetting("Mode", "Box, the thin vanilla glow line, both, or Shader: a thick glowing outline that hugs the model", "Box", "Box", "Glow", "Both", "Shader", "Shader + Box"));
    public final ModeSetting boxMode = add(new ModeSetting("Box style", "Outline, fill or both", "Outline", "Outline", "Fill", "Both"))
            .visibleIf(this::boxes);
    public final NumberSetting width = add(new NumberSetting("Line width", "Box line thickness", 2, 1, 5, 0.5)).visibleIf(this::boxes);
    public final NumberSetting outlineWidth = add(new NumberSetting("Outline width", "Shader outline thickness in pixels (at 1080p; scaled up on bigger screens)", 4, 1, 8, 1)).visibleIf(this::shader);
    public final NumberSetting glow = add(new NumberSetting("Glow", "How solid the shader outline is: low is a soft haze, high a hard line", 1.5, 0.2, 3, 0.1)).visibleIf(this::shader);
    public final ModeSetting shape = add(new ModeSetting("Shape", "The outline, a translucent fill of the whole model, or both", "Outline", "Outline", "Fill", "Both")).visibleIf(this::shader);
    public final NumberSetting fill = add(new NumberSetting("Fill opacity", "How strong the shader fill is", 0.15, 0.05, 1, 0.05)).visibleIf(() -> shader() && !shape.is("Outline"));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines from the crosshair", false));
    public final BoolSetting tags = add(new BoolSetting("Name tags", "Player name, health and distance through walls", true));
    public final NumberSetting range = add(new NumberSetting("Range", "Max distance", 256, 16, 1024, 16));
    public final BoolSetting players = add(new BoolSetting("Players", "", true));
    public final ModeSetting playerColorMode = add(new ModeSetting("Player color", "Fixed or by distance (red close, green far)", "Distance", "Distance", "Fixed"))
            .visibleIf(players::on);
    public final ColorSetting playerColor = add(new ColorSetting("Player fixed", "", 0xFFEEF3EE)).visibleIf(() -> players.on() && playerColorMode.is("Fixed"));
    public final BoolSetting hostiles = add(new BoolSetting("Hostiles", "", false));
    public final ColorSetting hostileColor = add(new ColorSetting("Hostile color", "", 0xFFDE6F4D)).visibleIf(hostiles::on);
    public final BoolSetting passives = add(new BoolSetting("Passives", "Animals, villagers, fish", false));
    public final ColorSetting passiveColor = add(new ColorSetting("Passive color", "", 0xFF6CBC7A)).visibleIf(passives::on);
    public final BoolSetting items = add(new BoolSetting("Items", "Dropped items", false));
    public final ColorSetting itemColor = add(new ColorSetting("Item color", "", 0xFFE0BD62)).visibleIf(items::on);
    public final BoolSetting other = add(new BoolSetting("Other", "Minecarts, boats, item frames, armor stands", false));
    public final ColorSetting otherColor = add(new ColorSetting("Other color", "", 0xFF78A8A8)).visibleIf(other::on);

    private static EntityEsp instance;

    public EntityEsp() {
        super("Entity ESP", "See players, mobs and items through walls", Category.RENDER);
        instance = this;
        dev.vantage.render.EspOutline.setSource(this);
    }

    // ---- render/EspOutline.Source: the Shader mode's numbers, read live ----
    @Override
    public boolean shaderActive() {
        return on() && shader();
    }

    @Override
    public int shapeIndex() {
        return shape.is("Outline") ? 0 : shape.is("Fill") ? 1 : 2;
    }

    @Override
    public int outlineWidth() {
        return outlineWidth.i();
    }

    @Override
    public float fillOpacity() {
        return fill.f();
    }

    @Override
    public float glowStrength() {
        return glow.f();
    }

    private int playerCol(double dist) {
        if (playerColorMode.is("Fixed")) return playerColor.argb();
        float t = (float) Math.min(1.0, dist / 64.0);
        int r = (int) (255 * Math.min(1f, 2f - 2f * t));
        int g = (int) (255 * Math.min(1f, 2f * t));
        return 0xFF000000 | r << 16 | g << 8 | 0x30;
    }

    /** 0 = don't show. */
    public int colorFor(Entity e) {
        if (e == mc.player || e == mc.getCameraEntity() || e.isRemoved()) return 0;
        if (e instanceof Player p) return !players.on() || p.isSpectator() ? 0 : dev.vantage.core.Friends.is(p) ? dev.vantage.module.client.FriendsModule.espColor() : playerCol(mc.player.distanceTo(p));
        if (e instanceof Enemy || e.getType().getCategory() == MobCategory.MONSTER) return hostiles.on() ? hostileColor.argb() : 0;
        if (e instanceof Mob || (e instanceof LivingEntity && !(e instanceof ArmorStand))) return passives.on() ? passiveColor.argb() : 0;
        if (e instanceof ItemEntity) return items.on() ? itemColor.argb() : 0;
        if (e instanceof VehicleEntity || e instanceof ItemFrame || e instanceof ArmorStand) return other.on() ? otherColor.argb() : 0;
        return 0;
    }

    /** Shader or Shader + Box: the silhouette pass (render/EspOutline) draws the outline. */
    public boolean shader() {
        return mode.is("Shader") || mode.is("Shader + Box");
    }

    /** Box, Both or Shader + Box: the 3D boxes are drawn. */
    public boolean boxes() {
        return mode.is("Box") || mode.is("Both") || mode.is("Shader + Box");
    }

    /** For the glow mixin: outline colour for this entity, or 0. Every mode but plain Box marks entities for the outline target. */
    public static int glowColor(Entity e) {
        EntityEsp m = instance;
        if (m == null || !m.on() || m.mode.is("Box") || m.mc.player == null) return 0;
        if (e.distanceToSqr(m.mc.player) > m.range.get() * m.range.get()) return 0;
        return m.colorFor(e) & 0xFFFFFF;
    }

    @Override
    public void onRender3D(R3 r) {
        boolean boxes = boxes();
        double r2 = range.get() * range.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.distanceToSqr(mc.player) > r2) continue;
            int c = colorFor(e);
            if (c == 0) continue;
            Vec3 lerp = e.getPosition(r.partial);
            AABB b = e.getBoundingBox().move(lerp.subtract(e.position()));
            if (boxes) {
                EspStyle.box(r, b, c, !boxMode.is("Fill"), !boxMode.is("Outline"), width.f(), 1f, true);
            }
            if (tracers.on()) EspStyle.tracer(r, b.getCenter(), c);
            if (tags.on() && e instanceof Player p) {
                String hp = String.valueOf((int) Math.ceil(p.getHealth() + p.getAbsorptionAmount()));
                r.label(lerp.add(0, p.getBbHeight() + 0.5, 0), (dev.vantage.core.Friends.is(p) ? "\u2605 " : "") + (dev.vantage.core.Presence.is(p.getUUID()) ? "[V] " : "") + dev.vantage.core.Streamer.name(p.getName().getString()) + "  " + hp + "hp  " + (int) mc.player.distanceTo(p) + "m", c);
            }
        }
    }

    static EntityEsp get() {
        return Modules.get(EntityEsp.class);
    }

    @Override
    public int labelClass() {
        return LABEL_NAME;
    }
}
