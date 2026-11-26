package dev.vantage.core.command;

/**
 * Where a command's answer goes. Every command answers through one of these three, so that the place where answers are shown is
 * changed in one spot ({@link Commands#setReply}) and not in every command.
 */
public interface Reply {
    /** Neutral information: a list, a status, help. */
    void info(String text);

    /** Something was done. */
    void success(String text);

    /** Something was refused or went wrong; the text says why. */
    void error(String text);
}
