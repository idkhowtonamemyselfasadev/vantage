package dev.vantage.gui;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.vantage.core.Modules;
import dev.vantage.core.Ui;
import dev.vantage.core.Vantage;
import dev.vantage.module.client.ClickGuiModule;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * The loading screen in place of the Mojang one. Centre: the mark, the serif wordmark, the tagline, a progress line with its
 * percentage; bottom: the same version line the title screen carries, so it never moves. When the title screen follows, the
 * wordmark glides from the centre into the exact spot of the title's own wordmark and hands over to it there.
 * Everything is drawn from textures baked by tools/bake_loading.py, because the game's fonts are not loaded yet while this
 * shows. The vanilla LoadingOverlay keeps its timing (tick, fade-out start); LoadingOverlayMixin routes its render here.
 */
public final class VantageLoading {
    private static final int BG = 0xFF07061A, COOL = 0x3B6BFF;
    private static final int[] WORD = {256, 512, 1024}, INTER = {12, 16, 20, 26, 34, 48}, MARK = {64, 192};
    /** Wordmark ink in em of the serif (bake_loading.py prints these): bearing, width, height, advance of "Vantage". */
    private static final float W_BEAR = -0.0216f, W_INK = 2.76f, W_H = 0.938f, W_ADV = 2.7406f;
    /** Where the title screen's text sits against the y it is drawn at, measured on its screenshot: the serif's ink top and Inter's cap top, in em. */
    private static final float SERIF_TOP = -0.073f, CAP_TOP = 0.084f, CAP = 0.727f;
    /** The game's own font renderer sets the title's wordmark a touch narrower than the baked one (measured on screenshots). */
    private static final float LANDED = 0.982f;
    private static final float TITLE_SERIF = 11.5f * 4.6f, UI_EM = 9.5f;
    /** Milliseconds into the fade-out at which the background starts to clear; the title screen times its entrance to this. */
    public static final long REVEAL_MS = 300;
    private static final float REVEAL = REVEAL_MS / 1000f, CLEAR = 0.6f, GONE = 0.5f;
    /** The glide into the title: starts, lasts, and the hand-over window in which the title's own wordmark takes over (seconds into the fade-out). */
    private static final float GLIDE_AT = 0.05f, GLIDE = 0.6f, LAND_AT = 0.54f, LAND = 0.14f, FOOT_AT = 0.6f, FOOT = 0.3f;

    private static boolean textures, failed;
    private static long first = -1, outWall = -1;
    private static float progress;
    private static final float[] ADV = new float[96];
    private static final int[] wordH = new int[WORD.length];

    private VantageLoading() {}

    public static boolean enabled() {
        if (failed) return false;
        try {
            ClickGuiModule m = Modules.get(ClickGuiModule.class);
            return m == null || m.screens.on();
        } catch (Throwable t) {
            return true;
        }
    }

    /** A baked texture with linear filtering: these are drawn smaller than they are baked. */
    private static final class Baked extends DynamicTexture {
        Baked(Identifier id, NativeImage img) {
            super(id::toString, img);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("vantage", "load/" + name);
    }

    private static boolean textures() {
        if (textures) return true;
        try {
            for (int i = 0; i < WORD.length; i++) wordH[i] = load("wordmark_" + WORD[i]);
            for (int em : INTER) load("inter_" + em);
            for (int m : MARK) load("mark_" + m);
            load("glow");
            try (InputStream in = VantageLoading.class.getResourceAsStream("/assets/vantage/textures/gui/load/inter.adv")) {
                if (in == null) throw new IllegalStateException("inter.adv");
                String[] p = new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim().split(" ");
                for (int i = 0; i < ADV.length; i++) ADV[i] = Float.parseFloat(p[i]);
            }
            textures = true;
        } catch (Throwable t) {
            Vantage.LOG.warn("Loading screen textures missing, vanilla loading screen instead: {}", t.toString());
            failed = true;
        }
        return textures;
    }

