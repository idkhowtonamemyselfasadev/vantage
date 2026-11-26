package dev.vantage.module.client;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Throws experience bottles faster while you hold the use key. It only lowers the client's own right-click delay counter
 * ({@code Minecraft.rightClickDelay}) while the use key is held with a bottle in hand; it makes no click of its own, throws nothing
 * when the key is up and sends exactly the packets a normal click sends, just more of them per second. Servers see that rate and may
 * punish it. Hook: {@code FastXpMixin}, at the head of {@code handleKeybinds}. The module keeps no state, so switching it off
 * (switch, Panic, the terms gate, leaving the world) is the end of it.
 */
public class FastXp extends Module {
    public final IntSetting delay = add(new IntSetting("Delay", "Ticks between two throws while you hold the use key (vanilla is 4). 0 and 1 both mean one throw per tick, the most one client tick allows", 1, 0, FastXpMath.VANILLA));

    public FastXp() {
        super("Fast XP", "Throws experience bottles faster while you hold the use key. Servers can see the faster rate and may punish it", Category.CLIENT);
        beta();
    }

    /** The module while it is on, else null. */
    public static FastXp active() {
        FastXp f = Modules.get(FastXp.class);
        return f != null && f.on() ? f : null;
    }

    /** True when holding use right now would throw a bottle: the hand a click uses (main, or the off hand when the main one is empty) holds one. */
    public static boolean bottleInUse(Minecraft mc) {
        if (mc.player == null) return false;
        ItemStack main = mc.player.getMainHandItem();
        ItemStack off = mc.player.getOffhandItem();
        return main.is(Items.EXPERIENCE_BOTTLE) || (main.isEmpty() && off.is(Items.EXPERIENCE_BOTTLE));
    }

    /** The counter after this module's say: lowered only while the use key is held, no screen is open and a bottle is the thing a click would throw. */
    public static int adjust(Minecraft mc, int counter) {
        FastXp f = active();
        if (f == null || counter <= 0 || mc.screen != null || !mc.options.keyUse.isDown() || !bottleInUse(mc)) return counter;
        return FastXpMath.cap(counter, f.delay.i());
    }
}
