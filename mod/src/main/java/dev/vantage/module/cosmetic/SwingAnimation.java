package dev.vantage.module.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * How your first-person arm swings: six styles, a tempo and a size. Replaces only the drawn motion of your own arm and the
 * client's copy of the swing length; attack timing and reach are vanilla's.
 */
public class SwingAnimation extends Module {
    public final ModeSetting style = add(new ModeSetting("Style", "The motion of the swing", "Slash", "Slash", "Chop", "Hook", "Spin", "Double tap", "Push"));
    public final NumberSetting tempo = add(new NumberSetting("Tempo", "Above 1 the swing plays faster, below 1 slower", 1, 0.5, 3, 0.1));
    public final NumberSetting intensity = add(new NumberSetting("Intensity", "How far the arm travels", 1, 0.3, 2, 0.1));
    public final BoolSetting offhand = add(new BoolSetting("Off hand too", "The off hand swings the same way", true));
    public final BoolSetting combatOnly = add(new BoolSetting("Combat only", "Only after a hit in the last 3 s or with a player or hostile under the crosshair; otherwise vanilla", false));
    public static int applied;
    private String latched;
    private boolean swingingLast;

    public SwingAnimation() {
        super("Swing Animation", "Your own arm swing: slash, chop, hook, spin, double tap or push", Category.COSMETIC);
    }

    public static SwingAnimation active() {
        SwingAnimation s = Modules.get(SwingAnimation.class);
        return s != null && s.on() ? s : null;
    }

    /** Does this arm get the custom swing right now. */
    public boolean applies(HumanoidArm arm) {
        if (mc.player == null) return false;
        if (!offhand.on() && arm != mc.player.getMainArm()) return false;
        return !combatOnly.on() || combat();
    }

    private boolean combat() {
        if (mc.player.getLastHurtMobTimestamp() > 0 && mc.player.tickCount - mc.player.getLastHurtMobTimestamp() < 60) return true;
        var e = mc.crosshairPickEntity;
        return e instanceof Player || e instanceof Enemy;
    }

    /** The client's swing length in ticks after the tempo. */
    public int ticks(int vanilla) {
        return Math.max(1, Math.round(vanilla / tempo.f()));
    }

    /** The arm transform for swing progress f (0 at the start, 1 at the end); side is +1 for the right arm, -1 for the left. */
    public void swing(float f, PoseStack pose, int side, HumanoidArm arm) {
        // the style latches at the start of a swing so a change mid-swing does not jump
        if (f < 0.05f || latched == null) latched = style.get();
        float k = intensity.f();
        float s1 = Mth.sin(Mth.sqrt(f) * (float) Math.PI), s2 = Mth.sin(f * (float) Math.PI);
        switch (latched) {
            case "Chop" -> {
                pose.translate(0, 0.15f * s1 * k, -0.25f * s2 * k);
                pose.mulPose(Axis.YP.rotationDegrees(side * 45));
                pose.mulPose(Axis.XP.rotationDegrees(s1 * -100 * k));
                pose.mulPose(Axis.YP.rotationDegrees(side * -45));
            }
            case "Hook" -> {
                pose.translate(side * -0.5f * s1 * k, 0.05f * s2, -0.15f * s2 * k);
                pose.mulPose(Axis.YP.rotationDegrees(side * 45));
                pose.mulPose(Axis.YP.rotationDegrees(side * s1 * -70 * k));
                pose.mulPose(Axis.ZP.rotationDegrees(side * s1 * -30 * k));
                pose.mulPose(Axis.YP.rotationDegrees(side * -45));
            }
            case "Spin" -> {
                float e = f * f * (3 - 2 * f);
                pose.translate(side * -0.2f * s2 * k, 0.1f * s2 * k, -0.2f * s2 * k);
                pose.mulPose(Axis.YP.rotationDegrees(side * 45));
                pose.mulPose(Axis.ZP.rotationDegrees(side * e * -360 * Math.min(k, 1.5f)));
                pose.mulPose(Axis.YP.rotationDegrees(side * -45));
            }
            case "Double tap" -> {
                float s = Mth.sin(((f * 2) % 1) * (float) Math.PI);
                pose.translate(0, -0.05f * s * k, -0.35f * s * k);
                pose.mulPose(Axis.XP.rotationDegrees(s * -25 * k));
            }
            case "Push" -> {
                pose.translate(0, -0.1f * s1 * k, -0.45f * s1 * k);
                pose.mulPose(Axis.XP.rotationDegrees(s1 * -20 * k));
                pose.mulPose(Axis.YP.rotationDegrees(side * s1 * 10 * k));
            }
            default -> { // Slash: the vanilla arc, wider
                float w = 1.15f * k;
                pose.translate(side * -0.4f * s1 * w, 0.2f * Mth.sin(Mth.sqrt(f) * (float) (Math.PI * 2)) * w, -0.2f * s2 * w);
                pose.mulPose(Axis.YP.rotationDegrees(side * (45 + Mth.sin(f * f * (float) Math.PI) * -20 * w)));
                pose.mulPose(Axis.ZP.rotationDegrees(side * s1 * -20 * w));
                pose.mulPose(Axis.XP.rotationDegrees(s1 * -80 * w));
                pose.mulPose(Axis.YP.rotationDegrees(side * -45));
            }
        }
        applied++;
    }
}
