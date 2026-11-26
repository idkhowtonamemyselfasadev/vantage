package dev.vantage.module.cosmetic;

import java.lang.reflect.Method;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Perf;
import dev.vantage.core.Vantage;
import dev.vantage.module.render.Freecam;
import dev.vantage.render.PostPass;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Camera motion blur from depth reprojection, the way shader packs do it, with no history image.
 * - At the end of LevelRenderer.renderLevel the world picture is finished and its depth is still intact.
 * - Every pixel is rebuilt from that depth, moved with last frame's camera rotation and position (this frame's projection,
 *   so bob, hurt tilt and FOV/zoom never blur) and the frame is averaged along that per-pixel velocity.
 * - Two tiny passes find the longest velocity around every pixel (tile max over T x T pixel tiles, then the max over the
 *   neighbouring tiles within half the max blur), so a still background next to a fast foreground knows that foreground
 *   smears over it: the streak fades out on both sides of a silhouette instead of stopping dead at it (McGuire 2012).
 * - Two full-screen passes per moving frame that act as one iterated blur: stage 1 (frame -> scratch) is a depth-aware
 *   reconstruction filter with n taps over the streak (front / own / back layers), stage 2 (scratch -> frame) takes m taps
 *   over one gap of stage 1 where stage 1 blurred, so n * m evenly spaced taps (64 at Samples 8) make a smooth streak with
 *   no staircase and no noise pattern.
 * - Only the current frame is read, so nothing ghosts or trails.
 * - A still camera runs no pass at all; pixels under half a pixel of motion are discarded, so they stay byte-exact.
 * - Hands: Keep sharp = the passes run before the hands are drawn. Blur too = the world depth is saved and the passes run
 *   right after the hands.
 * - Any failure switches the module off with one log line.
 */
public class MotionBlur extends Module {
    public final NumberSetting strength = add(new NumberSetting("Strength", "Shutter length: how far a turn streaks. 0.5 is a film camera, 2 is dramatic; looks the same at any fps", 0.6, 0.1, 2.0, 0.05));
    public final NumberSetting samples = add(new NumberSetting("Samples", "Taps per pass; the two passes multiply them (8 = 64 points along a streak). More is smoother on very long streaks and costs a little more", 8, 4, 16, 1));
    public final NumberSetting maxBlur = add(new NumberSetting("Max blur", "Longest streak in pixels at 1080p (scaled to your resolution), so a flick never turns the screen to soup", 80, 20, 200, 5));
    public final BoolSetting translation = add(new BoolSetting("Translation", "Also blur from moving (walking, flying, knockback), not only from turning", true));
    public final ModeSetting hands = add(new ModeSetting("Hands", "Keep sharp leaves your hands and held item crisp; Blur too streaks them with the picture", "Keep sharp", "Keep sharp", "Blur too"));

    /** For the rig: frames blurred, frames blurred after the hands (Blur too), frames skipped because the camera was still,
     *  cuts (teleport, F5, resize...), failures. */
    public static int frames, handFrames, stills, cuts, failures;
    /** For the rig: the shutter (Strength * 60 Hz frame-time scale) of the last blurred frame. */
    public static float lastShutter;
    /** Rig only: when not 0, last frame's view is taken as this frame's turned by this many degrees of yaw, every frame. */
    public static float testSpin;
    /** Rig only: when set, last frame's camera is taken as this far behind this frame's (camera moved by this since last frame). */
    public static Vec3 testMove;
    /** Rig only: run the passes even on a still camera (they must then leave every pixel untouched). */
    public static boolean testForce;

    // tile max + neighbour max (tiny), then one shader as two pipelines (each with its own uniform ring buffer, both run once
    // per frame); BlurData.Stage tells them apart
    private static final int BLOCK = 128;
    private static final PostPass TILE = new PostPass("motion_blur_tile", "motion_blur_tile", null, "BlurData", BLOCK, "DepthSampler");
    private static final PostPass NEAR = new PostPass("motion_blur_near", "motion_blur_near", null, "BlurData", BLOCK, "TileSampler");
    private static final PostPass STAGE1 = new PostPass("motion_blur_1", "motion_blur", null, "BlurData", BLOCK, "InSampler", "DepthSampler", "TileSampler");
    private static final PostPass STAGE2 = new PostPass("motion_blur_2", "motion_blur", null, "BlurData", BLOCK, "InSampler", "DepthSampler", "TileSampler");
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");
    private static Object irisApi;
    private static Method irisInUse;
    private static boolean irisBroken;

