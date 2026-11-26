package dev.vantage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class FeedbackTest {
    @Test
    void cleanDropsControlCharactersAndKeepsNewlines() {
        assertEquals("a\nb c", Feedback.clean(" a\r\nb\tc\u0007\u0000 "));
        assertEquals("", Feedback.clean(" \n\u0001 "));
        assertEquals("x", Feedback.clean("‮x⁦"));
        assertEquals("", Feedback.clean(null));
    }

    @Test
    void versionIsReducedToWhatTheServiceAccepts() {
        assertEquals("1.0.9", Feedback.version("1.0.9"));
        assertEquals("1.21.11_x", Feedback.version("1.21.11 x"));
        assertEquals("unknown", Feedback.version(""));
        assertEquals(32, Feedback.version("v".repeat(100)).length());
    }

    @Test
    void bodyCarriesOnlyTextAndTheTwoVersions() {
        var o = JsonParser.parseString(Feedback.body("hi <there>", "1.0.9", "1.21.11")).getAsJsonObject();
        assertEquals(java.util.Set.of("text", "version", "mc"), o.keySet());
        assertEquals("hi <there>", o.get("text").getAsString());
        assertTrue(Feedback.MAX == 1000);
        assertFalse(o.has("uuid") || o.has("name") || o.has("server"));
    }
}
