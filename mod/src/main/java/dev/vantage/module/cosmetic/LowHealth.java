package dev.vantage.module.cosmetic;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/** Red bands at the screen edges when your health is low, pulsing faster the lower it gets. Reads your own health, nothing more. */
public class LowHealth extends Module {
    public final NumberSetting warn = add(new NumberSetting("Warn below", "Health (half-hearts x2) where the warning starts", 7, 2, 20, 1));
    public final BoolSetting absorption = add(new BoolSetting("Count absorption", "Golden hearts count as health", true));
    /** Rig: the last pulse factor drawn (1 = steady). */
    public static float lastBeat = -1;
    public final ModeSetting pulse = add(new ModeSetting("Pulse", "Heartbeat, slow breathing, or a steady band", "Heartbeat", "Heartbeat", "Breath", "Steady"));
    public final NumberSetting intensity = add(new NumberSetting("Intensity", "How strong the red gets", 0.7, 0.1, 1, 0.05));
    public final NumberSetting reach = add(new NumberSetting("Reach", "How far the bands reach in, as a share of the screen", 0.16, 0.05, 0.5, 0.01));
    public static int drawn;
    /** The rig cannot lose health: pretend 3. */
    public static boolean demo;
    private float level, phase;
    private long lastNs;

    public LowHealth() {
        super("Low Health Warning", "Red pulsing edges when your health is low", Category.COSMETIC);
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        var p = mc.player;
        if (p == null || p.isSpectator() || (p.getAbilities().invulnerable && !demo)) return;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        float limit = warn.f();
        float health = demo ? 3 : p.getHealth() + (absorption.on() ? p.getAbsorptionAmount() : 0);
        float danger = health < limit ? Math.min(1f, (limit - health) / limit) : 0;
        float target = danger > 0 ? 1 : 0;
        float rate = p.isDeadOrDying() ? 12 : target > level ? 5 : 2.6f;
        level += (target - level) * (1 - (float) Math.exp(-rate * dt));
        if (level < 0.01f) { level = 0; return; }
        float bpm = 64 + 86 * danger;
        if (pulse.is("Breath")) bpm /= 2;
        phase += dt * bpm / 60 * (float) (Math.PI * 2);
        float beat = pulse.is("Steady") || Cos.calm() ? 1 : pulse.is("Breath") ? 0.5f + 0.5f * (float) Math.sin(phase) : (float) Math.pow(Math.max(0, Math.sin(phase)), 4);
        lastBeat = beat;
        float a = intensity.f() * level * (0.35f + 0.65f * beat);
        int w = g.guiWidth(), h = g.guiHeight();
        int steps = 14;
        int rx = Math.max(1, Math.round(w * reach.f())), ry = Math.max(1, Math.round(h * reach.f()));
        for (int i = 0; i < steps; i++) {
            float t = i / (float) steps;
            int alpha = Math.round(255 * a * (1 - t) * (1 - t));
            if (alpha <= 0) continue;
            int col = (alpha << 24) | 0xE02828;
            int x0 = rx * i / steps, x1 = rx * (i + 1) / steps, y0 = ry * i / steps, y1 = ry * (i + 1) / steps;
            g.fill(x0, 0, x1, h, col);
            g.fill(w - x1, 0, w - x0, h, col);
            g.fill(0, y0, w, y1, col);
            g.fill(0, h - y1, w, h - y0, col);
        }
        drawn++;
    }
}
