package dev.vantage.module.donut;

import java.util.List;
import java.util.Random;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Keys;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.command.Args;
import dev.vantage.core.util.Fmt;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import dev.vantage.setting.TextSetting;
import net.minecraft.world.phys.Vec3;

/**
 * Sends one command over and over, waiting a fresh random time between Min delay and Max delay after every send
 * (1 and 2 = somewhere between one and two seconds each time). Built at the owner's request for /tpa on Donut.
 * It only ever sends a command (never plain chat), never faster than once a second, and switches itself off after
 * Tries sends, when you get teleported, on a world change, when the connection is gone, on its key or on ".macro stop".
 */
public class Macro extends Module {
    /** Hard floor between two commands, whatever the sliders say. */
    public static final long MIN_GAP_MS = 1000;
    private static final Random RNG = new Random();

    public final TextSetting command = add(new TextSetting("Command", "The command to send, e.g. /tpa Name", "/tpa "));
    public final NumberSetting minDelay = add(new NumberSetting("Min delay", "Shortest wait between two sends, in seconds", 1, 1, 120, 0.1));
    public final NumberSetting maxDelay = add(new NumberSetting("Max delay", "Longest wait between two sends, in seconds; each wait is a random time between Min and Max", 2, 1, 120, 0.1));
    public final NumberSetting tries = add(new NumberSetting("Tries", "Stop after this many sends (0 = until you stop it)", 20, 0, 500, 1));
    public final BoolSetting stopOnTeleport = add(new BoolSetting("Stop on teleport", "Switch off as soon as you get teleported (the /tpa went through)", true));
    public final BoolSetting chat = add(new BoolSetting("Chat", "A line per send with the next wait", false));

    private long nextAt;
    private int sent;
    private Vec3 lastPos;
    /** For the rig: the last command sent (without the slash), how many went out in total, and the waits picked, in ms. */
    public static String lastCommand;
    public static int totalSent;
    public static final java.util.List<Long> waits = new java.util.ArrayList<>();

    public Macro() {
        super("Macro", "Repeats one command with a random delay between two values", Category.DONUT);
        command(dev.vantage.core.command.Command.named("macro", "Set the command to repeat and start or stop it; no argument shows its state").usableWhileOff()
                .arg(dev.vantage.core.command.Args.text("args", "/command, delay MIN [MAX], tries N, start or stop", List.of("start", "stop", "delay", "tries")).optional())
                .runs((a, r) -> r.info(command(a.has(0) ? a.get(0) : ""))));
    }

    @Override
    public String info() {
        if (!on()) return null;
        long left = Math.max(0, nextAt - System.currentTimeMillis());
        return sent + (tries.i() > 0 ? "/" + tries.i() : "") + "  ·  next " + Fmt.fixed(left / 1000.0, 1) + " s";
    }

    /** The command without its slash, or null when there is nothing usable. */
    public String cleaned() {
        String c = command.text() == null ? "" : command.text().trim();
        while (c.startsWith("/")) c = c.substring(1).trim();
        return c.isEmpty() || c.length() > 200 ? null : c;
    }

    /** A wait in ms: random between the two sliders (either order), never under the floor. */
    public long pickWait() {
        double lo = Math.min(minDelay.get(), maxDelay.get()), hi = Math.max(minDelay.get(), maxDelay.get());
        return Math.max(MIN_GAP_MS, Math.round((lo + RNG.nextDouble() * (hi - lo)) * 1000));
    }

    @Override
    protected void onEnable() {
        sent = 0;
        lastPos = null;
        if (cleaned() == null) { Chat.info("Macro not started: no command set (type one in the Command box or use .macro /tpa Name)"); setEnabled(false); return; }
        if (mc.player == null || mc.getConnection() == null) { Chat.info("Macro needs a server"); setEnabled(false); return; }
        nextAt = System.currentTimeMillis(); // the first one goes out right away
        Chat.info("Macro on: /" + cleaned() + " every " + range() + (tries.i() > 0 ? ", " + tries.i() + " tries" : ", until stopped")
                + (bind == -1 ? ". No key set: .macro stop ends it" : ", key " + Keys.name(bind) + " stops it"));
    }

