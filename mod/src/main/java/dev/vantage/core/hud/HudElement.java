package dev.vantage.core.hud;

/**
 * One HUD panel that lives for the whole session (ARCHITECTURE.md 16.2). It is declared once by the module that draws it, so it
 * exists whether or not the module is on. It knows where it is (stacked in a corner, or pinned to an anchor), how big it was when it
 * was last drawn, whether the user hid it, and whether it was drawn in the last frame.
 *
 * Plain data, no game classes: the registry is {@link HudElements}, the frame logic is core/Hud.
 */
public final class HudElement {
    private final String id, title, defaultCorner;
    private float w, h;
    private boolean hidden, shown, placedNow;
    private HudLayout.Pin pin;
    /** The corner stack the user moved it to (the name core/Hud uses), or null. Wins over the corner the module asks for. */
    private String stackCorner;
    /** Where it sits when it is in no stack and not pinned, or null for "in the stack of its corner". */
    private HudLayout.Place defaultPlace;
    private java.util.function.BooleanSupplier moduleOn;
    private boolean intro = true;
    /** An old absolute top-left (design units) that has not been turned into an anchor yet; see {@link #convert}. */
    private float[] absolute;

    HudElement(String id, String title, String defaultCorner) {
        this.id = id;
        this.title = title;
        this.defaultCorner = defaultCorner;
    }

    public String id() { return id; }

    /** The name a person sees. */
    public String title() { return title; }

    /** The corner it stacks in until it is pinned (a module may let the user pick another one in its settings). */
    public String defaultCorner() { return defaultCorner; }

    /** Size in design units as last drawn; 0 before the first frame it was drawn. */
    public float w() { return w; }

    public float h() { return h; }

    /** The user's choice not to show it. Separate from its module being off or having nothing to show. */
    public boolean hidden() { return hidden; }

    /** Whether it was drawn in the last frame. */
    public boolean shown() { return shown; }

    /** Pinned (to an anchor, or to an absolute spot that waits to be converted) rather than stacked. */
    public boolean pinned() { return pin != null || absolute != null; }

    /** The anchor and offset, or null when stacked or when the position still waits to be converted. */
    public HudLayout.Pin pin() { return pin; }

    /** An old absolute {x, y} that waits for the screen size to become an anchor, or null. */
    public float[] absolute() { return absolute == null ? null : absolute.clone(); }

    void setHidden(boolean hidden) { this.hidden = hidden; }

    /** Where it sits by default when it has no corner (null: it stacks in its corner). */
    public HudLayout.Place defaultPlace() { return defaultPlace; }

    /** Gives the element a fixed default place instead of a corner stack (for panels that belong beside the crosshair, not in a corner). */
    public HudElement defaultAt(HudLayout.Place place) {
        this.defaultPlace = place;
        return this;
    }

    /** True unless the element opted out of the fade-and-slide a panel gets when it first appears (a card that fades by itself). */
    public boolean intro() { return intro; }

    /** The panel animates itself: Hud.slot does not fade or slide it in. */
    public HudElement plain() {
        this.intro = false;
        return this;
    }

    /** Says which module draws it; {@link #moduleState} then tells whether that module is on. */
    public HudElement ownedBy(java.util.function.BooleanSupplier moduleOn) {
        this.moduleOn = moduleOn;
        return this;
    }

    /** True or false for whether the module that draws it is on; null when nobody said which module that is. */
    public Boolean moduleState() { return moduleOn == null ? null : moduleOn.getAsBoolean(); }

    /** The corner stack the user chose, or null. */
    public String stackCorner() { return stackCorner; }

    /** Moved by the user to anywhere other than where it started: pinned or sent to another corner. */
    public boolean moved() { return pinned() || stackCorner != null; }

    /** Sends it to another corner stack (a name from core/Hud's CORNERS); it leaves any pin. Null puts it back. */
    public void stackIn(String corner) {
        this.stackCorner = corner;
        this.pin = null;
        this.absolute = null;
    }

    public void setSize(float w, float h) {
        this.w = w;
        this.h = h;
    }

    public void pinTo(HudLayout.Pin pin) {
        this.pin = pin;
        this.absolute = null;
        this.stackCorner = null;
    }

    /** Pins to an absolute top-left from the old file formats; the anchor is chosen at the first frame it is placed. */
    public void pinAbsolute(float x, float y) {
        this.absolute = new float[] {x, y};
        this.pin = null;
        this.stackCorner = null;
    }

    /** Back to where it started: no pin, no other corner. (Hidden is separate.) */
    public void unpin() {
        pin = null;
        absolute = null;
        stackCorner = null;
    }

    /** Turns a waiting absolute position into the nearest anchor, using the element's size and the screen it is first placed on. */
    public void convert(float screenW, float screenH) {
        if (absolute == null) return;
        pin = HudLayout.pinFor(absolute[0], absolute[1], w, h, screenW, screenH);
        absolute = null;
    }

    /** Top-left for the current screen; only valid while {@link #pinned()} and converted. */
    public float[] position(float screenW, float screenH) {
        return HudLayout.position(pin.anchor(), pin.dx(), pin.dy(), w, h, screenW, screenH);
    }

    /** Called when the piece asks for its place in a frame. */
    public void placed() { placedNow = true; }

    /** Start of a frame: what was placed during the last one is what "shown" says. */
    void endFrame() {
        shown = placedNow;
        placedNow = false;
    }

    void reset() {
        unpin();
        hidden = false;
    }
}
