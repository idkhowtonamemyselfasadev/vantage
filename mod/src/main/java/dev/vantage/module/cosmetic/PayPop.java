package dev.vantage.module.cosmetic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Theme;
import dev.vantage.core.Ui;
import dev.vantage.core.util.Colors;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/** When the server's chat says somebody paid you or you got shards, the amount pops up under the crosshair. Reads the line the client already received; sends nothing. */
public class PayPop extends Module {
    public final NumberSetting size = add(new NumberSetting("Size", "Text size", 1.7, 0.8, 3.5, 0.1));
    public final NumberSetting duration = add(new NumberSetting("Duration", "Seconds a pop stays", 2.2, 0.8, 5, 0.1));
    public final BoolSetting money = add(new BoolSetting("Money", "Show payments (\"paid you $ 330M\")", true));
    public final BoolSetting shards = add(new BoolSetting("Shards", "Show shard rewards (\"You received 250 shards\")", true));
    public static int shown, drawn;
    private static final int MAX = 4;
    // tolerant on purpose: "$ 330M", "$330M", "$1,250.5k", and a star or other symbol before the shard amount
    private static final Pattern PAID = Pattern.compile("(?i)\\bpaid you\\s*\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt])?(?![a-z0-9])");
    private static final Pattern SHARDS = Pattern.compile("(?i)\\byou (?:have )?received\\W{0,4}([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?\\s*shards?\\b");
    private record Pop(String text, boolean shard, long t0) {}
    private final List<Pop> live = new ArrayList<>();
    private String lastText = "";
    private long lastAt;

    public PayPop() {
        super("Pay Pop", "The amount pops up by the crosshair when someone pays you or you get shards", Category.COSMETIC);
        beta();
        // a box around the pops that are up; its default place is left of the crosshair, level with the Target HUD card. It is in no corner stack
        hudPanel("paypop", "Pay Pop", "Top Left").overScreens().when(() -> !live.isEmpty()).size(wh -> {
            float s = size.f(), wide = 0;
            for (Pop p : live) wide = Math.max(wide, Ui.width(p.text, s * 1.1f, true) + 30 * s);
            wh[0] = wide;
            wh[1] = (live.size() - 1) * 28 * s + 24 * s + LIFT * s + 8;
        }).draw(this::drawPops).element().plain().defaultAt((w, h, sw, sh, out) -> {
            out[0] = sw / 2 - 18 - w; // left of the crosshair: the old spot, centred under it, overlapped the Target HUD card (B-171), so the pops mirror the card
            out[1] = sh / 2 + 34 - LIFT * size.f();
        });
    }

    /** How far a pop rises over its life, in units of the Size setting; the box reserves it above the first pop. */
    private static final float LIFT = 14;

    @Override
    protected void onDisable() { live.clear(); }

    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    private void forgetWorld() {
        live.clear();
    }

    /** A system message the client received. Public so the rig can feed lines. Returns the pop text, or null when the line is no payment. */
    public String onMessage(String text, boolean overlay) {
        if (text == null) return null;
        String t = ChatFormatting.stripFormatting(text);
        if (t == null) return null;
        t = t.replace(' ', ' ');
        Matcher m = money.on() ? PAID.matcher(t) : null;
        boolean shard = false;
        if (m == null || !m.find()) {
            m = shards.on() ? SHARDS.matcher(t) : null;
            if (m == null || !m.find()) return null;
            shard = true;
        }
        String before = t.substring(0, m.start()).trim().replaceFirst("^[^A-Za-z0-9]+", "");
        if (before.contains(":") || before.contains("»") || before.contains(">")) return null; // a player typing it, not the server
        String amount = m.group(1) + (m.group(2) == null ? "" : m.group(2).toUpperCase(Locale.ROOT));
        String pop = shard ? "+" + amount + " shards" : "+$" + amount;
        long now = System.currentTimeMillis();
        if (pop.equals(lastText) && now - lastAt < 1500) return pop; // chat and action bar showing the same receipt
        lastText = pop;
        lastAt = now;
        if (live.size() >= MAX) live.remove(0);
        live.add(new Pop(pop, shard, now));
        shown++;
        return pop;
    }

    /** How far a new pop has slid in at life fraction t (1 = in place); Calm makes it 1 from the start, so it only fades. */
    public static float slideIn(float t) {
        return Cos.calm() ? 1f : Cos.out(Math.min(1f, t / 0.12f));
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        long now = System.currentTimeMillis();
        double life = duration.get() * 1000;
        live.removeIf(p -> now - p.t0 > life);
    }

    private void drawPops(GuiGraphics g, float bx, float by, float bw, float bh) {
        long now = System.currentTimeMillis();
        double life = duration.get() * 1000;
        float s = size.f();
        int accent = Theme.accent();
        for (int i = live.size() - 1; i >= 0; i--) {
            Pop p = live.get(i);
            int slot = live.size() - 1 - i;
            float t = (float) ((now - p.t0) / life);
            float in = slideIn(t);
            float a = t < 0.7f ? 1f : 1 - (t - 0.7f) / 0.3f;
            float y = by + LIFT * s + slot * 28 * s - LIFT * Cos.out(t) * s + (1 - in) * 8;
            float fs = s * 1.1f, tw = Ui.width(p.text, fs, true), w = tw + 30 * s, h = 24 * s;
            float x = bx + (bw - w) / 2;
            Ui.rr(g, x, y, w, h, 7 * s, Colors.fade(Ui.GLASS, a), Colors.fade(accent, a * 0.55f));
            Ui.rr(g, x + 5 * s, y + 5 * s, 3 * s, h - 10 * s, 1.5f * s, Colors.fade(accent, a), 0);
            Ui.text(g, p.text, x + 18 * s, y + h / 2 - 4.6f * fs, Colors.fade(p.shard ? 0xFFF2C75C : Ui.GREEN, a), fs, true);
            drawn++;
        }
    }
}
