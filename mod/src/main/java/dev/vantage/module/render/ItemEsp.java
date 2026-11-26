package dev.vantage.module.render;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import dev.vantage.core.Category;
import dev.vantage.core.Config;
import dev.vantage.core.Module;
import dev.vantage.core.store.JsonStore;
import dev.vantage.core.util.Fmt;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Dropped items: a box, the name and what /sell pays. Donut's "Worth: $x" tooltip line is the real value of the whole stack,
 * so it's learned from anything you look through and saved. Until an item has been seen there is no value to show ($?).
 */
public class ItemEsp extends Module {
    private static final Pattern WORTH = Pattern.compile("(?i)worth\\D{0,4}([\\d][\\d,]*(?:\\.\\d+)?)\\s*([KMBT])?\\b");
    private static final int LEARN_TICKS = 40, MAX_LABELS = 48;
    private static final float LABEL_SCALE = 0.6f;
    private static final double LABEL_GAP = 6, LABEL_STEP = 0.32;

    public final NumberSetting range = add(new NumberSetting("Range", "Max distance", 64, 8, 256, 8));
    public final BoolSetting box = add(new BoolSetting("Box", "Outline each dropped item", true));
    public final BoolSetting names = add(new BoolSetting("Name", "Item name and stack size", true));
    public final BoolSetting value = add(new BoolSetting("Sell value", "What /sell pays for the stack, learned from Worth lines you have seen. $? until then", true));
    public final NumberSetting minValue = add(new NumberSetting("Min value", "Hide stacks worth less than this. Items with no known value stay", 0, 0, 100000, 100));
    public final ColorSetting color = add(new ColorSetting("Color", "Box and label colour", 0xFF62D6B0));

    private final Map<String, Double> learned = new HashMap<>();
    private List<ItemEntity> items = List.of();
    private int timer;
    private boolean dirty;

    public ItemEsp() {
        super("Item ESP", "Highlights dropped items with their name and /sell value", Category.RENDER);
        store.load(); // a damaged file is kept aside and the backup used; the user is told
    }

    /** Learned prices: object item id -> price, pretty, with a v field. On JsonStore: written on the storage thread. */
    private final JsonStore store = new JsonStore("learned prices", ItemEsp::file, 1, () -> {
        JsonObject o = new JsonObject();
        for (var e : learned.entrySet()) o.addProperty(e.getKey(), e.getValue());
        return o;
    }, (root, v) -> {
        Map<String, Double> m = new HashMap<>();
        for (var e : root.getAsJsonObject().entrySet()) m.put(e.getKey(), e.getValue().getAsDouble());
        learned.putAll(m);
    }).pretty();

    private static Path file() {
        return Config.dir().resolve("worth.json");
    }

    @Override
    public String info() {
        return items.isEmpty() ? null : String.valueOf(items.size());
    }

    /** The whole stack's value from its tooltip, or -1. */
    public static double tooltipWorth(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return -1;
        for (Component line : lore.lines()) {
            Matcher m = WORTH.matcher(line.getString());
            if (!m.find()) continue;
            double v = Double.parseDouble(m.group(1).replace(",", ""));
            String unit = m.group(2) == null ? "" : m.group(2).toUpperCase();
            return v * switch (unit) { case "K" -> 1e3; case "M" -> 1e6; case "B" -> 1e9; case "T" -> 1e12; default -> 1; };
        }
        return -1;
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Enchants and contents change the price, so those stacks say nothing about the plain item. */
    private void learn(ItemStack stack) {
        if (stack.isEmpty() || stack.isEnchanted() || stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.STORED_ENCHANTMENTS)) return;
        double whole = tooltipWorth(stack);
        if (whole <= 0) return;
        Double old = learned.put(id(stack), whole / stack.getCount());
        if (old == null || Math.abs(old - whole / stack.getCount()) > 1e-6) dirty = true;
    }

    /** Value of a stack from its own tooltip or from a price learned earlier; null when neither is there. */
    public Double valueOf(ItemStack stack) {
        double whole = tooltipWorth(stack);
        if (whole > 0) return whole;
        Double unit = learned.get(id(stack));
        return unit == null ? null : unit * stack.getCount();
    }

    public String text(ItemStack stack) {
        List<String> parts = new ArrayList<>();
        if (names.on()) parts.add(stack.getHoverName().getString() + (stack.getCount() > 1 ? " x" + stack.getCount() : ""));
        if (value.on()) {
            Double v = valueOf(stack);
            parts.add(v == null ? "$?" : Fmt.moneyShort(v));
        }
        return String.join("  ", parts);
    }

    @Override
    public void onTick() {
        Vec3 me = mc.player.position();
        double r = range.get() * range.get();
        List<ItemEntity> out = new ArrayList<>();
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ItemEntity item) || e.distanceToSqr(me) > r) continue;
            Double v = valueOf(item.getItem());
            if (v == null || v >= minValue.get()) out.add(item);
        }
        out.sort(Comparator.comparingDouble(e -> e.distanceToSqr(me)));
        items = out;
        if (++timer < LEARN_TICKS) return;
        timer = 0;
        for (Slot s : mc.player.containerMenu.slots) learn(s.getItem());
        for (ItemEntity e : out) learn(e.getItem());
        if (!dirty) return;
        dirty = false;
        store.markDirty();
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
        items = List.of();
    }

    @Override
    public void onRender3D(R3 r) {
        int c = color.argb(), labels = 0;
        List<Vec3> placed = new ArrayList<>();
        for (ItemEntity e : items) {
            if (e.isRemoved()) continue;
            Vec3 p = e.getPosition(r.partial);
            if (box.on()) EspStyle.box(r, new AABB(p.x - 0.2, p.y, p.z - 0.2, p.x + 0.2, p.y + 0.4, p.z + 0.2), c | 0xFF000000, true);
            if ((!names.on() && !value.on()) || labels++ >= MAX_LABELS) continue;
            // drops pile up, so a label near one already placed goes on top of it
            int under = 0;
            for (Vec3 q : placed) if (q.distanceToSqr(p) < LABEL_GAP * LABEL_GAP) under++;
            placed.add(p);
            r.label(p.add(0, 0.55 + under * LABEL_STEP, 0), text(e.getItem()), c | 0xFF000000);
        }
    }
}
