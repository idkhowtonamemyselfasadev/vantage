package dev.vantage.core.util;

import java.util.Locale;

/**
 * Text for distances, big numbers, money and durations. Everything is formatted with {@link Locale#ROOT}, so a German install
 * prints "1.2 km", never "1,2 km". Rounding is half up (as {@code String.format} does) and the switch to the next unit happens
 * on the rounded value: 999.6 m is "1.0 km", never "1000 m". NaN prints "NaN"; infinities print "Infinity" / "-Infinity".
 */
public final class Fmt {
    private static final String[] BIG = {"", "k", "M", "B", "T"};

    /** Distance in blocks as "350 m" (whole metres, below 999.5) or "1.2 km" (one decimal); negative values keep the sign. */
    public static String distance(double meters) {
        if (!Double.isFinite(meters)) return nonFinite(meters);
        double a = Math.abs(meters);
        String sign = meters < 0 && Math.round(a) != 0 ? "-" : "";
        if (Math.round(a) < 1000) return sign + Math.round(a) + " m";
        return sign + String.format(Locale.ROOT, "%.1f", a / 1000) + " km";
    }

    /** Big number as "999", "1.2k", "3.4M", "5.0B", "1.0T": plain whole number below 999.5, one decimal after; the sign is kept. */
    public static String count(double v) {
        if (!Double.isFinite(v)) return nonFinite(v);
        double a = Math.abs(v);
        int tier = 0;
        // the next unit starts where the shown value would round up to 1000 (999.95k, or 999.5 for plain numbers)
        while (tier < BIG.length - 1 && (tier == 0 ? Math.round(a) >= 1000 : Math.round(a / Math.pow(1000, tier) * 10) >= 10_000)) tier++;
        String body = tier == 0 ? String.valueOf(Math.round(a)) : String.format(Locale.ROOT, "%.1f", a / Math.pow(1000, tier)) + BIG[tier];
        return v < 0 && !body.equals("0") ? "-" + body : body;
    }

    /**
     * Money as "$1,234" (whole, grouped, below 10,000), "$12.3K" (one decimal, 10,000 up), "$1.20M" and "$3.40B" (two decimals);
     * a negative amount reads "-$1,234". The units are K, M, B (capitals, the server's style); there is nothing beyond B, so a
     * trillion reads "$1000.00B". A value that would round up to the next unit (999,950) already reads in it.
     */
    public static String money(double v) {
        if (!Double.isFinite(v)) return (v < 0 ? "-$" : "$") + (Double.isNaN(v) ? "NaN" : "Infinity");
        double a = Math.abs(v);
        // the thresholds sit just under 1e6 and 1e9 so that a value that would round up to "1000.0K" reads "1.00M" instead
        String s = a >= 999_995_000d ? String.format(Locale.ROOT, "%.2fB", a / 1e9) : a >= 999_950d ? String.format(Locale.ROOT, "%.2fM", a / 1e6)
                : a >= 1e4 ? String.format(Locale.ROOT, "%.1fK", a / 1e3) : String.format(Locale.ROOT, "%,.0f", a);
        return (v < 0 ? "-$" : "$") + s;
    }

    /**
     * Money for a label in the world, as short as it can be: "$750", "$1.5K", "$2M", "$1.2B" (at most one decimal, a ".0" is left
     * out, a fraction below 1,000 keeps one decimal). For floating labels where width matters; panels and chat use {@link #money}.
     */
    public static String moneyShort(double v) {
        if (!Double.isFinite(v)) return money(v);
        String[] units = {"", "K", "M", "B", "T"};
        double a = Math.abs(v);
        int i = 0;
        while (a >= 1000 && i < units.length - 1) {
            a /= 1000;
            i++;
        }
        String s = i == 0 && a == Math.rint(a) ? String.valueOf((long) a) : String.format(Locale.ROOT, "%.1f", a);
        return (v < 0 ? "-$" : "$") + (s.endsWith(".0") ? s.substring(0, s.length() - 2) : s) + units[i];
    }

    /** Duration as "45s", "3m 4s", "2h 5m", "3d 4h": the two largest units that apply; whole units, rounded down; negative counts as 0. */
    public static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m " + s % 60 + "s";
        if (s < 86_400) return s / 3600 + "h " + s % 3600 / 60 + "m";
        return s / 86_400 + "d " + s % 86_400 / 3600 + "h";
    }

    /** Decimal number with a fixed number of decimals, point as the separator ("%.{decimals}f" with {@link Locale#ROOT}). */
    public static String fixed(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static String nonFinite(double v) {
        return Double.isNaN(v) ? "NaN" : v > 0 ? "Infinity" : "-Infinity";
    }

    private Fmt() {}
}
