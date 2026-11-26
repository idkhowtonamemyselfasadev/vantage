package dev.vantage.module.render;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Theme;
import dev.vantage.core.Ui;
import dev.vantage.core.util.Fmt;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Card next to the crosshair with the health, armour and distance of whoever you are looking at. */
public class TargetHud extends Module {
    public final NumberSetting hold = add(new NumberSetting("Hold time", "Seconds the card stays after you look away", 3, 0, 10, 0.5));
    public final NumberSetting range = add(new NumberSetting("Range", "Also pick the nearest player in front within this many blocks", 6, 0, 16, 1));
    public final BoolSetting armor = add(new BoolSetting("Armor", "Show armour and held item", true));
    public final BoolSetting players = add(new BoolSetting("Players only", "Ignore mobs", false));

    private LivingEntity target;
    private long seen;
    private float shownHp = -1, fade;
    /** The trailing bar: holds where the health was for a moment after a hit, then falls after it. */
    private float trailHp = -1;
    private long hitAt;

    public TargetHud() {
        super("Target HUD", "Health, armour and distance of what you look at", Category.RENDER);
        // beside the crosshair, as before: right of it and a little below, 190 wide; not in a corner stack, so it only moves when the user moves it
        hudPanel("targethud", "Target HUD", "Top Left").overScreens().when(() -> target != null && fade >= 0.02f).size(wh -> {
            LivingEntity t = target;
            kit = false;
            if (armor.on()) for (EquipmentSlot sl : SLOTS) if (!t.getItemBySlot(sl).isEmpty()) { kit = true; break; }
            ench = armor.on() ? enchants(t.getMainHandItem()) : "";
            if (ench.isEmpty() && armor.on()) ench = enchants(t.getOffhandItem());
            lastEnchants = ench;
            wh[0] = 190;
            wh[1] = 46 + (kit ? 24 : 0) + (ench.isEmpty() ? 0 : 14);
        }).draw(this::drawCard).element().plain().defaultAt((w, h, sw, sh, out) -> {
            out[0] = sw / 2 + 18;
            out[1] = sh / 2 + 14;
        });
    }

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    /** What the card shows below the health line, worked out when it is measured: the kit row and the enchants of what they hold. */
    private boolean kit;
    private float step;
    private String ench = "";

    private boolean valid(Entity e) {
        return e instanceof LivingEntity l && l != mc.player && l.isAlive() && (!players.on() || e instanceof AbstractClientPlayer);
    }

    @Override
    public void onTick() {
        Entity e = mc.crosshairPickEntity;
        if (!valid(e) && range.get() > 0) {
            // nearest player roughly in front of you
            double best = range.get() * range.get();
            var look = mc.player.getLookAngle();
            for (var pl : mc.level.players()) {
                if (!valid(pl) || dev.vantage.core.Friends.is(pl)) continue;
                double d = pl.distanceToSqr(mc.player);
                var to = pl.position().subtract(mc.player.position()).normalize();
                if (d < best && to.dot(look) > 0.6) { best = d; e = pl; }
            }
        }
        if (valid(e)) {
            if (e != target) shownHp = trailHp = -1;
            target = (LivingEntity) e;
            seen = System.currentTimeMillis();
        } else if (target != null && (!target.isAlive() || target.isRemoved()
                || System.currentTimeMillis() - seen > hold.get() * 1000)) {
            target = null;
        }
    }

    @Override
    protected void onDisable() {
        target = null;
    }