    private String range() {
        double lo = Math.min(minDelay.get(), maxDelay.get()), hi = Math.max(minDelay.get(), maxDelay.get());
        return lo == hi ? Fmt.fixed(lo, 1) + " s" : Fmt.fixed(lo, 1) + " to " + Fmt.fixed(hi, 1) + " s";
    }

    @Override
    protected void onDisable() {
        if (sent > 0) Chat.info("Macro off after " + sent + (sent == 1 ? " send" : " sends"));
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
        if (mc.player == null || mc.level == null) return;
        if (mc.getConnection() == null) { setEnabled(false); return; }
        Vec3 pos = mc.player.position();
        if (stopOnTeleport.on() && sent > 0 && lastPos != null && pos.distanceToSqr(lastPos) > 32 * 32) {
            Chat.info("Macro: you were teleported, stopping");
            setEnabled(false);
            return;
        }
        lastPos = pos;
        long now = System.currentTimeMillis();
        if (now < nextAt) return;
        String cmd = cleaned();
        if (cmd == null) { setEnabled(false); return; }
        dev.vantage.core.command.ServerCommands.send(this, cmd);
        lastCommand = cmd;
        totalSent++;
        sent++;
        if (tries.i() > 0 && sent >= tries.i()) { setEnabled(false); return; }
        long wait = pickWait();
        waits.add(wait);
        nextAt = now + wait;
        if (chat.on()) Chat.info("Macro " + sent + (tries.i() > 0 ? "/" + tries.i() : "") + ": next in " + Fmt.fixed(wait / 1000.0, 1) + " s");
    }

    /**
     * ".macro /tpa Name" sets the command, ".macro delay 1 2" the two delays, ".macro tries N", ".macro start", ".macro stop",
     * ".macro" shows the state. Returns the reply.
     */
    public static String command(String args) {
        Macro m = Modules.get(Macro.class);
        if (m == null) return "Macro is not loaded";
        String t = args.trim();
        String[] a = t.isEmpty() ? new String[0] : t.split("\\s+");
        try {
            if (a.length == 0) return "Macro " + (m.on() ? "on (" + m.sent + " sent)" : "off") + ": " + (m.cleaned() == null ? "no command" : "/" + m.cleaned()) + ", every " + m.range()
                    + (m.tries.i() > 0 ? ", " + m.tries.i() + " tries" : ", until stopped") + ". .macro /command · .macro delay MIN MAX · .macro tries N · .macro start · .macro stop";
            switch (a[0].toLowerCase()) {
                case "start", "on", "go" -> { if (m.on()) return "Macro is already on"; m.setEnabled(true); return m.on() ? "Macro started" : "Macro did not start"; }
                case "stop", "off" -> { if (!m.on()) return "Macro is already off"; m.setEnabled(false); return "Macro stopped"; }
                case "delay" -> {
                    if (a.length < 2) return "Usage: .macro delay MIN [MAX] (seconds)";
                    double lo = Args.number(a[1]), hi = a.length >= 3 ? Args.number(a[2]) : lo;
                    if (lo < 1 || hi < 1 || lo > 120 || hi > 120) return "Delays must be 1 to 120 seconds";
                    m.minDelay.set(Math.min(lo, hi));
                    m.maxDelay.set(Math.max(lo, hi));
                    return "Delay " + m.range();
                }
                case "tries" -> {
                    if (a.length < 2) return "Usage: .macro tries N (0 = until stopped)";
                    int n = Integer.parseInt(a[1]);
                    if (n < 0 || n > 500) return "Tries must be 0 to 500";
                    m.tries.set((double) n);
                    return n == 0 ? "Runs until you stop it" : n + " tries";
                }
                default -> {
                    if (!t.startsWith("/")) return "Commands start with a slash: .macro /tpa Name";
                    if (t.length() > 200) return "That command is too long";
                    m.command.set(t);
                    return "Macro command set to " + t + ". .macro start when ready";
                }
            }
        } catch (NumberFormatException e) {
            return "That's not a number";
        }
    }
}
