package dev.vantage.core.hud;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.vantage.core.Hud;
import dev.vantage.core.Ui;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The one way a module draws a HUD panel (B-204): the module declares it once with {@code hudPanel(id, title, corner)} (a method of
 * Module), says how big it is and how to draw it, and this class does the rest every frame, only while the module is on: nothing
 * while a screen is open, nothing while the user hid the element, then the slot ({@link Hud#slot}), {@code Ui.begin} and
 * {@code Ui.end}. The module's code is only the panel.
 *
 * <pre>
 *   hudPanel("my-panel", "My Panel", "Top Left")
 *       .when(() -> hud.on())                  // optional: anything else that decides whether there is something to show
 *       .size(wh -> { wh[0] = 150; wh[1] = 34; })
 *       .draw((g, x, y, w, h) -> Ui.text(g, "hello", x + 10, y + 8, Ui.TEXT));
 * </pre>
 */
public final class HudPanel {
    /** Writes the panel's width and height (design units) into wh[0] and wh[1]; the array is reused every frame. */
    @FunctionalInterface
    public interface Size {
        void measure(float[] wh);
    }

    /** Draws the panel with its top-left at x, y. Ui.begin has been called and Ui.end follows. */
    @FunctionalInterface
    public interface Draw {
        void draw(GuiGraphics g, float x, float y, float w, float h);
    }

    private final HudElement element;
    private final float[] wh = new float[2];
    private BooleanSupplier when = () -> true;
    private Supplier<String> corner;
    private boolean overScreens;
    private Size size;
    private Draw draw;

    public HudPanel(HudElement element) {
        this.element = element;
    }

    public HudElement element() {
        return element;
    }

    /** The panel is drawn only while this is true (checked after the module is on, no screen is open and the element is not hidden). */
    public HudPanel when(BooleanSupplier condition) {
        this.when = condition;
        return this;
    }

    /** The stack the panel takes its place in, when a setting of the module lets the user choose; the declared corner is the fallback. */
    public HudPanel corner(Supplier<String> corner) {
        this.corner = corner;
        return this;
    }

    /** The panel stays while a screen (inventory, chat, a menu) is open; by default panels hide behind screens. */
    public HudPanel overScreens() {
        this.overScreens = true;
        return this;
    }

    public HudPanel size(Size size) {
        this.size = size;
        return this;
    }

    public HudPanel draw(Draw draw) {
        this.draw = draw;
        return this;
    }

    /** Called by the module every frame it is on. */
    public void render(GuiGraphics g) {
        if (!overScreens && Hud.hidden() || element.hidden() || !when.getAsBoolean()) return;
        size.measure(wh);
        float[] at = Hud.slot(element.id(), corner == null ? element.defaultCorner() : corner.get(), wh[0], wh[1]);
        if (at == null) return;
        int mark = Ui.depth();
        Ui.begin(g);
        try {
            draw.draw(g, at[0], at[1], wh[0], wh[1]);
        } finally {
            Ui.unwind(g, mark); // a draw that throws must not leave its scale on for the rest of the frame
        }
    }
}
