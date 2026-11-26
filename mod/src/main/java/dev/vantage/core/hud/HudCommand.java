package dev.vantage.core.hud;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.command.Args;
import dev.vantage.core.command.Command;
import dev.vantage.core.command.Reply;

/**
 * The chat command {@code hud}: list every HUD element, hide or show one, move one to an anchor, or reset one or all. Until the editor
 * screen of phase 3 this is how a user hides or moves a panel whose module is off or has nothing to show. Everything is saved through
 * {@link HudElements}, so it survives a restart; the words to type come from the argument types (ids and anchors complete with Tab).
 */
public final class HudCommand {
    private static final List<String> ACTIONS = List.of("list", "hide", "show", "move", "reset");

    private HudCommand() {}

    public static Command command() {
        return Command.named("hud", "List, hide, show, move or reset the HUD panels")
                .arg(Args.oneOf("action", "list, hide, show, move or reset", ACTIONS))
                .arg(Args.oneOf("panel", "the panel's id (hud list shows them)", HudElements::ids).optional())
                .arg(Args.oneOf("anchor", "where to move it: top-left, top, top-right, left, center, right, bottom-left, bottom, bottom-right", HudCommand::anchorKeys).optional())
                .note("hud list: every panel with its place, whether it is hidden and whether its module is on. A hidden panel stays hidden when its module is switched on.")
                .note("hud move <panel> <corner>: the panel stacks in that corner with the others, so nothing overlaps. The other anchors (top, left, center, right, bottom) pin it at that spot.")
                .note("hud reset [panel]: back to the default place, and shown again; without a panel, all of them.")
                .runs((a, r) -> run(a.get(0), a.get(1), a.get(2), r));
    }

    private static List<String> anchorKeys() {
        List<String> out = new ArrayList<>();
        for (Anchor a : Anchor.values()) out.add(a.key);
        return out;
    }

    /** Runs one action. Public so the in-game check and the unit tests run exactly what the command runs. */
    public static void run(String action, String id, String anchorKey, Reply r) {
        switch (action) {
            case "list" -> r.info(listing());
            case "reset" -> {
                if (id == null) {
                    HudElements.resetAll();
                    r.success("Every HUD panel is back in its default place and shown");
                } else if (HudElements.reset(id)) r.success(title(id) + " is back in its default place and shown");
                else r.error(unknown(id));
            }
            case "hide", "show" -> {
                if (id == null) {
                    r.error("Say which panel: .hud " + action + " <panel>. .hud list shows them");
                    return;
                }
                boolean hide = action.equals("hide");
                if (!HudElements.setHidden(id, hide)) r.error(unknown(id));
                else r.success(title(id) + " is now " + (hide ? "hidden" : "shown") + (hide ? "" : moduleNote(id)));
            }
            case "move" -> {
                if (id == null || anchorKey == null) {
                    r.error("Say which panel and where: .hud move <panel> <anchor>. .hud list shows the panels");
                    return;
                }
                Anchor anchor = Anchor.byKey(anchorKey);
                if (anchor == null) r.error("'" + anchorKey + "' is not an anchor");
                else if (!HudElements.moveTo(id, anchor)) r.error(unknown(id));
                else r.success(title(id) + " is now " + place(HudElements.get(id)));
            }
            default -> r.error("'" + action + "' is not one of: " + String.join(", ", ACTIONS));
        }
    }

    private static String title(String id) {
        return HudElements.title(id);
    }

    private static String unknown(String id) {
        return "There is no HUD panel called '" + id + "'. .hud list shows them";
    }

    private static String moduleNote(String id) {
        Boolean on = HudElements.get(id).moduleState();
        return on == null || on ? "" : " (its module is off, so nothing is drawn yet)";
    }

    /** Where the element is, in words: its stack, its pin or its default. */
    public static String place(HudElement e) {
        if (e.pin() != null) return "pinned at " + e.pin().anchor().key;
        if (e.absolute() != null) return "pinned (old position, becomes an anchor when first drawn)";
        if (e.stackCorner() != null) return "in the " + Anchor.byCorner(e.stackCorner()).key + " stack";
        if (e.defaultPlace() != null) return "in its own default place";
        return "in the " + Anchor.byCorner(e.defaultCorner()).key + " stack (default)";
    }

    /** One line per element: id, title, place, hidden or shown, and whether its module is on. */
    public static String listing() {
        StringBuilder sb = new StringBuilder();
        for (HudElement e : HudElements.all()) {
            Boolean on = e.moduleState();
            sb.append(sb.length() == 0 ? "" : "\n").append(e.id()).append(": ").append(e.title()).append(", ").append(place(e)).append(", ")
                    .append(e.hidden() ? "hidden" : "shown").append(", module ").append(on == null ? "unknown" : on ? "on" : "off");
        }
        return sb.length() == 0 ? "No HUD panels" : sb.toString();
    }
}
