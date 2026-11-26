package dev.vantage.core;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vantage.core.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Drawing kit for the glass panels. Everything is laid out in design units: a 720 tall screen, whatever the
 * window size or GUI scale, so panels keep their size on screen. Shapes are drawn in real pixels so corners
 * stay smooth, text uses the bundled Inter font.
 */
public final class Ui {
    // the themed part of the palette: Theme.apply writes these, everything reads them
    public static int BG = 0xE41B2132, BORDER = 0x60566080;
    public static final int LINE = 0x24FFFFFF;
    public static int GLASS = 0xD8151A28, FIELD = 0x70101522;
    public static final int TEXT = 0xFFE8ECF4, SUB = 0xFFA3ACBE, FAINT = 0xFF8A93A8;
    public static int ACCENT = 0xFF7B6BFF, TITLE = 0xFF8D84F2;
    public static final int GREEN = 0xFFB1D069, YELLOW = 0xFFE5C05C, ORANGE = 0xFFE8963C, BLUE = 0xFF5088E6, GREY = 0xFF8A8F9C, TEAL = 0xFF62D6B0, RED = 0xFFE06A6A;
    // Click GUI
    public static int WIN = 0xF60B0A20, SIDE = 0xF2100F2C, CARD = 0xE8131230, CARD_LINE = 0xFF1F1E44, SELECT = 0xFF26245C, WIN_LINE = 0xFF1E1C44;

    /** A hard-coded navy tone turned to the current theme (see Theme.tone). */
    public static int tone(int base) { return Theme.tone(base); }

    private static final FontDescription REG = new FontDescription.Resource(Identifier.fromNamespaceAndPath("vantage", "ui"));
    private static final FontDescription BOLD = new FontDescription.Resource(Identifier.fromNamespaceAndPath("vantage", "ui_bold"));

    // Sharp text at any size and resolution. Minecraft rasterises a TTF glyph once at size x oversample pixels and stretches that
    // bitmap, so one fixed raster is blocky when drawn bigger (headings, 4K) and jaggy when drawn smaller (small panel size).
    // Every family therefore ships in density steps (font/<family>_o<i>.json, oversample 2^(i/3): 1, 1.26, 1.59 ... 20.2) and each
    // draw picks the step closest to the real pixels per font unit it lands on. The atlas pages for these are sampled linearly
    // (GlyphRenderStateMixin), so the last few percent between two steps blend instead of dropping or doubling texels.
    private static final int F_REG = 0, F_BOLD = 1, F_SERIF = 2, F_SERIF_IT = 3;
    private static final String[] FAMILY = {"ui", "ui_bold", "serif", "serif_italic"};
    /** Highest step per family: 9.5 x 20.2 and 11.5 x 16 pixel ems, so the tallest glyph still fits a 256 px atlas page. */
    private static final int[] TOP = {13, 13, 12, 12};
    private static final FontDescription[][] VAR = new FontDescription[4][14];
    /** Dev A/B switch (-Dvantage.oldText=true, menushot OLDTEXT=true): the old fixed raster with NEAREST sampling. */
    private static final boolean OLD_TEXT = Boolean.getBoolean("vantage.oldText");
    static {
        for (int f = 0; f < 4; f++) for (int i = 0; i <= TOP[f]; i++)
            VAR[f][i] = new FontDescription.Resource(Identifier.fromNamespaceAndPath("vantage", FAMILY[f] + "_o" + i));
    }

    /** Glyph atlas pages of the density-matched fonts (FontTextureMixin marks them when they are made). Weak: a resource reload
     *  closes and drops the old pages. */
    private static final java.util.Set<com.mojang.blaze3d.textures.GpuTexture> SMOOTH_PAGES = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** FontTextureMixin: a new glyph page named after its font set (font id + "/n"); TTF glyph pages only (bitmap fallback glyphs
     *  live on RGBA pages and stay pixel-sharp). */
    public static void markGlyphPage(String name, com.mojang.blaze3d.textures.GpuTexture page) {
        if (page != null && name != null && name.startsWith("vantage:") && name.contains("_o")) SMOOTH_PAGES.add(page);
    }

