package dev.vantage.module.cosmetic;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Theme;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** A burst of particles where your hit lands. Spawned on your own client only, on your own attack click; the hit itself is vanilla. */
public class HitParticles extends Module {
    public final ModeSetting type = add(new ModeSetting("Particle", "Which particle bursts out", "Crit", "Crit", "Magic crit", "Spark", "Flame", "Soul flame", "End rod", "Totem", "Heart", "Dust"));
    public final NumberSetting amount = add(new NumberSetting("Amount", "Particles per hit", 10, 1, 40, 1));
    public final ModeSetting origin = add(new ModeSetting("Origin", "Where they start: the point you hit, or the target's middle", "Impact", "Impact", "Body"));
    public final BoolSetting onlyCrits = add(new BoolSetting("Only crits", "Only on a jump crit (falling, charged, not sprinting, not in water)", false));
    public final ModeSetting targets = add(new ModeSetting("Targets", "Which hits count", "Living", "Living", "Players", "All"));
    public static int spawned;

    public HitParticles() {
        super("Hit Particles", "Particles burst out where you hit", Category.COSMETIC);
    }

    public static HitParticles active() {
        HitParticles h = Modules.get(HitParticles.class);
        return h != null && h.on() ? h : null;
    }

    /** Your attack landed on this entity (called before vanilla sends it). */
    public void burst(Entity target) {
        if (mc.level == null || mc.player == null || target == null) return;
        if (targets.is("Living") && !(target instanceof LivingEntity)) return;
        if (targets.is("Players") && !(target instanceof Player)) return;
        if (onlyCrits.on() && !crit()) return;
        Vec3 at = origin.is("Impact") && mc.hitResult instanceof EntityHitResult eh && eh.getEntity() == target
                ? eh.getLocation() : target.position().add(0, target.getBbHeight() / 2, 0);
        ParticleOptions p = particle();
        var rnd = mc.level.random;
        int n = amount.i();
        for (int i = 0; i < n; i++) {
            double vx = (rnd.nextDouble() - 0.5) * 0.4, vy = rnd.nextDouble() * 0.3 + 0.05, vz = (rnd.nextDouble() - 0.5) * 0.4;
            mc.level.addParticle(p, true, true, at.x + (rnd.nextDouble() - 0.5) * 0.3, at.y + (rnd.nextDouble() - 0.5) * 0.3, at.z + (rnd.nextDouble() - 0.5) * 0.3, vx, vy, vz);
        }
        spawned += n;
    }

    /** Vanilla's crit rule, read from the client's own player. */
    private boolean crit() {
        var p = mc.player;
        return p.getAttackStrengthScale(0.5f) > 0.9f && p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater()
                && !p.hasEffect(MobEffects.BLINDNESS) && !p.isPassenger() && !p.isSprinting();
    }

    private ParticleOptions particle() {
        return switch (type.get()) {
            case "Magic crit" -> ParticleTypes.ENCHANTED_HIT;
            case "Spark" -> ParticleTypes.ELECTRIC_SPARK;
            case "Flame" -> ParticleTypes.FLAME;
            case "Soul flame" -> ParticleTypes.SOUL_FIRE_FLAME;
            case "End rod" -> ParticleTypes.END_ROD;
            case "Totem" -> ParticleTypes.TOTEM_OF_UNDYING;
            case "Heart" -> ParticleTypes.HEART;
            case "Dust" -> new DustParticleOptions(Theme.accent() & 0xFFFFFF, 1f);
            default -> ParticleTypes.CRIT;
        };
    }
}
