package dev.vantage.module.render;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.render.R3;
import dev.vantage.render.RenderStyle;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * ADOPTION (Block ESP, Sound Detector; switch over once their agents are done, both are one-line swaps):
 *  Block ESP:    r.outline(blocks, col, w, true, fa, lines) -> pass EspStyle.weight(2f) as w, EspStyle.fill(...) alpha as fa
 *                (fill-only = EspStyle.fillAlpha(false, 1), outline + fill = EspStyle.fillAlpha(true, 1), outline only = 0);
 *                singles/groups likewise; its labels already get the pill look (R3.label), drop its own scale arg.
 *  Sound Detector: the two r.label(...) calls need nothing (look is in R3); boxes/markers -> EspStyle.box(r, aabb, color, true, false, 2f, 1f, true).
 * The look rules (one weight, one fill pair, one tracer, one label) live here; the label pill, nudge and range fade live in R3.
 *
 * How every box, line, tile and label in the world is drawn. One place instead of a setting per module, in the style of
 * Anubis' EspStyle: merged vein outlines instead of a cube per block, a soft glow under each line, everything fading
 * with distance, labels that shrink the further away they are. Off = the plain look (every block its own cube, no fade).
 */
public class EspStyle extends Module {
    public final BoolSetting merge = add(new BoolSetting("Merge outlines", "Touching blocks of one kind share one outline instead of a cube each", true));
    public final BoolSetting glow = add(new BoolSetting("Glow", "A soft wide line under every outline", true));
    public final BoolSetting fade = add(new BoolSetting("Distance fade", "Boxes, fills and labels fade out past 64 blocks, down to 45% at 256", true));
    public final NumberSetting lines = add(new NumberSetting("Line width", "Scales every outline", 1.0, 0.5, 2.0, 0.1));
    public final BoolSetting shrink = add(new BoolSetting("Shrink labels", "Labels get smaller with distance instead of staying one size", true));
    public final NumberSetting minLabel = add(new NumberSetting("Min label size", "How small a far label may get", 0.55, 0.3, 1.0, 0.05)).visibleIf(shrink::on);
    public final BoolSetting fillsFade = add(new BoolSetting("Fade fills too", "Distance fade also thins the translucent fills", true)).visibleIf(fade::on);
    public final BoolSetting depth = add(new BoolSetting("Depth cue", "What you can see draws crisp, what is behind blocks draws dimmer", true));
    public final BoolSetting reveal = add(new BoolSetting("Reveal", "New outlines fade in over a third of a second instead of popping", true));

    public final NumberSetting fillOpacity = add(new NumberSetting("Fill opacity", "Scales every translucent fill (16% fill alone, 11% next to an outline at 1.0)", 1.0, 0.25, 2.0, 0.05));
    public final NumberSetting labelSize = add(new NumberSetting("Label size", "Scales every world label", 0.8, 0.5, 1.6, 0.05));
    public final BoolSetting labelBacking = add(new BoolSetting("Label backing", "A small dark pill behind world labels (off: plain text with a shadow)", true));
    public final NumberSetting labelRange = add(new NumberSetting("Label range", "Labels fade out over the last third of this distance and are gone past it", 512, 64, 1024, 32));