    private static TextureTarget scratch, depthKeep, tileMax, nearMax;
    private static final Matrix4d curView = new Matrix4d(), curProj = new Matrix4d(), prevView = new Matrix4d(), rd = new Matrix4d(), tmp = new Matrix4d();
    private static final Quaternionf curRot = new Quaternionf(), prevRot = new Quaternionf();
    private static final Matrix4f reproject = new Matrix4f();
    private static Vec3 curPos = Vec3.ZERO, prevPos = Vec3.ZERO;
    private static ClientLevel prevLevel;
    private static Entity prevEntity;
    private static CameraType prevType;
    private static Freecam prevFreecam;
    private static int prevW, prevH;
    private static long lastNs;
    private static double dtAvg = 1.0 / 60.0;
    private static boolean captured, havePrev, pending;
    private static float shutter, maxPx, taps, debug;
    private static float zx, zy, zz, zw, wx, wy, wz, ww;   // rows 2 and 3 of inverse(P): view-space z and w of an NDC point

    public MotionBlur() {
        super("Motion Blur", "Fast turns and moves streak the picture along their motion, like a real camera shutter", Category.COSMETIC);
    }

    @Override protected void onEnable() { captured = false; forget(); }
    @Override protected void onDisable() { free(); forget(); }
    @Override
    public void onJoinWorld() {
        forget();
    }

    @Override
    public void onLeaveWorld() {
        forget();
        free(); // back on the title screen: give the VRAM back (about 100 MB at 4K)
    }

    @Override
    public void onDimensionChange() {
        forget();
    }

    private static void forget() { havePrev = false; pending = false; prevLevel = null; prevEntity = null; prevFreecam = null; }

    private static void free() {
        if (scratch != null) { scratch.destroyBuffers(); scratch = null; }
        if (depthKeep != null) { depthKeep.destroyBuffers(); depthKeep = null; }
        if (tileMax != null) { tileMax.destroyBuffers(); tileMax = null; }
        if (nearMax != null) { nearMax.destroyBuffers(); nearMax = null; }
    }

    /** True while an Iris shader pack runs: depth and matrices are the pack's then (same rule as HandAura). Cached reflection. */
    private static boolean shaderPack() {
        if (!IRIS) return false;
        if (irisBroken) return true;
        try {
            if (irisInUse == null) {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisInUse = api.getMethod("isShaderPackInUse");
            }
            return (boolean) irisInUse.invoke(irisApi);
        } catch (Throwable t) {
            irisBroken = true;                                // never retry reflection every frame
            Vantage.LOG.warn("Iris API not found, Motion Blur stays off while Iris is loaded", t);
            return true;
        }
    }

    /** LevelRenderer.renderLevel HEAD: the exact matrices vanilla renders the world with (copied, vanilla reuses them). */
    public static void capture(Camera camera, Matrix4f view, Matrix4f projection) {
        pending = false;
        MotionBlur mb = Modules.get(MotionBlur.class);
        if (mb == null || !mb.on()) { captured = false; return; }
        curView.set(view);
        curProj.set(projection);
        curRot.set(camera.rotation());
        curPos = camera.position();
        captured = true;
    }

