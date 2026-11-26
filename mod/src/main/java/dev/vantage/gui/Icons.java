package dev.vantage.gui;

import java.util.HashMap;
import java.util.Map;

import dev.vantage.core.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

/**
 * The small pictograms in the menu. Each one is a white-on-transparent texture under assets/vantage/textures/gui/icon/<name>.png,
 * tinted with whatever colour the caller wants. A name without a texture falls back to a glyph built from rings and bars.
 */
public final class Icons {
    private static final Map<String, Identifier> FOUND = new HashMap<>();

    private static Identifier texture(String name) {
        return FOUND.computeIfAbsent(name, n -> {
            Identifier id = Identifier.fromNamespaceAndPath("vantage", "textures/gui/icon/" + n + ".png");
            return Minecraft.getInstance().getResourceManager().getResource(id).isPresent() ? id : null;
        });
    }

    /** Draws the icon inside an s by s box at x, y (design units). */
    public static void draw(GuiGraphics g, String name, float x, float y, float s, int c) {
        Identifier id = texture(name);
        if (id != null) { Ui.image(g, id, x, y, s, s, c); return; }
        float k = s / 13f;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(k, k);
        glyph(g, name, c);
        g.pose().popMatrix();
    }

    private static void glyph(GuiGraphics g, String name, int c) {
        switch (name) {
            case "active" -> { Ui.rr(g, 1, 1, 11, 11, 5.5f, 0, c); Ui.rr(g, 4.5f, 4.5f, 4, 4, 2, c, 0); }
            case "finder" -> { Ui.rr(g, 0.5f, 0.5f, 9, 9, 4.5f, 0, c); Ui.rr(g, 8.3f, 8.3f, 4.5f, 1.8f, 0.9f, c, 0); Ui.rr(g, 8.3f, 8.3f, 1.8f, 4.5f, 0.9f, c, 0); }
            case "render" -> { Ui.rr(g, 0, 2.5f, 13, 8, 4, 0, c); Ui.rr(g, 4.5f, 4.5f, 4, 4, 2, c, 0); }
            case "donut" -> { Ui.rr(g, 0.5f, 0.5f, 12, 12, 6, 0, c); Ui.rr(g, 3.5f, 3.5f, 6, 6, 3, 0, c); }
            case "client" -> { Ui.rr(g, 0.5f, 1.5f, 12, 10, 2.5f, 0, c); Ui.rect(g, 0.5f, 4.5f, 12, 1, c); Ui.rect(g, 2.5f, 2.5f, 1.5f, 1, c); }
            case "settings" -> { for (int i = 0; i < 3; i++) { Ui.rect(g, 0.5f, 2 + i * 4, 12, 1.2f, c); Ui.rr(g, i == 1 ? 8 : 3, 0.8f + i * 4, 3.4f, 3.4f, 1.7f, c, 0); } }
            case "keybinds" -> { Ui.rr(g, 0.5f, 2.5f, 12, 8, 2, 0, c); for (int i = 0; i < 3; i++) Ui.rect(g, 3 + i * 3, 5, 1.5f, 1.5f, c); Ui.rect(g, 3.5f, 7.8f, 6, 1.2f, c); }
            case "friends" -> { Ui.rr(g, 1, 1, 5, 5, 2.5f, 0, c); Ui.rr(g, 7.5f, 2.5f, 4, 4, 2, 0, c); Ui.rr(g, 0, 7.5f, 7.5f, 5, 2.5f, 0, c); Ui.rr(g, 7.5f, 8, 5.5f, 4.5f, 2.2f, 0, c); }
            case "configs" -> { Ui.rr(g, 0.5f, 6.5f, 12, 6, 2, 0, c); Ui.rr(g, 2, 3.6f, 9, 1.4f, 0.7f, c, 0); Ui.rr(g, 3.5f, 0.8f, 6, 1.4f, 0.7f, c, 0); }
            case "waypoints" -> { Ui.rr(g, 2, 0.5f, 9, 9, 4.5f, 0, c); Ui.rr(g, 5.2f, 3.8f, 2.6f, 2.6f, 1.3f, c, 0); Ui.rect(g, 5.8f, 9, 1.4f, 4, c); }
            default -> Ui.rr(g, 2, 2, 9, 9, 2, 0, c);
        }
    }

    private Icons() {}
}