    private static int load(String name) throws Exception {
        String path = "/assets/vantage/textures/gui/load/" + name + ".png";
        try (InputStream in = VantageLoading.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path);
            NativeImage img = NativeImage.read(in);
            int h = img.getHeight();
            Minecraft.getInstance().getTextureManager().register(id(name), new Baked(id(name), img));
            return h;
        }
    }

    /** For the title screen: 0..1, how far its own wordmark has taken over from the gliding one (1 when no loading screen is handing over). */
    public static float landed(long nowWall) {
        return outWall < 0 ? 1f : Ui.ease(((nowWall - outWall) / 1000f - LAND_AT) / LAND);
    }

    /** For the title screen: 0..1, the same for its version line, which the loading screen shows in the same place. */
    public static float footer(long nowWall) {
        return outWall < 0 ? 1f : Mth.clamp(((nowWall - outWall) / 1000f - FOOT_AT) / FOOT, 0f, 1f);
    }

    /** The version line both screens show bottom left. */
    public static String versionLine() {
        return "Minecraft " + SharedConstants.getCurrentVersion().name() + "  ·  Fabric  ·  client side";
    }

    private static String version;

    private static String version() {
        if (version == null) version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("vantage").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        return version;
    }

    private static float px() {
        try {
            float p = Ui.px();
            if (p > 0.05f) return p;
        } catch (Throwable ignored) {}
        return Minecraft.getInstance().getWindow().getHeight() / 720f;
    }

    private static float span(long t, long from, long len) {
        return Ui.ease((t - from) / (float) len);
    }

