package dev.vantage.core.command;

/** The text of the help command: every command on one line, or one command in detail. */
public final class Help {
    private Help() {}

    /** Every available command in registration order: its usage line and its one-line description. */
    public static String listing(Commands reg) {
        char p = CommandLine.prefix();
        StringBuilder sb = new StringBuilder("Commands (" + p + "help <command> explains one):");
        for (Command c : reg.available()) sb.append("\n  ").append(c.usage(p)).append("  -  ").append(c.description);
        return sb.toString();
    }

    /** The usage line, the description, the aliases, each argument with what it expects, and the command's notes. */
    public static String explain(Command c) {
        char p = CommandLine.prefix();
        StringBuilder sb = new StringBuilder(c.usage(p)).append("\n  ").append(c.description);
        if (!c.aliases().isEmpty()) sb.append("\n  also: ").append(String.join(", ", c.aliases().stream().map(x -> p + x).toList()));
        for (Arg<?> a : c.args()) sb.append("\n  ").append(a.isOptional() ? "[" : "<").append(a.name).append(a.isOptional() ? "]" : ">").append(": ").append(a.description).append(" (").append(a.expects()).append(")");
        for (String n : c.notes()) sb.append("\n  ").append(n);
        return sb.toString();
    }
}
