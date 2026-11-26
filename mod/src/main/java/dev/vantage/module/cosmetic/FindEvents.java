package dev.vantage.module.cosmetic;

/** Base Finder and Stash Finder call this when they mark a new find; the cosmetics that care listen here, and so does the Find Log (beta). Read-only, nothing leaves the client. */
public final class FindEvents {
    private FindEvents() {}

    /** How many marks were announced (for the rig). */
    public static int marked;

    public static void marked(double x, double y, double z) {
        marked("Find", "", x, y, z);
    }

    /** kind: "Base", "Stash" (or the base's own label), detail: a short reason, shown in the Find Log. */
    public static void marked(String kind, String detail, double x, double y, double z) {
        marked++;
        try {
            Pet p = Pet.active();
            if (p != null) p.hop();
            FindPulse f = FindPulse.active();
            if (f != null) f.pulse(x, y, z);
            var log = dev.vantage.module.client.FindLogModule.active();
            if (log != null) log.record(kind, detail, x, y, z);
        } catch (Throwable ignored) {
            // a cosmetic must never break a finder
        }
    }
}
