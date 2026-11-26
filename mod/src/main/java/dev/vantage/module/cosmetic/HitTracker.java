package dev.vantage.module.cosmetic;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Remembers the few things you hit lately and what the client sees happen to their health: a drop is a damage number, a dead
 * target is a kill. Fed by the attack click (AttackMixin), read by Damage Numbers and Kill Effect. Never reads anything the
 * server would not send anyway, and looks at 8 entities at most.
 */
public final class HitTracker {
    private HitTracker() {}

    /** Health that went away on a target. */
    public record Drop(double x, double y, double z, float amount, boolean crit) {}
    /** A target that went down. */
    public record Kill(double x, double y, double z, double height, boolean player) {}

    private static final class Tracked {
        int id;
        float health;
        long lastHit;
        boolean dead;
        long critAt;
    }

    private static final int MAX = 8;
    private static final long KEEP_MS = 8000;
    private static final List<Tracked> TRACKED = new ArrayList<>();
    public static final List<Drop> DROPS = new ArrayList<>();
    public static final List<Kill> KILLS = new ArrayList<>();
    private static long lastTick = Long.MIN_VALUE;
    private static Object lastLevel;

    /** Your attack click on this entity, called as it goes out. */
    public static void hit(Entity target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(target instanceof LivingEntity le)) return;
        if (DamageNumbers.active() == null && KillEffect.active() == null) return;
        long now = System.currentTimeMillis();
        for (Tracked t : TRACKED) {
            if (t.id == le.getId()) { t.lastHit = now; if (crit()) t.critAt = now; return; }
        }
        if (TRACKED.size() >= MAX) TRACKED.remove(0);
        Tracked t = new Tracked();
        t.id = le.getId();
        t.health = le.getHealth() + le.getAbsorptionAmount();
        t.lastHit = now;
        if (crit()) t.critAt = now;
        TRACKED.add(t);
    }

    /** Vanilla's jump-crit rule, read from your own player as the click goes out. */
    private static boolean crit() {
        var p = Minecraft.getInstance().player;
        return p != null && p.getAttackStrengthScale(0.5f) > 0.9f && p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater() && !p.isSprinting() && !p.isPassenger();
    }

    /** Once per game tick (whichever module asks first): fills DROPS and KILLS for this tick. */
    public static void update() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { clear(); return; }
        if (mc.level != lastLevel) { clear(); lastLevel = mc.level; }
        long gt = mc.level.getGameTime();
        if (gt == lastTick) return;
        lastTick = gt;
        DROPS.clear();
        KILLS.clear();
        long now = System.currentTimeMillis();
        for (int i = TRACKED.size() - 1; i >= 0; i--) {
            Tracked t = TRACKED.get(i);
            Entity e = mc.level.getEntity(t.id);
            if (!(e instanceof LivingEntity le) || now - t.lastHit > KEEP_MS || t.dead && now - t.lastHit > 1500) { TRACKED.remove(i); continue; }
            float cur = le.getHealth() + le.getAbsorptionAmount();
            if (cur < t.health - 0.05f) DROPS.add(new Drop(le.getX(), le.getY() + le.getBbHeight(), le.getZ(), t.health - cur, now - t.critAt < 700));
            t.health = cur;
            if (!t.dead && le.isDeadOrDying()) {
                t.dead = true;
                KILLS.add(new Kill(le.getX(), le.getY(), le.getZ(), le.getBbHeight(), le instanceof Player));
            }
        }
    }

    public static void clear() {
        TRACKED.clear();
        DROPS.clear();
        KILLS.clear();
        lastTick = Long.MIN_VALUE;
    }
}
