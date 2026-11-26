package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Toasts;
import dev.vantage.core.Ui;
import dev.vantage.setting.BoolSetting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;

/**
 * Players the server keeps in its player-info list with listed=false, so they're online but not in tab, plus the info and team
 * packets that give them away. Servers with their own tab plugin can unlist everybody: then the panel says so instead of a list.
 */
public class VanishWatch extends Module {
    private static final int MAX_ROWS = 8;

    public final BoolSetting panel = add(new BoolSetting("Panel", "List on the right", true));
    public final BoolSetting toast = add(new BoolSetting("Toast", "Card when someone new shows up", true));
    public final BoolSetting allIds = add(new BoolSetting("Odd profiles too", "Also show profiles that don't look like real accounts (NPCs, tab fillers)", false));

    private final Set<UUID> known = new HashSet<>();
    private final List<String> rows = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private boolean custom;
    private int settle;

    public VanishWatch() {
        super("Vanish Watch", "Players who are online but hidden from the tab list", Category.FINDER);
        // top right stack: the old fixed spot (y 290, "below the Region Map") sat under the Region Map only while that was on, and over the module list otherwise
        hudPanel("vanish-watch", "Vanish Watch", "Top Right").overScreens().when(() -> panel.on() && (!rows.isEmpty() || !events.isEmpty() || custom)).size(wh -> {
            wh[0] = 240;
            wh[1] = 34 + (custom ? 1 : Math.min(rows.size(), MAX_ROWS) + Math.min(events.size(), 2)) * 15;
        }).draw((g, x, y, w, h) -> {
            Ui.rr(g, x, y, w, h, 7, Ui.GLASS, Ui.BORDER);
            Ui.text(g, "HIDDEN FROM TAB", x + 10, y + 8, Ui.TEXT, 1.05f, false);
            Ui.hline(g, x + 10, y + 26, w - 20, Ui.LINE);
            float ly = y + 31;
            if (custom) {
                Ui.text(g, "Server hides most of tab, list is no use", x + 10, ly, Ui.SUB, 0.85f, false);
            } else {
                for (int i = 0; i < Math.min(rows.size(), MAX_ROWS); i++, ly += 15) Ui.text(g, rows.get(i), x + 10, ly, Ui.YELLOW, 0.9f, false);
                for (int i = 0; i < Math.min(events.size(), 2); i++, ly += 15) Ui.text(g, events.get(i), x + 10, ly, Ui.SUB, 0.82f, false);
            }
        });
        // tab-list and team packets
        listen(dev.vantage.core.event.PacketHandledEvent.class, e -> {
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket p) onInfo(p);
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket p) onTeam(p);
        });
    }

    @Override
    public String info() {
        return custom ? "?" : String.valueOf(known.size());
    }

    public List<String> rows() {
        return List.copyOf(rows);
    }

    public List<String> events() {
        return List.copyOf(events);
    }

    private boolean real(PlayerInfo p) {
        if (dev.vantage.core.Friends.is(p.getProfile().name())) return false;
        if (allIds.on()) return true;
        int v = p.getProfile().id().version();
        return (v == 4 || v == 3) && p.getProfile().name().matches("[A-Za-z0-9_]{3,16}");
    }

    private boolean me(UUID id) {
        return mc.player != null && mc.player.getUUID().equals(id);
    }

    private void event(String text) {
        if (settle > 0) return;
        events.add(0, text);
        while (events.size() > 4) events.remove(events.size() - 1);
        dev.vantage.core.LeakLog.note("VANISH", text);
        if (toast.on()) Toasts.warn("Vanish Watch", text);
    }

    public void onInfo(ClientboundPlayerInfoUpdatePacket p) {
        var c = mc.getConnection();
        if (c == null) return;
        var listed = c.getListedOnlinePlayers();
        for (var e : p.entries()) {
            PlayerInfo pi = c.getPlayerInfo(e.profileId());
            if (pi == null || me(e.profileId()) || !real(pi)) continue;
            var a = p.actions();
            String name = pi.getProfile().name();
            // a listed player flipping to spectator with no reason is a vanish plugin at work
            if (listed.contains(pi)) {
                if (a.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE) && e.gameMode() == net.minecraft.world.level.GameType.SPECTATOR) event(name + " switched to spectator");
                continue;
            }
            if (a.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) event(name + " is hidden, game mode " + e.gameMode().getName());
            else if (a.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER)) event(name + " is hidden, list order changed");
        }
    }

    public void onTeam(ClientboundSetPlayerTeamPacket p) {
        var c = mc.getConnection();
        if (c == null || p.getPlayerAction() != ClientboundSetPlayerTeamPacket.Action.ADD) return;
        for (String n : p.getPlayers()) {
            PlayerInfo pi = c.getPlayerInfo(n);
            if (pi != null && !me(pi.getProfile().id()) && !c.getListedOnlinePlayers().contains(pi) && real(pi)) event(n + " joined a team while hidden");
        }
    }

    @Override
    public void onTick() {
        if (settle > 0) settle--;
        var c = mc.getConnection();
        if (c == null || mc.level.getGameTime() % 20 != 0) return;
        var listed = c.getListedOnlinePlayers();
        Set<UUID> now = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (PlayerInfo p : c.getOnlinePlayers()) {
            UUID id = p.getProfile().id();
            if (me(id) || listed.contains(p) || !real(p)) continue;
            now.add(id);
            out.add(p.getProfile().name() + "  " + (p.getGameMode() == null ? "?" : p.getGameMode().getName()) + "  " + p.getLatency() + "ms");
        }
        custom = now.size() >= 10 && now.size() >= listed.size();
        if (!custom && settle == 0) {
            for (PlayerInfo p : c.getOnlinePlayers()) {
                if (!now.contains(p.getProfile().id()) || known.contains(p.getProfile().id())) continue;
                dev.vantage.core.LeakLog.note("VANISH", p.getProfile().name() + " online, hidden from tab");
                if (toast.on()) Toasts.warn("Vanish Watch", p.getProfile().name() + " is online, hidden from tab");
            }
        }
        known.clear();
        known.addAll(now);
        rows.clear();
        rows.addAll(out);
    }

    private void reset() {
        known.clear();
        rows.clear();
        events.clear();
        custom = false;
        settle = 100; // the join burst of info and team packets is not news
    }

    @Override protected void onEnable() { reset(); }
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
}
