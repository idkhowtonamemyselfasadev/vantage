package dev.vantage.module.finder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Module;
import dev.vantage.core.notify.Level;
import dev.vantage.core.notify.Notify;
import dev.vantage.core.notify.Sound;
import dev.vantage.setting.BoolSetting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;

/** Tells you when a player enters or leaves your render distance, and who joins or leaves the server. */
public class PlayerNotifier extends Module {
    public final BoolSetting visual = add(new BoolSetting("Render distance", "Player comes into / leaves view range", true));
    public final BoolSetting coords = add(new BoolSetting("Coords", "Add their coordinates", true));
    public final BoolSetting leave = add(new BoolSetting("Leaving", "Also tell when they leave view range", true));
    public final BoolSetting tab = add(new BoolSetting("Join / leave", "Server join and leave messages from the tab list", false));
    public final BoolSetting sound = add(new BoolSetting("Sound", "Ping when someone comes into view", true));

    private final Map<UUID, String> inView = new HashMap<>();
    private final Set<String> tabNames = new HashSet<>();
    private boolean tabPrimed;
    private int settle;

    public PlayerNotifier() {
        super("Player Notifier", "Alerts when players come into view or join", Category.FINDER);
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    public void onJoinWorld() {
        reset();
    }

    @Override
    public void onLeaveWorld() {
        reset();
    }

    @Override
    public void onDimensionChange() {
        reset();
    }

    private static final long VIEW_COOLDOWN_MS = 10_000;

    private void reset() {
        inView.clear();
        tabNames.clear();
        tabPrimed = false;
        settle = 40; // players already around on login/teleport are not news
    }

    @Override
    public void onTick() {
        boolean quiet = settle > 0;
        if (settle > 0) settle--;
        Map<UUID, String> now = new HashMap<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || dev.vantage.core.Friends.is(p)) continue;
            now.put(p.getUUID(), p.getName().getString());
            if (!inView.containsKey(p.getUUID()) && visual.on() && !quiet) {
                var b = p.blockPosition();
                String where = coords.on() ? "  " + Chat.coords(b.getX(), b.getY(), b.getZ()) : "";
                // one line per player and 10 s: a player flickering at the edge of tracking range is one line, not a stream
                Notify.post(this, Level.WARNING, "view:" + p.getUUID(), VIEW_COOLDOWN_MS, p.getName().getString() + " came into view  " + (int) p.distanceTo(mc.player) + "m" + where,
                        sound.on() ? new Sound(0.6f) : null);
            }
        }
        if (visual.on() && leave.on() && !quiet) {
            for (var e : inView.entrySet()) {
                if (!now.containsKey(e.getKey())) {
                    Notify.info(this, "left:" + e.getKey(), VIEW_COOLDOWN_MS, e.getValue() + " left view");
                }
            }
        }
        inView.clear();
        inView.putAll(now);

        if (tab.on() && mc.getConnection() != null && mc.level.getGameTime() % 10 == 0) {
            Set<String> names = new HashSet<>();
            for (PlayerInfo pi : mc.getConnection().getOnlinePlayers()) if (!dev.vantage.core.Friends.is(pi.getProfile().name())) names.add(pi.getProfile().name());
            if (tabPrimed && !quiet) {
                for (String n : names) if (!tabNames.contains(n)) Notify.info(this, "tab+:" + n, VIEW_COOLDOWN_MS, n + " joined");
                for (String n : tabNames) if (!names.contains(n)) Notify.info(this, "tab-:" + n, VIEW_COOLDOWN_MS, n + " left");
            }
            tabNames.clear();
            tabNames.addAll(names);
            tabPrimed = true;
        }
    }
}
