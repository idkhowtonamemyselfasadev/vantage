package dev.vantage.core;

import dev.vantage.core.log.ClientLog;

public final class Vantage {
    /** The client's log: the game's logger "Vantage" and a copy of every line in config/vantage/logs/vantage.log (see core/log). */
    public static final ClientLog LOG = new ClientLog();
    public static final String NAME = "Vantage";

    private Vantage() {}
}
