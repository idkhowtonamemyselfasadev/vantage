package dev.vantage.module.render;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Detaches the camera and flies it around with the movement keys while your player stands still.
 * Purely visual: the player doesn't move, turn, click, drop or swap items, so nothing changes for the server.
 */
public class Freecam extends Module {
    public final NumberSetting speed = add(new NumberSetting("Speed", "Blocks per second, or scroll while flying", 12, 1, 200, 1));
    public final BoolSetting hideHand = add(new BoolSetting("Hide hand", "Don't draw your held item", true));
    public final BoolSetting seeUnderground = add(new BoolSetting("See underground", "No cave culling while flying, so caves and rooms under the ground draw when you fly into stone", true));
    public final BoolSetting caveView = add(new BoolSetting("Draw everything", "Draw terrain from both sides, so inside the ground you see caves and the surface from below instead of sky", true));
    public final BoolSetting baseView = add(new BoolSetting("See-through view", "Underground, hide natural terrain so only player-placed blocks and structures are left", false));
    public final BoolSetting bright = add(new BoolSetting("Fullbright", "Light everything up while flying", true));
    public final BoolSetting autoOff = add(new BoolSetting("Off on damage", "Turn off when you get hurt", true));

    private static Freecam instance;
    private double x, y, z;
    private float yaw, pitch;
    private long lastNanos;
    private float lastHealth;
    private int lastHurtTime;

    public Freecam() {
        super("Freecam", "Fly the camera around while you stand still", Category.RENDER);
        bind = GLFW.GLFW_KEY_F4;
        instance = this;
    }

    public static Freecam active() {
        return instance != null && instance.on() && instance.mc.player != null ? instance : null;
    }

    @Override
    protected void onEnable() {
        if (mc.player == null) { setEnabled(false); return; }
        Vec3 p = mc.gameRenderer.getMainCamera().position();
        x = p.x; y = p.y; z = p.z;
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        lastNanos = System.nanoTime();
        lastHealth = mc.player.getHealth();
        lastHurtTime = mc.player.hurtTime;
        // a block being mined when freecam starts would otherwise stay half broken on the server
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        if (seeUnderground.on()) mc.smartCull = false;
        flipTicks = 0;
        double top = surface();
        underground = !Double.isNaN(top) && y < top - 4;
    }

    /** Ticks the camera must stay past the line before anything switches: each switch rebuilds every chunk. */
    private static final int FLIP_TICKS = 20;
    private boolean underground;
    private int flipTicks;

    /**
     * Ground height the camera counts as "the surface": the higher of the average around it (so one cliff or tree doesn't
     * count) and the actual height straight above it (so a camera inside a cliff, a wall or a hill flank, whose surroundings
     * are flat ground, still counts as inside the ground). NaN if nothing is loaded there.
     */
    private double surface() {
        // the Nether's height map stops at the bedrock roof, so "below the surface" would be the whole dimension
        if (mc.level.dimensionType().hasCeiling()) return Double.NaN;
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z), n = 0;
        double sum = 0;
        for (int dx = -16; dx <= 16; dx += 8) for (int dz = -16; dz <= 16; dz += 8) {
            int px = cx + dx, pz = cz + dz;
            if (!mc.level.hasChunk(px >> 4, pz >> 4)) continue;
            sum += mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, px, pz);
            n++;
        }
        if (n == 0) return Double.NaN;
        double avg = sum / n;
        if (!mc.level.hasChunk(cx >> 4, cz >> 4)) return avg;
        return Math.max(avg, mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, cx, cz));
    }

    private boolean belowSurface(double top) {
        return underground ? y <= top + 1 : y < top - 4;
    }

    private void updateBaseView() {
        double top = surface();
        if (mc.level.dimensionType().hasCeiling()) underground = false;
        else if (!Double.isNaN(top)) {
            boolean want = belowSurface(top);
            flipTicks = want != underground ? flipTicks + 1 : 0;
            if (flipTicks >= FLIP_TICKS) { underground = want; flipTicks = 0; }
        }
        Xray.setOverride(underground && baseView.on());
    }

    /** Voxy's far terrain has giant solid blocks that swallow a camera underground, so it goes while down there. */
    public static boolean hideLod() {
        Freecam f = active();
        return f != null && f.underground;
    }

    @Override
    protected void onDisable() {
        underground = false;
        flipTicks = 0;
        Xray.setOverride(false);
        // give cave culling back unless xray still needs it off
        if (!Xray.active()) mc.smartCull = true;
    }

    /** Read by the render-state mixins every time a terrain pipeline is set up. */
    public static boolean caveView() {
        Freecam f = active();
        return f != null && f.caveView.on();
    }

    /** Fullbright reads this so caves are lit while flying through them. */
    public static boolean bright() {
        Freecam f = active();
        return f != null && f.bright.on();
    }

    @Override
    public void onJoinWorld() {
        switchOffForNewWorld();
    }

    @Override
    public void onLeaveWorld() {
        switchOffForNewWorld();
    }

    @Override
    public void onDimensionChange() {
        switchOffForNewWorld();
    }

    private void switchOffForNewWorld() {
        if (on()) setEnabled(false);
    }

    @Override
    public void onTick() {
        if (mc.player.isDeadOrDying()) { setEnabled(false); return; }
        // hurtTime too: a hit that only eats absorption hearts leaves health unchanged
        if (autoOff.on() && (mc.player.getHealth() < lastHealth || mc.player.hurtTime > lastHurtTime)) { setEnabled(false); return; }
        lastHealth = mc.player.getHealth();
        lastHurtTime = mc.player.hurtTime;
        updateBaseView();
        mc.smartCull = !seeUnderground.on() && !Xray.active();
    }

    /** Wheel up is faster. Steps grow with the speed, but always move at least one so low speeds don't get stuck. */
    public void scroll(double wheel) {
        double now = speed.get();
        speed.set(wheel > 0 ? Math.max(now + 1, now * 1.25) : Math.min(now - 1, now / 1.25));
        mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("Freecam speed " + speed.text()), false);
    }

    /** Mouse look while freecam is on turns the camera instead of the player. */
    public void turn(double dx, double dy) {
        yaw += (float) dx * 0.15f;
        pitch = Mth.clamp(pitch + (float) dy * 0.15f, -90f, 90f);
    }

    /** Called every frame from the camera mixin; moves with real frame time so it's smooth at any FPS. */
    public void frame() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (mc.screen != null) return;
        var o = mc.options;
        double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double s = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        double v = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);
        if (f == 0 && s == 0 && v == 0) return;
        double sp = speed.get() * (o.keySprint.isDown() ? 3 : 1) * dt;
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r), cos = Math.cos(r);
        // MC: yaw 0 faces +z; forward = (-sin, cos), left = (cos, sin)
        double mx = -sin * f + cos * s, mz = cos * f + sin * s;
        double len = Math.sqrt(mx * mx + mz * mz);
        if (len > 0) { mx /= len; mz /= len; }
        x += mx * sp;
        z += mz * sp;
        y += v * sp;
    }

    /** Test rig: no mouse or keys there, so the camera gets put in place. */
    public void place(double x, double y, double z, float yaw, float pitch) {
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    static Freecam get() {
        return Modules.get(Freecam.class);
    }
}