    // ---- Declutter (BETA): only exists while beta is unlocked, see README "Declutter (beta)" ----
    public final dev.vantage.setting.Header declutterHead = add(new dev.vantage.setting.Header("Declutter (beta)")).visibleIf(Modules::betaUnlocked);
    public final BoolSetting declutter = add(new BoolSetting("Declutter", "Beta: touching boxes of one kind become one outline with a count, at most Max boxes and Max labels draw, labels never overlap, far ones fade", true)).visibleIf(Modules::betaUnlocked);
    public final BoolSetting groupBoxes = add(new BoolSetting("Group boxes", "Touching marks of one kind share one outline and one label (\"32 chests\")", true)).visibleIf(() -> Modules.betaUnlocked() && declutter.on());
    public final dev.vantage.setting.ModeSetting groupShape = add(new dev.vantage.setting.ModeSetting("Group shape", "Outline follows the real outline of the cluster (L-shapes stay L-shaped); Box is the plain box round it", "Outline", "Outline", "Box")).visibleIf(() -> Modules.betaUnlocked() && declutter.on() && groupBoxes.on());
    public final BoolSetting groupFillSet = add(new BoolSetting("Group fill", "A translucent fill on group outlines (off: outline only, so groups do not stack into slabs)", false)).visibleIf(() -> Modules.betaUnlocked() && declutter.on() && groupBoxes.on());
    public final NumberSetting groupMin = add(new NumberSetting("Group from", "A cluster needs at least this many marks to become one outline", 3, 2, 12, 1)).visibleIf(() -> Modules.betaUnlocked() && declutter.on() && groupBoxes.on());
    public final NumberSetting maxBoxes = add(new NumberSetting("Max boxes", "Most separate boxes (groups count as one) drawn at once, nearest first; the rest fold into \"+N more\"", 40, 10, 300, 5)).visibleIf(() -> Modules.betaUnlocked() && declutter.on());
    public final NumberSetting maxLabels = add(new NumberSetting("Max labels", "Most world labels drawn at once; the less important of two that would overlap is hidden", 24, 5, 100, 1)).visibleIf(() -> Modules.betaUnlocked() && declutter.on());
    public final BoolSetting farLabels = add(new BoolSetting("Fade far labels", "Labels past about 40 blocks fade towards half strength", true)).visibleIf(() -> Modules.betaUnlocked() && declutter.on());

    /** The one outline weight (before the global Line width) and the one fill pair, in alpha bytes: fill alone / next to an outline. */
    public static final float WEIGHT = 1.4f;
    public static final int FILL_ONLY = 0x1E, FILL_WITH = 0x0E;
    /** The 'Line width' style settings of modules are relative to this: 2 = the standard weight. */
    public static final float STD = 2f;

    private static EspStyle instance;

    public EspStyle() {
        super("ESP Style", "The look of all world drawing: merged outlines, glow, distance fade, label size", Category.RENDER);
        onByDefault();
        instance = this;
    }

    private static boolean live() {
        return instance != null && instance.on();
    }

    public static boolean merge() { return live() && instance.merge.on(); }
    public static boolean glow() { return live() && instance.glow.on(); }
    public static boolean shrink() { return live() && instance.shrink.on(); }
    public static float minLabel() { return live() ? instance.minLabel.f() : 1f; }
    public static float lineScale() { return live() ? instance.lines.f() : 1f; }
    public static boolean fillsFade() { return live() && instance.fade.on() && instance.fillsFade.on(); }
    public static boolean depth() { return live() && instance.depth.on(); }
    /** Declutter is on: ESP Style is on, beta is unlocked and the switch is on. With beta locked this is always false, so nothing below it ever runs. */
    public static boolean declutter() { return live() && Modules.betaUnlocked() && instance.declutter.on(); }
    public static boolean groupBoxes() { return declutter() && instance.groupBoxes.on(); }
    public static boolean groupOutline() { return declutter() && instance.groupShape.is("Outline"); }
    public static boolean groupFill() { return declutter() && instance.groupFillSet.on(); }
    public static int groupMin() { return instance.groupMin.i(); }
    public static int maxBoxes() { return instance.maxBoxes.i(); }
    public static int maxLabels() { return instance.maxLabels.i(); }
    public static boolean farLabels() { return declutter() && instance.farLabels.on(); }
    public static boolean reveal() { return live() && instance.reveal.on(); }

    public static float labelSize() { return live() ? instance.labelSize.f() : 1f; }
    public static boolean labelBacking() { return !live() || instance.labelBacking.on(); }
    public static float labelRange() { return live() ? instance.labelRange.f() : 512f; }
    public static float fillScale() { return live() ? instance.fillOpacity.f() : 1f; }

    private static final RenderStyle SNAPSHOT = new RenderStyle();

