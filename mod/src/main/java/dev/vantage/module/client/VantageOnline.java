package dev.vantage.module.client;

import java.util.List;

import dev.vantage.core.Category;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.OwnerList;
import dev.vantage.core.Presence;
import dev.vantage.core.Ui;
import dev.vantage.core.command.Args;
import dev.vantage.core.command.Command;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A small HUD panel: how many accounts are online with the client right now (the service's total over all servers) and which
 * Vantage users are on the server you are on, by name. Nothing new is sent or learned: the total is the service's public number, and
 * the names are the same ones the Friends screen lists under "On Vantage" (players on this server who agreed to presence and whose
 * name the tab list gives). It is a beta, so it exists only where betas are unlocked.
 */
public class VantageOnline extends Module {
    public final ModeSetting corner = add(new ModeSetting("Corner", "Where the panel sits", "Top Left", Hud.CORNERS));
    public final BoolSetting names = add(new BoolSetting("Names", "List the Vantage users on this server by name", true));
    public final NumberSetting lines = add(new NumberSetting("Lines", "How many names at most", 8, 1, 20, 1)).visibleIf(names::on);

    public VantageOnline() {
        super("Vantage Online", "How many play with Vantage right now, and who is on your server", Category.CLIENT);
        beta();
        hudPanel("vantage-online", "Vantage Online", "Top Left").corner(corner::get).size(wh -> {
            if (Presence.isOwner()) { ownerSize(wh); return; }
            List<Presence.Near> here = Presence.onVantageNear();
            int shown = names.on() ? Math.min(lines.i(), here.size()) : 0;
            wh[0] = 150;
            wh[1] = 30 + (names.on() ? 14 + shown * 12 : 0);
            for (int i = 0; i < shown; i++) wh[0] = Math.max(wh[0], Ui.width(here.get(i).name(), 0.9f, false) + 70);
        }).draw(this::drawPanel);
        command(Command.named("vantage", "Who is on this server with Vantage, and the total online")
                .arg(Args.word("view", "what to show").optional())
                .runs((a, r) -> {
                    if (a.has(0) && "all".equalsIgnoreCase(a.get(0)) && Presence.isOwner()) { for (String l : ownerLines(30)) r.info(l); return; }
                    r.info(summary());
                }));
    }

    /** One line for chat: the total and the names here. */
    public String summary() {
        int total = Presence.total();
        List<Presence.Near> here = Presence.onVantageNear();
        StringBuilder b = new StringBuilder("Vantage online: ").append(total < 0 ? "unknown" : String.valueOf(total)).append(". On this server: ");
        if (!Presence.enabled()) return b.append("presence is off, so nobody is listed").toString();
        if (here.isEmpty()) return b.append("nobody else").toString();
        b.append(here.size()).append(" (");
        for (int i = 0; i < here.size(); i++) b.append(i == 0 ? "" : ", ").append(here.get(i).name());
        return b.append(")").toString();
    }

    @Override
    public void onTick() {
        Presence.refreshTotal(); // asks at most once a minute
        Presence.refreshOwner(); // only does anything for the owner's account, and stops for the launch at the first 403
    }

    // ---- the owner's view: everybody online with Vantage, by name -----------------------------------------------------------------

    private void ownerSize(float[] wh) {
        List<OwnerList.Row> rows = Presence.ownerRows();
        int shown = names.on() ? Math.min(lines.i(), rows.size()) : 0;
        wh[0] = 190;
        wh[1] = 30 + (names.on() ? 14 + shown * 12 + (rows.size() > shown ? 12 : 0) : 0);
        for (int i = 0; i < shown; i++) wh[0] = Math.max(wh[0], Ui.width(rows.get(i).name(), 0.9f, false) + 110);
    }

    /** The lines of the owner view as chat shows them: the summary, then one line per account (at most {@code max}). */
    public static List<String> ownerLines(int max) {
        List<OwnerList.Row> rows = Presence.ownerRows();
        List<String> out = new java.util.ArrayList<>();
        out.add("Vantage online: " + OwnerList.summary(rows));
        for (int i = 0; i < Math.min(max, rows.size()); i++) {
            OwnerList.Row r = rows.get(i);
            out.add((r.friend() ? "[friend] " : "") + r.name() + " - " + OwnerList.whereText(r.where()));
        }
        if (rows.size() > max) out.add("... and " + (rows.size() - max) + " more");
        return out;
    }

    private void drawOwnerPanel(GuiGraphics g, float x, float y, float w, float h) {
        List<OwnerList.Row> rows = Presence.ownerRows();
        int shown = names.on() ? Math.min(lines.i(), rows.size()) : 0;
        Ui.panel(g, x, y, w, h);
        Ui.text(g, "VANTAGE ONLINE", x + 10, y + 8, Ui.FAINT, 0.85f, false);
        int total = Presence.total();
        Ui.right(g, total < 0 ? "-" : String.valueOf(total), x + w - 10, y + 7, Ui.TEXT, 1.2f, false);
        if (!names.on()) return;
        Ui.hline(g, x + 10, y + 23, w - 20, Ui.LINE);
        Ui.text(g, OwnerList.summary(rows), x + 10, y + 28, Ui.SUB, 0.85f, false);
        float yy = y + 42;
        for (int i = 0; i < shown; i++) {
            OwnerList.Row r = rows.get(i);
            Ui.text(g, r.name(), x + 10, yy, r.friend() ? FriendsModule.espColor() : Ui.TEXT, 0.9f, false);
            Ui.right(g, (r.friend() ? "friend, " : "") + OwnerList.whereText(r.where()), x + w - 10, yy, Ui.SUB, 0.9f, false);
            yy += 12;
        }
        if (rows.size() > shown) Ui.text(g, "+" + (rows.size() - shown) + " more", x + 10, yy, Ui.FAINT, 0.85f, false);
    }

    private void drawPanel(GuiGraphics g, float x, float y, float w, float h) {
        if (Presence.isOwner()) { drawOwnerPanel(g, x, y, w, h); return; }
        List<Presence.Near> here = Presence.onVantageNear();
        int shown = names.on() ? Math.min(lines.i(), here.size()) : 0;
        Ui.panel(g, x, y, w, h);
        Ui.text(g, "VANTAGE ONLINE", x + 10, y + 8, Ui.FAINT, 0.85f, false);
        int total = Presence.total();
        Ui.right(g, total < 0 ? "-" : String.valueOf(total), x + w - 10, y + 7, Ui.TEXT, 1.2f, false);
        if (names.on()) {
            Ui.hline(g, x + 10, y + 23, w - 20, Ui.LINE);
            Ui.text(g, Presence.enabled() ? "On this server" : "Presence is off", x + 10, y + 28, Ui.SUB, 0.85f, false);
            Ui.right(g, Presence.enabled() ? String.valueOf(here.size()) : "", x + w - 10, y + 28, Ui.TEXT, 0.85f, false);
            float yy = y + 42;
            for (int i = 0; i < shown; i++) {
                Presence.Near n = here.get(i);
                Ui.text(g, n.name(), x + 10, yy, Ui.TEXT, 0.9f, false);
                Ui.right(g, n.far() ? "far" : Math.round(n.dist()) + " m", x + w - 10, yy, Ui.SUB, 0.9f, false);
                yy += 12;
            }
        }
    }
}
