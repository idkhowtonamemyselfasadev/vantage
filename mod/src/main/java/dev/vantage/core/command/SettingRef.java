package dev.vantage.core.command;

import java.util.List;

/** What the command system needs from a setting, so that it can be unit tested with fake ones. */
public interface SettingRef extends Named {
    String description();

    /** In words, what a value must look like: "a whole number from 1 to 256", "on or off". */
    String expects();

    /** The value now, as it is typed. */
    String current();

    /** Null when the text has the right shape for this setting (a number, one of the modes), otherwise the reason. Changes nothing. */
    String problem(String text);

    /** Sets the value: null when it was taken, otherwise the setting's own reason for refusing it. */
    String apply(String text);

    /** Values that fit the partly typed text, for tab completion (modes, on and off). Empty when anything goes. */
    List<String> suggest(String partial);

    void reset();

    boolean isDefault();
}