    /**
     * Fills the one reused snapshot R3 reads for a frame (no allocation). The same values and the same "ESP Style off means neutral"
     * rules as the static getters above; the Declutter numbers are only read while declutter is on.
     */
    public static RenderStyle snapshot() {
        RenderStyle s = SNAPSHOT;
        s.reveal = reveal();
        s.merge = merge();
        s.declutter = declutter();
        s.fillsFade = fillsFade();
        s.depth = depth();
        s.glow = glow();
        s.shrink = shrink();
        s.minLabel = minLabel();
        s.labelSize = labelSize();
        s.labelBacking = labelBacking();
        s.labelRange = labelRange();
        s.farLabels = farLabels();
        s.groupBoxes = groupBoxes();
        s.groupOutline = groupOutline();
        s.groupFill = groupFill();
        s.fadeOn = live() && instance.fade.on();
        s.lineScale = lineScale();
        boolean d = instance != null;
        s.groupMin = d ? groupMin() : 0;
        s.maxBoxes = d ? maxBoxes() : 0;
        s.maxLabels = d ? maxLabels() : 0;
        return s;
    }

    // ---- the shared drawing helpers: every ESP box, zone, beam, tracer goes through these -----------------------------------

    /** Line weight from a module's own width setting (2 = standard). The global Line width scales it again at draw time. */
    public static float weight(float setting) { return WEIGHT * setting / STD; }

    /** Alpha byte of a fill: alone or next to an outline, times the global Fill opacity and a module multiplier (1 = default). */
    public static int fillAlpha(boolean withOutline, float mult) {
        return Math.max(1, Math.min(255, Math.round((withOutline ? FILL_WITH : FILL_ONLY) * fillScale() * mult)));
    }

    /** rgb of c with a fill alpha. */
    public static int fill(int c, boolean withOutline, float mult) {
        return (c & 0xFFFFFF) | (fillAlpha(withOutline, mult) << 24);
    }

    /** A box: outline, fill or both with the one weight and the one fill pair. widthSetting 2 = standard, fillMult 1 = default. */
    public static void box(R3 r, AABB b, int c, boolean outline, boolean fill, float widthSetting, float fillMult, boolean xray) {
        if (fill) r.fill(b, fill(c, outline, fillMult), xray);
        if (outline) r.box(b, c, weight(widthSetting), xray);
    }

    public static void box(R3 r, AABB b, int c, boolean xray) {
        box(r, b, c, true, false, STD, 1f, xray);
    }

    /** A flat horizontal rectangle (chunk square, hole floor): perimeter and/or fill, same weight and fill pair as a box. */
    public static void zone(R3 r, double x0, double z0, double x1, double z1, double y, int c, boolean outline, boolean fill, boolean xray) {
        if (fill) r.flat(x0, z0, x1, z1, y, fill(c, outline, 1f), xray);
        if (outline) {
            float w = weight(STD);
            r.line(x0, y, z0, x1, y, z0, c, w, xray);
            r.line(x1, y, z0, x1, y, z1, c, w, xray);
            r.line(x1, y, z1, x0, y, z1, c, w, xray);
            r.line(x0, y, z1, x0, y, z0, c, w, xray);
        }
    }

    /** A vertical line (waypoint beam, column) with the one weight. */
    public static void beam(R3 r, double x, double y0, double y1, double z, int c) {
        r.line(x, y0, z, x, y1, z, c, weight(STD), true);
    }

    /** The one tracer: thinner than an outline (0.6 x), 80% alpha, from the crosshair. */
    public static void tracer(R3 r, Vec3 to, int c) {
        r.tracer(to, (c & 0xFFFFFF) | 0xCC000000, WEIGHT * 0.6f);
    }

    /** 1 up to 64 blocks, then 1 - 0.55 t^2 with t = (d - 64) / 192: 0.45 at 256 and beyond. */
    public static float fade(double dist) {
        if (!live() || !instance.fade.on() || dist <= 64) return 1f;
        double t = Math.min(1, (dist - 64) / 192);
        return (float) (1 - 0.55 * t * t);
    }

    /** Lines get 30% thinner by 256 blocks. */
    public static float widthAt(float base, double dist) {
        float w = base * lineScale();
        if (!live() || !instance.fade.on()) return w;
        return w * (float) (1 - 0.3 * Math.min(1, dist / 256));
    }
}
