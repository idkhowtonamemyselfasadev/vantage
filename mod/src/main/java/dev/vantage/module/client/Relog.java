package dev.vantage.module.client;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Theme;
import dev.vantage.core.util.Fmt;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Relog: leave the server and join it again in one press (bind a key or toggle it in the GUI).
 * Also puts a Reconnect button on the disconnect screen and can reconnect on its own after a kick.
 * Uses the normal vanilla join, exactly like clicking the server in the list.
 */
public class Relog extends Module {
    public final NumberSetting delay = add(new NumberSetting("Delay", "Seconds to wait before joining again", 1, 0, 30, 0.5));
    public final BoolSetting button = add(new BoolSetting("Reconnect button", "Adds Reconnect to the disconnect screen", true));
    public final BoolSetting auto = add(new BoolSetting("Auto reconnect", "Rejoin by itself after a kick or lost connection", false));
    public final NumberSetting autoDelay = add(new NumberSetting("Auto delay", "Seconds before the auto rejoin", 5, 1, 120, 1)).visibleIf(auto::on);

    private static ServerData last;
    private static long pendingAt = -1;
    private static boolean pendingAuto;

    public Relog() {
        super("Relog", "Leave and rejoin the server in one press, plus reconnect after kicks", Category.CLIENT);
    }

    private static Relog get() {
        return Modules.get(Relog.class);
    }

    /** Pressing the key / clicking it runs one relog and switches itself back off. */
    @Override
    protected void onEnable() {
        relog();
        setEnabled(false);
    }

    public void relog() {
        if (mc.level == null || mc.isLocalServer() || mc.getCurrentServer() == null) {
            Chat.info("relog only works on a server");
            return;
        }
        last = mc.getCurrentServer();
        mc.disconnectFromWorld(Component.literal("Relogging"));
        mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        pendingAt = System.currentTimeMillis() + Math.round(delay.get() * 1000);
        pendingAuto = false;
    }

    private static void connect() {
        Minecraft mc = Minecraft.getInstance();
        pendingAt = -1;
        if (last == null) return;
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(last.ip), last, false, null);
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != null && !mc.isLocalServer() && mc.getCurrentServer() != null) last = mc.getCurrentServer();
            if (pendingAt < 0) return;
            // auto reconnect is cancelled by leaving the disconnect screen (Back to server list)
            if (pendingAuto && !(mc.screen instanceof DisconnectedScreen)) { pendingAt = -1; return; }
            if (System.currentTimeMillis() >= pendingAt) connect();
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof DisconnectedScreen) || last == null) return;
            Relog r = get();
            if (r == null) return;
            if (pendingAt >= 0 && !pendingAuto) return; // our own relog is running
            if (r.button.on()) {
                int bottom = h / 2;
                for (AbstractWidget b : Screens.getButtons(screen)) bottom = Math.max(bottom, b.getY() + b.getHeight());
                Screens.getButtons(screen).add(Button.builder(Component.literal("Reconnect"), b -> connect())
                        .bounds(w / 2 - 100, bottom + 4, 200, 20).build());
            }
            if (r.auto.on()) {
                pendingAt = System.currentTimeMillis() + Math.round(r.autoDelay.get() * 1000);
                pendingAuto = true;
            }
            ScreenEvents.afterRender(screen).register((s, g, mx, my, d) -> {
                if (pendingAt < 0 || !pendingAuto) return;
                long left = Math.max(0, pendingAt - System.currentTimeMillis());
                String t = "Reconnecting to " + last.ip + " in " + Fmt.fixed(left / 1000.0, 1) + "s";
                dev.vantage.core.Ui.begin(g);
                float tw = dev.vantage.core.Ui.width(t, 0.95f, false) + 24;
                dev.vantage.core.Ui.rr(g, dev.vantage.core.Ui.W() / 2 - tw / 2, 10, tw, 20, 10, dev.vantage.core.Ui.GLASS, dev.vantage.core.Ui.BORDER);
                dev.vantage.core.Ui.center(g, t, dev.vantage.core.Ui.W() / 2, 16, Theme.accent(), 0.95f, false);
                dev.vantage.core.Ui.end(g);
            });
        });
    }
}
