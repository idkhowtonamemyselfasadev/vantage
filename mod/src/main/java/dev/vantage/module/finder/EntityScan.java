package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Theme;
import dev.vantage.core.hud.HudElements;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Counts every entity the client knows about, by type, and flags the ones that usually mean players were
 * there: chest donkeys/llamas, chest and hopper minecarts, item frames with items, armor stands, name-tagged
 * mobs and dropped shulker boxes.
 */
public class EntityScan extends Module {
    public final NumberSetting range = add(new NumberSetting("Range", "Blocks around you (0 = everything loaded)", 0, 0, 512, 16));
    public final BoolSetting list = add(new BoolSetting("HUD list", "Entity counts on screen", true));
    public final NumberSetting lines = add(new NumberSetting("Lines", "Types shown in the HUD list", 8, 3, 20, 1)).visibleIf(list::on);
    public final ModeSetting corner = add(new ModeSetting("HUD corner", "Where the list goes", "Top Left", Hud.CORNERS)).visibleIf(list::on);
    public final BoolSetting alert = add(new BoolSetting("Alert", "Chat + sound for player-made things", true));
    public final BoolSetting highlight = add(new BoolSetting("Highlight", "Box + label on player-made things", true));
    public final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0xFFE0BD62));

    private List<Map.Entry<String, Integer>> counts = List.of();
    private int total;
    private List<Entity> flagged = List.of();
    private final Set<UUID> alerted = new HashSet<>();
    private int timer;

    static {
        HudElements.declare("entities", "Entities", "Top Left", EntityScan.class);
    }

    public EntityScan() {
        super("Entity Scan", "Counts entities and flags signs of players", Category.FINDER);
    }

    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    private int cartsNew;
    private long cartFirst;
    private double cartNearD = Double.MAX_VALUE;
    private net.minecraft.core.BlockPos cartNear;

    private void forgetWorld() {
        cartsNew = 0; cartFirst = 0; cartNearD = Double.MAX_VALUE; cartNear = null;
        alerted.clear();
        counts = List.of();
        flagged = List.of();
    }

    @Override
    public String info() {
        return String.valueOf(total);
    }

    /** Why an entity looks player-made, or null. */
    public static String flag(Entity e) {
        if (e instanceof AbstractChestedHorse h && h.hasChest()) return "chest " + e.getType().getDescription().getString().toLowerCase();
        if (e instanceof MinecartChest) return "chest minecart";
        if (e instanceof MinecartHopper) return "hopper minecart";
        if (e instanceof ItemFrame f && !f.getItem().isEmpty()) return "frame: " + f.getItem().getHoverName().getString();
        if (e instanceof ArmorStand) return "armor stand";
        if (e instanceof ItemEntity it && it.getItem().getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)
            return "dropped shulker";
        if (!(e instanceof Player) && e.hasCustomName()) return "named \"" + e.getCustomName().getString() + "\"";
        return null;
    }

    @Override
    public void onTick() {
        if (--timer > 0) return;
        timer = 10;
        Map<String, Integer> m = new HashMap<>();
        List<Entity> fl = new ArrayList<>();
        double r2 = range.get() * range.get();
        int n = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (range.get() > 0 && e.distanceToSqr(mc.player) > r2) continue;
            n++;
            m.merge(e.getType().getDescription().getString(), 1, Integer::sum);
            String why = flag(e);
            if (why == null) continue;
            fl.add(e);
            if (alert.on() && alerted.add(e.getUUID())) {
                var p = e.blockPosition();
                if (why.equals("chest minecart")) { cartsNew++; double d = e.distanceTo(mc.player); if (d < cartNearD) { cartNearD = d; cartNear = p; } cartFirst = cartFirst == 0 ? System.currentTimeMillis() : cartFirst; continue; } // a mineshaft holds dozens: one line below
                Chat.alert(Component.literal(why).withColor(color.argb()).append(Component.literal("  " + Chat.coords(p.getX(), p.getY(), p.getZ())
                        + "  " + (int) e.distanceTo(mc.player) + "m").withColor(Theme.TEXT)), 1.0f);
            }
        }
        if (cartsNew > 0 && System.currentTimeMillis() - cartFirst > 4000) { // chest minecarts from world generation come in dozens: one line
            Chat.alert(Component.literal("chest minecart").withColor(color.argb()).append(Component.literal((cartsNew > 1 ? " x" + cartsNew + ", nearest " : "  ")
                    + Chat.coords(cartNear.getX(), cartNear.getY(), cartNear.getZ()) + "  " + (int) cartNearD + "m").withColor(Theme.TEXT)), 1.0f);
            cartsNew = 0; cartFirst = 0; cartNearD = Double.MAX_VALUE; cartNear = null;
        }
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        counts = l;
        total = n;
        flagged = fl;
    }

    @Override
    public void onRender3D(R3 r) {
        if (!highlight.on()) return;
        int c = color.argb();
        for (Entity e : flagged) {
            if (e.isRemoved()) continue;
            Vec3 lerp = e.getPosition(r.partial);
            EspStyle.box(r, e.getBoundingBox().move(lerp.subtract(e.position())), c, true);
            String why = flag(e);
            if (why != null) r.label(lerp.add(0, e.getBbHeight() + 0.4, 0), why, c);
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (!list.on()) return;
        int shown = Math.min(lines.i(), counts.size());
        float h = 30 + shown * 12 + (flagged.isEmpty() ? 0 : 12);
        String cn = corner.get();
        float w = 150;
        for (int i = 0; i < shown; i++) w = Math.max(w, dev.vantage.core.Ui.width(counts.get(i).getKey(), 0.9f, false) + 60);
        float s = dev.vantage.core.Ui.scale();
        float[] at = Hud.slot("entities", cn, w, h);
        if (at == null) return;
        dev.vantage.core.Ui.begin(g);
        float x = at[0], y = at[1];
        dev.vantage.core.Ui.panel(g, x, y, w, h);
        dev.vantage.core.Ui.text(g, "ENTITIES", x + 10, y + 8, dev.vantage.core.Ui.FAINT, 0.85f, false);
        dev.vantage.core.Ui.right(g, String.valueOf(total), x + w - 10, y + 7, dev.vantage.core.Ui.TEXT, 1.2f, false);
        dev.vantage.core.Ui.hline(g, x + 10, y + 23, w - 20, dev.vantage.core.Ui.LINE);
        float yy = y + 28;
        for (int i = 0; i < shown; i++) {
            var e = counts.get(i);
            dev.vantage.core.Ui.text(g, e.getKey(), x + 10, yy, dev.vantage.core.Ui.SUB, 0.9f, false);
            dev.vantage.core.Ui.right(g, String.valueOf(e.getValue()), x + w - 10, yy, dev.vantage.core.Ui.TEXT, 0.9f, false);
            yy += 12;
        }
        if (!flagged.isEmpty()) dev.vantage.core.Ui.text(g, flagged.size() + " player-made", x + 10, yy, color.argb(), 0.9f, false);
        dev.vantage.core.Ui.end(g);
    }

    @Override
    public int labelClass() {
        return LABEL_EVIDENCE;
    }
}
