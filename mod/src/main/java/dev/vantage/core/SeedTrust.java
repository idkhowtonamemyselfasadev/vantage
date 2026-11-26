package dev.vantage.core;

/**
 * How the seed tools (.seed, Seed Cracker) say what a seed result is worth. Only one signal is solid and the client has it: the
 * hash of the seed that the server sends at login (see {@link SeedCheck}). A seed that matches it is the world's seed. Everything
 * else is "unverified on this server": the login hash does not match (a server can send a fake hash, or the seed is wrong) or
 * there is only a list of candidates.
 */
public final class SeedTrust {
    /** Beta: the Seed Cracker panel is this wide (it was 170). */
    public static final float PANEL_W = 200f;

    public static float panelWidth(float legacy) {
        return Modules.betaUnlocked() ? PANEL_W : legacy;
    }

    public static final String LABEL = "unverified on this server";

    private SeedTrust() {}
}
