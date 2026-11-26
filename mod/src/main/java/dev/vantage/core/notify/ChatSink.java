package dev.vantage.core.notify;

import dev.vantage.core.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The chat box as the place for notifications: every line gets the {@code [Vantage]} prefix from {@link Chat#send} and one colour
 * per level (info grey, success green, warning yellow, error red). A sound goes through {@link Chat#alert}, which also honours the
 * HUD's "ping sound" switch.
 */
public final class ChatSink implements Sink {
    @Override
    public boolean ready() {
        return Minecraft.getInstance().player != null;
    }

    @Override
    public void show(Level level, String text, Sound sound) {
        Component body = Component.literal(text).withStyle(colour(level));
        if (sound != null) Chat.alert(body, sound.pitch());
        else Chat.send(body);
    }

    static ChatFormatting colour(Level level) {
        return switch (level) {
            case INFO -> ChatFormatting.GRAY;
            case SUCCESS -> ChatFormatting.GREEN;
            case WARNING -> ChatFormatting.YELLOW;
            case ERROR -> ChatFormatting.RED;
        };
    }
}
