package dev.vantage.core.hud;

/** The nine places on the screen a HUD element can be pinned to: four corners, four edge centres and the middle. */
public enum Anchor {
    TOP_LEFT("top-left", 0, 0), TOP("top", 1, 0), TOP_RIGHT("top-right", 2, 0),
    LEFT("left", 0, 1), CENTER("center", 1, 1), RIGHT("right", 2, 1),
    BOTTOM_LEFT("bottom-left", 0, 2), BOTTOM("bottom", 1, 2), BOTTOM_RIGHT("bottom-right", 2, 2);

    /** The name written to config.json. */
    public final String key;
    /** 0 = left, 1 = middle, 2 = right. */
    public final int col;
    /** 0 = top, 1 = middle, 2 = bottom. */
    public final int row;

    Anchor(String key, int col, int row) {
        this.key = key;
        this.col = col;
        this.row = row;
    }

    /** The anchor with this saved name, or null when there is none. */
    public static Anchor byKey(String key) {
        for (Anchor a : values()) if (a.key.equals(key)) return a;
        return null;
    }

    /** The stack this anchor names ("Top Left", "Top Right", "Bottom Left", "Bottom Right": the corner names core/Hud uses), or null for an edge centre or the middle. */
    public String corner() {
        return switch (this) {
            case TOP_LEFT -> "Top Left";
            case TOP_RIGHT -> "Top Right";
            case BOTTOM_LEFT -> "Bottom Left";
            case BOTTOM_RIGHT -> "Bottom Right";
            default -> null;
        };
    }

    /** The corner anchor for a stack name of core/Hud, or null. */
    public static Anchor byCorner(String corner) {
        for (Anchor a : values()) if (corner != null && corner.equals(a.corner())) return a;
        return null;
    }

    public static Anchor of(int col, int row) {
        return values()[row * 3 + col];
    }
}
