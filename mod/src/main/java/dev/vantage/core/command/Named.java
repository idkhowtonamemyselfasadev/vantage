package dev.vantage.core.command;

/** Something a command can name: a module or a setting. The stable id is always accepted, the display name in any case without spaces. */
public interface Named {
    String id();

    String name();
}
