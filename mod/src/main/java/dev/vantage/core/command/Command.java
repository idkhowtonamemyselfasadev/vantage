package dev.vantage.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * One chat command: a name (and aliases), a one-line description, typed arguments and the code to run. Built with the fluent calls
 * and finished with {@link #runs}; the registry reads it, nothing changes it afterwards.
 */
public final class Command {
    /** What a command does once its arguments were read. */
    @FunctionalInterface
    public interface Run {
        void run(Parsed args, Reply reply);
    }

    public final String name;
    public final String description;
    private final List<String> aliases = new ArrayList<>(2);
    private final List<Arg<?>> args = new ArrayList<>(3);
    private final List<String> notes = new ArrayList<>(2);
    private BooleanSupplier available = () -> true;
    private Run run;
    private String owner, ownerId;
    private BooleanSupplier ownerOn;
    private boolean whileOff;

    private Command(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public static Command named(String name, String description) {
        return new Command(name, description);
    }

    public Command alias(String... more) {
        aliases.addAll(List.of(more));
        return this;
    }

    public Command arg(Arg<?> arg) {
        args.add(arg);
        return this;
    }

    /** A line of extra explanation shown by help for this command. */
    public Command note(String text) {
        notes.add(text);
        return this;
    }

    /** The command exists only while this holds (a module's command while the module is not a locked beta). */
    public Command availableIf(BooleanSupplier condition) {
        available = condition;
        return this;
    }

    /**
     * Says that this command belongs to a module (called by {@code Module.command}): while {@code on} is false the dispatcher answers
     * "&lt;name&gt; is off. Turn it on with .toggle &lt;id&gt;" itself and the code never runs, unless the command was declared
     * {@link #usableWhileOff}.
     */
    public Command ownedBy(String name, String id, BooleanSupplier on) {
        owner = name;
        ownerId = id;
        ownerOn = on;
        return this;
    }

    /**
     * The command also works while its module is off (listing saved data, setting something up, starting the module itself). Without
     * this a module's command needs the module on, and the dispatcher says so; no command has to check that itself.
     */
    public Command usableWhileOff() {
        whileOff = true;
        return this;
    }

    /** The dispatcher's answer when this module command is called while its module is off, or null when it may run. */
    String offAnswer(char prefix) {
        if (ownerOn == null || whileOff || ownerOn.getAsBoolean()) return null;
        return owner + " is off. Turn it on with " + prefix + "toggle " + ownerId;
    }

    public Command runs(Run code) {
        run = code;
        return this;
    }

    public List<String> aliases() {
        return List.copyOf(aliases);
    }

    public List<Arg<?>> args() {
        return List.copyOf(args);
    }

    public List<String> notes() {
        return List.copyOf(notes);
    }

    public boolean available() {
        return available.getAsBoolean();
    }

    void execute(Parsed parsed, Reply reply) {
        run.run(parsed, reply);
    }

    /** True when the name or an alias is this word (already lower case). */
    boolean answersTo(String word) {
        return name.equals(word) || aliases.contains(word);
    }

    /** ".set <module> <setting> <value>": optional arguments in square brackets, a rest argument with dots. */
    public String usage(char prefix) {
        StringBuilder sb = new StringBuilder().append(prefix).append(name);
        for (Arg<?> a : args) sb.append(' ').append(a.isOptional() ? "[" : "<").append(a.name).append(a.rest() ? "..." : "").append(a.isOptional() ? "]" : ">");
        return sb.toString();
    }

    /** Checks the declaration; throws for a programming mistake, at registration. */
    void validate() {
        if (!name.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("bad command name '" + name + "'");
        for (String a : aliases) if (!a.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("bad alias '" + a + "' of " + name);
        if (run == null) throw new IllegalArgumentException("command " + name + " has no code");
        boolean optionalSeen = false;
        for (int i = 0; i < args.size(); i++) {
            Arg<?> a = args.get(i);
            if (optionalSeen && !a.isOptional()) throw new IllegalArgumentException(name + ": a required argument after an optional one");
            optionalSeen |= a.isOptional();
            if (a.rest() && i != args.size() - 1) throw new IllegalArgumentException(name + ": only the last argument can take the rest of the line");
        }
    }
}
