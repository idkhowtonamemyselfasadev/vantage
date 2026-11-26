package dev.vantage.module.cosmetic;

import java.util.Random;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Vantage;
import dev.vantage.core.util.Dist;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A small vanilla mob keeps you company. It is NOT an entity of the world: a Mob object is built once (EntityType.create, never
 * added to the level, never ticked) and used only as a pose holder. Each game tick this module moves it by hand and each frame the
 * vanilla renderer is asked for its render state (see LevelRendererPetMixin), which is added to the frame's list. So the server never
 * hears of it, the crosshair and collisions cannot find it (they only look at the level's entities), it makes no sound (nothing
 * ticks it) and it is dropped on world change and on disable.
 *
 * Movement is meant to feel like a calm animal next to you: it matches your pace, never darts, stops inside a dead zone around you,
 * steps up and hops over blocks, and never walks through walls or off high drops. All speeds are blocks per game tick.
 */
public class Pet extends Module {
    public final ModeSetting type = add(new ModeSetting("Type", "Which little mob walks with you. Real Fox is a smooth furry fox with its own animation, Spirit Fox is the blocky fox on fire", "Real Fox", "Real Fox", "Spirit Fox", "Fox", "Cat", "Wolf", "Allay", "Bee", "Axolotl"));
    public final NumberSetting size = add(new NumberSetting("Size", "How big it is next to the real mob", 0.7, 0.3, 1.5, 0.05));
    public final ModeSetting side = add(new ModeSetting("Side", "Which side of you it keeps to", "Right", "Right", "Left", "Behind"));
    public final NumberSetting distance = add(new NumberSetting("Distance", "How far from you it stays, in blocks", 1.8, 1, 5, 0.1));
    public final ModeSetting energy = add(new ModeSetting("Energy", "How lively it is: Calm walks slowly, Lively trots and runs faster", "Normal", "Calm", "Normal", "Lively"));
    public final BoolSetting baby = add(new BoolSetting("Baby", "The baby version of the mob, even smaller", false));
    public final BoolSetting sit = add(new BoolSetting("Sit when idle", "Foxes, cats and wolves sit down while you stand still", true));
    public final BoolSetting look = add(new BoolSetting("Look at you", "Its head follows you", true));

    /** For the rig: frames the pet went into the frame list, snaps and hops seen. */
    public static int rendered, snaps, hops;
    /** For the rig: largest horizontal step in one tick (not counting snaps), and the sum of all steps, in blocks. The rig may reset them. */
    public static double maxStep, walked;
    /** For the rig: how far the last snap jumped, and the gap between old and new position on the snap tick (must be 0, else it streaks). */
    public static double snapJump, snapOldGap = -1;

    // ---- tuning (blocks per tick unless noted) ---------------------------------------------------------------------------
    private static final double V_WALK = 0.24, V_TROT = 0.34, V_RUN = 0.42; // top speeds at Normal energy
    private static final double WALK_FLOOR = 0.23;      // Calm walk never drops below the owner's walk (0.216)
    private static final double ACC = 0.012, DEC = 0.022; // speed change per tick
    private static final double LEASH_SLACK = 0.8;      // blocks beyond Distance before it starts walking again
    private static final double SIDE_FRAC = 0.9;        // side offset of the spot, times Distance
    private static final double AHEAD_FRAC = 0.35;      // forward offset of the spot: a little ahead of the owner's shoulder, so it is in view in third person and at a sideways glance (not in the straight-ahead first-person view)
    private static final float HEADING_RATE = 5f;       // degrees per tick the remembered heading may turn
    private static final float TURN_WALK = 9f, TURN_FAST = 14f; // body turn, degrees per tick
    private static final float HEAD_RATE = 12f;         // head turn, degrees per tick
    private static final int SIT_AFTER = 80;            // ticks of standing still before it sits
    private static final double STEP_GLIDE = 0.18;      // climb speed over slabs and carpets
    private static final double HOP_ABOVE = 0.55;       // steps higher than this need a hop
    private static final double STEP_MAX = 1.1;         // highest step it will try
    private static final double DROP_MAX = 2.5;         // deepest drop it will walk off
    private static final double HALF = 0.25, BODY_H = 0.6; // collision box of a ground pet, whatever the Size
    private static final int HIDE_AFTER = 20, SHOW_AFTER = 10; // ticks before it hides or comes back
    private static final double TELEPORT = 8;           // owner displacement in one tick that counts as a teleport
    private static final int TELEPORT_DELAY = 10;       // ticks it waits before snapping after a teleport

