package dev.vantage.module.render;

import dev.vantage.core.Category;
import dev.vantage.core.Keys;
import dev.vantage.core.Module;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.CameraType;
import org.lwjgl.glfw.GLFW;

/**
 * Look around your player without turning it. While Free Look is active the mouse only orbits the camera (third person, back view);
 * the player's yaw and pitch, what is sent to the server and the direction the movement keys push all stay as they were. Purely
 * visual: no packet is added, changed or held back.
 *
 * The module's own switch means "ready" (like Zoom it starts on, with a key); the key makes it active: held in Hold mode, flipped in
 * Toggle mode. Hooks: {@code EntityTurnMixin} sends the mouse to {@link #turn}, {@code CameraMixin} places the camera from
 * {@link #yaw}, {@link #pitch} and {@link #distance}. It cannot be active while Freecam is on: an active look ends (the module's own switch is untouched) and the key works again once Freecam is off.
 */
public class FreeLook extends Module {
    public final ModeSetting mode = add(new ModeSetting("Mode", "Hold: active while the key is held. Toggle: the key switches it on and off", "Hold", "Hold", "Toggle"));
    public final BoolSetting seeThrough = add(new BoolSetting("See through blocks", "The camera is not pushed in by walls and does not stop at them: it sits where you point it, inside the wall if need be. Off: the vanilla third person camera is pulled in", false));
    public final NumberSetting distance = add(new NumberSetting("Distance", "How far behind you the camera sits, in blocks (vanilla is 4)", 4, 1, 12, 0.5));
    public final BoolSetting returnView = add(new BoolSetting("Return", "When it ends the camera snaps back to where you look. Off: your player turns to where the camera was looking", true));

    private static FreeLook instance;
    private boolean active, latched, culled;
    private float yaw, pitch;
    private CameraType before;

    public FreeLook() {
        super("Free Look", "Look around your player without turning it", Category.RENDER);
        beta();
        bind = GLFW.GLFW_KEY_LEFT_ALT;
        instance = this;
    }

    @Override
    public boolean holdKey() {
        return true;
    }

    /** The module while it is really looking around (also checks Freecam, in case a config load left both on), else null. */
    public static FreeLook active() {
        FreeLook f = instance;
        return f != null && f.active && f.on() && f.mc.player != null && Freecam.active() == null ? f : null;
    }

    @Override
    public void onBindPress() {
        if (mode.is("Toggle") && Freecam.active() == null) latched = !latched;
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        boolean want;
        if (Freecam.active() != null) { stop(false); return; } // not while Freecam is on; the module stays on, the key works again afterwards
        if (mc.player.isDeadOrDying()) want = false;
        else if (mode.is("Toggle")) want = latched;
        else want = bind != -1 && mc.screen == null && Keys.down(mc, bind);
        if (want && !active) start();
        else if (!want && active) stop(true);
        if (!active) return;
        if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        // the sections the camera sits in or behind would be culled by cave culling, so a wall would still hide what is behind it
        if (seeThrough.on()) { mc.smartCull = false; culled = true; }
        else restoreCull();
    }

    private void start() {
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        before = mc.options.getCameraType();
        active = true;
    }

    /** Ends the look. {@code apply}: only for a natural end, so Return off may turn the player; a reset (leave, panic, off) never does. */
    private void stop(boolean apply) {
        if (!active) { latched = false; return; }
        active = false;
        latched = false;
        if (apply && !returnView.on() && mc.player != null) {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.yRotO = yaw;
            mc.player.xRotO = pitch;
        }
        if (before != null && mc.options != null) mc.options.setCameraType(before);
        before = null;
        restoreCull();
    }

    /** True while this module has switched cave culling off; Xray reads it so it never saves that as the "normal" value. */
    public static boolean holdingCull() {
        return instance != null && instance.culled;
    }

    private void restoreCull() {
        if (!culled) return;
        culled = false;
        if (!Xray.active()) mc.smartCull = true;
    }

    @Override
    protected void onDisable() {
        stop(false);
    }

    @Override
    public void onJoinWorld() {
        stop(false);
    }

    @Override
    public void onLeaveWorld() {
        stop(false);
    }

    @Override
    public void onDimensionChange() {
        stop(false);
    }

    /** Mouse look while active turns the camera only. */
    public void turn(double dx, double dy) {
        yaw = FreeLookMath.turnYaw(yaw, dx);
        pitch = FreeLookMath.turnPitch(pitch, dy);
    }

    /** Test rig: no mouse there, so the camera angles get put in place. */
    public void place(float yaw, float pitch) {
        this.yaw = FreeLookMath.wrapDegrees(yaw);
        this.pitch = FreeLookMath.clampPitch(pitch);
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public double distance() { return distance.get(); }
    public boolean seeThrough() { return seeThrough.on(); }
    public boolean isLatched() { return latched; }
}
