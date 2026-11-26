package dev.vantage.core;

import java.util.Locale;

/**
 * Stable ids for modules and settings. An id is what saved files, profiles, share codes and commands use, so it must never change
 * once released, while the display name next to it may. By default the id is made from the first name a thing was given.
 */
public final class Ids {
    private Ids() {}

    /** "Storage ESP" becomes "storage-esp": lower case, every run of other characters one dash, no dash at either end. */
    public static String of(String name) {
        StringBuilder b = new StringBuilder(name.length());
        boolean dash = false;
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (dash && b.length() > 0) b.append('-');
                dash = false;
                b.append(c);
            } else {
                dash = true;
            }
        }
        if (b.length() == 0) throw new IllegalArgumentException("no id can be made from the name '" + name + "'");
        return b.toString();
    }

    /** True for a well-formed id: lower-case letters and digits in groups joined by single dashes. */
    public static boolean valid(String id) {
        return id != null && id.matches("[a-z0-9]+(-[a-z0-9]+)*") && id.equals(id.toLowerCase(Locale.ROOT));
    }
}
