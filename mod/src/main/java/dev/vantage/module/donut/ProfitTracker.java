package dev.vantage.module.donut;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Ui;
import dev.vantage.core.Vantage;
import dev.vantage.core.hud.HudElements;
import dev.vantage.core.log.DataLogs;
import dev.vantage.core.util.Fmt;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Money per hour. Reads the server's own sale messages (chat and action bar) and adds every "$" it pays you: /sell receipts
 * ("You sold 64 Diamond and received $1,234") and auction sales ("Your Diamond x64 sold to Someone for $1,000"). Anything you
 * paid is ignored, and lines that look like player chat (a name before the words) are too. Totals for the last 10 minutes,
 * the last hour and the session, the pace per hour, and a chat summary every N minutes. Every sale also lands in
 * config/vantage/profit/<server>.log. Nothing is sent: it only reads what the server already told you.
 */
public class ProfitTracker extends Module {
    private static final float W = 150;
    private static final Pattern MONEY = Pattern.compile("\\$\\s?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?(?![\\w])");
    private static final Pattern EARNED = Pattern.compile("(?i)\\b(sold|received|earned|paid you|you got)\\b");
    /** Donut's own receipt is an action bar line that is nothing but the amount: "$ 10.5K". The $ may be a custom glyph, so it is optional, but then the K/M/B is not. */
    private static final Pattern BARE = Pattern.compile("^[^0-9A-Za-z$]*(\\$)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?[^0-9A-Za-z]*$");
    private static final Pattern SPENT = Pattern.compile("(?i)\\b(bought|purchased|spent|you paid|withdrew|sent|listed|tax)\\b");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public final NumberSetting report = add(new NumberSetting("Report every", "Minutes between chat summaries, 0 = never", 10, 0, 120, 5));
    public final BoolSetting hud = add(new BoolSetting("HUD panel", "Profit panel on screen", true));
    public final ModeSetting corner = add(new ModeSetting("Corner", "Where the panel goes", "Top Left", Hud.CORNERS)).visibleIf(hud::on);
    public final BoolSetting auctions = add(new BoolSetting("Auction sales", "Count items sold on the auction house too", true));
    public final BoolSetting actionBar = add(new BoolSetting("Action bar", "Read receipts from the action bar as well as chat", true));
    public final BoolSetting each = add(new BoolSetting("Chat each sale", "Line in chat for every sale counted", false));

    public record Sale(long time, double amount, String text) {}

    private final ArrayDeque<Sale> sales = new ArrayDeque<>();
    private long started, lastReport, lastSaleAt;
    private double lastSale, session;

    static {
        HudElements.declare("profit", "Profit", "Top Left", ProfitTracker.class);
    }

