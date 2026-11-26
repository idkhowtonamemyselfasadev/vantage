package dev.vantage.core.event;

/**
 * A keyboard key changed state, straight from the game's key callback (so it also fires while a screen is open; a listener that
 * should only react in the world checks that itself). {@code action} is GLFW's: 0 released, 1 pressed, 2 repeated while held.
 * One object is reused for every firing; a listener must not keep it.
 */
public final class KeyPressEvent {
    public static final KeyPressEvent INSTANCE = new KeyPressEvent();
    public static final Events.Channel<KeyPressEvent> CHANNEL = Events.channel(KeyPressEvent.class);
    public int key, scancode, action, modifiers;

    private KeyPressEvent() {}

    public static void fire(int key, int scancode, int action, int modifiers) {
        if (CHANNEL.size() == 0) return;
        INSTANCE.key = key;
        INSTANCE.scancode = scancode;
        INSTANCE.action = action;
        INSTANCE.modifiers = modifiers;
        CHANNEL.fire(INSTANCE);
    }
}
