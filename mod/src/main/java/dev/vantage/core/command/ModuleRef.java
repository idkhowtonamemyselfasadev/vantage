package dev.vantage.core.command;

import java.util.List;

/** What the command system needs from a module, so that it can be unit tested with fake ones. */
public interface ModuleRef extends Named {
    String description();

    /** The title of its category. */
    String category();

    boolean on();

    void setOn(boolean on);

    /** The settings a command may offer: shown to the user, saved, and of a kind that can be typed. */
    List<? extends SettingRef> settings();
}