    private Mob pet;
    private String built = "";
    private Object builtLevel;
    private double px, py, pz, vy, spd;
    private boolean airborne, follow, hidden;
    private int gear;                                    // 0 walk, 1 trot, 2 run (with hysteresis)
    private double hopY, hopV;                           // the little hop when a find is marked
    private float bodyYaw, headYaw;
    private float heading;                               // remembered owner heading, degrees
    private double ownerSm, ownerSmPrev, prevX, prevY, prevZ, ownerVx, ownerVy, ownerVz;
    private boolean havePrev;
    private int tpTicks, runTicks, stuck, idle, hideCount, showCount;
    private boolean sitting;
    private double fvx, fvy, fvz;                        // flier velocity
    private double tx, ty, tz;                           // the spot it wants
    private double lastGround = Double.NaN;
    private boolean gapCheck;
    private float lastStride = -1f;                    // stride and scale in use: a change resets the leg phase
    private double lastScale = -1;
    private double lastMoved;                            // horizontal step of this tick
    private int glanceIn = 80, glanceLeft;
    private float glanceOff;
    private final Random rng = new Random();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    public Pet() {
        super("Pet", "A small vanilla mob walks calmly next to you and sits when you stop", Category.COSMETIC);
        beta();
    }

    public static Pet active() {
        Pet p = Modules.get(Pet.class);
        return p != null && p.on() ? p : null;
    }

    @Override
    protected void onDisable() { drop(); }

    @Override
    public void onLeaveWorld() {
        drop();
    }

    @Override
    public void onDimensionChange() {
        drop();
    }

    @Override
    public void onRender3D(dev.vantage.render.R3 r) {
        if (pet == null || hidden) return;
        if (type.is("Real Fox")) { RealFox.render(r, pet, size.get() * 1.25 * (baby.on() ? 0.6 : 1)); rendered++; }
        else if (type.is("Spirit Fox")) SpiritFox.render(r, pet, size.get() * (baby.on() ? 0.55 : 1), sitting);
    }

    private void drop() {
        SpiritFox.reset();
        RealFox.reset();
        pet = null;
        built = "";
        builtLevel = null;
        hidden = false;
        havePrev = false;
        lastGround = Double.NaN;
    }

    private boolean flier() {
        return type.is("Allay") || type.is("Bee");
    }

    private boolean canSit() {
        return type.is("Real Fox") || type.is("Spirit Fox") || type.is("Fox") || type.is("Cat") || type.is("Wolf");
    }

    private double energyFactor() {
        return energy.is("Calm") ? 0.85 : energy.is("Lively") ? 1.2 : 1.0;
    }

    /** The pet's position, for the rig; null when there is none. */
    public double[] pos() {
        return pet == null ? null : new double[] {px, py, pz};
    }

    /** True while the pet object exists (the rig then checks it is nowhere in the level). */
    public Entity object() { return pet; }

    public boolean isSitting() { return sitting; }

    /** For the rig: true while the pet is hidden (owner on elytra, riding, flying, dead). */
    public boolean isHidden() { return hidden; }

    /** For the rig: true while it is in the air on a hop or a fall. */
    public boolean isAirborne() { return airborne; }

    /** How far off the ground the hop has lifted it, for the rig. */
    public double hopHeight() { return hopY; }

    /** For the rig: the leg phase the renderer reads (grows with the distance walked). */
    public float legPhase() { return pet == null ? 0f : pet.walkAnimation.position(); }

    /** For the rig: the leg stride scale in use. */
    public float strideNow() { return stride(); }

    /** Something worth a look was marked: the pet hops (a bob in the air for fliers). Ignored while it is hidden. */
    public void hop() {
        if (pet == null || hidden || Cos.calm()) return;
        hopV = flier() ? 0.2 : 0.42;
        sitting = false;
        idle = 0;
        hops++;
    }

    // ---- building ------------------------------------------------------------------------------------------------------