    /** LevelRenderer.renderLevel TAIL: world colour finished, world depth intact, hands / overlays / HUD not drawn yet. */
    public static void afterWorld() {
        MotionBlur mb = Modules.get(MotionBlur.class);
        if (mb == null || !mb.on()) { captured = false; forget(); return; }
        Minecraft mc = Minecraft.getInstance();
        if (!captured || mc.level == null || mc.player == null || mc.gameRenderer.isPanoramicMode() || shaderPack()) { captured = false; forget(); return; }
        captured = false;
        long t0 = System.nanoTime();
        try {
            RenderTarget main = mc.getMainRenderTarget();
            int w = main.width, h = main.height;
            Entity camEntity = mc.getCameraEntity();
            CameraType camType = mc.options.getCameraType();
            Freecam freecam = Freecam.active();
            boolean move = mb.translation.on();
            double dx = curPos.x - prevPos.x, dy = curPos.y - prevPos.y, dz = curPos.z - prevPos.z;
            if (testMove != null) { dx = testMove.x; dy = testMove.y; dz = testMove.z; }
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double dot = Math.abs((double) prevRot.x * curRot.x + (double) prevRot.y * curRot.y + (double) prevRot.z * curRot.z + (double) prevRot.w * curRot.w);
            double dt = (t0 - lastNs) / 1e9;
            // a cut gets one sharp frame instead of a full-screen streak: first frame, world / camera entity / F5 / freecam switch,
            // resize, hitch or alt-tab (> 0.25 s), teleport (> 8 blocks, or faster than 100 blocks/s with a 1 block floor: pearls,
            // setbacks, mount snaps) or more than 90 degrees of turn in one frame
            boolean jump = testMove == null && dist > Math.max(1.0, 100.0 * dt);
            boolean fakeCam = testForce || testSpin != 0f || testMove != null;   // rig only: a slow rig frame must not cut a faked turn
            boolean cut = !havePrev || mc.level != prevLevel || camEntity != prevEntity || camType != prevType || freecam != prevFreecam
                    || w != prevW || h != prevH || dt <= 0 || (dt > 0.25 && !fakeCam) || dist > 8.0 || jump || dot < 0.7071;
            if (!cut) dtAvg += (dt - dtAvg) * 0.2;           // smoothed frame time: one hitch never makes the streak length jump
            boolean still = !cut && !testForce && testSpin == 0f && prevView.equals(curView, 1e-6) && (!move || dist < 1e-6);
            if (!cut && !still) {
                // Reproject = P * Rprev * T(camNow - camPrev) * inverse(P * Rnow), in double, cast once. Same P both frames:
                // bob, hurt tilt, nausea and FOV/zoom changes cancel; only real camera rotation and translation make velocity.
                rd.set(curProj);
                if (testSpin != 0f) rd.mul(tmp.set(curView).rotateY(Math.toRadians(testSpin)));
                else rd.mul(prevView);
                if (move) rd.translate(dx, dy, dz);           // JOML post-multiplies: point relative to the previous camera = p + (now - prev)
                rd.mul(tmp.set(curProj).mul(curView).invert());
                reproject.set(rd);
                tmp.set(curProj).invert();                    // depth -> distance, for the front / behind tests (JOML mCR = column C, row R)
                zx = (float) tmp.m02(); zy = (float) tmp.m12(); zz = (float) tmp.m22(); zw = (float) tmp.m32();
                wx = (float) tmp.m03(); wy = (float) tmp.m13(); wz = (float) tmp.m23(); ww = (float) tmp.m33();
            }
            // roll this frame into the history whatever happens next
            prevView.set(curView); prevRot.set(curRot); prevPos = curPos;
            prevLevel = mc.level; prevEntity = camEntity; prevType = camType; prevFreecam = freecam;
            prevW = w; prevH = h; lastNs = t0; havePrev = true;
            if (cut) { cuts++; return; }
            if (still || !reproject.isFinite()) { stills++; return; }
            boolean fake = testSpin != 0f || testMove != null;
            float frameScale = fake ? 1f : (float) Math.max(0.25, Math.min(16.0, (1.0 / 60.0) / dtAvg)); // 60 Hz shutter up to 960 fps
            shutter = mb.strength.f() * frameScale;
            lastShutter = shutter;
            maxPx = mb.maxBlur.f() * h / 1080f;
            taps = mb.samples.i();
            if (mb.hands.is("Blur too") && (freecam == null || !freecam.hideHand.on())) {
                if (depthKeep == null || depthKeep.width != w || depthKeep.height != h) {
                    if (depthKeep != null) depthKeep.destroyBuffers();
                    depthKeep = new TextureTarget("vantage blur depth", w, h, true);
                }
                depthKeep.copyDepthFrom(main);            // vanilla clears main depth before the hands; keep the world's
                pending = true;                           // the passes run in afterHands()
            } else {
                if (depthKeep != null) { depthKeep.destroyBuffers(); depthKeep = null; }
                blur(main, main.getDepthTextureView());
            }
            Perf.add("Motion blur passes", System.nanoTime() - t0);
        } catch (Throwable t) {
            fail(mb, t);
        }
    }

