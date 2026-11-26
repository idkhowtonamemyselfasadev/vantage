package dev.vantage.module.client;

/** Fast XP's one rule, kept plain so it can be tested without a game. */
public final class FastXpMath {
    /** The vanilla right-click delay (ticks between two throws while the use key is held). */
    public static final int VANILLA = 4;

    private FastXpMath() {}

    /**
     * The value the client's right-click delay counter may have at the moment it is checked, given the module's Delay setting (0..4).
     * The vanilla client counts the delay down once per tick and throws when it reaches 0, so a throw every {@code delay} ticks means
     * the counter is allowed at most {@code delay - 1} on that check. It never raises the counter: a counter already lower stays.
     * Delay 0 and 1 both give one throw per tick, which is the most one client tick can do (one use per tick), Delay 4 is vanilla.
     */
    public static int cap(int counter, int delay) {
        int limit = Math.max(0, Math.min(VANILLA, delay) - 1);
        return Math.min(counter, limit);
    }

    /** Throws in {@code ticks} ticks of a held key with this Delay (the first one happens on tick 1), the figure the rig compares against. */
    public static int throwsIn(int ticks, int delay) {
        int interval = Math.max(1, Math.min(VANILLA, delay));
        return ticks <= 0 ? 0 : (ticks - 1) / interval + 1;
    }
}