    /** True for a glyph atlas page of a density-matched Vantage font: GlyphRenderStateMixin samples it LINEAR. */
    public static boolean smoothGlyphs(com.mojang.blaze3d.textures.GpuTextureView view) {
        if (view == null || OLD_TEXT) return false;
        boolean ours = SMOOTH_PAGES.contains(view.texture());
        if (ours) smoothPages++;
        return ours;
    }

    /** For the rig: glyph batches drawn from the smooth density-matched pages. */
    public static int smoothPages;

    /** The density step for text of this size in design units: the one whose oversample is nearest the real pixels per font unit. */
    private static int step(int family, float size) {
        float k = px() * size;
        if (OLD_TEXT || !(k > 0f)) return 6;                                    // oversample 4, the old fixed raster
        int i = Math.round(3f * (float) (Math.log(k) / Math.log(2)));
        return Math.max(0, Math.min(TOP[family], i));
    }

    /** Multiplies everything drawn, so a whole panel can fade. Put it back to 1 when done. */
    public static float alpha = 1f;
    /** Extra size while the menu draws, so it can be bigger than the HUD. Put it back to 1 when done. */
    public static float zoom = 1f;
    /** A pending slide in design units for the next begin(): Hud.slot sets it while a piece is coming in. */
    public static float shiftX, shiftY;

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static int a(int color) {
        return alpha >= 1f ? color : Colors.fade(color, alpha);
    }

    /** The HUD module's "Panel size" setting (0.6 to 1.6). The module sets it when it is created and whenever the setting changes. */
    public static float panelSize = 1f;

    /** Real pixels per design unit. */
    public static float px() {
        return mc().getWindow().getHeight() / 720f * panelSize * zoom;
    }

    /** GUI units per design unit. */
    public static float scale() {
        return px() / (float) mc().getWindow().getGuiScale();
    }

    /** Screen size in design units. */
    public static float W() {
        return mc().getWindow().getWidth() / px();
    }

    public static float H() {
        return mc().getWindow().getHeight() / px();
    }

    public static void begin(GuiGraphics g) {
        Theme.sync();
        float s = scale();
        g.pose().pushMatrix();
        depth++;
        g.pose().scale(s, s);
        if (shiftX != 0 || shiftY != 0) {
            g.pose().translate(shiftX, shiftY);
            shiftX = shiftY = 0;
        }
    }

    /** One of the menu's icons (gui/Icons, textures/gui/icon) drawn in an s by s box at x, y in design units, tinted; for HUD pieces. */
    public static void icon(GuiGraphics g, String icon, float x, float y, float size, int color) {
        dev.vantage.gui.Icons.draw(g, icon, x, y, size, color);
    }

    public static void end(GuiGraphics g) {
        g.pose().popMatrix();
        if (depth > 0) depth--;
        alpha = 1f;
    }

    /** How many begin() calls are still open. */
    private static int depth;

    public static int depth() {
        return depth;
    }

    /**
     * Closes every begin() opened since {@code mark} (a value of depth()). A piece of HUD that fails halfway never reaches its end(), and
     * its scale would stay on the stack: everything drawn after it in that frame, the menu included, came out small and in the wrong place.
     * Returns how many were closed.
     */
    public static int unwind(GuiGraphics g, int mark) {
        int n = 0;
        while (depth > mark) { end(g); n++; }
        zoom = 1f;
        return n;
    }

