package dev.vantage.module.donut;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.command.Args;
import dev.vantage.core.util.Fmt;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.Header;
import dev.vantage.setting.NumberSetting;
import dev.vantage.setting.TextSetting;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Keeps sending a random one of your RTP commands until you land within the radius of a target (X and Z only), then stops.
 * The first automation in Vantage, at the owner's request: it sends chat commands, nothing else. Never faster than once a second
 * and never faster than the interval; off on arrival, on the key (0 by default), on ".rtp stop" or when the connection is gone.
 * Regions: Donut's /rtp eu west etc. If the target lies in another server region than you, it goes there first; and every N
 * teleports it can hop to a random ticked region so the server's spot picker starts over.
 */
public class RtpLoop extends Module {
    /** Hard floor between two commands, whatever the interval says. */
    private static final long MIN_GAP_MS = 1000;
    private static final Random RNG = new Random();

    public final NumberSetting targetX = add(new NumberSetting("Target X", "Where you want to end up (block X)", 0, -300_000, 300_000, 100));
    public final NumberSetting targetZ = add(new NumberSetting("Target Z", "Where you want to end up (block Z)", 0, -300_000, 300_000, 100));
    public final NumberSetting radius = add(new NumberSetting("Radius", "Done when you are this close, horizontally", 1000, 50, 25_000, 50));
    public final NumberSetting interval = add(new NumberSetting("Interval", "Seconds between commands", 4, 1, 60, 0.5));
    public final TextSetting commands = add(new TextSetting("Commands", "One is picked at random each time; separate with commas", "/rtp"));
    public final BoolSetting chat = add(new BoolSetting("Chat", "A line per teleport with the distance left", true));
    public final Header regionsHead = add(new Header("Regions"));
    public final BoolSetting regionFirst = add(new BoolSetting("Target region first", "If the target lies in another server region, /rtp there before anything else", true));
    public final NumberSetting hopEvery = add(new NumberSetting("Hop every", "After this many teleports, /rtp to a random ticked region so the spot picker starts over (0 = never)", 0, 0, 50, 1));
    public final List<BoolSetting> regions = new ArrayList<>();

    private long lastSent;
    private int sent, sinceHop;
    private Vec3 sentFrom;
    /** For the rig: the last command sent, without the slash. */
    public static String lastCommand;
    public static int totalSent;

    public RtpLoop() {
        super("RTP Loop", "Repeats /rtp until you are within a radius of a target, then stops", Category.DONUT);
        command(dev.vantage.core.command.Command.named("rtp", "Set the target and start or stop the RTP loop; no argument shows its state").usableWhileOff()
                .arg(dev.vantage.core.command.Args.text("args", "X Z [radius], radius R, here, start or stop", List.of("start", "stop", "radius", "here")).optional())
                .runs((a, r) -> r.info(command(a.has(0) ? a.get(0) : ""))));
        bind = GLFW.GLFW_KEY_0;
        for (String r : DonutRegions.REGIONS) regions.add(add(new BoolSetting(r, "May hop to " + r, true)));
    }

    @Override
    public String info() {
        if (mc.player == null) return null;
        return Fmt.distance(distance()) + (sent > 0 ? "  ·  " + sent + " sent" : "");
    }