    private static float inOut(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    /**
     * Draws one frame. fadeInStart / fadeOutStart are the overlay's own clocks (-1 = not started).
     * @return true once the fade-out is over and the overlay can go.
     */
    public static boolean render(GuiGraphics g, Minecraft mc, float actual, boolean fadeIn, long fadeInStart, long fadeOutStart, float delta) {
        if (!textures()) return false;
        long now = Util.getMillis();
        if (first < 0) { first = now; progress = 0; outWall = -1; Vantage.LOG.info("Vantage loading screen"); }
        long t = now - first;
        int gw = g.guiWidth(), gh = g.guiHeight();
        float out = fadeOutStart > -1 ? (now - fadeOutStart) / 1000f : -1f;
        float in = fadeIn && fadeInStart > -1 ? (now - fadeInStart) / 400f : -1f;
        boolean toTitle = mc.screen instanceof VantageTitleScreen;
        if (out >= 0 && outWall < 0 && toTitle) outWall = System.currentTimeMillis() - (now - fadeOutStart);

        // background: solid while loading, the screen underneath while fading in or out
        float bgA = 1f;
        if (out >= REVEAL) bgA = 1f - Ui.ease((out - REVEAL) / CLEAR);
        else if (fadeIn && in < 1f) bgA = Mth.clamp(in, 0.15f, 1f);
        if (bgA < 1f) {
            if (mc.screen != null) mc.screen.renderWithTooltipAndSubtitles(g, 0, 0, delta);
            else mc.gui.renderDeferredSubtitles();
            g.nextStratum();
        }
        g.fill(0, 0, gw, gh, ARGB.color(Mth.ceil(bgA * 255), BG));

        // how much of the small pieces is left: they leave quickly so the wordmark travels alone
        float gone = out >= 0 ? Ui.ease(out / (toTitle ? 0.22f : GONE)) : 0f;
        float rest = (1f - gone) * (toTitle ? 1f : bgA);
        progress = Mth.clamp(Math.max(progress, progress * 0.88f + actual * 0.12f), 0f, 1f);

        float d = (float) mc.getWindow().getGuiScale(), u = px();
        int W = mc.getWindow().getWidth(), H = mc.getWindow().getHeight();
        g.pose().pushMatrix();
        g.pose().scale(1f / d, 1f / d);
        float cx = W / 2f, cy = H * 0.455f;

        // light: the accent glow behind the wordmark breathes, a cooler one drifts slowly low on the right
        float light = span(t, 0, 600) * (out >= 0 ? 1f - Ui.ease(out / 0.45f) : 1f) * (toTitle ? 1f : bgA);
        float breathe = 1f + 0.03f * (float) Math.sin(t / 1100.0);
        float gs = Math.min(W, H) * 1.05f * breathe;
        blit(g, id("glow"), cx - gs / 2f, cy - gs / 2f, gs, gs, 512, 512, Ui.ACCENT, 0.42f * light);
        float cs = Math.min(W, H) * 0.9f;
        float dx = W * 0.27f + (float) Math.sin(t / 5200.0) * W * 0.03f, dy = H * 0.3f + (float) Math.cos(t / 4300.0) * H * 0.03f;
        blit(g, id("glow"), cx + dx - cs / 2f, cy + dy - cs / 2f, cs, cs, 512, 512, COOL, 0.16f * light);
        blit(g, id("glow"), cx - dx * 1.2f - cs / 2f, cy - dy * 1.3f - cs / 2f, cs, cs, 512, 512, Ui.ACCENT, 0.10f * light);

        // the wordmark: 300 design units of ink in the centre; with the title next it glides to the title's own
        float w0 = 300 * u, h0 = w0 * W_H / W_INK, x0 = cx - w0 / 2f, y0 = cy - h0 * 0.62f;
        float wIn = span(t, 250, 650);
        float wx = x0, wy = y0 + 10 * u * (1f - wIn), ww = w0, wa = wIn;
        if (out >= 0 && toTitle) {
            float em = TITLE_SERIF * u, e = inOut((out - GLIDE_AT) / GLIDE);
            float w1 = W_INK * em * LANDED, x1 = cx - W_ADV * em / 2f + (W_BEAR + W_INK * (1f - LANDED) / 2f - 0.012f) * em, y1 = H * 0.16f + SERIF_TOP * em;
            ww = Mth.lerp(e, w0, w1);
            wx = Mth.lerp(e, x0, x1);
            wy = Mth.lerp(e, y0, y1);
            wa = wIn * (1f - Mth.clamp((out - LAND_AT - LAND * 0.5f) / LAND, 0f, 1f));
        } else if (out >= 0) {
            wy -= 28 * u * gone;
            wa = wIn * rest;
        } else wa *= bgA;
        int lv = 0;
        while (lv < WORD.length - 1 && WORD[lv] < ww) lv++;
        blit(g, id("wordmark_" + WORD[lv]), wx, wy, ww, ww * wordH[lv] / WORD[lv], WORD[lv], wordH[lv], Ui.TEXT, wa);

        // the mark above it
        float mIn = span(t, 120, 520), ms = 26 * u * (0.82f + 0.18f * mIn);
        blit(g, id(ms > 64 ? "mark_192" : "mark_64"), cx - ms / 2f, y0 - 40 * u - ms / 2f + 6 * u * (1f - mIn), ms, ms, ms > 64 ? 192 : 64, ms > 64 ? 192 : 64, Ui.ACCENT, mIn * rest);

        // tagline, the same words the title carries
        float tIn = span(t, 480, 520), tem = UI_EM * 1.12f * u;
        String tag = "finders, leaks and a clean view";
        text(g, tag, cx - width(tag, tem) / 2f, y0 + h0 + 16 * u + 6 * u * (1f - tIn), tem, Ui.SUB, tIn * rest);

        // progress: a faint track that opens from the middle, the accent fill, a glowing head, the percentage under it
        float bIn = span(t, 640, 520), bw = 220 * u * bIn, bh = Math.max(2f, Math.round(1.5f * u));
        float bx = cx - bw / 2f, by = y0 + h0 + 74 * u;
        fill(g, bx, by, bw, bh, ARGB.color(Mth.ceil(26 * bIn * rest), 0xFFFFFF));
        float fw = bw * progress;
        if (fw > 0.5f) {
            fill(g, bx, by, fw, bh, ARGB.color(Mth.ceil(255 * bIn * rest), Ui.ACCENT));
            float hs = bh * 10;
            blit(g, id("glow"), bx + fw - hs / 2f, by + bh / 2f - hs / 2f, hs, hs, 512, 512, Ui.ACCENT, 0.9f * bIn * rest);
        }
        float pIn = span(t, 800, 400), pem = UI_EM * 0.84f * u;
        String pct = Math.round(progress * 100) + "%";
        float slot = ADV['0' - 32] * pem, pw = 0;
        for (int i = 0; i < pct.length(); i++) pw += Character.isDigit(pct.charAt(i)) ? slot : ADV[pct.charAt(i) - 32] * pem;
        float px = cx - pw / 2f;
        for (int i = 0; i < pct.length(); i++) {
            char c = pct.charAt(i);
            float a = ADV[c - 32] * pem, cell = Character.isDigit(c) ? slot : a;
            text(g, String.valueOf(c), px + (cell - a) / 2f, by + 14 * u, pem, Ui.FAINT, pIn * rest);
            px += cell;
        }

        // bottom left: the version line, in the title screen's place and size, so the title takes it over without a jump
        float fIn = span(t, 600, 500), fem = UI_EM * 0.84f * u;
        float column = Math.min(W / u, 1180f), fx = (W / u - column) / 2f + 40, fy = H / u - 40 - 20;
        float fa = fIn * (out < 0 ? bgA : toTitle ? 1f - Mth.clamp((out - FOOT_AT) / FOOT, 0f, 1f) : rest);
        text(g, versionLine(), fx * u, fy * u + CAP_TOP * fem, fem, Ui.FAINT, fa);
        String ver = "Vantage " + version();
        text(g, ver, (fx + column - 80) * u - width(ver, fem), fy * u + CAP_TOP * fem, fem, Ui.FAINT, fIn * rest);
        g.pose().popMatrix();

        boolean done = out >= REVEAL + CLEAR + 0.05f;
        if (done) first = -1;
        return done;
    }

    private static float width(String s, float em) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) w += ADV[cell(s.charAt(i))] * em;
        return w;
    }

    private static int cell(char c) {
        return c == '·' ? 95 : c >= 32 && c < 127 ? c - 32 : '?' - 32;
    }

    /** Text from the baked Inter sheets: x is the pen start, top is where the capitals' top edge goes, em the size in pixels. */
    private static void text(GuiGraphics g, String s, float x, float top, float em, int color, float alpha) {
        if (alpha <= 0.004f) return;
        int size = INTER[INTER.length - 1];
        for (int i = INTER.length - 1; i >= 0; i--) if (INTER[i] >= em) size = INTER[i];
        int cw = size + 4, ch = Mth.ceil(size * 1.4f) + 4, base = 2 + size;
        float k = em / size;
        int argb = ARGB.color(Mth.ceil(Mth.clamp(alpha, 0f, 1f) * (color >>> 24)), color);
        Identifier sheet = id("inter_" + size);
        g.pose().pushMatrix();
        g.pose().translate(x, top + CAP * em);
        g.pose().scale(k, k);
        float pen = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = cell(s.charAt(i));
            if (c != 0) g.blit(RenderPipelines.GUI_TEXTURED, sheet, Math.round(pen) - 2, -base, c % 16 * cw, c / 16 * ch, cw, ch, cw, ch, cw * 16, ch * 6, argb);
            pen += ADV[c] * size;
        }
        g.pose().popMatrix();
    }

    /** One whole texture of tw x th texels into the given rectangle (pixels, fractions kept), tinted. */
    private static void blit(GuiGraphics g, Identifier id, float x, float y, float w, float h, int tw, int th, int color, float alpha) {
        if (alpha <= 0.004f || w <= 0 || h <= 0) return;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(w / tw, h / th);
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, tw, th, tw, th, tw, th, ARGB.color(Mth.ceil(Mth.clamp(alpha, 0f, 1f) * 255), color));
        g.pose().popMatrix();
    }

    private static void fill(GuiGraphics g, float x, float y, float w, float h, int color) {
        if (color >>> 24 == 0 || w < 0.5f) return;
        g.fill(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), color);
    }
}
