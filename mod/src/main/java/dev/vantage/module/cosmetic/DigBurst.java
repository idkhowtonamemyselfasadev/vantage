package dev.vantage.module.cosmetic;

import java.util.ArrayDeque;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Theme;
import dev.vantage.core.util.Colors;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * When a block you mine breaks: block shards that really fly out of the face you hit, glowing spark streaks, a ring on that face and a few coloured dust specks.
 * Plain geometry through R3 plus vanilla particles on your own client; nothing is sent and nothing is added to the world.
 */
public class DigBurst extends Module {
    public final ModeSetting colour = add(Cos.mode());
    public final ColorSetting manual = add(Cos.manual()).visibleIf(() -> colour.is("Manual"));
    public final NumberSetting amount = add(new NumberSetting("Amount", "Extra block shards per block", 10, 0, 30, 1).legacy(14));
    public final NumberSetting power = add(new NumberSetting("Power", "How fast the shards and sparks fly out (effect stops at 1.5)", 1, 0.3, 2.5, 0.1));
    public final BoolSetting sparkle = add(new BoolSetting("Ore sparkle", "Ores throw more sparks in their own colour", true));

    // rig counters
    public static int bursts, spawned, sparkles, shardsMade, ringsMade, failures;
    /** Rig: what the last frame drew. */
    public static int sparksDrawn, ringsDrawn;
    /** Rig: fastest shard of the last burst, blocks per tick (0 when it threw none). */
    public static double maxShardSpeed;
    /** Rig: the last rate scale times 100. */
    public static int rateScaleX100 = 100;
    /** Rig: when not 0, this is the clock in milliseconds, so the age of the effect in a picture is exact. */
    public static long testClock;

    private static final int MAX_SPARKS = 64;
    private static final int MAX_RINGS = 4;
    private static final int RATE_MEMORY = 8;
    private static final long RATE_WINDOW_MS = 1000;
    private static final double FULL_RATE = 2;          // breaks per second with full strength
    private static final double MIN_SCALE = 0.25;       // weakest scale while strip mining
    private static final double RING_MAX_RATE = 3;      // no ring above this many breaks per second
    private static final double MAX_POWER = 1.5;        // Power never speeds things up beyond this
    private static final double SHARD_SPEED_MIN = 0.07; // blocks per tick, outward
    private static final double SHARD_SPEED_MAX = 0.14;
    private static final double SHARD_SIDE = 0.025;     // blocks per tick, sideways at most
    private static final double SHARD_TRAVEL_TICKS = 15; // distance a shard covers is about speed * this (friction 0.98)
    private static final int SHARD_LIFE_MIN = 12, SHARD_LIFE_MAX = 18; // ticks
    private static final double DUST_SPEED_MIN = 0.03, DUST_SPEED_MAX = 0.06; // blocks per tick
    private static final double DUST_TRAVEL_TICKS = 20;  // friction 0.96
    private static final int BASE_SPARKS = 14;          // streaks per break (ore: 1.5 times)
    private static final int BASE_DUST = 6;             // coloured dust, ore
    private static final int BASE_DUST_PLAIN = 5;       // coloured dust, any other block
    private static final double SPARK_SPEED_MIN = 2.0, SPARK_SPEED_MAX = 3.5; // blocks per second
    private static final int SPARK_LIFE_MIN = 380, SPARK_LIFE_MAX = 560;      // milliseconds
    private static final double SPARK_GRAVITY = 6.0;    // blocks per second squared
    private static final double SPARK_TAIL_S = 0.09;    // streak length in seconds of flight
    private static final int RING_LIFE = 580;           // milliseconds
    private static final double RING_FROM = 0.5, RING_TO = 0.8; // half side in blocks: starts at the hole edge
    private static final double RING_BAND = 0.12;       // blocks
    private static final float SPARK_HOLD = 0.3f;  // share of the life a spark keeps full strength
    private static final float RING_HOLD = 0.35f;   // same for the ring
    private static final float SPARK_GLOW_W = 5.5f, SPARK_CORE_W = 2.5f, RING_LINE_W = 5.5f; // pixels
    private static final float SPARK_GLOW_A = 0.6f, RING_BAND_A = 0.5f;
    private static final float CORE_WHITE = 0.35f;  // how far the bright core is mixed toward white
    private static final double RING_FADE_DIST = 1.6;   // blocks from the eye: closer than this the ring gets fainter
    private static final double SPARK_MAX_TRAVEL = 1.5; // blocks a spark may fly, whatever Power and Calm say
    private static final double CAMERA_GAP = 0.9;       // blocks: nothing flies closer to the eye than this
    private static final double CALM_SLOWER = 1.3;      // Calm: effects last this much longer