    private double distance() {
        double dx = mc.player.getX() - targetX.get(), dz = mc.player.getZ() - targetZ.get();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private List<String> commandList() {
        List<String> out = new ArrayList<>();
        for (String c : commands.text().split(",")) {
            c = c.trim();
            if (c.startsWith("/")) c = c.substring(1);
            if (!c.isEmpty() && c.length() <= 100) out.add(c);
        }
        return out;
    }

    /** What is wrong with the settings, or null. */
    public String problem() {
        if (commandList().isEmpty()) return "no command set";
        if (radius.get() < 50) return "radius under 50";
        if (interval.get() < 1) return "interval under 1 s";
        return null;
    }

    @Override
    protected void onEnable() {
        remember();
        sent = 0;
        sinceHop = 0;
        sentFrom = null;
        lastSent = 0;
        String p = problem();
        if (p != null) { Chat.info("RTP loop not started: " + p); setEnabled(false); return; }
        if (mc.player == null || mc.getConnection() == null) { Chat.info("RTP loop needs a server"); setEnabled(false); return; }
        if (distance() <= radius.get()) { Chat.info("Already within " + Fmt.distance(radius.get()) + " of the target"); setEnabled(false); return; }
        Chat.info("RTP loop on: heading for " + targetX.i() + " " + targetZ.i() + ", radius " + Fmt.distance(radius.get()) + ", key " + dev.vantage.core.Keys.name(bind) + " stops it");
    }

    @Override
    protected void onDisable() {
        if (sent > 0) Chat.info("RTP loop off after " + sent + " teleports, " + (mc.player == null ? "" : Fmt.distance(distance()) + " from the target"));
    }

    private long leftAt;
    private String leftKey = "";
    private String upKey = ""; // the server key while the world is up: during the configuration phase of a shard hop the client has no current server, so it cannot be read at the moment of leaving

    private static boolean known(String k) {
        return k != null && !k.isEmpty() && !k.equals("unknown");
    }

    private void remember() {
        String k = dev.vantage.core.Config.serverKey();
        if (known(k)) upKey = k;
    }

    /** Remembers where and when the world went away; onJoinWorld decides, because a hop to another shard behind a proxy also passes through "no world" and must not stop the loop. */
    @Override
    public void onLeaveWorld() {
        if (!isEnabled()) return;
        leftAt = System.currentTimeMillis();
        String now = dev.vantage.core.Config.serverKey();
        leftKey = known(upKey) ? upKey : now;
    }

    @Override
    public void onJoinWorld() {
        if (!isEnabled()) return;
        boolean hadLeft = leftAt > 0;
        String now = dev.vantage.core.Config.serverKey();
        // an unreadable key at either end counts as the same server (the 60 s limit still applies)
        boolean same = !known(leftKey) || !known(now) || leftKey.equals(now);
        boolean hop = hadLeft && System.currentTimeMillis() - leftAt < 60_000 && same;
        leftAt = 0;
        if (hop) { remember(); return; } // same network, quick rejoin: a shard hop or an RTP landing, carry on
        setEnabled(false);
        if (hadLeft) dev.vantage.core.Toasts.ok("RTP loop", "RTP loop switched off after rejoining");
    }

    @Override
    public void onTick() {
        // the connection is gone (disconnect, title screen): off for good, so it cannot wake up on the next server and send /rtp there
        if (mc.player == null || mc.level == null || mc.getConnection() == null) { setEnabled(false); return; }
        remember();
        double d = distance();
        if (d <= radius.get()) {
            dev.vantage.core.Toasts.ok("RTP loop", "Arrived: " + Fmt.distance(d) + " from the target after " + sent + " teleports");
            Chat.info("RTP loop: arrived, " + Fmt.distance(d) + " from the target after " + sent + " teleports");
            setEnabled(false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSent < Math.max(MIN_GAP_MS, (long) (interval.get() * 1000))) return;
        // give a teleport that is still in flight a moment: no new command while we have not moved yet, unless it took too long
        if (sentFrom != null && mc.player.position().distanceToSqr(sentFrom) < 64 * 64 && now - lastSent < 8000 && !mc.player.onGround()) return;
        String cmd = pick();
        if (cmd == null) { setEnabled(false); return; }
        dev.vantage.core.command.ServerCommands.send(this, cmd);
        lastCommand = cmd;
        totalSent++;
        lastSent = now;
        sent++;
        sentFrom = mc.player.position();
        if (chat.on()) Chat.info("RTP " + sent + ": /" + cmd + "  ·  " + Fmt.distance(d) + " to go");
    }

    private int regionOf(double x, double z) {
        int col = DonutRegions.cell(x), row = DonutRegions.cell(z);
        return col < 0 || row < 0 ? -1 : DonutRegions.region(col, row);
    }

    private static String regionCommand(int region) {
        return "rtp " + DonutRegions.REGIONS[region].toLowerCase();
    }

    private String pick() {
        if (regionFirst.on()) {
            int want = regionOf(targetX.get(), targetZ.get()), have = regionOf(mc.player.getX(), mc.player.getZ());
            if (want >= 0 && have != want) return regionCommand(want);
        }
        if (hopEvery.i() > 0 && sinceHop >= hopEvery.i()) {
            List<Integer> allowed = new ArrayList<>();
            for (int i = 0; i < regions.size(); i++) if (regions.get(i).on()) allowed.add(i);
            if (!allowed.isEmpty()) { sinceHop = 0; return regionCommand(allowed.get(RNG.nextInt(allowed.size()))); }
        }
        sinceHop++;
        List<String> list = commandList();
        return list.isEmpty() ? null : list.get(RNG.nextInt(list.size()));
    }

    /**
     * ".rtp X Z [radius]" sets the target, ".rtp radius R", ".rtp here" (target = where you stand), ".rtp start", ".rtp stop",
     * ".rtp" shows the state. Returns the reply.
     */
    public static String command(String args) {
        RtpLoop m = Modules.get(RtpLoop.class);
        if (m == null) return "RTP loop is not loaded";
        String[] a = args.trim().isEmpty() ? new String[0] : args.trim().split("\\s+");
        try {
            if (a.length == 0) return "RTP loop " + (m.on() ? "on" : "off") + ": target " + m.targetX.i() + " " + m.targetZ.i() + ", radius " + Fmt.distance(m.radius.get())
                    + (m.mc.player == null ? "" : ", " + Fmt.distance(m.distance()) + " away") + ". .rtp X Z [radius] · .rtp radius R · .rtp here · .rtp start · .rtp stop";
            switch (a[0].toLowerCase()) {
                case "start", "on", "go" -> { if (m.on()) return "RTP loop is already on"; m.setEnabled(true); return m.on() ? "RTP loop started" : "RTP loop did not start"; }
                case "stop", "off" -> { if (!m.on()) return "RTP loop is already off"; m.setEnabled(false); return "RTP loop stopped"; }
                case "radius" -> {
                    if (a.length < 2) return "Usage: .rtp radius R";
                    double r = Args.number(a[1]);
                    if (r < 50 || r > 25_000) return "Radius must be 50 to 25000";
                    m.radius.set(r);
                    return "Radius " + Fmt.distance(r);
                }
                case "here" -> {
                    if (m.mc.player == null) return "No player";
                    m.targetX.set(Math.floor(m.mc.player.getX()));
                    m.targetZ.set(Math.floor(m.mc.player.getZ()));
                    return "Target set to " + m.targetX.i() + " " + m.targetZ.i();
                }
                default -> {
                    if (a.length < 2) return "Usage: .rtp X Z [radius]";
                    double x = Args.number(a[0]), z = Args.number(a[1]);
                    if (Math.abs(x) > 300_000 || Math.abs(z) > 300_000) return "Coordinates must be within ±300000";
                    if (a.length >= 3) {
                        double r = Args.number(a[2]);
                        if (r < 50 || r > 25_000) return "Radius must be 50 to 25000";
                        m.radius.set(r);
                    }
                    m.targetX.set(x);
                    m.targetZ.set(z);
                    return "Target " + (int) x + " " + (int) z + ", radius " + Fmt.distance(m.radius.get()) + ". .rtp start when ready";
                }
            }
        } catch (NumberFormatException e) {
            return "That's not a number: .rtp X Z [radius]";
        }
    }
}
