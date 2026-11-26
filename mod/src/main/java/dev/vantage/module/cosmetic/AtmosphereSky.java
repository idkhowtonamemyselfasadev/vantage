package dev.vantage.module.cosmetic;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Random;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.vantage.core.Perf;
import dev.vantage.core.Vantage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The drawing for {@link Atmosphere}. Everything is plain coloured geometry through vanilla's position_color shaders, so no
 * shader files of its own: a dome of quads with per-vertex colours, star quads, a milky-way strip, an aurora curtain, halo
 * fans and meteor streaks. Translucent for the dome, OVERLAY (src alpha, one) for every light, the blend vanilla's stars use: ADDITIVE ignores alpha and would paint every light at full strength. Any throw marks the sky broken and vanilla's
 * sky comes back, so a driver quirk can never leave you without a sky.
 */
public final class AtmosphereSky {
    static boolean broken;
    private static RenderPipeline fill, add;
    private static final float R = 100f;
    private static final long START = System.nanoTime();
    private static final Random RNG = new Random();
    /** Stars: fixed positions, brightness redrawn every 50 ms. */
    private static float[] starPos, starSize, starSeed;
    private static GpuBuffer starBuf;
    private static int starIndices;
    private static long starBuiltAt;
    private static GpuBuffer milkyBuf;
    private static int milkyIndices;
    /** Meteor: when the next one starts, when the current one started and its path. */
    private static double nextMeteor = -1, meteorStart, meteorLength;
    private static final Vector3f meteorFrom = new Vector3f(), meteorDir = new Vector3f();
    /** Aurora fade: rises 0.5 per second at night, falls as fast by day. */
    private static float auroraLevel;
    private static long auroraTick;
    /** The last fog colour vanilla computed, used as the dome's horizon by day. */
    static final Vector4f vanillaFog = new Vector4f(0.6f, 0.7f, 0.9f, 1f);
    /** The dome's horizon colour as last drawn, for the fog hook. */
    static final Vector3f horizonNow = new Vector3f(0.6f, 0.7f, 0.9f);

    private AtmosphereSky() {}

    static void free() {
        if (starBuf != null) { starBuf.close(); starBuf = null; }
        if (milkyBuf != null) { milkyBuf.close(); milkyBuf = null; }
    }

    private static float seconds() { return (System.nanoTime() - START) / 1e9f; }

    /** 0 by day, 1 at night, following vanilla's star brightness (which tops out at 0.5). */
    static float night(SkyRenderState s) { return Math.min(1f, Math.max(0f, s.starBrightness * 2f)); }

