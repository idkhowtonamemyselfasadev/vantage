package dev.vantage.module.donut;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Friends;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Toasts;
import dev.vantage.core.Ui;
import dev.vantage.core.hud.HudElements;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Who is on your shard. Donut runs on Folia with the map cut into shards (its "goliaths"), and while the tab list shows the
 * whole network, scoreboard teams and game-mode updates only arrive for players on the shard you stand on. A name that
 * reaches you through one of those, or walks into view, is on your goliath, within one 12.5k square of you.
 * Media accounts carry a team whose name starts with "09-" or whose prefix holds the camera emoji. Reads only; after
 * your own /rtp it waits three seconds and says how many share the shard.
 */
public class ShardWatch extends Module {
    private static final float W = 170;
    private static final long FORGET_MS = 15 * 60_000, RTP_WAIT_MS = 3000;

    public final BoolSetting alerts = add(new BoolSetting("Chat", "Line when someone new is on your shard", true));
    public final BoolSetting rtpToast = add(new BoolSetting("RTP summary", "Card after /rtp with how many share the shard", true));
    public final BoolSetting hud = add(new BoolSetting("HUD panel", "Names on screen", true));
    public final ModeSetting corner = add(new ModeSetting("Corner", "Where the panel goes", "Top Left", Hud.CORNERS)).visibleIf(hud::on);
    public final BoolSetting mediaOnly = add(new BoolSetting("Media only", "Only list media accounts", false));

    public record Seen(String name, String proof, boolean media, long time) {}

    private final Map<String, Seen> seen = new LinkedHashMap<>();
    private final Map<String, String> teamOf = new HashMap<>();
    private long rtpAt;
    private int timer;

    static {
        HudElements.declare("shard", "Shard", "Top Left", ShardWatch.class);
    }

