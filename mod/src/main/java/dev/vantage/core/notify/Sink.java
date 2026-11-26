package dev.vantage.core.notify;

/**
 * Where a notification that passed the limiter is shown. Today this is the chat box ({@link ChatSink}); phase 3 routes levels to
 * an on-screen display by giving {@link Notify#sink(Sink)} another object. Nothing else has to change.
 */
public interface Sink {
    /** False while there is nowhere to show anything (no world joined); then {@link Notify} does not even count the call. */
    boolean ready();

    void show(Level level, String text, Sound sound);
}