    private record Spark(double x, double y, double z, float vx, float vy, float vz, long t0, int life, int col) {}
    private record Ring(double x, double y, double z, int nx, int ny, int nz, long t0, int life, int col, float fade) {}

    private final ArrayDeque<Spark> sparks = new ArrayDeque<>();
    private final ArrayDeque<Ring> rings = new ArrayDeque<>();
    private final long[] recent = new long[RATE_MEMORY];
    private int recentHead;
    private BlockPos pendingPos;
    private BlockState pendingState;
    private Direction pendingFace;

    public DigBurst() {
        super("Dig Burst", "Shards, sparks, dust and a ring fly out of the block face you break, ores in their own colour", Category.COSMETIC);
        beta();
    }

    public static DigBurst active() {
        DigBurst d = Modules.get(DigBurst.class);
        return d != null && d.on() ? d : null;
    }

    private static long now() {
        return testClock != 0 ? testClock : System.currentTimeMillis();
    }

    @Override
    protected void onDisable() { clear(); }

    @Override
    public void onLeaveWorld() {
        clear();
    }

    @Override
    public void onDimensionChange() {
        clear();
    }

    private void clear() {
        sparks.clear();
        rings.clear();
        pendingPos = null;
        pendingState = null;
        pendingFace = null;
        java.util.Arrays.fill(recent, 0L);
        sparksDrawn = ringsDrawn = 0;
    }

    /** Rig: forget the break rate and any live effect, so one test does not slow the next. */
    public void resetForTest() { clear(); }

    /** Rig: live spark streaks and rings. */
    public int liveSparks() { return sparks.size(); }
    public int liveRings() { return rings.size(); }

    /** The block you are about to break, read just before it goes (MultiPlayerGameMode.destroyBlock, head). */
    public void begin(BlockPos pos) {
        pendingPos = null;
        pendingState = null;
        pendingFace = null;
        try {
            if (mc.level == null || mc.player == null) return;
            BlockState st = mc.level.getBlockState(pos);
            if (st.isAir() || st.getDestroySpeed(mc.level, pos) < 0) return;
            pendingPos = pos.immutable();
            pendingState = st;
            if (mc.hitResult instanceof BlockHitResult h && h.getBlockPos().equals(pos)) pendingFace = h.getDirection();
        } catch (Throwable t) {
            failures++;
            pendingPos = null;
            pendingState = null;
            pendingFace = null;
        }
    }

    /** destroyBlock came back: the burst only fires when the block really broke. */
    public void end(boolean broke) {
        BlockPos p = pendingPos;
        BlockState s = pendingState;
        Direction f = pendingFace;
        pendingPos = null;
        pendingState = null;
        pendingFace = null;
        try {
            if (broke && p != null) broke(p, s, f);
        } catch (Throwable t) {
            failures++;
        }
    }

    /** The burst, out of the face nearest to you; public so the rig can fire one. */
    public void broke(BlockPos pos, BlockState st) {
        broke(pos, st, null);
    }

    /** The burst out of the given face (null: the face nearest to you). */
    public void broke(BlockPos pos, BlockState st, Direction face) {
        try {
            burst(pos, st, face);
        } catch (Throwable t) {
            failures++;
        }
    }