    /** Ease-out cubic: fast start, soft landing. The one curve every HUD motion uses. */
    public static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1 - t;
        return 1 - u * u * u;
    }

    /** Frame-rate independent glide of a value towards its target: rate per second. */
    public static float glide(float now, float target, float rate, float dt) {
        float v = now + (target - now) * (1 - (float) Math.exp(-rate * dt));
        return Math.abs(target - v) < 0.002f ? target : v;
    }

    // Text caches: the HUD draws the same few hundred strings every frame, so the styled component, its width and the fitted cut are
    // made once per string instead of once per frame (a new Component plus a layout pass per call was a good part of the frame time).
    // Per family and density step; a step's glyph advances differ by hinting, so widths are measured in the step that draws.
    @SuppressWarnings({"unchecked", "rawtypes"}) // Java cannot create an array of a generic type directly
    private static final java.util.Map<String, Component>[][] COMPS = new java.util.Map[4][14];
    @SuppressWarnings({"unchecked", "rawtypes"}) // Java cannot create an array of a generic type directly
    private static final java.util.Map<String, Float>[][] WIDTHS = new java.util.Map[4][14];
    private static final java.util.Map<String, Component> COMP = new java.util.HashMap<>(), COMP_BOLD = new java.util.HashMap<>();
    private static final java.util.Map<String, String> FIT = new java.util.HashMap<>();
    private static final int CACHE_MAX = 6000;

    /** The fixed-raster component, for text drawn outside the kit (world labels), where the screen density is unknown. */
    private static Component c(String s, boolean bold) {
        var m = bold ? COMP_BOLD : COMP;
        Component c = m.get(s);
        if (c == null) {
            if (m.size() > CACHE_MAX) m.clear();
            m.put(s, c = Component.literal(s).withStyle(st -> st.withFont(bold ? BOLD : REG)));
        }
        return c;
    }

    private static Component c(String s, int family, int step) {
        var m = COMPS[family][step];
        if (m == null) m = COMPS[family][step] = new java.util.HashMap<>();
        Component c = m.get(s);
        if (c == null) {
            if (m.size() > CACHE_MAX) m.clear();
            FontDescription f = VAR[family][step];
            m.put(s, c = Component.literal(s).withStyle(st -> st.withFont(f)));
        }
        return c;
    }

    /** Raw font width of a string in one family and density step, cached. */
    private static float rawWidth(String s, int family, int step) {
        var m = WIDTHS[family][step];
        if (m == null) m = WIDTHS[family][step] = new java.util.HashMap<>();
        Float w = m.get(s);
        if (w == null) {
            if (m.size() > CACHE_MAX) m.clear();
            m.put(s, w = (float) mc().font.width(c(s, family, step)));
        }
        return w;
    }

    /** Drops the caches, for a font or resource reload. */
    public static void dropTextCaches() {
        COMP.clear(); COMP_BOLD.clear(); FIT.clear();
        for (var row : COMPS) java.util.Arrays.fill(row, null);
        for (var row : WIDTHS) java.util.Arrays.fill(row, null);
    }

    /** Text in the bundled Inter font, for drawing outside the kit (world labels, batches). */
    public static Component inter(String s, boolean bold) {
        return c(s, bold);
    }

    /** Draws a component at (x, y) in design units, scaled by size, with its origin snapped to a whole real pixel, so a glyph
     *  raster that matches the density lands texel on pixel. */
    private static void draw(GuiGraphics g, Component c, float x, float y, float size, int color) {
        float d = px();
        g.pose().pushMatrix();
        g.pose().translate(Math.round(x * d) / d, Math.round(y * d) / d);
        g.pose().scale(size, size);
        g.drawString(mc().font, c, 0, 0, color, false);
        g.pose().popMatrix();
    }

    public static void text(GuiGraphics g, String s, float x, float y, int color) {
        text(g, s, x, y, color, 1f, false);
    }

    /** Rig only: when set, every string drawn is added here (to check what a screen shows). */
    public static java.util.List<String> spy;

    public static void text(GuiGraphics g, String s, float x, float y, int color, float size, boolean bold) {
        if (spy != null) spy.add(s);
        color = a(color);
        if (color >>> 24 == 0) return;
        int f = bold ? F_BOLD : F_REG;
        draw(g, c(s, f, step(f, size)), x, y, size, color);
    }

    /** Headline text in the bundled Instrument Serif (OFL). Returns the width drawn. */
    public static float serif(GuiGraphics g, String s, float x, float y, int color, float size, boolean italic) {
        color = a(color);
        int f = italic ? F_SERIF_IT : F_SERIF, st = step(f, size);
        if (color >>> 24 != 0) draw(g, c(s, f, st), x, y, size, color);
        return rawWidth(s, f, st) * size;
    }

    public static float serifWidth(String s, float size, boolean italic) {
        int f = italic ? F_SERIF_IT : F_SERIF;
        return rawWidth(s, f, step(f, size)) * size;
    }

    public static void right(GuiGraphics g, String s, float x, float y, int color, float size, boolean bold) {
        text(g, s, x - width(s, size, bold), y, color, size, bold);
    }

    public static void center(GuiGraphics g, String s, float x, float y, int color, float size, boolean bold) {
        text(g, s, x - width(s, size, bold) / 2f, y, color, size, bold);
    }

    public static float width(String s, float size, boolean bold) {
        int f = bold ? F_BOLD : F_REG;
        return rawWidth(s, f, step(f, size)) * size;
    }

    /** Cuts a string to fit w, with dots at the end. */
    public static String fit(String s, float w, float size) {
        if (width(s, size, false) <= w) return s;
        String key = s + '\u0001' + Math.round(w * 4) + '\u0001' + Math.round(size * 100) + '\u0001' + step(F_REG, size);
        String hit = FIT.get(key);
        if (hit != null) return hit;
        // binary search for the longest cut that fits with the dots
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (width(s.substring(0, mid) + "...", size, false) <= w) lo = mid; else hi = mid - 1;
        }
        String out = s.substring(0, lo) + "...";
        if (FIT.size() > CACHE_MAX) FIT.clear();
        FIT.put(key, out);
        return out;
    }

    /** Colour with its alpha multiplied, for fading. */
    public static int fade(int color, float a) {
        return Colors.fade(color, a);
    }

    public static void rect(GuiGraphics g, float x, float y, float w, float h, int color) {
        color = a(color);
        float d = px();
        g.pose().pushMatrix();
        g.pose().scale(1f / d, 1f / d);
        int x0 = Math.round(x * d), y0 = Math.round(y * d);
        g.fill(x0, y0, Math.max(x0 + 1, Math.round((x + w) * d)), Math.max(y0 + 1, Math.round((y + h) * d)), color);
        g.pose().popMatrix();
    }

    /** A whole texture stretched into a box, in design units, with the current alpha. */
    public static void image(GuiGraphics g, Identifier id, float x, float y, float w, float h) {
        float d = px();
        g.pose().pushMatrix();
        g.pose().scale(1f / d, 1f / d);
        int X = Math.round(x * d), Y = Math.round(y * d), W = Math.round((x + w) * d) - X, H = Math.round((y + h) * d) - Y;
        g.blit(RenderPipelines.GUI_TEXTURED, id, X, Y, 0, 0, W, H, W, H, a(0xFFFFFFFF));
        g.pose().popMatrix();
    }

    /** A straight line between two points, any angle: one rotated fill, thickness in real pixels. */
    public static void line(GuiGraphics g, float x1, float y1, float x2, float y2, float thickness, int color) {
        color = a(color);
        if (color >>> 24 == 0) return;
        float d = px();
        float dx = (x2 - x1) * d, dy = (y2 - y1) * d, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) return;
        g.pose().pushMatrix();
        g.pose().scale(1f / d, 1f / d);
        g.pose().translate(x1 * d, y1 * d);
        g.pose().rotate((float) Math.atan2(dy, dx));
        int t = Math.max(1, Math.round(thickness));
        g.fill(0, -t / 2, Math.round(len), -t / 2 + t, color);
        g.pose().popMatrix();
    }

    /** Thin line across a panel; always one real pixel. */
    public static void hline(GuiGraphics g, float x, float y, float w, int color) {
        color = a(color);
        float d = px();
        g.pose().pushMatrix();
        g.pose().scale(1f / d, 1f / d);
        int yy = Math.round(y * d);
        g.fill(Math.round(x * d), yy, Math.round((x + w) * d), yy + 1, color);
        g.pose().popMatrix();
    }

    public static void vline(GuiGraphics g, float x, float y, float h, int color) {
        color = a(color);
        float d = px();
        g.pose().pushMatrix();
        g.pose().scale(1f / d, 1f / d);
        int xx = Math.round(x * d);
        g.fill(xx, Math.round(y * d), xx + 1, Math.round((y + h) * d), color);
        g.pose().popMatrix();
    }

    /** Corner masks per radius in real pixels: white with coverage as alpha, so one texture takes any colour. */
    private static final java.util.Map<Integer, Identifier[]> CORNERS = new java.util.HashMap<>();

    /** How much of a pixel lies inside a circle of radius rad around the corner's centre, 4x4 samples. */
    private static float cover(int x, int y, int R, float rad) {
        int in = 0;
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            float dx = R - (x + (i + 0.5f) / 4), dy = R - (y + (j + 0.5f) / 4);
            if (dx * dx + dy * dy <= rad * rad) in++;
        }
        return in / 16f;
    }

    private static Identifier mask(int R, boolean ring) {
        int s = R * 2;
        NativeImage img = new NativeImage(s, s, false);
        for (int x = 0; x < s; x++) for (int y = 0; y < s; y++) {
            int qx = x < R ? x : s - 1 - x, qy = y < R ? y : s - 1 - y;
            float c = cover(qx, qy, R, R);
            if (ring) c -= cover(qx, qy, R, R - 1);
            img.setPixel(x, y, Math.round(Math.max(0, c) * 255) << 24 | 0xFFFFFF);
        }
        Identifier id = Identifier.fromNamespaceAndPath("vantage", "corner/" + R + (ring ? "_ring" : "_fill"));
        mc().getTextureManager().register(id, new DynamicTexture(id::toString, img));
        return id;
    }

    private static void corners(GuiGraphics g, Identifier id, int X, int Y, int X2, int Y2, int R, int color) {
        int s = R * 2;
        g.blit(RenderPipelines.GUI_TEXTURED, id, X, Y, 0, 0, R, R, s, s, color);
        g.blit(RenderPipelines.GUI_TEXTURED, id, X2 - R, Y, R, 0, R, R, s, s, color);
        g.blit(RenderPipelines.GUI_TEXTURED, id, X, Y2 - R, 0, R, R, R, s, s, color);
        g.blit(RenderPipelines.GUI_TEXTURED, id, X2 - R, Y2 - R, R, R, R, R, s, s, color);
    }

    /** Rounded rectangle with soft corners and an optional one pixel border (0 = none). Corners are textures, the rest plain fills, so it costs about 15 draws at any size. */
    public static void rr(GuiGraphics g, float x, float y, float w, float h, float r, int bg, int border) {
        bg = a(bg);
        border = a(border);
        float d = px();
        g.pose().pushMatrix();
        g.pose().scale(1f / d, 1f / d);
        int X = Math.round(x * d), Y = Math.round(y * d), X2 = Math.round((x + w) * d), Y2 = Math.round((y + h) * d);
        int R = Math.max(1, Math.min(Math.round(r * d), Math.min(X2 - X, Y2 - Y) / 2));
        Identifier[] m = CORNERS.computeIfAbsent(R, k -> new Identifier[] {mask(k, false), mask(k, true)});
        if (bg >>> 24 != 0) {
            corners(g, m[0], X, Y, X2, Y2, R, bg);
            g.fill(X + R, Y, X2 - R, Y2, bg);
            g.fill(X, Y + R, X + R, Y2 - R, bg);
            g.fill(X2 - R, Y + R, X2, Y2 - R, bg);
        }
        if (border != 0) {
            corners(g, m[1], X, Y, X2, Y2, R, border);
            g.fill(X + R, Y, X2 - R, Y + 1, border);
            g.fill(X + R, Y2 - 1, X2 - R, Y2, border);
            g.fill(X, Y + R, X + 1, Y2 - R, border);
            g.fill(X2 - 1, Y + R, X2, Y2 - R, border);
        }
        g.pose().popMatrix();
    }

    /** A small rounded square in 7 fills instead of rr's hundreds, for things drawn dozens of times per frame. Nothing overlaps, so faded colours stay even. */
    public static void cell(GuiGraphics g, float x, float y, float w, float h, float r, int color) {
        color = a(color);
        float d = px();
        g.pose().pushMatrix();
        g.pose().scale(1f / d, 1f / d);
        int X = Math.round(x * d), Y = Math.round(y * d), X2 = Math.round((x + w) * d), Y2 = Math.round((y + h) * d);
        int R = Math.max(1, Math.min(Math.round(r * d), Math.min(X2 - X, Y2 - Y) / 2)), k = R / 2;
        g.fill(X + R, Y, X2 - R, Y2, color);
        g.fill(X, Y + R, X + R, Y2 - R, color);
        g.fill(X2 - R, Y + R, X2, Y2 - R, color);
        g.fill(X + k, Y + k, X + R, Y + R, color);
        g.fill(X2 - R, Y + k, X2 - k, Y + R, color);
        g.fill(X + k, Y2 - R, X + R, Y2 - k, color);
        g.fill(X2 - R, Y2 - R, X2 - k, Y2 - k, color);
        g.pose().popMatrix();
    }

    /** A thin chevron of size s: dir 0 points right, 1 down, 2 left, 3 up. Short overlapping squares, so it works at any pixel density. */
    public static void chevron(GuiGraphics g, float x, float y, float s, int dir, int color) {
        float t = 1.5f, half = s / 2;
        int n = Math.max(8, Math.round(half * px() / 1.5f));
        for (int i = 0; i <= n; i++) {
            float f = half * i / n;
            float ax, ay, bx, by;
            switch (dir) {
                case 0 -> { ax = x + f; ay = y + f; bx = x + f; by = y + s - f; }
                case 1 -> { ax = x + f; ay = y + f; bx = x + s - f; by = y + f; }
                case 2 -> { ax = x + half - f; ay = y + f; bx = x + half - f; by = y + s - f; }
                default -> { ax = x + f; ay = y + half - f; bx = x + s - f; by = y + half - f; }
            }
            rect(g, ax - t / 2, ay - t / 2, t, t, color);
            rect(g, bx - t / 2, by - t / 2, t, t, color);
        }
    }

    /** A whole texture stretched over a box in design units, tinted (white pixels take the colour, alpha stays). */
    public static void image(GuiGraphics g, Identifier id, float x, float y, float w, float h, int color) {
        color = a(color);
        float d = px();
        g.pose().pushMatrix();
        g.pose().scale(1f / d, 1f / d);
        int X = Math.round(x * d), Y = Math.round(y * d), W = Math.max(1, Math.round(w * d)), H = Math.max(1, Math.round(h * d));
        g.blit(RenderPipelines.GUI_TEXTURED, id, X, Y, 0, 0, W, H, W, H, color);
        g.pose().popMatrix();
    }

    /** A soft drop under a card: one wider, slightly lower rounded rect at low alpha, so panels lift off the world. */
    public static void shadow(GuiGraphics g, float x, float y, float w, float h, float r) {
        rr(g, x - 1, y + 1.5f, w + 2, h + 2, r + 1, 0x38000000, 0);
    }

    /** The standard HUD panel: a shadow, the glass and a hairline. */
    public static void panel(GuiGraphics g, float x, float y, float w, float h) {
        shadow(g, x, y, w, h, 7);
        rr(g, x, y, w, h, 7, BG, BORDER);
    }

    /** A floating card (toasts, target card, Base ESP): same kit, smaller radius, optional accent bar at the left edge. */
    public static void card(GuiGraphics g, float x, float y, float w, float h, int bar) {
        shadow(g, x, y, w, h, 6);
        rr(g, x, y, w, h, 6, GLASS, BORDER);
        if (bar != 0) rr(g, x + 1.5f, y + 9, 3, h - 18, 1.5f, bar, 0);
    }

    /** Small uppercase label, the way every panel is titled, with an optional right-aligned value. */
    public static void title(GuiGraphics g, String label, String value, float x, float y, float w) {
        text(g, label, x + 10, y + 8, FAINT, 0.85f, false);
        if (value != null) right(g, value, x + w - 10, y + 7, TEXT, 1.2f, false);
        hline(g, x + 10, y + 23, w - 20, LINE);
    }

    public static void toggle(GuiGraphics g, float x, float y, boolean on) {
        toggle(g, x, y, on ? 1f : 0f);
    }

    /** t runs from 0 (off) to 1 (on), so the switch can slide. */
    public static void toggle(GuiGraphics g, float x, float y, float t) {
        rr(g, x, y, 30, 15, 7.5f, Colors.mix(0xFF0A0D1E, ACCENT, t), Colors.mix(0xFF23284A, 0x0023284A, t));
        rr(g, x + 4 + 14 * t, y + 3, 9, 9, 4.5f, 0xFFF4F4FA, 0);
    }

    /** Screen position of a world point in design units, or null when it is behind the camera. */
    public static float[] project(Vec3 p) {
        Minecraft mc = mc();
        var cam = mc.gameRenderer.getMainCamera();
        var fwd = cam.forwardVector();
        Vec3 v = p.subtract(cam.position());
        if (v.x * fwd.x() + v.y * fwd.y() + v.z * fwd.z() <= 0.1) return null;
        Vec3 n = mc.gameRenderer.projectPointToScreen(p);
        return new float[] {(float) ((n.x + 1) / 2 * W()), (float) ((1 - n.y) / 2 * H())};
    }

    private Ui() {}
}
