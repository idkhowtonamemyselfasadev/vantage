package dev.vantage.core;

import com.google.gson.JsonObject;

/**
 * The "Send feedback" text: what is allowed in it and what the request carries. The pieces are pure functions (UiTest and the unit
 * tests call them); the sending itself is {@link Presence#sendFeedback}. The text is user content and is never written to the log.
 */
public final class Feedback {
    public static final int MAX = 1000;

    private Feedback() {}

    /** Control characters out (a newline stays, a tab becomes a space), line ends as \n, trimmed. */
    public static String clean(String t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\r') { if (i + 1 < t.length() && t.charAt(i + 1) == '\n') continue; sb.append('\n'); }
            else if (c == '\t') sb.append(' ');
            else if (c == '\n' || !(c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == 0x2028 || c == 0x2029 || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069) || c == 0xfeff)) sb.append(c);
        }
        return sb.toString().strip();
    }

    /** A version string reduced to what the service accepts: letters, digits and . _ + -, at most 32. */
    public static String version(String v) {
        String s = v == null ? "" : v.replaceAll("[^0-9A-Za-z._+\\-]", "_");
        if (s.length() > 32) s = s.substring(0, 32);
        return s.isEmpty() ? "unknown" : s;
    }

    /** The JSON body: text, Vantage version, Minecraft version. Nothing else. */
    public static String body(String cleanText, String vantage, String mc) {
        JsonObject o = new JsonObject();
        o.addProperty("text", cleanText);
        o.addProperty("version", version(vantage));
        o.addProperty("mc", version(mc));
        return o.toString();
    }

    public static String vantageVersion() {
        return dev.vantage.gui.AboutScreen.version();
    }

    public static String minecraftVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** The one plain line that says what is sent. */
    public static String sentLine() {
        return "Sent: your text, the Vantage version (" + version(vantageVersion()) + ") and the Minecraft version (" + version(minecraftVersion())
                + "). No account name, UUID, server address or system info.";
    }
}
