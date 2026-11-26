package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Toasts;
import dev.vantage.core.util.ChunkMath;
import dev.vantage.core.util.Cooldown;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Signs of players the server won't show: packets naming an entity we don't have, chests opening with nobody there and invisible
 * players. Each is a hint, marked for a minute. Only reads packets. Chunks ticking past your simulation range are Simulation Ring's
 * job, zeroed sky light Sky Fingerprint's.
 */
public class HiddenPlayers extends Module {
    private static final long KEEP_MS = 60_000;
    private static final double NEAR = 6;

    public final BoolSetting unseen = add(new BoolSetting("Unseen entities", "Damage and item pickups naming someone you can't see", true));
    public final BoolSetting containers = add(new BoolSetting("Containers", "Chests and barrels opening with nobody there", true));
    public final BoolSetting attacks = add(new BoolSetting("Attack particles", "Sweep and damage particles with nobody there", true));
    public final BoolSetting invisible = add(new BoolSetting("Invisible", "Players with the invisible flag", true));
    public final BoolSetting toast = add(new BoolSetting("Toast", "Card for each new signal", true));

    public record Signal(String kind, String text, Vec3 pos, long last, int count) {}

    private final Map<String, Signal> signals = new HashMap<>();
    private final Cooldown toastGate = Cooldown.wall(3000);

    public HiddenPlayers() {
        super("Hidden Players", "Signs of players the server hides from you", Category.FINDER);
        listen(dev.vantage.core.event.PacketHandledEvent.class, e -> {
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundDamageEventPacket p) onDamage(p);
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket p) onSound(p);
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket p) onParticle(p);
        });
        // before the game handles it: the picked-up item is gone afterwards
        listen(dev.vantage.core.event.PacketInEvent.class, e -> { if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket p) onTake(p); });
    }

    @Override
    public String info() {
        return String.valueOf(signals.size());
    }

    public List<Signal> signals() {
        return new ArrayList<>(signals.values());
    }

    private void mark(String kind, String text, Vec3 pos) {
        String key = kind + " " + ChunkMath.chunk(pos.x) + " " + ChunkMath.chunk(pos.z);
        Signal old = signals.get(key);
        long now = System.currentTimeMillis();
        signals.put(key, new Signal(kind, text, pos, now, old == null ? 1 : old.count() + 1));
        dev.vantage.core.LeakLog.add("PLAYER-" + kind, (int) pos.x, (int) pos.y, (int) pos.z, text);
        if (old == null && toast.on() && toastGate.tryUse()) {
            Toasts.warn("Hidden Players", text + " at " + (int) pos.x + " " + (int) pos.y + " " + (int) pos.z);
        }
    }

    /** Anyone we can see close enough to have caused this, us included. */
    private boolean someoneNear(Vec3 pos, double r) {
        for (Player p : mc.level.players()) if (p.position().distanceToSqr(pos) <= r * r) return true;
        return false;
    }

    public void onDamage(ClientboundDamageEventPacket p) {
        if (!unseen.on() || p.sourceCauseId() < 0 || mc.level.getEntity(p.sourceCauseId()) != null) return;
        Entity victim = mc.level.getEntity(p.entityId());
        if (victim == null) return;
        mark("attacker", "Unseen attacker hit " + victim.getName().getString(), p.sourcePosition().orElse(victim.position()));
    }

    public void onTake(ClientboundTakeItemEntityPacket p) {
        if (!unseen.on() || mc.level.getEntity(p.getPlayerId()) != null) return;
        Entity item = mc.level.getEntity(p.getItemId());
        if (item != null) mark("pickup", "Unseen pickup", item.position());
    }

    public void onSound(ClientboundSoundPacket p) {
        if (!containers.on()) return;
        String path = p.getSound().value().location().getPath();
        if (!path.endsWith(".open") || !(path.contains("chest") || path.contains("barrel") || path.contains("shulker_box"))) return;
        Vec3 pos = new Vec3(p.getX(), p.getY(), p.getZ());
        if (!someoneNear(pos, NEAR)) mark("container", "Container opened, nobody there", pos);
    }

    public void onParticle(ClientboundLevelParticlesPacket p) {
        if (!attacks.on()) return;
        var type = p.getParticle().getType();
        if (type != ParticleTypes.SWEEP_ATTACK && type != ParticleTypes.DAMAGE_INDICATOR) return;
        Vec3 pos = new Vec3(p.getX(), p.getY(), p.getZ());
        if (!someoneNear(pos, NEAR)) mark("attack", "Attack, nobody there", pos);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        signals.values().removeIf(s -> now - s.last() > KEEP_MS);
        if (invisible.on()) {
            for (Player p : mc.level.players()) {
                if (p != mc.player && p.isInvisible() && !dev.vantage.core.Friends.is(p)) mark("invisible", p.getName().getString() + " is invisible", p.position());
            }
        }
    }

    private void reset() {
        signals.clear();
    }

    @Override protected void onEnable() { reset(); }
    @Override
    public void onLeaveWorld() {
        reset();
    }

    @Override
    public void onDimensionChange() {
        reset();
    }

    @Override
    public void onRender3D(R3 r) {
        long now = System.currentTimeMillis();
        for (Signal s : signals.values()) {
            int color = 0xFF000000 | 0x60A8FF;
            EspStyle.box(r, new AABB(s.pos().x - 0.3, s.pos().y, s.pos().z - 0.3, s.pos().x + 0.3, s.pos().y + 1.8, s.pos().z + 0.3), color, true);
            r.label(s.pos().add(0, 2.1, 0), s.text() + "  " + (now - s.last()) / 1000 + "s", color);
        }
    }

    @Override
    public int labelClass() {
        return LABEL_NAME;
    }
}
