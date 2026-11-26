package dev.vantage.core.hud;

/**
 * The position maths of pinned HUD elements, as pure functions of numbers (design units) so they can be tested without the game.
 *
 * An offset is the distance inward from the anchor: from the left or top edge for a left or top anchor, from the right or bottom
 * edge for a right or bottom anchor, and from the exact middle (positive = right or down) for a centre anchor. So an element pinned
 * bottom-right keeps its distance from that corner whatever size the screen has.
 */
public final class HudLayout {
    /** An anchor and the offset from it. */
    public record Pin(Anchor anchor, float dx, float dy) {}

    private HudLayout() {}

    /** Left edge of a w-wide element on a screen W wide, before clamping. */
    private static float axis(int slot, float offset, float screen, float size) {
        return switch (slot) {
            case 0 -> offset;
            case 1 -> (screen - size) / 2 + offset;
            default -> screen - size - offset;
        };
    }

    /** The offset that puts an element's edge at pos: the reverse of {@link #axis}. */
    private static float offsetOf(int slot, float pos, float screen, float size) {
        return switch (slot) {
            case 0 -> pos;
            case 1 -> pos - (screen - size) / 2;
            default -> screen - size - pos;
        };
    }

    /** Which of the three places along one axis is nearest to the element's centre. */
    private static int nearest(float pos, float screen, float size) {
        float c = pos + size / 2;
        float d0 = Math.abs(c), d1 = Math.abs(c - screen / 2), d2 = Math.abs(c - screen);
        return d0 <= d1 && d0 <= d2 ? 0 : d1 <= d2 ? 1 : 2;
    }

    /** Keeps an element of this size fully on the screen; one larger than the screen sits at 0. */
    public static float clamp(float pos, float screen, float size) {
        return Math.max(0, Math.min(screen - size, pos));
    }

    /** Top-left {x, y} of an element w by h pinned at (anchor, dx, dy) on a screen W by H, clamped into the screen. */
    public static float[] position(Anchor anchor, float dx, float dy, float w, float h, float screenW, float screenH) {
        return new float[] {clamp(axis(anchor.col, dx, screenW, w), screenW, w), clamp(axis(anchor.row, dy, screenH, h), screenH, h)};
    }

    /** The reverse: the nearest anchor to an element w by h whose top-left is at (x, y), and the offset from it. */
    public static Pin pinFor(float x, float y, float w, float h, float screenW, float screenH) {
        int col = nearest(x, screenW, w), row = nearest(y, screenH, h);
        return new Pin(Anchor.of(col, row), offsetOf(col, x, screenW, w), offsetOf(row, y, screenH, h));
    }

    /** Left edge of a piece in a corner stack: 3 design units from the side of its corner. */
    public static float stackX(boolean rightCorner, float w, float screenW) {
        return rightCorner ? screenW - w - 3 : 3;
    }

    /**
     * Where an element sits by default when it is not in a corner stack: its top-left for a w by h element on a screen sw by sh
     * (design units), written into out. For the panels that never had a corner (the target card beside the crosshair).
     */
    @FunctionalInterface
    public interface Place {
        void at(float w, float h, float sw, float sh, float[] out);
    }

    /**
     * The four corner stacks of one frame, as numbers: each corner hands out the next free strip, top corners growing down from
     * pad, bottom corners growing up from the bottom edge. The bottom left starts higher, clear of the chat box. Pieces taken
     * from one corner never overlap each other; that is the whole point of stacking.
     */
    public static final class Stacks {
        private final float[] used = new float[4];
        private float screenH, gap;

        /** Starts a frame: screenH is the screen height, pad the margin at top and bottom, bottomLeftStart the margin of the bottom left corner. All design units. */
        public void reset(float screenH, float pad, float gap, float bottomLeftStart) {
            this.screenH = screenH;
            this.gap = gap;
            java.util.Arrays.fill(used, pad);
            used[2] = bottomLeftStart;
        }

        /** Reserves h units in corner (0 top left, 1 top right, 2 bottom left, 3 bottom right) plus the gap below, and returns the top y of the strip. */
        public float take(int corner, float h) {
            float y = corner < 2 ? used[corner] : screenH - used[corner] - h;
            used[corner] += h + gap;
            return y;
        }
    }
}