    private Mob build() {
        EntityType<? extends Mob> t = switch (type.get()) {
            case "Cat" -> EntityType.CAT;
            case "Wolf" -> EntityType.WOLF;
            case "Allay" -> EntityType.ALLAY;
            case "Bee" -> EntityType.BEE;
            case "Axolotl" -> EntityType.AXOLOTL;
            default -> EntityType.FOX;
        };
        Mob m = t.create(mc.level, EntitySpawnReason.LOAD);
        if (m == null) return null;
        m.setSilent(true);
        m.setNoGravity(true);
        if (baby.on()) m.setBaby(true);
        if (m instanceof net.minecraft.world.entity.animal.wolf.Wolf w) { try { w.setTame(true, false); } catch (Throwable ignored) { /* a wild wolf is fine too */ } }
        return m;
    }

    private void ensure() {
        String key = type.get() + "|" + baby.on();
        if (pet != null && key.equals(built) && builtLevel == mc.level) return;
        try {
            pet = build();
        } catch (Throwable t) {
            log().once("build-failed", t, () -> "Pet could not be built (ensure() retries every tick; later failures are only counted)");
            pet = null;
        }
        built = key;
        builtLevel = mc.level;
        if (pet == null) return;
        Player p = mc.player;
        heading = p.yBodyRot;
        prevX = p.getX(); prevY = p.getY(); prevZ = p.getZ();
        havePrev = true;
        ownerSm = ownerSmPrev = ownerVx = ownerVy = ownerVz = 0;
        spd = vy = hopY = hopV = fvx = fvy = fvz = 0;
        idle = runTicks = stuck = tpTicks = hideCount = showCount = gear = 0;
        airborne = follow = hidden = sitting = false;
        lastGround = Double.NaN;
        snapToTarget(false, false, false);
        bodyYaw = headYaw = heading;
        pose(0, true);
        copyOld();
    }

    // ---- the spot ------------------------------------------------------------------------------------------------------------

    /** Ground spot beside (or behind) the owner, from the remembered heading; if it is not standable, right behind the owner. */
    private void spot(Player p) {
        double yaw = Math.toRadians(heading);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double d = distance.get();
        if (side.is("Behind")) {
            tx = p.getX() - fx * d;
            tz = p.getZ() - fz * d;
        } else {
            double s = side.is("Right") ? 1 : -1;
            tx = p.getX() + rx * d * SIDE_FRAC * s + fx * d * AHEAD_FRAC;
            tz = p.getZ() + rz * d * SIDE_FRAC * s + fz * d * AHEAD_FRAC;
        }
        if (flier()) return;
        if (!standable(tx, tz, p)) {
            tx = p.getX() - fx * 0.9;
            tz = p.getZ() - fz * 0.9;
        }
    }

    private boolean standable(double x, double z, Player p) {
        double g = groundAt(x, z, p.getY() + 1.1);
        if (Double.isNaN(g) || g > p.getY() + 1.05 || g < p.getY() - 3) return false;
        if (wet(x, g, z)) return false;
        return mc.level.noCollision(new AABB(x - HALF, g + 0.05, z - HALF, x + HALF, g + 0.05 + BODY_H, z + HALF));
    }

    /** Top of the highest block under (x, z) at or below startY, within 8 blocks; NaN when there is none. */
    private double groundAt(double x, double z, double startY) {
        int bx = Mth.floor(x), bz = Mth.floor(z), y0 = Mth.floor(startY);
        for (int y = y0; y >= y0 - 8; y--) {
            probe.set(bx, y, bz);
            BlockState s = mc.level.getBlockState(probe);
            VoxelShape sh = s.getCollisionShape(mc.level, probe);
            if (!sh.isEmpty()) return y + sh.max(Direction.Axis.Y);
        }
        return Double.NaN;
    }

    /** Highest floor under the pet's footprint (centre and four corners), so it steps up when its edge meets a block, not its centre. */
    private double footGround(double x, double z, double startY) {
        double g = groundAt(x, z, startY);
        if (Double.isNaN(g)) return g;
        for (int i = 0; i < 4; i++) {
            double c = groundAt(x + ((i & 1) == 0 ? -HALF : HALF), z + ((i & 2) == 0 ? -HALF : HALF), startY);
            if (!Double.isNaN(c) && c > g) g = c;
        }
        return g;
    }