    public ShardWatch() {
        super("Shard Watch", "Who shares your Donut shard: team and game-mode packets only come for players on your goliath", Category.DONUT);
        // tab-list and team packets
        listen(dev.vantage.core.event.PacketHandledEvent.class, e -> {
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket p) onInfo(p);
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket p) onTeam(p);
        });
    }

    @Override
    public String info() {
        return seen.isEmpty() ? null : String.valueOf(count());
    }

    public List<Seen> seen() {
        return new ArrayList<>(seen.values());
    }

    public int count() {
        int n = 0;
        for (Seen s : seen.values()) if (!mediaOnly.on() || s.media()) n++;
        return n;
    }

    private boolean me(String name) {
        return mc.player != null && mc.player.getGameProfile().name().equals(name);
    }

    public static boolean mediaTeam(String teamName, String prefix) {
        return (teamName != null && teamName.startsWith("09-")) || (prefix != null && prefix.contains("📹"));
    }

    private boolean isMedia(String name) {
        String team = teamOf.get(name);
        if (team == null) return false;
        PlayerTeam t = mc.level == null ? null : mc.level.getScoreboard().getPlayerTeam(team);
        return mediaTeam(team, t == null ? null : t.getPlayerPrefix().getString());
    }

    /** Public so the rig can feed names. */
    public void prove(String name, String proof) {
        if (name == null || name.isEmpty() || me(name)) return;
        long now = System.currentTimeMillis();
        Seen old = seen.get(name);
        boolean media = isMedia(name);
        seen.put(name, new Seen(name, proof, media, now));
        if (old != null) return;
        dev.vantage.core.LeakLog.note("SHARD", name + " " + proof + (media ? " media" : ""));
        if (!alerts.on() || (mediaOnly.on() && !media)) return;
        Chat.send(Component.literal(name).withStyle(media ? ChatFormatting.LIGHT_PURPLE : Friends.is(name) ? ChatFormatting.GREEN : ChatFormatting.YELLOW)
                .append(Component.literal((media ? " (media)" : "") + " is on your shard: " + proof).withStyle(ChatFormatting.GRAY)));
    }

    public void onInfo(ClientboundPlayerInfoUpdatePacket p) {
        var a = p.actions();
        if (a.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) || !a.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) return;
        var c = mc.getConnection();
        if (c == null) return;
        for (var e : p.entries()) {
            var pi = c.getPlayerInfo(e.profileId());
            if (pi != null) prove(pi.getProfile().name(), "game mode update");
        }
    }

    public void onTeam(ClientboundSetPlayerTeamPacket p) {
        if (p.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) return;
        for (String n : p.getPlayers()) {
            teamOf.put(n, p.getName());
            prove(n, "team " + p.getName());
        }
    }

    /** Our own command, read on the way out, nothing added. */
    public void onCommand(String command) {
        String c = command.trim().toLowerCase();
        if (c.equals("rtp") || c.startsWith("rtp ")) rtpAt = System.currentTimeMillis();
    }

    @Override
    public void onTick() {
        if (mc.level == null) return;
        long now = System.currentTimeMillis();
        if (rtpAt != 0 && now - rtpAt > RTP_WAIT_MS) {
            rtpAt = 0;
            if (rtpToast.on()) Toasts.ok("Shard Watch", count() == 0 ? "Nobody else on this shard" : count() + " on this shard: " + names(4));
        }
        if (--timer > 0) return;
        timer = 20;
        for (Player pl : mc.level.players()) if (pl != mc.player) prove(pl.getGameProfile().name(), "in view");
        for (PlayerTeam t : mc.level.getScoreboard().getPlayerTeams()) {
            for (String n : t.getPlayers()) { teamOf.put(n, t.getName()); prove(n, "team " + t.getName()); }
        }
        seen.values().removeIf(s -> now - s.time() > FORGET_MS);
    }

    private String names(int max) {
        List<String> out = new ArrayList<>();
        for (Seen s : seen.values()) if (!mediaOnly.on() || s.media()) out.add(s.name() + (s.media() ? " (media)" : ""));
        if (out.size() > max) return String.join(", ", out.subList(0, max)) + " +" + (out.size() - max);
        return String.join(", ", out);
    }

    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    private void forgetWorld() {
        seen.clear();
        teamOf.clear();
        rtpAt = 0;
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (!hud.on() || Hud.hidden()) return;
        List<Seen> list = new ArrayList<>();
        for (Seen s : seen.values()) if (!mediaOnly.on() || s.media()) list.add(s);
        int shown = Math.min(8, list.size());
        float h = 30 + shown * 12 + (list.size() > shown ? 12 : 0);
        float s = Ui.scale();
        String cn = corner.get();
        float[] at = Hud.slot("shard", cn, W, h);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        Ui.panel(g, x, y, W, h);
        Ui.text(g, "ON YOUR SHARD", x + 10, y + 8, Ui.FAINT, 0.85f, false);
        Ui.right(g, String.valueOf(list.size()), x + W - 10, y + 7, Ui.TEXT, 1.2f, false);
        int vu = dev.vantage.core.Presence.count();
        if (vu > 0 && dev.vantage.core.Presence.badgeOn()) { // how many here run Vantage too (you included)
            float bx = x + 10 + Ui.width("ON YOUR SHARD", 0.85f, false) + 10;
            dev.vantage.core.Presence.badge(g, bx, y + 8, 8);
            Ui.text(g, String.valueOf(vu), bx + 10, y + 8, Ui.ACCENT, 0.85f, false);
        }
        Ui.hline(g, x + 10, y + 23, W - 20, Ui.LINE);
        long now = System.currentTimeMillis();
        float yy = y + 28;
        for (int i = 0; i < shown; i++) {
            Seen sn = list.get(i);
            int c = sn.media() ? 0xFFD98BFF : Friends.is(sn.name()) ? Ui.GREEN : Ui.TEXT;
            float nx = x + 10;
            if (dev.vantage.core.Presence.isName(sn.name())) { dev.vantage.core.Presence.badge(g, nx, yy + 0.5f, 8); nx += 11; }
            Ui.text(g, Ui.fit((Friends.is(sn.name()) ? "\u2605 " : "") + dev.vantage.core.Streamer.name(sn.name()), W - 70 - (nx - x - 10), 0.9f), nx, yy, c, 0.9f, false);
            long m = (now - sn.time()) / 60_000;
            Ui.right(g, sn.media() ? "media" : m == 0 ? "now" : m + "m", x + W - 10, yy, Ui.FAINT, 0.8f, false);
            yy += 12;
        }
        if (list.size() > shown) Ui.text(g, "+" + (list.size() - shown) + " more", x + 10, yy, Ui.FAINT, 0.8f, false);
        Ui.end(g);
    }
}