    /** For the rig. */
    public static LivingEntity lastTarget;
    public static String lastEnchants = "";
    public static int drawnItems;

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        step = Math.min(1f, delta.getRealtimeDeltaTicks() * 0.3f);
        fade += ((target != null ? 1f : 0f) - fade) * step;
        if (target == null || fade < 0.02f) return;
        lastTarget = target;
    }

    private void drawCard(GuiGraphics g, float x, float y, float w, float h) {
        LivingEntity t = target;
        int a = Math.round(fade * 255);
        Ui.alpha = fade;
        int accent = Theme.accent();
        y += 6 * (1 - fade); // lifts into place
        Ui.card(g, x, y, w, h, accent);

        float tx = x + 12;
        if (t instanceof AbstractClientPlayer pl) {
            Ui.rr(g, x + 10, y + 8, 30, 30, 5, 0xFF0A0D1E, 0);
            PlayerFaceRenderer.draw(g, pl.getSkin(), (int) x + 12, (int) y + 10, 26);
            tx = x + 48;
        }
        String name = dev.vantage.core.Streamer.scrub(t.getDisplayName().getString());
        Ui.text(g, Ui.fit(name, w - (tx - x) - 56, 1.05f), tx, y + 8, Ui.TEXT, 1.05f, true);
        // ping for players, top right
        if (t instanceof AbstractClientPlayer pl && mc.getConnection() != null) {
            var info = mc.getConnection().getPlayerInfo(pl.getUUID());
            if (info != null) {
                int ms = info.getLatency();
                Ui.right(g, ms + " ms", x + w - 10, y + 9, ms < 80 ? Ui.GREEN : ms < 180 ? Ui.YELLOW : Ui.RED, 0.8f, false);
            }
        }

        float max = Math.max(1f, t.getMaxHealth());
        float hp = t.getHealth();
        float abs = t.getAbsorptionAmount();
        if (shownHp < 0) shownHp = hp;
        if (trailHp < 0) trailHp = hp;
        if (hp < shownHp - 0.01f) hitAt = System.currentTimeMillis();
        shownHp += (hp - shownHp) * Math.min(1f, step * 1.5f);
        if (hp > trailHp) trailHp = hp;
        else if (System.currentTimeMillis() - hitAt > 400) trailHp += (hp - trailHp) * step * 0.5f;
        float barW = x + w - 10 - tx;
        float by = y + 22;
        int hpCol = hp / max > 0.6f ? Ui.GREEN : hp / max > 0.3f ? Ui.YELLOW : Ui.RED;
        Ui.rr(g, tx, by, barW, 4, 2, 0xFF262A4C, 0);
        if (trailHp > shownHp + 0.05f) Ui.rr(g, tx, by, Math.max(4, barW * Math.min(1f, trailHp / max)), 4, 2, Ui.fade(Ui.RED, 0.55f), 0);
        Ui.rr(g, tx, by, Math.max(4, barW * Math.min(1f, shownHp / max)), 4, 2, hpCol, 0);
        if (abs > 0) Ui.rr(g, tx, by + 5, Math.max(2, barW * Math.min(1f, abs / max)), 1.5f, 0.7f, Ui.YELLOW, 0);

        int dist = Math.round(mc.player.distanceTo(t));
        String info = Fmt.fixed(hp, 1) + (abs > 0 ? " +" + Fmt.fixed(abs, 0) : "") + " hp   " + dist + "m";
        float mine = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        String vs = mine > hp + abs + 1 ? "winning" : mine + 1 < hp + abs ? "losing" : "even";
        int vsCol = vs.equals("winning") ? Ui.GREEN : vs.equals("losing") ? Ui.RED : Ui.SUB;
        Ui.text(g, info, tx, by + 9, Ui.SUB, 0.85f, false);
        Ui.right(g, vs, x + w - 10, by + 9, vsCol, 0.85f, false);

        if (kit && a > 200) {
            // armour and hands as item icons with vanilla durability bars, a hairline before the hands
            int ix = (int) x + 10, iy = (int) y + 44;
            for (int i = 0; i < SLOTS.length; i++) {
                ItemStack st = t.getItemBySlot(SLOTS[i]);
                if (i == 4) { Ui.rect(g, ix + 1, iy + 2, 1, 14, Ui.fade(Ui.SUB, 0.4f)); ix += 6; }
                if (st.isEmpty()) { ix += 19; continue; }
                g.renderItem(st, ix, iy);
                g.renderItemDecorations(mc.font, st, ix, iy);
                drawnItems++;
                ix += 19;
            }
        }
        if (!ench.isEmpty()) Ui.text(g, Ui.fit(ench, w - 20, 0.8f), x + 10, y + h - 16, Ui.SUB, 0.8f, false);
        Ui.alpha = 1f;
    }

    /** "Sharp V, FA II, Unb III": the enchantments of a stack, shortest names first-letter style. */
    public static String enchants(ItemStack st) {
        if (st.isEmpty()) return "";
        ItemEnchantments e = EnchantmentHelper.getEnchantmentsForCrafting(st);
        if (e.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var en : e.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            String path = en.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
            sb.append(shortName(path)).append(' ').append(roman(en.getIntValue()));
        }
        return sb.toString();
    }

    private static String shortName(String path) {
        return switch (path) {
            case "sharpness" -> "Sharp"; case "smite" -> "Smite"; case "bane_of_arthropods" -> "Bane"; case "knockback" -> "KB"; case "fire_aspect" -> "FA";
            case "looting" -> "Loot"; case "sweeping_edge" -> "Sweep"; case "unbreaking" -> "Unb"; case "mending" -> "Mend"; case "efficiency" -> "Eff";
            case "fortune" -> "Fort"; case "silk_touch" -> "Silk"; case "protection" -> "Prot"; case "fire_protection" -> "FProt"; case "blast_protection" -> "BProt";
            case "projectile_protection" -> "PProt"; case "feather_falling" -> "FF"; case "thorns" -> "Thorns"; case "depth_strider" -> "DS"; case "frost_walker" -> "FW";
            case "respiration" -> "Resp"; case "aqua_affinity" -> "AA"; case "soul_speed" -> "SS"; case "swift_sneak" -> "Swift"; case "power" -> "Pow";
            case "punch" -> "Punch"; case "flame" -> "Flame"; case "infinity" -> "Inf"; case "multishot" -> "Multi"; case "piercing" -> "Pierce"; case "quick_charge" -> "QC";
            case "density" -> "Dens"; case "breach" -> "Breach"; case "wind_burst" -> "WB"; case "loyalty" -> "Loy"; case "riptide" -> "Rip"; case "channeling" -> "Chan";
            case "impaling" -> "Imp"; case "lure" -> "Lure"; case "luck_of_the_sea" -> "Luck"; case "binding_curse" -> "Bind"; case "vanishing_curse" -> "Vanish";
            default -> path.length() > 5 ? Character.toUpperCase(path.charAt(0)) + path.substring(1, 5) : Character.toUpperCase(path.charAt(0)) + path.substring(1);
        };
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }
}