    /** True when the cell a pet would stand in (just above the floor at g) holds water or lava. */
    private boolean wet(double x, double g, double z) {
        probe.set(Mth.floor(x), Mth.floor(g + 0.01), Mth.floor(z));
        return !mc.level.getBlockState(probe).getFluidState().isEmpty();
    }

    // ---- snapping and hiding -------------------------------------------------------------------------------------------------

    /** Puts it on its spot. puffs: clouds where it lands (and where it left, if puffOld). count: add to the rig's snap counters. */
    private void snapToTarget(boolean puffs, boolean puffOld, boolean count) {
        Player p = mc.player;
        double oldX = px, oldY = py, oldZ = pz;
        if (flier()) {
            spot(p);
            flierHeight(p);
            px = tx; py = ty; pz = tz;
        } else {
            spot(p);
            px = tx; pz = tz;
            double g = groundAt(px, pz, Math.max(py, p.getY()) + 1.1);
            if (Double.isNaN(g)) g = Double.isNaN(lastGround) ? p.getY() : lastGround;
            lastGround = g;
            py = g;
        }
        vy = spd = fvx = fvy = fvz = 0;
        airborne = follow = false;
        gear = 0;
        stuck = runTicks = tpTicks = 0;
        if (puffs) {
            int n = Cos.calmCount(5);
            for (int i = 0; i < n; i++) {
                if (puffOld) mc.level.addParticle(ParticleTypes.CLOUD, oldX, oldY + 0.2, oldZ, 0, 0.02, 0);
                mc.level.addParticle(ParticleTypes.CLOUD, px, py + 0.2, pz, 0, 0.02, 0);
            }
            if (count) {
                snaps++;
                snapJump = Math.sqrt((px - oldX) * (px - oldX) + (pz - oldZ) * (pz - oldZ));
                gapCheck = true;
            }
        }
    }

    /** Owner states that hide the pet: elytra, riding, spectator, dead; ground pets also in creative flight or with no ground below. */
    private boolean wantHidden(Player p) {
        if (p.isFallFlying() || p.isPassenger() || p.isSpectator() || !p.isAlive()) return true;
        if (flier()) return false;
        if (p.getAbilities().flying && !p.onGround()) return true;
        return Double.isNaN(groundAt(p.getX(), p.getZ(), p.getY() + 0.1));
    }

    private boolean shouldSnap(Player p, double toPlayer) {
        if (tpTicks > 0 && --tpTicks == 0 && toPlayer > 8) return true;
        return toPlayer > 16 || Math.abs(p.getY() - py) > 40 || (runTicks > 90 && toPlayer > 9) || (stuck > 50 && toPlayer > 3);
    }

    // ---- ticking ---------------------------------------------------------------------------------------------------------------