    public ProfitTracker() {
        super("Profit Tracker", "Money per hour from the server's sale messages, with 10 min / 1 h / session totals", Category.DONUT);
        // the action bar's own packet never goes through the chat listener, so the sale receipts on it are read here
        listen(dev.vantage.core.event.PacketHandledEvent.class, e -> { if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket p) onMessage(p.text().getString(), true); });
    }

    @Override
    public String info() {
        return started == 0 ? null : money(perHour()) + "/h";
    }

    public double session() {
        return session;
    }

    public double last(long ms) {
        long cut = System.currentTimeMillis() - ms;
        double sum = 0;
        for (Sale s : sales) if (s.time() >= cut) sum += s.amount();
        return sum;
    }

    public double perHour() {
        long elapsed = System.currentTimeMillis() - started;
        if (elapsed < 60_000) return 0;
        return session / (elapsed / 3_600_000.0);
    }

    public List<Sale> sales() {
        return new ArrayList<>(sales);
    }

    /** $1.2M style, the way the server writes it. */
    /** Money text, see {@link Fmt#money}; kept as a public name because an in-game check calls it. */
    public static String money(double v) {
        return Fmt.money(v);
    }

    private static double parse(Matcher m) {
        double v = Double.parseDouble(m.group(1).replace(",", ""));
        String suffix = m.group(2);
        if (suffix != null) v *= switch (Character.toLowerCase(suffix.charAt(0))) { case 'k' -> 1e3; case 'm' -> 1e6; default -> 1e9; };
        return v;
    }

    /** A message from the server. Returns the amount it counted, 0 when it wasn't a sale. Public so the rig can feed lines. */
    public double onMessage(String text, boolean overlay) {
        if (started == 0 || text == null) return 0;
        if (overlay && !actionBar.on()) return 0;
        String t = ChatFormatting.stripFormatting(text);
        if (t == null) return 0;
        if (t.indexOf('$') >= 0 && !t.startsWith("[" + Vantage.NAME + "]")) note(t, overlay); // every money line, counted or not, so new formats can be read off the log
        Matcher bare = BARE.matcher(t.trim());
        if (overlay && bare.matches() && (bare.group(1) != null || bare.group(3) != null)) {
            double v = Double.parseDouble(bare.group(2).replace(",", ""));
            if (bare.group(3) != null) v *= switch (Character.toLowerCase(bare.group(3).charAt(0))) { case 'k' -> 1e3; case 'm' -> 1e6; default -> 1e9; };
            return count(v, t, 3000); // the bar shows the same receipt for a few seconds
        }
        Matcher earned = EARNED.matcher(t);
        if (!earned.find()) return 0;
        String before = t.substring(0, earned.start()).trim().replaceFirst("^[^A-Za-z0-9]+", ""); // check marks and icons in front don't count
        if (before.contains(":") || before.contains("»") || before.contains(">")) return 0; // a player saying it, not the server
        // Donut tells everyone "<name> sold multiple items for $ 5.6M": only lines about you count
        String me = mc.player == null ? "" : mc.player.getGameProfile().name();
        boolean mine = before.equalsIgnoreCase("you") || before.toLowerCase(Locale.ROOT).startsWith("your ") || before.toLowerCase(Locale.ROOT).startsWith("you ")
                || before.isEmpty() || (!me.isEmpty() && before.endsWith(me));
        if (!mine) return 0;
        if (SPENT.matcher(t).find() && !t.toLowerCase(Locale.ROOT).contains("sold")) return 0;
        if (!auctions.on() && t.toLowerCase(Locale.ROOT).contains(" sold to ")) return 0;
        Matcher m = MONEY.matcher(t);
        double amount = 0;
        while (m.find()) amount = Math.max(amount, parse(m)); // "for $1,000 (tax $50)": the sale, not the tax
        if (amount <= 0) return 0;
        return count(amount, t, 1500);
    }

    /** Adds a sale unless it is the same amount within the window (the same receipt in chat and on the bar). */
    private double count(double amount, String t, long window) {
        long now = System.currentTimeMillis();
        if (amount == lastSale && now - lastSaleAt < window) return 0;
        lastSale = amount;
        lastSaleAt = now;
        sales.add(new Sale(now, amount, t));
        session += amount;
        if (each.on()) Chat.send(Component.literal("+" + money(amount)).withStyle(ChatFormatting.GREEN)
                .append(Component.literal("  session " + money(session) + "  " + money(perHour()) + "/h").withStyle(ChatFormatting.GRAY)));
        log(amount, t);
        return amount;
    }

    private void note(String text, boolean overlay) {
        DataLogs.appendServer("profit", "-messages.log", TIME.format(LocalDateTime.now()) + (overlay ? " [bar] " : " [chat] ") + text);
    }

    private void log(double amount, String text) {
        DataLogs.appendServer("profit", ".log", TIME.format(LocalDateTime.now()) + " +" + String.format(Locale.ROOT, "%.0f", amount) + " " + text);
    }

    private String elapsed() {
        return Fmt.duration(System.currentTimeMillis() - started);
    }

    private void summary() {
        Chat.send(Component.literal("Profit ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(money(last(600_000)) + " last 10 min  ·  " + money(last(3_600_000)) + " last hour  ·  " + money(perHour()) + "/h  ·  "
                        + money(session) + " in " + elapsed() + " (" + sales.size() + " sales)").withStyle(ChatFormatting.GRAY)));
    }

    @Override
    protected void onEnable() {
        started = lastReport = System.currentTimeMillis();
        sales.clear();
        session = 0;
        Chat.info("Profit Tracker started: sell something and it counts");
    }

    @Override
    protected void onDisable() {
        if (started != 0 && session > 0) summary();
        started = 0;
    }

    @Override
    public void onTick() {
        if (started == 0 || report.i() <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastReport >= report.i() * 60_000L) { lastReport = now; summary(); }
        while (!sales.isEmpty() && now - sales.peekFirst().time() > 3_600_000L * 24) sales.pollFirst();
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (!hud.on() || (started == 0 && !Hud.editing()) || Hud.hidden()) return;
        float h = 74;
        String cn = corner.get();
        float[] at = Hud.slot("profit", cn, W, h);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        Ui.panel(g, x, y, W, h);
        Ui.text(g, "PROFIT", x + 10, y + 8, Ui.FAINT, 0.85f, false);
        Ui.right(g, money(perHour()) + "/h", x + W - 10, y + 7, Ui.GREEN, 1.1f, true);
        Ui.hline(g, x + 10, y + 23, W - 20, Ui.LINE);
        row(g, x, y + 29, "10 min", money(last(600_000)));
        row(g, x, y + 42, "1 hour", money(last(3_600_000)));
        row(g, x, y + 55, elapsed() + "  ·  " + sales.size() + " sales", money(session));
        Ui.end(g);
    }

    private static void row(GuiGraphics g, float x, float y, String label, String value) {
        Ui.text(g, label, x + 10, y, Ui.SUB, 0.88f, false);
        Ui.right(g, value, x + W - 10, y, Ui.TEXT, 0.92f, false);
    }
}
