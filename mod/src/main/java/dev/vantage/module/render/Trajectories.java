package dev.vantage.module.render;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Draws the flight path of whatever you would throw or shoot right now, and where it lands. Only draws. */
public class Trajectories extends Module {
    public final BoolSetting onlyCharging = add(new BoolSetting("Only while aiming", "Bow/trident path only while drawing it", false));
    public final BoolSetting entities = add(new BoolSetting("Entity hits", "Stop the path at mobs/players and box them", true));
    public final ColorSetting color = add(new ColorSetting("Color", "Path colour", 0xFF6CBC7A));
    public final ColorSetting hitColor = add(new ColorSetting("Hit color", "Colour when it would hit an entity", 0xFFDE6F4D));

    private record Kind(double speed, double gravity, double drag, float pitchOffset) {}

    public Trajectories() {
        super("Trajectories", "Shows where arrows, pearls, potions and tridents will land", Category.RENDER);
    }

    private Kind kind(Player p, ItemStack s) {
        Item i = s.getItem();
        boolean using = p.isUsingItem() && p.getUseItem() == s;
        if (i instanceof BowItem) {
            if (onlyCharging.on() && !using) return null;
            float power = using ? BowItem.getPowerForTime(p.getTicksUsingItem()) : 1f;
            if (power < 0.1f) power = 0.1f;
            return new Kind(power * 3.0, 0.05, 0.99, 0);
        }
        if (i instanceof CrossbowItem) return CrossbowItem.isCharged(s) ? new Kind(3.15, 0.05, 0.99, 0) : null;
        if (i instanceof TridentItem) return onlyCharging.on() && !using ? null : new Kind(2.5, 0.05, 0.99, 0);
        if (i instanceof SnowballItem || i instanceof EggItem || i instanceof EnderpearlItem) return new Kind(1.5, 0.03, 0.99, 0);
        if (i instanceof ThrowablePotionItem) return new Kind(0.5, 0.05, 0.99, -20);
        if (i instanceof ExperienceBottleItem) return new Kind(0.7, 0.07, 0.99, -20);
        if (s.is(Items.WIND_CHARGE)) return new Kind(1.5, 0.0, 1.0, 0);
        return null;
    }

    @Override
    public void onRender3D(R3 r) {
        Player p = mc.player;
        Kind k = kind(p, p.getMainHandItem());
        if (k == null) k = kind(p, p.getOffhandItem());
        if (k == null) return;
        float t = r.partial;
        float yaw = (float) Math.toRadians(p.getViewYRot(t)), pitch = (float) Math.toRadians(p.getViewXRot(t));
        float pitchOff = (float) Math.toRadians(p.getViewXRot(t) + k.pitchOffset());
        Vec3 dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitchOff), Math.cos(yaw) * Math.cos(pitch)).normalize();
        Vec3 vel = dir.scale(k.speed());
        Vec3 m = p.getDeltaMovement();
        vel = vel.add(m.x, p.onGround() ? 0 : m.y, m.z);
        Vec3 eye = p.getPosition(t).add(0, p.getEyeHeight() - 0.1, 0);
        // start the drawn line a little to the side so it does not sit right on the crosshair
        Vec3 pos = eye;
        Vec3 prev = eye.add(-Math.cos(yaw) * 0.2, -0.1, -Math.sin(yaw) * 0.2);
        int col = color.argb();
        HitResult hit = null;
        java.util.List<Vec3> pts = new java.util.ArrayList<>();
        pts.add(prev);
        for (int step = 0; step < 300; step++) {
            Vec3 next = pos.add(vel);
            BlockHitResult bh = mc.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            Vec3 end = bh.getType() == HitResult.Type.MISS ? next : bh.getLocation();
            if (entities.on()) {
                EntityHitResult eh = ProjectileUtil.getEntityHitResult(mc.level, p, pos, end,
                        new AABB(pos, end).inflate(1.0), e -> e != p && e.isPickable() && !e.isSpectator(), 0.3f);
                if (eh != null) { hit = eh; pts.add(eh.getLocation()); break; }
            }
            if (bh.getType() != HitResult.Type.MISS) { hit = bh; pts.add(end); break; }
            pts.add(next);
            pos = next;
            vel = vel.scale(k.drag()).subtract(0, k.gravity(), 0);
            if (pos.y < mc.level.getMinY() - 16) break;
        }
        if (hit instanceof EntityHitResult eh) col = hitColor.argb();
        for (int i = 1; i < pts.size(); i++) {
            Vec3 a = pts.get(i - 1), b = pts.get(i);
            r.line(a.x, a.y, a.z, b.x, b.y, b.z, col, EspStyle.WEIGHT, false);
        }
        if (hit instanceof EntityHitResult eh) {
            Entity e = eh.getEntity();
            AABB bb = e.getBoundingBox().move(e.getPosition(t).subtract(e.position()));
            EspStyle.box(r, bb, col, true, true, EspStyle.STD, 1f, true);
        } else if (hit instanceof BlockHitResult bh) {
            Vec3 l = bh.getLocation();
            double s = 0.25;
            AABB marker = switch (bh.getDirection().getAxis()) {
                case Y -> new AABB(l.x - s, l.y - 0.01, l.z - s, l.x + s, l.y + 0.01, l.z + s);
                case X -> new AABB(l.x - 0.01, l.y - s, l.z - s, l.x + 0.01, l.y + s, l.z + s);
                case Z -> new AABB(l.x - s, l.y - s, l.z - 0.01, l.x + s, l.y + s, l.z + 0.01);
            };
            EspStyle.box(r, marker, col, true, true, EspStyle.STD, 1f, false);
        }
    }
}