    /** Owner speed (smoothed), the owner's own velocity, the remembered heading and teleport detection. */
    private void trackOwner(Player p) {
        double dx = havePrev ? p.getX() - prevX : 0, dy = havePrev ? p.getY() - prevY : 0, dz = havePrev ? p.getZ() - prevZ : 0;
        havePrev = true;
        prevX = p.getX(); prevY = p.getY(); prevZ = p.getZ();
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > TELEPORT) {
            dx = dy = dz = 0;
            tpTicks = TELEPORT_DELAY;
        }
        ownerVx = dx; ownerVy = dy; ownerVz = dz;
        ownerSmPrev = ownerSm;
        ownerSm += (Math.sqrt(dx * dx + dz * dz) - ownerSm) * 0.25;
        // the heading is read from the body only while the owner really moves, so turning the view never moves the spot
        if (ownerSm > 0.06) heading = Mth.approachDegrees(heading, p.yBodyRot, HEADING_RATE);
    }

    @Override
    public void onTick() {
        Player p = mc.player;
        if (mc.level == null || p == null) return;
        ensure();
        if (pet == null) return;
        trackOwner(p);
        // hide and come back with a small delay each way, so a short flight or a mount does not flicker it
        if (!hidden) {
            hideCount = wantHidden(p) ? hideCount + 1 : 0;
            if (hideCount >= HIDE_AFTER) {
                hidden = true;
                hideCount = showCount = 0;
                int n = Cos.calmCount(5);
                for (int i = 0; i < n; i++) mc.level.addParticle(ParticleTypes.CLOUD, px, py + 0.2, pz, 0, 0.02, 0);
                return; // no more pose this tick
            }
        } else {
            showCount = !wantHidden(p) && (flier() || p.onGround()) ? showCount + 1 : 0;
            if (showCount >= SHOW_AFTER) {
                hidden = false;
                showCount = hideCount = 0;
                snapToTarget(true, false, false); // coming back is not counted as a snap
                bodyYaw = headYaw = heading;
                hopY = hopV = 0;
                pose(0, true);
                copyOld();
            }
            return;
        }
        boolean fresh = flier() ? flierMove(p) : groundMove(p);
        double moved = lastMoved;
        // hop physics (a find was marked)
        if (hopY > 0 || hopV > 0) {
            hopY = Math.max(0, hopY + hopV);
            hopV -= flier() ? 0.03 : 0.08;
            if (hopY <= 0) { hopY = 0; hopV = 0; }
        }
        // idle: it sits after a while standing still
        boolean still = ownerSm < 0.03 && !follow && !airborne && hopY == 0 && hopV == 0 && !flier();
        idle = still ? idle + 1 : 0;
        sitting = still && idle > SIT_AFTER && sit.on() && canSit();
        if (!fresh) {
            copyOld(); // old values before the new pose, so the renderer lerps over exactly one tick
            maxStep = Math.max(maxStep, moved);
            walked += moved;
        }
        head(p, moved > 0.03);
        pose(moved, fresh);
        if (fresh) {
            copyOld(); // after the pose: no streak across the world on a snap
            if (gapCheck) {
                snapOldGap = Math.sqrt((pet.xOld - pet.getX()) * (pet.xOld - pet.getX()) + (pet.zOld - pet.getZ()) * (pet.zOld - pet.getZ()));
                gapCheck = false;
            }
        }
    }

    private void copyOld() {
        pet.setOldPosAndRot();
        pet.yBodyRotO = pet.yBodyRot;
        pet.yHeadRotO = pet.yHeadRot;
        pet.xRotO = pet.getXRot();
    }

    private static float yawTo(double dx, double dz) {
        return (float) Dist.bearing(dx, dz);
    }

    private double topSpeed(int g) {
        double e = energyFactor();
        return g == 0 ? Math.max(WALK_FLOOR, V_WALK * e) : g == 1 ? V_TROT * e : V_RUN * e;
    }

    // ---- ground pets -------------------------------------------------------------------------------------------------------------

    /** One tick of ground following. Returns true when it snapped (the pose then must not be lerped). */
    private boolean groundMove(Player p) {
        lastMoved = 0;
        spot(p);
        double toPlayer = Math.sqrt((p.getX() - px) * (p.getX() - px) + (p.getZ() - pz) * (p.getZ() - pz));
        runTicks = toPlayer > 5 ? runTicks + 1 : 0;
        if (shouldSnap(p, toPlayer)) {
            snapToTarget(true, true, true);
            return true;
        }
        double d = distance.get();
        double distSpot = Math.sqrt((tx - px) * (tx - px) + (tz - pz) * (tz - pz));
        boolean ownerIdle = ownerSm < 0.03;
        if (!follow && (toPlayer > d + LEASH_SLACK || toPlayer < 0.8 || (ownerSm > 0.08 && distSpot > 1.0))) follow = true;
        if (follow) {
            // owner idle and not crowded: only walk to the edge of the ring around him; else to the spot
            boolean toOwner = ownerIdle && toPlayer >= Math.max(1.0, d * 0.6);
            double gap = toOwner ? toPlayer - d : distSpot;
            double gx = toOwner ? p.getX() : tx, gz = toOwner ? p.getZ() : tz;
            // a walking owner: trot only when really far behind (it speeds up from rest, so the gap peaks near 3)
            if (gear == 0 && gap > (ownerSm < 0.3 ? 3.2 : 2.4)) gear = 1;
            else if (gear == 1 && gap < 1.8) gear = 0;
            if (gear == 1 && gap > 5.5) gear = 2;
            else if (gear == 2 && gap < 4.2) gear = 1;
            // match the owner's pace, plus a catch-up share of the gap; a faster owner raises the top speed (up to run)
            double top = Math.max(topSpeed(gear), Math.min(ownerSm + 0.03, topSpeed(2)));
            double vDes = ownerSm + 0.06 * gap;
            if (gap > 0.3) vDes = Math.max(vDes, 0.12);
            vDes = Math.min(vDes, top);
            // arrival: never faster than it can stop at the spot (plus the owner's own speed)
            vDes = Math.min(vDes, Math.sqrt(2 * DEC * Math.max(gap - 0.1, 0)) + ownerSm);
            // the owner is stopping: start easing off at once, do not coast past the spot
            if (ownerSm < 0.12 && ownerSm < ownerSmPrev && distSpot < 1.0) vDes = Math.min(vDes, Math.max(spd - DEC, 0));
            // direction: next to a walking owner it faces his way, else towards the goal
            float goal = (!toOwner && ownerSm > 0.06 && distSpot < 0.6) ? heading : yawTo(gx - px, gz - pz);
            if (Math.abs(Mth.wrapDegrees(goal - bodyYaw)) > 60f) vDes *= 0.25; // turn first, then go
            bodyYaw = Mth.approachDegrees(bodyYaw, goal, (gear > 0 || spd > 0.3) ? TURN_FAST : TURN_WALK);
            spd = Math.max(0, spd + Mth.clamp(vDes - spd, -DEC, ACC));
            double r = Math.toRadians(bodyYaw);
            move(-Math.sin(r) * spd, Math.cos(r) * spd);
            boolean arrived = (toOwner && gap <= 0.15) || (!toOwner && distSpot < 0.35 && !ownerIdle) || (toOwner && toPlayer <= d)
                || (ownerIdle && toPlayer >= 0.8 && (distSpot < 0.35 || toPlayer <= d + 0.3)); // idle owner: close enough is done
            if ((spd < 0.03 && arrived) || (ownerIdle && stuck > 40)) {
                follow = false;
                spd = 0;
                stuck = 0;
            }
        } else {
            spd = 0;
            stuck = 0;
        }
        return settle(p);
    }

    private static final int BLOCKED = 0, MOVED = 1, HOLD = 2;

    /** Moves by (mx, mz) with collision: whole step, else slide along x, else along z. */
    private void move(double mx, double mz) {
        if (spd < 1e-4) return;
        double ox = px, oz = pz;
        int r = stepTo(px + mx, pz + mz);
        if (r == BLOCKED) r = stepTo(px + mx, pz);
        if (r == BLOCKED) r = stepTo(px, pz + mz);
        double m = Math.sqrt((px - ox) * (px - ox) + (pz - oz) * (pz - oz));
        lastMoved = m;
        if (r == HOLD) return; // waiting for the hop to lift it, not stuck
        if (m < spd * 0.4) { spd *= 0.5; stuck++; }
        else stuck = Math.max(0, stuck - 2);
    }

    /** Tries to put it at (nx, nz): checks the floor there, water, and a box above it at the height it will stand. */
    private int stepTo(double nx, double nz) {
        double g = footGround(nx, nz, py + 1.3);
        if (Double.isNaN(g)) return BLOCKED;
        double h = g - py;
        if (h > STEP_MAX || -h > DROP_MAX) return BLOCKED;
        if (wet(nx, g, nz)) return BLOCKED;
        double base = Math.max(py, g) + 0.05;
        if (!mc.level.noCollision(new AABB(nx - HALF, base, nz - HALF, nx + HALF, base + BODY_H, nz + HALF))) return BLOCKED;
        if (!airborne && h > HOP_ABOVE) {
            airborne = true; // jump first, walk onto the step once it is high enough
            vy = Mth.clamp(Math.sqrt(0.16 * (h + 0.1)), 0.30, 0.46);
            return HOLD;
        }
        if (airborne && h > 0.3) return HOLD;
        px = nx;
        pz = nz;
        return MOVED;
    }

    /** Vertical part: stand on the floor, glide up slabs, fall off edges, land from hops. */
    private boolean settle(Player p) {
        double g = footGround(px, pz, py + 1.3);
        if (Double.isNaN(g)) g = Double.isNaN(lastGround) ? p.getY() : lastGround;
        lastGround = g;
        if (airborne) {
            py += vy;
            vy = (vy - 0.08) * 0.98;
            if (py <= g && vy < 0) { py = g; vy = 0; airborne = false; }
        } else if (g > py + 0.001) {
            double h = g - py;
            if (h > STEP_MAX) { // ground rose out of reach (piston, building): snap instead of a teleport at landing
                snapToTarget(true, true, true);
                return true;
            }
            if (h > HOP_ABOVE) { airborne = true; vy = Mth.clamp(Math.sqrt(0.16 * (h + 0.1)), 0.30, 0.46); }
            else py = Math.min(g, py + STEP_GLIDE);
        } else if (g < py - 0.001) {
            airborne = true;
            vy = 0;
        } else {
            py = g;
        }
        return false;
    }

    // ---- fliers --------------------------------------------------------------------------------------------------------------------

    /** Height of the flier's spot (ty) above the owner's feet, with a small bob. */
    private void flierHeight(Player p) {
        double t = pet == null ? 0 : pet.tickCount;
        double bob = type.is("Allay") ? 0.10 * Math.sin(t * 0.12) : 0.05 * Math.sin(t * 0.45);
        ty = p.getY() + p.getEyeHeight() - 0.3 + bob;
    }

    private boolean freeCube(double x, double y, double z) {
        return mc.level.noCollision(new AABB(x - 0.2, y, z - 0.2, x + 0.2, y + 0.4, z + 0.2));
    }

    /** One tick of flying: a spring towards an orbiting spot, damped relative to the owner's own velocity. */
    private boolean flierMove(Player p) {
        lastMoved = 0;
        double dx = p.getX() - px, dy = p.getY() + 1.3 - py, dz = p.getZ() - pz;
        double toPlayer = Math.sqrt(dx * dx + dz * dz);
        runTicks = toPlayer > 5 ? runTicks + 1 : 0;
        if (shouldSnap(p, toPlayer)) {
            snapToTarget(true, true, true);
            return true;
        }
        double t = pet.tickCount, d = distance.get();
        double a = Math.toRadians(side.is("Right") ? 50 : side.is("Left") ? -50 : 180) + 0.45 * Math.sin(t * 0.035);
        double rad = d * (0.95 + 0.12 * Math.sin(t * 0.05));
        double yaw = Math.toRadians(heading);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double wob = type.is("Bee") ? 0.18 * Math.sin(t * 0.15) : 0;
        tx = p.getX() + (fx * Math.cos(a) + rx * Math.sin(a)) * rad + rx * wob;
        tz = p.getZ() + (fz * Math.cos(a) + rz * Math.sin(a)) * rad + rz * wob;
        flierHeight(p);
        if (!freeCube(tx, ty, tz)) { // the spot is inside rock: right behind the owner instead
            tx = p.getX() - fx * 0.9;
            tz = p.getZ() - fz * 0.9;
            ty = p.getY() + 1.3;
        }
        fvx += 0.025 * (tx - px) - 0.22 * (fvx - ownerVx);
        fvy += 0.025 * (ty - py) - 0.22 * (fvy - ownerVy);
        fvz += 0.025 * (tz - pz) - 0.22 * (fvz - ownerVz);
        double cap = Math.min(0.9, Math.max((type.is("Allay") ? 0.40 : 0.46) * energyFactor(), ownerSm * 1.15 + 0.12));
        double v = Math.sqrt(fvx * fvx + fvy * fvy + fvz * fvz);
        if (v > cap) { fvx *= cap / v; fvy *= cap / v; fvz *= cap / v; }
        double ox = px, oz = pz;
        boolean hit = false;
        if (freeCube(px + fvx, py, pz)) px += fvx; else { fvx = 0; hit = true; }
        if (freeCube(px, py + fvy, pz)) py += fvy; else { fvy = 0; hit = true; }
        if (freeCube(px, py, pz + fvz)) pz += fvz; else { fvz = 0; hit = true; }
        double far = Math.sqrt((tx - px) * (tx - px) + (tz - pz) * (tz - pz));
        stuck = hit && far > 0.8 ? stuck + 1 : Math.max(0, stuck - 1);
        lastMoved = Math.sqrt((px - ox) * (px - ox) + (pz - oz) * (pz - oz));
        if (Math.sqrt(fvx * fvx + fvz * fvz) > 0.04) bodyYaw = Mth.approachDegrees(bodyYaw, yawTo(fvx, fvz), 10f);
        return false;
    }

    // ---- pose --------------------------------------------------------------------------------------------------------------------------

    /** Head follows the owner (blended towards the body while walking), with a random glance away now and then. */
    private void head(Player p, boolean moving) {
        double dx = p.getX() - px, dz = p.getZ() - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean watch = look.on() && dist < 6;
        float goal = bodyYaw;
        if (watch) {
            float rel = Mth.clamp(Mth.wrapDegrees(yawTo(dx, dz) - bodyYaw), -70f, 70f);
            goal = bodyYaw + (moving ? 0.4f * rel : rel);
        }
        if (look.on() && !moving) {
            if (glanceLeft > 0) {
                glanceLeft--;
                goal = bodyYaw + glanceOff;
            } else if (--glanceIn <= 0) {
                glanceLeft = 30 + rng.nextInt(21);
                glanceOff = (35 + rng.nextInt(26)) * (rng.nextBoolean() ? 1 : -1);
                glanceIn = 60 + rng.nextInt(81);
            }
        }
        headYaw = Mth.approachDegrees(headYaw, goal, HEAD_RATE);
        float pitch = 0f;
        if (watch) pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(p.getEyeY() - (py + pet.getBbHeight() * 0.7), dist + 0.01)), -30f, 30f);
        pet.setXRot(Mth.clamp(pet.getXRot() + (pitch - pet.getXRot()) * 0.2f, -30f, 30f));
    }

    /** Leg stride per block: a smaller pet needs a faster stride to keep its feet on the ground. */
    private float stride() {
        return (float) Mth.clamp(1.0 / size.get(), 0.8, 2.2) * (baby.on() ? 1.6f : 1f);
    }

    private void pose(double moved, boolean fresh) {
        Mob m = pet;
        if (type.is("Real Fox")) RealFox.tick(fresh ? 0 : moved, sitting, bodyYaw);
        if (type.is("Spirit Fox")) SpiritFox.tick(px, py + hopY, pz, moved, gear == 2 || (mc.player != null && mc.player.isSprinting()), mc.level.getGameTime());
        m.setPos(px, py + hopY, pz);
        m.setYRot(bodyYaw);
        m.yBodyRot = bodyYaw;
        m.yHeadRot = headYaw;
        m.tickCount++;
        m.setOnGround(!flier() && !airborne && hopY <= 0);
        float st = stride();
        double scNow = size.get();
        if (st != lastStride || scNow != lastScale) { // Stride or Size changed: restart the leg phase, no jump
            lastStride = st;
            lastScale = scNow;
            m.walkAnimation.stop();
        }
        if (fresh) m.walkAnimation.stop();
        else if (flier()) m.walkAnimation.update((float) Math.min(moved * 3, 1.0), 0.4f, 1.0f);
        else m.walkAnimation.update(airborne ? 0.3f : (float) (moved * 4), 0.4f, st); // not capped: the phase follows the distance walked
        double sc = size.get();
        var attr = m.getAttribute(Attributes.SCALE);
        if (attr != null && Math.abs(attr.getBaseValue() - sc) > 1e-4) attr.setBaseValue(sc);
        if (m instanceof Fox f) f.setSitting(sitting);
        if (m instanceof TamableAnimal t) t.setInSittingPose(sitting);
        if (m instanceof Axolotl a) {
            a.onGroundAnimator.tick(true);
            a.inWaterAnimator.tick(false);
            a.movingAnimator.tick(moved > 0.03);
            a.playingDeadAnimator.tick(false);
        }
    }

    // ---- drawing -----------------------------------------------------------------------------------------------------------------------

    /** Called from the frame's entity extraction: hands the vanilla renderer's state for the pet to the frame. */
    public static void extract(net.minecraft.client.renderer.state.LevelRenderState state, float partial) {
        Pet m = active();
        if (m == null || m.pet == null || m.hidden || m.type.is("Real Fox")) return; // the Real Fox draws its own body
        try {
            var s = Minecraft.getInstance().getEntityRenderDispatcher().extractEntity(m.pet, partial);
            state.entityRenderStates.add(s);
            rendered++;
        } catch (Throwable t) {
            Vantage.LOG.error("Pet could not be drawn, turning it off", t);
            m.pet = null;
            m.setEnabled(false);
        }
    }
}