    private void burst(BlockPos pos, BlockState st, Direction face) {
        if (mc.level == null || mc.player == null || st == null) return;
        long now = now();
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        Vec3 eye = mc.player.getEyePosition();
        if (face == null) face = Direction.getApproximateNearest(eye.x - cx, eye.y - cy, eye.z - cz);
        int nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();
        int[] ax = axes(nx, ny, nz); // two axes along the face
        int ux = ax[0], uy = ax[1], uz = ax[2], vx = ax[3], vy = ax[4], vz = ax[5];
        double fx = cx + nx * 0.5, fy = cy + ny * 0.5, fz = cz + nz * 0.5; // centre of the hit face
        double dist = Math.sqrt((eye.x - fx) * (eye.x - fx) + (eye.y - fy) * (eye.y - fy) + (eye.z - fz) * (eye.z - fz));
        double reach = Math.max(0, dist - CAMERA_GAP);
        var rnd = mc.level.random;
        double pw = Math.min(power.get(), MAX_POWER);

        // how fast are you breaking: full strength up to 2 per second, less when strip mining
        recent[recentHead] = now;
        recentHead = (recentHead + 1) % RATE_MEMORY;
        int rate = 0;
        for (long t : recent) if (now - t >= 0 && now - t <= RATE_WINDOW_MS) rate++;
        double k = rate <= FULL_RATE ? 1.0 : Math.max(MIN_SCALE, FULL_RATE / rate);
        rateScaleX100 = (int) Math.round(k * 100);

        int ore = sparkle.on() ? oreColour(st) : 0;
        int col = (ore != 0 ? ore : Cos.pick(colour, manual)) & 0xFFFFFF;

        // block shards: vanilla particles, given their real speed
        int n = amount.i() > 0 ? Math.max(1, (int) Math.round(amount.i() * k)) : 0;
        if (n > 0) n = Cos.calmCount(n);
        ParticleStatus ps = mc.options.particles().get();
        if (ps == ParticleStatus.MINIMAL) n = 0;
        else if (ps == ParticleStatus.DECREASED) n /= 2;
        maxShardSpeed = 0;
        throwParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), n, fx, fy, fz, nx, ny, nz, ux, uy, uz, vx, vy, vz,
                SHARD_SPEED_MIN * pw, SHARD_SPEED_MAX * pw, SHARD_SIDE * pw, SHARD_LIFE_MIN, SHARD_LIFE_MAX, reach, SHARD_TRAVEL_TICKS);
        spawned += n;

        // coloured dust in the picked colour (ores: their own colour, a few more), a little slower
        if (ps != ParticleStatus.MINIMAL) {
            int d = Cos.calmCount(Math.max(1, (int) Math.round((ore != 0 ? BASE_DUST : BASE_DUST_PLAIN) * k)));
            var dust = new DustParticleOptions(col, Cos.calm() ? 0.8f : 1.0f);
            double saved = maxShardSpeed;
            throwParticles(dust, d, fx, fy, fz, nx, ny, nz, ux, uy, uz, vx, vy, vz,
                    DUST_SPEED_MIN * pw, DUST_SPEED_MAX * pw, SHARD_SIDE * 0.5 * pw, SHARD_LIFE_MIN, SHARD_LIFE_MAX + 4, reach, DUST_TRAVEL_TICKS);
            maxShardSpeed = saved;
        }

        // spark streaks, drawn by R3 on the face plane
        int m = Cos.calmCount(Math.max(1, (int) Math.round(BASE_SPARKS * k * (ore != 0 ? 1.5 : 1))));
        double calmLife = Cos.calm() ? CALM_SLOWER : 1;
        double sparkFit = fit(reach, SPARK_SPEED_MAX * pw * SPARK_LIFE_MAX * calmLife / 1000.0);
        for (int i = 0; i < m; i++) {
            double a = rnd.nextDouble() * 2 - 1, b = rnd.nextDouble() * 2 - 1;
            double dx = nx + ux * a * 0.7 + vx * b * 0.7, dy = ny + uy * a * 0.7 + vy * b * 0.7, dz = nz + uz * a * 0.7 + vz * b * 0.7;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double sa = (rnd.nextDouble() - 0.5) * 0.6, sb = (rnd.nextDouble() - 0.5) * 0.6;
            int life = (int) ((SPARK_LIFE_MIN + rnd.nextInt(SPARK_LIFE_MAX - SPARK_LIFE_MIN + 1)) * calmLife);
            double speed = (SPARK_SPEED_MIN + rnd.nextDouble() * (SPARK_SPEED_MAX - SPARK_SPEED_MIN)) * pw * sparkFit;
            speed = Math.min(speed, SPARK_MAX_TRAVEL * 1000.0 / life); // travel = speed * life, hard cap
            double sp = speed / len;
            if (sparks.size() >= MAX_SPARKS) sparks.pollFirst();
            sparks.add(new Spark(fx + ux * sa + vx * sb, fy + uy * sa + vy * sb, fz + uz * sa + vz * sb,
                    (float) (dx * sp), (float) (dy * sp), (float) (dz * sp), now, life, col | 0xFF000000));
        }
        if (ore != 0) sparkles += m;

        // one ring on the face, only while you break slowly; fainter when your eye is very close
        if (rate <= RING_MAX_RATE) {
            float fade = (float) Math.max(0.3, Math.min(1, (dist - 0.6) / (RING_FADE_DIST - 0.6)));
            if (rings.size() >= MAX_RINGS) rings.pollFirst();
            rings.add(new Ring(fx + nx * 0.03, fy + ny * 0.03, fz + nz * 0.03, nx, ny, nz, now, (int) (RING_LIFE * calmLife), col | 0xFF000000, fade));
            ringsMade++;
        }
        bursts++;
    }

    /** Two unit axes lying in the face with this normal: {ux, uy, uz, vx, vy, vz}. */
    private static int[] axes(int nx, int ny, int nz) {
        if (nx != 0) return new int[] {0, 1, 0, 0, 0, 1};
        if (ny != 0) return new int[] {1, 0, 0, 0, 0, 1};
        return new int[] {1, 0, 0, 0, 1, 0};
    }

    /** Shrinks a speed so the flight stays shorter than the room between the face and your eye. 1 = no change. */
    private static double fit(double reach, double travel) {
        return travel <= 0 ? 1 : (reach <= 0 ? 0.1 : Math.max(0.1, Math.min(1, reach / travel)));
    }

    /** Creates n particles on the face and gives each its real speed (the engine normalises the speed it is given). */
    private void throwParticles(ParticleOptions opt, int n, double fx, double fy, double fz, int nx, int ny, int nz, int ux, int uy, int uz, int vx, int vy, int vz,
                                double vMin, double vMax, double side, int lifeMin, int lifeMax, double reach, double travelTicks) {
        var rnd = mc.level.random;
        double f = fit(reach, vMax * travelTicks);
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() - 0.5, b = rnd.nextDouble() - 0.5;
            double out = (vMin + rnd.nextDouble() * (vMax - vMin)) * f;
            double sa = (rnd.nextDouble() - 0.5) * 2 * side * f, sb = (rnd.nextDouble() - 0.5) * 2 * side * f;
            var p = mc.particleEngine.createParticle(opt,
                    fx - nx * 0.05 + ux * a * 0.8 + vx * b * 0.8, fy - ny * 0.05 + uy * a * 0.8 + vy * b * 0.8, fz - nz * 0.05 + uz * a * 0.8 + vz * b * 0.8, 0, 0, 0);
            if (p == null) continue;
            p.setParticleSpeed(nx * out + ux * sa + vx * sb, ny * out + uy * sa + vy * sb + 0.015, nz * out + uz * sa + vz * sb);
            p.setLifetime(lifeMin + rnd.nextInt(lifeMax - lifeMin + 1));
            shardsMade++;
            if (out > maxShardSpeed) maxShardSpeed = out;
        }
    }

    @Override
    public void onRender3D(R3 r) {
        if (sparks.isEmpty() && rings.isEmpty()) { sparksDrawn = ringsDrawn = 0; return; }
        long now = now();
        sparks.removeIf(s -> now < s.t0 || now - s.t0 >= s.life);
        rings.removeIf(g -> now < g.t0 || now - g.t0 >= g.life);
        float calmA = Cos.calmAlpha();
        int drawnS = 0, drawnR = 0;
        for (Spark s : sparks) {
            double t = (now - s.t0) / 1000.0, life = (double) (now - s.t0) / s.life;
            double tt = Math.max(0, t - SPARK_TAIL_S);
            float a = hold(life, SPARK_HOLD, 0.9) * calmA;
            double x1 = s.x + s.vx * tt, y1 = s.y + s.vy * tt - 0.5 * SPARK_GRAVITY * tt * tt, z1 = s.z + s.vz * tt;
            double x2 = s.x + s.vx * t, y2 = s.y + s.vy * t - 0.5 * SPARK_GRAVITY * t * t, z2 = s.z + s.vz * t;
            r.line(x1, y1, z1, x2, y2, z2, Colors.fade(s.col, a * SPARK_GLOW_A), SPARK_GLOW_W, false); // soft wide line in the colour
            r.line(x1, y1, z1, x2, y2, z2, Colors.fade(Colors.lighten(s.col, CORE_WHITE), a), SPARK_CORE_W, false); // thin bright core
            drawnS++;
        }
        for (Ring g : rings) {
            double t = (double) (now - g.t0) / g.life;
            float e = Cos.out((float) t);
            double h = RING_FROM + (RING_TO - RING_FROM) * e, hi = h - RING_BAND;
            float a = hold((float) t, RING_HOLD, 1.4) * calmA * g.fade;
            int line = Colors.fade(g.col, a), band = Colors.fade(g.col, a * RING_BAND_A);
            int[] ax = axes(g.nx, g.ny, g.nz);
            // corners of the outer and the inner square, in order around the face
            double[] cu = {-1, 1, 1, -1}, cv = {-1, -1, 1, 1};
            double[] o = new double[12], in = new double[12];
            for (int i = 0; i < 4; i++) {
                o[i * 3] = g.x + (ax[0] * cu[i] + ax[3] * cv[i]) * h;
                o[i * 3 + 1] = g.y + (ax[1] * cu[i] + ax[4] * cv[i]) * h;
                o[i * 3 + 2] = g.z + (ax[2] * cu[i] + ax[5] * cv[i]) * h;
                in[i * 3] = g.x + (ax[0] * cu[i] + ax[3] * cv[i]) * hi;
                in[i * 3 + 1] = g.y + (ax[1] * cu[i] + ax[4] * cv[i]) * hi;
                in[i * 3 + 2] = g.z + (ax[2] * cu[i] + ax[5] * cv[i]) * hi;
            }
            for (int i = 0; i < 4; i++) {
                int j = ((i + 1) & 3) * 3, q = i * 3;
                r.line(o[q], o[q + 1], o[q + 2], o[j], o[j + 1], o[j + 2], line, RING_LINE_W, false);
                r.quad(band, false, (float) o[q], (float) o[q + 1], (float) o[q + 2], (float) o[j], (float) o[j + 1], (float) o[j + 2],
                        (float) in[j], (float) in[j + 1], (float) in[j + 2], (float) in[q], (float) in[q + 1], (float) in[q + 2]);
            }
            drawnR++;
        }
        sparksDrawn = drawnS;
        ringsDrawn = drawnR;
    }

    /** 1 for the first part of the life, then fading to 0 at the end. */
    private static float hold(double life, float hold, double pow) {
        if (life <= hold) return 1f;
        return (float) Math.pow(Math.max(0, 1 - (life - hold) / (1 - hold)), pow);
    }

    /** The colour mixed toward white by f (0 to 1), alpha kept. */
    /** RGB of an ore's colour, 0 for a block that is no ore. */
    static int oreColour(BlockState st) {
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        boolean ore = id.endsWith("_ore") || id.equals("ancient_debris") || id.equals("budding_amethyst");
        if (!ore) return 0;
        if (id.contains("coal")) return 0x9A9AAA;
        if (id.contains("iron")) return 0xEBC9A0;
        if (id.contains("copper")) return 0xE8905C;
        if (id.contains("gold")) return 0xFFD84A;
        if (id.contains("redstone")) return 0xFF3535;
        if (id.contains("lapis")) return 0x4A78FF;
        if (id.contains("diamond")) return 0x55F0E4;
        if (id.contains("emerald")) return 0x3DE070;
        if (id.contains("quartz")) return 0xF4EFE6;
        if (id.contains("debris")) return 0xB07A60;
        if (id.contains("amethyst")) return 0xB885FF;
        return Theme.accent() & 0xFFFFFF;
    }
}
