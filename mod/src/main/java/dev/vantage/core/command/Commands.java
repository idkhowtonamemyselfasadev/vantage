package dev.vantage.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The registry of chat commands and the one dispatcher. A line that looks like a command ({@link CommandLine#isAttempt}) is always
 * handled here and never goes anywhere else, whether the command exists or not; {@link #run} says "not mine" only for ordinary chat.
 * Everything the user sees is said through a {@link Reply}.
 */
public final class Commands {
    private static final Logger LOG = LoggerFactory.getLogger("vantage.commands");
    private static final Commands MAIN = new Commands();

    private final List<Command> all = new ArrayList<>();
    private Reply reply = new Reply() {
        @Override public void info(String text) { LOG.info("no reply path set: {}", text); }
        @Override public void success(String text) { info(text); }
        @Override public void error(String text) { info(text); }
    };

    /** The registry the game uses. Tests make their own with the constructor. */
    public static Commands main() {
        return MAIN;
    }

    public Commands() {}

    /** Where answers go when {@link #run(String)} is used. Set once at start; the notification system will take it over here. */
    public void setReply(Reply r) {
        reply = r;
    }

    public Reply reply() {
        return reply;
    }

    public void register(Command c) {
        c.validate();
        for (Command other : all) {
            if (other.answersTo(c.name)) throw new IllegalArgumentException("command '" + c.name + "' is already taken by " + other.name);
            for (String a : c.aliases()) if (other.answersTo(a)) throw new IllegalArgumentException("alias '" + a + "' of " + c.name + " is already taken by " + other.name);
        }
        all.add(c);
    }

    /** The commands that exist now, in the order they were registered (core first, then the modules'). */
    public List<Command> available() {
        List<Command> out = new ArrayList<>(all.size());
        for (Command c : all) if (c.available()) out.add(c);
        return out;
    }

    /** The command a word names, or null. Case does not matter. */
    public Command find(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (Command c : all) if (c.available() && c.answersTo(w)) return c;
        return null;
    }

    public boolean run(String line) {
        return run(line, reply);
    }

    /** Runs a typed line. Returns true when it was a command attempt (it is answered, whatever happened), false for ordinary chat. */
    public boolean run(String line, Reply out) {
        if (!CommandLine.isAttempt(line)) return false;
        String t = line.strip();
        int end = 1;
        while (end < t.length() && !Character.isWhitespace(t.charAt(end))) end++;
        String word = t.substring(1, end).toLowerCase(Locale.ROOT);
        String rest = t.substring(end);
        Command c = find(word);
        if (dev.vantage.core.Terms.off()) { // the terms gate: no Vantage command runs, but chat that is not one of ours (a server's own .command) goes through untouched
            if (c == null) return false;
            out.error(dev.vantage.core.Terms.OFF_LINE);
            return true;
        }
        if (c == null) {
            out.error(unknown(word));
            return true;
        }
        String off = c.offAnswer(CommandLine.prefix());
        if (off != null) { // a module's command while the module is off: said here once, so no module has to guard its commands itself
            out.error(off);
            return true;
        }
        Parsed parsed = new Parsed();
        int pos = 0;
        List<Arg<?>> args = c.args();
        for (Arg<?> a : args) {
            pos = skipSpace(rest, pos);
            if (pos >= rest.length()) {
                if (!a.isOptional()) {
                    out.error("Missing <" + a.name + ">: " + a.description + ". Usage: " + c.usage(CommandLine.prefix()));
                    return true;
                }
                parsed.add(null);
                continue;
            }
            String token;
            if (a.rest()) {
                token = unquote(rest.substring(pos).strip());
                pos = rest.length();
            } else {
                int e = tokenEnd(rest, pos);
                if (e < 0) {
                    out.error("Wrong <" + a.name + ">: the quote is never closed. Usage: " + c.usage(CommandLine.prefix()));
                    return true;
                }
                token = unquote(rest.substring(pos, e));
                pos = e;
            }
            Parse<?> p = a.parse(token, parsed);
            if (!p.ok()) {
                out.error("Wrong <" + a.name + ">: " + p.problem() + ". Expected " + a.expects(parsed) + ". Usage: " + c.usage(CommandLine.prefix()));
                return true;
            }
            parsed.add(p.value());
        }
        pos = skipSpace(rest, pos);
        if (pos < rest.length()) {
            String extra = rest.substring(pos).strip();
            out.error("Too many arguments: '" + extra + "' is not expected. Usage: " + c.usage(CommandLine.prefix()));
            return true;
        }
        try {
            c.execute(parsed, out);
        } catch (RuntimeException e) {
            LOG.error("command {} failed", c.name, e);
            out.error(CommandLine.prefix() + c.name + " ran into an error. The details are in the log.");
        }
        return true;
    }

    /** The answer for a word that is no command: the nearest command when one is close, otherwise a pointer to help. */
    public String unknown(String word) {
        List<String> names = new ArrayList<>();
        for (Command c : available()) {
            names.add(c.name);
            names.addAll(c.aliases());
        }
        String near = Names.closest(word, names);
        char p = CommandLine.prefix();
        String head = "Unknown command " + p + word + ". ";
        if (near == null) return head + "Type " + p + "help for the list of commands.";
        Command c = find(near);
        return head + "Did you mean " + p + (c == null ? near : c.name) + "?";
    }

    /**
     * What tab completion offers for a line with the cursor at {@code cursor}: command names for the first word, then what the
     * argument under the cursor suggests, given the earlier ones. Nothing when an earlier argument is wrong or the line is not a command.
     */
    public Suggestions suggest(String line, int cursor) {
        int at = Math.max(0, Math.min(cursor, line.length()));
        String head = line.substring(0, at);
        int lead = skipSpace(head, 0);
        if (lead >= head.length() || head.charAt(lead) != CommandLine.prefix()) return Suggestions.NONE;
        int wordStart = lead + 1, wordEnd = wordStart;
        while (wordEnd < head.length() && !Character.isWhitespace(head.charAt(wordEnd))) wordEnd++;
        if (wordEnd == head.length()) {
            List<String> names = new ArrayList<>();
            for (Command c : available()) names.add(c.name);
            List<String> hits = Names.startingWith(head.substring(wordStart), names);
            for (Command c : available()) for (String a : c.aliases()) if (a.startsWith(head.substring(wordStart).toLowerCase(Locale.ROOT)) && !hits.contains(a)) hits.add(a);
            return new Suggestions(wordStart, at, hits);
        }
        Command c = find(head.substring(wordStart, wordEnd));
        if (c == null) return Suggestions.NONE;
        Parsed parsed = new Parsed();
        int pos = wordEnd;
        for (Arg<?> a : c.args()) {
            pos = skipSpace(head, pos);
            if (pos >= head.length()) return new Suggestions(pos, at, a.suggest("", parsed));
            if (a.rest()) return new Suggestions(pos, at, a.suggest(head.substring(pos), parsed));
            int e = tokenEnd(head, pos);
            if (e < 0 || e == head.length()) return new Suggestions(pos, at, a.suggest(head.charAt(pos) == '"' ? head.substring(pos + 1) : head.substring(pos), parsed));
            Parse<?> p = a.parse(unquote(head.substring(pos, e)), parsed);
            if (!p.ok()) return Suggestions.NONE;
            parsed.add(p.value());
            pos = e;
        }
        return Suggestions.NONE;
    }

    private static int skipSpace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** The end of the word that starts at {@code from}: the next space, or after the closing quote of a quoted word; -1 for a quote never closed. */
    private static int tokenEnd(String s, int from) {
        int i = from;
        if (s.charAt(i) == '"') {
            int close = s.indexOf('"', i + 1);
            if (close < 0) return -1;
            i = close + 1;
        }
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"' ? s.substring(1, s.length() - 1) : s;
    }
}