    private static void pipelines() {
        if (fill != null) return;
        fill = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("vantage", "pipeline/sky_fill"))
                .withVertexShader("core/position_color").withFragmentShader("core/position_color")
                .withBlend(BlendFunction.TRANSLUCENT).withDepthWrite(false)
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS).build();
        add = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("vantage", "pipeline/sky_add"))
                .withVertexShader("core/position_color").withFragmentShader("core/position_color")
                .withBlend(BlendFunction.OVERLAY).withDepthWrite(false)
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS).build();
    }

    /** Draws a quad mesh with the given model-view and colour modulator onto the main target. */
    private static void draw(RenderPipeline pipe, GpuBuffer vb, int indices, Matrix4f modelView, float r, float g, float b, float a) {
        if (indices <= 0) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        GpuBufferSlice tr = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(r, g, b, a), new Vector3f(), new Matrix4f());
        var idx = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        try (RenderPass p = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "vantage sky", main.getColorTextureView(), OptionalInt.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
            p.setPipeline(pipe);
            RenderSystem.bindDefaultUniforms(p);
            p.setUniform("DynamicTransforms", tr);
            p.setVertexBuffer(0, vb);
            p.setIndexBuffer(idx.getBuffer(indices), idx.type());
            p.drawIndexed(0, 0, indices, 1);
        }
    }

    /** Builds, draws once and frees a mesh. */
    private static void drawOnce(RenderPipeline pipe, BufferBuilder b, Matrix4f modelView, float r, float g, float bl, float a) {
        MeshData mesh = b.build();
        if (mesh == null) return;
        try (mesh) {
            GpuBuffer vb = RenderSystem.getDevice().createBuffer(() -> "vantage sky mesh", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            draw(pipe, vb, mesh.drawState().indexCount(), modelView, r, g, bl, a);
            vb.close();
        }
    }

    private static Matrix4f view() { return new Matrix4f(RenderSystem.getModelViewMatrix()); }

    private static Matrix4f view(PoseStack pose) { return view().mul(pose.last().pose()); }

    private static float lerp(float t, float a, float b) { return a + (b - a) * t; }

    private static int mix(int a, int b, float t) { return ARGB.srgbLerp(t, a, b); }

    private static void vertex(BufferBuilder b, float x, float y, float z, int argb, float alpha) {
        b.addVertex(x, y, z).setColor(ARGB.red(argb) / 255f, ARGB.green(argb) / 255f, ARGB.blue(argb) / 255f, alpha);
    }

    // ---------------------------------------------------------------- the dome

    /** Replaces the sky disc: a dome whose colour runs from the palette's zenith down to the fog colour at the horizon. */
    public static void dome(Atmosphere m, SkyRenderState s) {
        long t0 = System.nanoTime();
        try {
            pipelines();
            float n = night(s), k = Math.max(n, m.dayTint.f());
            int[] pal = m.palette();
            int fogC = ARGB.colorFromFloat(1f, vanillaFog.x, vanillaFog.y, vanillaFog.z);
            int dayTop = s.skyColor | 0xFF000000, dayMid = mix(s.skyColor | 0xFF000000, fogC, 0.45f);
            int top = mix(dayTop, pal[0], k), midC = mix(dayMid, pal[1], k), hor = mix(fogC, pal[2], k * 0.8f), below = mix(fogC, mix(pal[0], 0xFF000000, 0.5f), k);
            horizonNow.set(ARGB.red(hor) / 255f, ARGB.green(hor) / 255f, ARGB.blue(hor) / 255f);
            // rings from the zenith down to a little below the horizon; colours eased so the horizon band stays wide
            float[] el = {90, 60, 38, 22, 11, 4, 0, -6, -20, -90};
            int[] col = {top, mix(top, midC, 0.45f), mix(top, midC, 0.85f), midC, mix(midC, hor, 0.5f), mix(midC, hor, 0.85f), hor, mix(hor, below, 0.5f), below, below};
            try (ByteBufferBuilder bytes = new ByteBufferBuilder(48 * 10 * 4 * 16)) {
                BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                int seg = 48;
                for (int ring = 0; ring < el.length - 1; ring++) {
                    float e0 = (float) Math.toRadians(el[ring]), e1 = (float) Math.toRadians(el[ring + 1]);
                    float y0 = (float) Math.sin(e0) * R, r0 = (float) Math.cos(e0) * R, y1 = (float) Math.sin(e1) * R, r1 = (float) Math.cos(e1) * R;
                    for (int i = 0; i < seg; i++) {
                        float a0 = (float) (i * Math.PI * 2 / seg), a1 = (float) ((i + 1) * Math.PI * 2 / seg);
                        vertex(b, (float) Math.cos(a0) * r0, y0, (float) Math.sin(a0) * r0, col[ring], 1f);
                        vertex(b, (float) Math.cos(a0) * r1, y1, (float) Math.sin(a0) * r1, col[ring + 1], 1f);
                        vertex(b, (float) Math.cos(a1) * r1, y1, (float) Math.sin(a1) * r1, col[ring + 1], 1f);
                        vertex(b, (float) Math.cos(a1) * r0, y0, (float) Math.sin(a1) * r0, col[ring], 1f);
                    }
                }
                drawOnce(fill, b, view(), 1f, 1f, 1f, 1f);
            }
            Atmosphere.frames++;
            Perf.add("Atmosphere dome", System.nanoTime() - t0);
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ---------------------------------------------------------------- stars and the milky way

    private static void makeStars() {
        Random r = new Random(10842L);
        int n = 2600;
        starPos = new float[n * 3];
        starSize = new float[n];
        starSeed = new float[n];
        for (int i = 0; i < n; i++) {
            // uniform on the sphere
            float z = r.nextFloat() * 2 - 1, a = r.nextFloat() * (float) Math.PI * 2, rr = (float) Math.sqrt(1 - z * z);
            starPos[i * 3] = rr * (float) Math.cos(a) * R;
            starPos[i * 3 + 1] = z * R;
            starPos[i * 3 + 2] = rr * (float) Math.sin(a) * R;
            float s = r.nextFloat();
            starSize[i] = 0.12f + s * s * 0.3f;
            starSeed[i] = r.nextFloat() * 1000f;
        }
    }

    /** Rebuilds the star quads with fresh twinkle every 50 ms. */
    private static void buildStars() {
        if (starPos == null) makeStars();
        long now = System.currentTimeMillis();
        if (starBuf != null && now - starBuiltAt < 50) return;
        starBuiltAt = now;
        float t = now / 1000f;
        int n = starSize.length;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(n * 4 * 16 + 64)) {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            Vector3f p = new Vector3f(), u = new Vector3f(), v = new Vector3f();
            for (int i = 0; i < n; i++) {
                p.set(starPos[i * 3], starPos[i * 3 + 1], starPos[i * 3 + 2]);
                // a tangent frame: u = p x up (or x axis near the poles), v = p x u
                Vector3f ref = Math.abs(p.y) > R * 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
                p.cross(ref, u).normalize(); p.cross(u, v).normalize();
                float tw = Cos.calm() ? 0.8f : 0.65f + 0.35f * (float) Math.sin(t * (1.5f + (starSeed[i] % 1f) * 2.5f) + starSeed[i]);
                float s = starSize[i];
                int c = 0xFFFFFFFF;
                vertex(b, p.x + (u.x + v.x) * s, p.y + (u.y + v.y) * s, p.z + (u.z + v.z) * s, c, tw);
                vertex(b, p.x + (u.x - v.x) * s, p.y + (u.y - v.y) * s, p.z + (u.z - v.z) * s, c, tw);
                vertex(b, p.x + (-u.x - v.x) * s, p.y + (-u.y - v.y) * s, p.z + (-u.z - v.z) * s, c, tw);
                vertex(b, p.x + (-u.x + v.x) * s, p.y + (-u.y + v.y) * s, p.z + (-u.z + v.z) * s, c, tw);
            }
            MeshData mesh = b.build();
            if (mesh == null) return;
            try (mesh) {
                if (starBuf != null) starBuf.close();
                starBuf = RenderSystem.getDevice().createBuffer(() -> "vantage stars", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                starIndices = mesh.drawState().indexCount();
            }
        }
    }

    /** A band of light along a tilted great circle, patchy along its length, soft across. Built once. */
    private static void buildMilkyWay() {
        if (milkyBuf != null) return;
        int along = 180, across = 12;
        float half = (float) Math.toRadians(17);
        Random r = new Random(77L);
        float[] patch = new float[along + 1];
        for (int i = 0; i <= along; i++) patch[i] = 0.55f + 0.45f * r.nextFloat();
        for (int k = 0; k < 3; k++) for (int i = 1; i < along; i++) patch[i] = (patch[i - 1] + patch[i] + patch[i + 1]) / 3f; // smooth
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(along * across * 4 * 16 + 64)) {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < along; i++) {
                float l0 = (float) (i * Math.PI * 2 / along), l1 = (float) ((i + 1) * Math.PI * 2 / along);
                for (int j = 0; j < across; j++) {
                    float w0 = -half + half * 2 * j / across, w1 = -half + half * 2 * (j + 1) / across;
                    float a0 = (float) Math.exp(-Math.pow(w0 / half * 1.8, 2)) * patch[i], a1 = (float) Math.exp(-Math.pow(w1 / half * 1.8, 2)) * patch[i + 1];
                    int c = 0xFFDCE6FF;
                    vertex(b, (float) (Math.cos(l0) * Math.cos(w0)) * R, (float) Math.sin(w0) * R, (float) (Math.sin(l0) * Math.cos(w0)) * R, c, a0);
                    vertex(b, (float) (Math.cos(l1) * Math.cos(w0)) * R, (float) Math.sin(w0) * R, (float) (Math.sin(l1) * Math.cos(w0)) * R, c, a0);
                    vertex(b, (float) (Math.cos(l1) * Math.cos(w1)) * R, (float) Math.sin(w1) * R, (float) (Math.sin(l1) * Math.cos(w1)) * R, c, a1);
                    vertex(b, (float) (Math.cos(l0) * Math.cos(w1)) * R, (float) Math.sin(w1) * R, (float) (Math.sin(l0) * Math.cos(w1)) * R, c, a1);
                }
            }
            MeshData mesh = b.build();
            if (mesh == null) return;
            try (mesh) {
                milkyBuf = RenderSystem.getDevice().createBuffer(() -> "vantage milky way", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                milkyIndices = mesh.drawState().indexCount();
            }
        }
    }

    // ---------------------------------------------------------------- everything after the sun and moon

    /** Stars, milky way, halos, aurora and meteors, drawn once vanilla has placed the sun and moon. */
    public static void extras(Atmosphere m, SkyRenderState s, PoseStack pose) {
        long t0 = System.nanoTime();
        try {
            pipelines();
            float n = night(s), rain = s.rainBrightness;
            int[] pal = m.palette();
            float t = seconds();
            // the star frame: vanilla's rotation, so our stars wheel with the vanilla moon
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(-90f));
            pose.pushPose();
            pose.mulPose(Axis.XP.rotation(s.starAngle));
            if (m.stars.on() && n > 0.01f) {
                buildStars();
                float a = Math.min(1f, n * 0.75f * m.starBrightness.f()) * rain;
                draw(add, starBuf, starIndices, view(pose), 1f, 1f, 1f, a);
            }
            if (m.milkyWay.on() && n > 0.01f) {
                buildMilkyWay();
                pose.pushPose();
                pose.mulPose(Axis.ZP.rotationDegrees(62f));
                float a = n * rain * 0.22f;
                float gr = ARGB.red(pal[3]) / 255f, gg = ARGB.green(pal[3]) / 255f, gb = ARGB.blue(pal[3]) / 255f;
                draw(add, milkyBuf, milkyIndices, view(pose), lerp(0.35f, 1f, gr), lerp(0.35f, 1f, gg), lerp(0.35f, 1f, gb), a);
                pose.popPose();
            }
            pose.popPose();
            if (m.halos.on()) {
                // the sun sits at (0, 100, 0) after the X rotation by its angle, the moon likewise
                pose.pushPose();
                pose.mulPose(Axis.XP.rotation(s.sunAngle));
                halo(pose, 70f, 1f, 0.86f, 0.62f, 0.30f * (1f - n) * rain);
                pose.popPose();
                pose.pushPose();
                pose.mulPose(Axis.XP.rotation(s.moonAngle));
                halo(pose, 42f, 0.75f, 0.82f, 1f, 0.22f * n * rain);
                pose.popPose();
            }
            pose.popPose();
            if (!m.aurora.is("Off")) aurora(m, n, rain, t, pal[3]);
            if (m.meteors.on()) meteor(n, rain, t);
            Perf.add("Atmosphere extras", System.nanoTime() - t0);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** A fan in the plane y = 100 round the celestial body: bright centre, clear edge. */
    private static void halo(PoseStack pose, float radius, float r, float g, float b, float a) {
        if (a <= 0.003f) return;
        int seg = 24;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(seg * 4 * 16 + 64)) {
            BufferBuilder bb = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int c = ARGB.colorFromFloat(1f, r, g, b);
            float inner = radius * 0.22f;
            for (int i = 0; i < seg; i++) {
                float a0 = (float) (i * Math.PI * 2 / seg), a1 = (float) ((i + 1) * Math.PI * 2 / seg);
                vertex(bb, (float) Math.cos(a0) * inner, 100f, (float) Math.sin(a0) * inner, c, 1f);
                vertex(bb, (float) Math.cos(a1) * inner, 100f, (float) Math.sin(a1) * inner, c, 1f);
                vertex(bb, (float) Math.cos(a1) * radius, 100f, (float) Math.sin(a1) * radius, c, 0f);
                vertex(bb, (float) Math.cos(a0) * radius, 100f, (float) Math.sin(a0) * radius, c, 0f);
            }
            drawOnce(add, bb, view(pose), 1f, 1f, 1f, a);
        }
    }

    /** The curtain: 64 columns along an arc to the north, 6 rows up, waving slowly, bright at the foot and fading up. */
    private static void aurora(Atmosphere m, float n, float rain, float t, int colour) {
        long now = System.currentTimeMillis();
        float dt = auroraTick == 0 ? 0 : Math.min(0.1f, (now - auroraTick) / 1000f);
        auroraTick = now;
        boolean cold = true;
        if (m.aurora.is("Cold biomes")) {
            var mc = Minecraft.getInstance();
            cold = mc.level != null && mc.player != null && mc.level.getBiome(mc.player.blockPosition()).value().getBaseTemperature() < 0.3f;
        }
        float target = n > 0.3f && cold ? 1f : 0f;
        auroraLevel += Math.signum(target - auroraLevel) * 0.5f * dt;
        auroraLevel = Math.max(0f, Math.min(1f, auroraLevel));
        if (auroraLevel <= 0.01f) return;
        int cols = 64, rows = 6;
        float speed = m.auroraSpeed.f();
        float cr = ARGB.red(colour) / 255f, cg = ARGB.green(colour) / 255f, cb = ARGB.blue(colour) / 255f;
        int foot = ARGB.colorFromFloat(1f, cr, cg, cb), head = ARGB.colorFromFloat(1f, lerp(0.6f, cr, 0.55f), lerp(0.6f, cg, 0.2f), lerp(0.6f, cb, 0.9f));
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(cols * rows * 4 * 16 + 64)) {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            float[] az = new float[cols + 1], el = new float[cols + 1];
            for (int i = 0; i <= cols; i++) {
                float f = i / (float) cols;
                az[i] = (float) Math.toRadians(-70 + 140 * f);
                // the foot of the curtain wanders in height and the whole thing ripples
                el[i] = (float) Math.toRadians(28 + 6 * Math.sin(f * 7.1f + t * 0.35f * speed) + 3 * Math.sin(f * 17f - t * 0.6f * speed));
            }
            for (int i = 0; i < cols; i++) {
                float wave = 0.55f + 0.45f * (float) Math.sin(i * 0.31f + t * 0.9f * speed) * (float) Math.cos(i * 0.11f - t * 0.4f * speed);
                for (int j = 0; j < rows; j++) {
                    float h0 = j / (float) rows, h1 = (j + 1) / (float) rows;
                    float a0 = (1f - h0) * (1f - h0) * wave, a1 = (1f - h1) * (1f - h1) * wave;
                    int c0 = mix(foot, head, h0), c1 = mix(foot, head, h1);
                    float rise = (float) Math.toRadians(26);
                    vertex(b, north(az[i], el[i] + rise * h0, 0), north(az[i], el[i] + rise * h0, 1), north(az[i], el[i] + rise * h0, 2), c0, a0);
                    vertex(b, north(az[i + 1], el[i + 1] + rise * h0, 0), north(az[i + 1], el[i + 1] + rise * h0, 1), north(az[i + 1], el[i + 1] + rise * h0, 2), c0, a0);
                    vertex(b, north(az[i + 1], el[i + 1] + rise * h1, 0), north(az[i + 1], el[i + 1] + rise * h1, 1), north(az[i + 1], el[i + 1] + rise * h1, 2), c1, a1);
                    vertex(b, north(az[i], el[i] + rise * h1, 0), north(az[i], el[i] + rise * h1, 1), north(az[i], el[i] + rise * h1, 2), c1, a1);
                }
            }
            float a = auroraLevel * n * rain * 0.5f * m.auroraBrightness.f();
            drawOnce(add, b, view(), 1f, 1f, 1f, Math.min(1f, a));
        }
    }

    /** A point on the sky sphere: azimuth 0 is north (-Z), elevation up from the horizon. */
    private static float north(float az, float el, int axis) {
        float c = (float) Math.cos(el) * R;
        return switch (axis) { case 0 -> (float) Math.sin(az) * c; case 1 -> (float) Math.sin(el) * R; default -> -(float) Math.cos(az) * c; };
    }

    /** Every 6 to 24 s at night a streak crosses part of the sky for 0.7 to 1.2 s, ten segments fading behind the head. */
    private static void meteor(float n, float rain, float t) {
        if (n < 0.4f) { nextMeteor = -1; return; }
        if (nextMeteor < 0) { nextMeteor = t + 6 + RNG.nextDouble() * 18; meteorStart = -1; }
        if (meteorStart < 0 && t >= nextMeteor) {
            meteorStart = t;
            meteorLength = 0.7 + RNG.nextDouble() * 0.5;
            float az = (float) (RNG.nextDouble() * Math.PI * 2), el = (float) Math.toRadians(30 + RNG.nextDouble() * 40);
            meteorFrom.set(north(az, el, 0), north(az, el, 1), north(az, el, 2));
            // heading: mostly sideways, a little down
            Vector3f up = new Vector3f(0, 1, 0), side = new Vector3f(meteorFrom).cross(up).normalize();
            meteorDir.set(side).mul(RNG.nextBoolean() ? 1 : -1).add(0, -0.45f, 0).normalize().mul(22f);
        }
        if (meteorStart < 0) return;
        float f = (float) ((t - meteorStart) / meteorLength);
        if (f >= 1f) { meteorStart = -1; nextMeteor = t + 6 + RNG.nextDouble() * 18; return; }
        float fade = f < 0.15f ? f / 0.15f : f > 0.6f ? (1f - f) / 0.4f : 1f;
        int segs = 10;
        Vector3f head = new Vector3f(meteorFrom).add(new Vector3f(meteorDir).mul(f)), p0 = new Vector3f(), p1 = new Vector3f();
        Vector3f wdir = new Vector3f(head).cross(meteorDir).normalize().mul(0.35f);
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(segs * 4 * 16 + 64)) {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int c = 0xFFFFF4E0;
            for (int i = 0; i < segs; i++) {
                float s0 = i / (float) segs, s1 = (i + 1) / (float) segs;
                p0.set(head).sub(new Vector3f(meteorDir).mul(0.28f * (1f - s0)));
                p1.set(head).sub(new Vector3f(meteorDir).mul(0.28f * (1f - s1)));
                p0.normalize(R); p1.normalize(R);
                float a0 = s0 * s0, a1 = s1 * s1;
                vertex(b, p0.x + wdir.x * (1f - s0), p0.y + wdir.y * (1f - s0), p0.z + wdir.z * (1f - s0), c, a0);
                vertex(b, p1.x + wdir.x * (1f - s1), p1.y + wdir.y * (1f - s1), p1.z + wdir.z * (1f - s1), c, a1);
                vertex(b, p1.x - wdir.x * (1f - s1), p1.y - wdir.y * (1f - s1), p1.z - wdir.z * (1f - s1), c, a1);
                vertex(b, p0.x - wdir.x * (1f - s0), p0.y - wdir.y * (1f - s0), p0.z - wdir.z * (1f - s0), c, a0);
            }
            drawOnce(add, b, view(), 1f, 1f, 1f, fade * n * rain);
        }
    }

    /** True once a draw threw this session: the hooks then leave vanilla alone. */
    public static boolean failed() { return broken; }

    /**
     * The fog hook: remembers vanilla's colour for the dome's horizon, then, when the sky is ours, pulls the fog toward the dome's
     * horizon colour, strongly at night and faintly by day; in the Nether a little more saturated and brighter when asked.
     */
    public static Vector4f fog(Vector4f c, net.minecraft.client.Camera camera, net.minecraft.client.multiplayer.ClientLevel level) {
        Atmosphere m = dev.vantage.core.Modules.get(Atmosphere.class);
        if (m == null || !m.on() || broken) return c;
        try {
            if (level.dimensionType().hasCeiling()) {
                if (!m.netherColours.on()) return c;
                float[] hsv = java.awt.Color.RGBtoHSB(Math.round(c.x * 255), Math.round(c.y * 255), Math.round(c.z * 255), null);
                int rgb = java.awt.Color.HSBtoRGB(hsv[0], Math.min(1f, hsv[1] * 1.45f), Math.min(1f, hsv[2] * 1.12f));
                return c.set((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, c.w);
            }
            if (level.dimensionType().skybox() != net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD) return c;
            vanillaFog.set(c);
            if (!m.fog.on() || camera.getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE) return c;
            // how dark it is, from the client sky's star brightness: the same measure the dome uses
            float n = Math.min(1f, Math.max(0f, camera.attributeProbe().getValue(net.minecraft.world.attribute.EnvironmentAttributes.STAR_BRIGHTNESS, 0f) * 2f));
            // With the dome on, the fog IS the dome's horizon colour: anything fading into fog (far terrain, chunks fading in)
            // then melts into the sky behind it. A partial pull left a darker band, and freshly loaded chunks stood out as
            // solid blocks against the brighter dome. Without the dome, vanilla's sky disc is behind the terrain: pull gently.
            float k = m.gradient.on() && Atmosphere.frames > 0 ? 1f : Math.max(n * 0.55f, m.dayTint.f() * 0.3f);
            return c.set(lerp(k, c.x, horizonNow.x), lerp(k, c.y, horizonNow.y), lerp(k, c.z, horizonNow.z), c.w);
        } catch (Throwable t) {
            fail(t);
            return c;
        }
    }

    private static void fail(Throwable t) {
        Atmosphere.failures++;
        broken = true;
        free();
        Vantage.LOG.warn("Atmosphere sky failed, vanilla sky is back", t);
    }
}
