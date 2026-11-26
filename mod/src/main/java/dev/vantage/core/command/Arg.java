package dev.vantage.core.command;

import java.util.List;

/**
 * One argument of a command: a name and description for help, how to read a typed word into a value, and what to suggest for a
 * partly typed one. Arguments that depend on an earlier one (the setting of the module named before it) get the values read so far.
 */
public abstract class Arg<T> {
    public final String name;
    public final String description;
    private boolean optional;

    protected Arg(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /** The argument may be left out; only the last ones of a command may be optional. */
    public Arg<T> optional() {
        optional = true;
        return this;
    }

    public boolean isOptional() {
        return optional;
    }

    /** True for an argument that takes the rest of the line, spaces included. It has to be the last one. */
    public boolean rest() {
        return false;
    }

    /** In words, what the argument has to be: "a whole number from 1 to 10". */
    public abstract String expects();

    /** Like {@link #expects()}, for an argument whose answer depends on the earlier ones (the value of the setting that was named). */
    public String expects(Parsed before) {
        return expects();
    }

    /** Reads one typed word (or, for a rest argument, the whole rest of the line). A word that cannot be used gives a reason. */
    public abstract Parse<T> parse(String text, Parsed before);

    /** Words that fit a partly typed one. */
    public List<String> suggest(String partial, Parsed before) {
        return List.of();
    }
}
