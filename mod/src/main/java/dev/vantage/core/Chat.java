package dev.vantage.core;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

/** Local-only chat lines. These go straight into the chat box and are never sent anywhere. */
public final class Chat {
    public static void info(String text) {
        send(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    public static void send(Component body) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        MutableComponent line = Component.literal("[" + Vantage.NAME + "] ").withColor(Theme.accent()).append(body);
        mc.gui.getChat().addMessage(line);
    }

    /** Chat line, plus a short UI sound if Ping sound is on in HUD. */
    public static void alert(Component body, float pitch) {
        send(body);
        if (!Modules.get(dev.vantage.module.client.HudModule.class).pingSound.on()) return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch));
    }

    public static String coords(int x, int y, int z) {
        return Streamer.coords(x + " " + y + " " + z); // streamer mode: "hidden"
    }

    private Chat() {}
}
