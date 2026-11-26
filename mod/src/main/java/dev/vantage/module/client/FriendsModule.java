package dev.vantage.module.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vantage.core.Category;
import dev.vantage.core.Friends;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Presence;
import dev.vantage.core.Toasts;
import dev.vantage.gui.FriendsScreen;
import dev.vantage.setting.ActionSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

/** Its key (or a middle click) adds or removes the player you're looking at. Nothing is sent: the list only changes what this
 *  mod shows: a star in the tab list and on name tags, their own colour in Entity ESP, and no finder ever flags them. */
public class FriendsModule extends Module {
    private static final double REACH = 64, AIM = 0.995;

    public final BoolSetting middleClick = add(new BoolSetting("Middle click", "Middle-click a player to add or remove them", true));
    public final BoolSetting mark = add(new BoolSetting("Star", "A green star before friends in the tab list and on name tags", true));
    public final ColorSetting color = add(new ColorSetting("ESP colour", "How friends look in Entity ESP (other players go red to green by distance)", 0xFF4FC3FF));
    public final BoolSetting nearbyPrompt = add(new BoolSetting("Nearby prompt", "A Vantage user who is not your friend comes within 6 blocks: one toast asks if you want to add them (once per player per session). Nothing is added until you press the key or click", true));
    /** Unbound by default; set it in Controls. */
    public static final KeyMapping ADD_NEAREST = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.vantage.add_nearest", InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
    private static final Set<UUID> PROMPTED = new HashSet<>();
    /** For the rig: names we showed the nearby toast for. */
    public static final List<String> prompts = new ArrayList<>();
    private boolean middleWas;

    static {
        ClientTickEvents.END_CLIENT_TICK.register(FriendsModule::keyTick);
    }

    public FriendsModule() {
        super("Friends", "Friends never get flagged and wear a star. Key or middle click: add or remove who you're looking at. In chat: .friend Name", Category.CLIENT);
        bind = GLFW.GLFW_KEY_F7;
        for (var c : Friends.commands()) command(c.usableWhileOff()); // the friend list is kept whether the module is on or not
        add(new ActionSetting("Open list", "Add by name, or click anyone online", () -> mc.setScreen(new FriendsScreen(mc.screen))));
    }

    @Override
    public boolean holdKey() {
        return true;
    }

    @Override
    public String info() {
        return String.valueOf(Friends.list().size());
    }

    @Override
    public void onBindPress() {
        toggleLooked();
    }

    @Override
    public void onTick() {
        boolean down = mc.screen == null && GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        if (down && !middleWas && middleClick.on() && looked() != null) toggleLooked();
        middleWas = down;
    }

    /** The player closest to the crosshair within 64 blocks and about 6 degrees, or null. */
    private Player looked() {
        if (mc.player == null || mc.level == null) return null;
        Player best = null;
        double bestAim = AIM;
        var look = mc.player.getLookAngle();
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.distanceTo(mc.player) > REACH) continue;
            double aim = p.getEyePosition().subtract(mc.player.getEyePosition()).normalize().dot(look);
            if (aim > bestAim) { bestAim = aim; best = p; }
        }
        return best;
    }

    private void toggleLooked() {
        Player best = looked();
        if (best == null) { Toasts.warn("Friends", "Look at a player first"); return; }
        String n = best.getGameProfile().name();
        Friends.remember(n, best.getUUID());
        Toasts.ok("Friends", n + (Friends.toggle(n) ? " added" : " removed"));
    }

    private static void keyTick(Minecraft mc) {
        FriendsModule m = Modules.get(FriendsModule.class);
        if (m == null || mc.player == null || mc.level == null) return;
        while (ADD_NEAREST.consumeClick()) if (mc.screen == null) addNearest(8);
        if (!m.on() || !m.nearbyPrompt.on() || mc.screen != null || mc.player.tickCount % 10 != 0) return;
        var n = Presence.nearestNonFriend(6);
        if (n != null && PROMPTED.add(n.id())) {
            prompts.add(n.name());
            Toasts.ok("Vantage nearby", promptText(n.name()));
        }
    }

    /** The toast body: the key when "Add nearest Vantage player" is bound, else how to add by hand. */
    public static String promptText(String name) {
        String how = ADD_NEAREST.isUnbound() ? "Look at them and press F7, or open Friends" : "Press [" + ADD_NEAREST.getTranslatedKeyMessage().getString() + "] to add";
        return name + " is on Vantage: add as friend? " + how;
    }

    /** Adds the nearest Vantage user (not a friend yet) within `max` blocks. Returns who, or null. Only ever called by a key press. */
    public static String addNearest(double max) {
        var n = Presence.nearestNonFriend(max);
        if (n == null) { Toasts.warn("Friends", "No Vantage player nearby"); return null; }
        Presence.addFriend(n.id());
        Toasts.ok("Friends", "Added " + n.name());
        return n.name();
    }

    /** The friend star before a name (tab list, name tags), or the name as it was. */
    public static Component decorate(String name, Component shown) {
        FriendsModule m = Modules.get(FriendsModule.class);
        if (shown == null || m == null || !m.mark.on() || !Friends.is(name) || dev.vantage.core.Panic.active()) return shown;
        return Component.empty().append(Component.literal("★ ").withStyle(ChatFormatting.GREEN)).append(shown);
    }

    /** Entity ESP colour for friends. */
    public static int espColor() {
        FriendsModule m = Modules.get(FriendsModule.class);
        return m == null ? 0xFF4FC3FF : m.color.argb() | 0xFF000000;
    }
}