    /** GameRenderer.renderItemInHand TAIL (hands are flushed by then), Blur too mode only; before screen effects and HUD. */
    public static void afterHands() {
        if (!pending) return;
        pending = false;
        MotionBlur mb = Modules.get(MotionBlur.class);
        if (mb == null || !mb.on() || depthKeep == null) return;
        long t0 = System.nanoTime();
        try {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            if (depthKeep.width != main.width || depthKeep.height != main.height) return;
            blur(main, depthKeep.getDepthTextureView());
            handFrames++;
            Perf.add("Motion blur passes", System.nanoTime() - t0);
        } catch (Throwable t) {
            fail(mb, t);
        }
    }

    /** Tile max and neighbour max into two tiny targets, then stage 1 blurs the frame into scratch (a pass cannot read its own
     *  target) and stage 2 blurs scratch back onto main, discarding pixels stage 1 left alone so they keep their exact
     *  vanilla value. */
    private static void blur(RenderTarget main, GpuTextureView depth) {
        int w = main.width, h = main.height;
        scratch = sized(scratch, "vantage blur stage 1", w, h);
        // tiles of 16 px at 1080p (scaled with the height, so the tile grid and the neighbour radius look the same at any
        // resolution); at most 16 x 16 depth samples per tile; the neighbour radius covers half the max blur
        int tile = Math.max(8, Math.round(16f * h / 1080f));
        int stride = (tile + 15) / 16;
        int radius = Math.max(1, Math.min(8, (int) Math.ceil(maxPx * 0.5f / tile)));
        int tw = (w + tile - 1) / tile, th = (h + tile - 1) / tile;
        tileMax = sized(tileMax, "vantage blur tile max", tw, th);
        nearMax = sized(nearMax, "vantage blur neighbour max", tw, th);
        debug = Boolean.getBoolean("vantage.blurDebug") ? 1f : 0f;
        TILE.run(tileMax.getColorTextureView(), FilterMode.NEAREST, u -> block(u, 3f, tile, stride), depth);
        NEAR.run(nearMax.getColorTextureView(), FilterMode.NEAREST, u -> block(u, 4f, tile, radius), tileMax.getColorTextureView());
        STAGE1.run(scratch.getColorTextureView(), FilterMode.LINEAR, u -> block(u, 1f, tile, 0),
                main.getColorTextureView(), depth, nearMax.getColorTextureView());
        STAGE2.run(main.getColorTextureView(), FilterMode.LINEAR, u -> block(u, 2f, tile, 0),
                scratch.getColorTextureView(), depth, nearMax.getColorTextureView());
        frames++;
    }

    private static TextureTarget sized(TextureTarget t, String name, int w, int h) {
        if (t != null && t.width == w && t.height == h) return t;
        if (t != null) t.destroyBuffers();
        return new TextureTarget(name, w, h, false);
    }

    /** BlurData, 128 bytes: Reproject, Params, Stage (stage, tile size, extra, 0), ViewZ, ViewW. */
    private static void block(Std140Builder u, float stage, float tile, float extra) {
        u.putMat4f(reproject).putVec4(shutter, maxPx, taps, debug).putVec4(stage, tile, extra, 0f)
                .putVec4(zx, zy, zz, zw).putVec4(wx, wy, wz, ww);
    }

    private static void fail(MotionBlur mb, Throwable t) {
        failures++;
        pending = false;
        Vantage.LOG.warn("Motion blur pass failed, switching it off", t);
        mb.setEnabled(false);                              // onDisable frees the targets: vanilla picture from the next frame
    }
}
