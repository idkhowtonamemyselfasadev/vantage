package dev.vantage.core.command;

/**
 * Decides what a typed chat line is, before anything is sent anywhere. The one rule that matters most: a line that looks like a
 * client command never goes to the server, whether the command exists or not. A typo in a client command would otherwise appear
 * in public chat and tell everyone which client is in use.
 */
public final class CommandLine {
    private CommandLine() {}

    /** The character client commands start with. The ClickGUI module's "Command prefix" setting sets it when it is created and on every change. */
    private static volatile char prefix = '.';

    public static char prefix() {
        return prefix;
    }

    public static void setPrefix(char c) {
        prefix = c;
    }

    /** Why a text cannot be the prefix, or null when it can: one character that is not a letter, a digit, a space or a slash. */
    public static String problemWith(String text) {
        if (text == null || text.length() != 1) return "the prefix is one character";
        char c = text.charAt(0);
        if (Character.isLetterOrDigit(c)) return "the prefix cannot be a letter or a digit";
        if (Character.isWhitespace(c) || Character.isISOControl(c)) return "the prefix cannot be a space";
        if (c == '/') return "the prefix cannot be / because that starts a server command";
        if (Character.isSurrogate(c)) return "the prefix is one character the keyboard can type";
        return null;
    }

    /**
     * True when the line is an attempt at a client command: the prefix directly followed by a letter. "..." and ".5" and ". ok"
     * are ordinary chat and stay chat; ".frend Bob" is a mistyped command and must be kept on this side.
     */
    public static boolean isAttempt(String line) {
        if (line == null) return false;
        String t = line.stripLeading();
        return t.length() >= 2 && t.charAt(0) == prefix && Character.isLetter(t.charAt(1));
    }

    /** The command word of an attempt, without the prefix, in lower case: ".Friend Bob" gives "friend". */
    public static String word(String line) {
        String t = line.stripLeading();
        int end = 1;
        while (end < t.length() && !Character.isWhitespace(t.charAt(end))) end++;
        return t.substring(1, end).toLowerCase(java.util.Locale.ROOT);
    }
}
