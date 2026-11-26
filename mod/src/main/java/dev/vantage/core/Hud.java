package dev.vantage.core;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.hud.HudElement;
import dev.vantage.core.hud.HudElements;
import dev.vantage.core.hud.HudLayout;
import net.minecraft.client.Minecraft;

/**
 * Where HUD pieces go. Each piece asks for a slot by id with its corner and size (design units) and gets back the top-left to
 * draw at, or null when the user hid it. The place itself belongs to the piece's {@link HudElement}: by default it stacks per corner
 * so nothing overlaps; one dragged in the HUD editor is pinned to the nearest of nine anchors with an offset (saved in the config)
 * and leaves the stack. Reset every frame; the editor reads the slots of the last frame.
 *
 * Stack margins are design units, like everything else on the HUD, so the spacing does not change with the GUI scale. The clear
 * strip above the chat box at the bottom left stays in GUI pixels, because the chat is drawn in them.
 */
public final class Hud {
    public static final String[] CORNERS = {"Top Left", "Top Right", "Bottom Left", "Bottom Right"};
    /** Top and bottom margin of a stack and the gap between pieces, design units. 32/3 and 8 are the old 4 and 3 GUI px at GUI scale 4 on the 1080p rig window. */
    public static final float PAD = 32f / 3f, GAP = 8f;
    /** Clear strip above the bottom left corner for the chat box, GUI pixels. */
    private static final int CHAT_CLEAR = 40;
    private static final HudLayout.Stacks STACKS = new HudLayout.Stacks();

    /** One piece as drawn in the last frame, in design units. */
    public record Slot(String id, float x, float y, float w, float h) {
        public boolean has(float px, float py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    private static List<Slot> slots = new ArrayList<>(), building = new ArrayList<>();
    /** When each piece was first and last drawn, so a piece that just came on can slide and fade in. */
    private static final java.util.Map<String, long[]> SEEN = new java.util.HashMap<>();
    /** The intro: 360 ms, and a piece counts as new again after 400 ms off screen. */
    private static final long INTRO_MS = 360, GONE_MS = 400;
    private static long lastPrune;

    public static void reset() {
        Ui.shiftX = Ui.shiftY = 0;
        STACKS.reset(Ui.H(), PAD, GAP, CHAT_CLEAR / Ui.scale()); // the bottom left stays clear of the chat box
        HudElements.endFrame();
        slots = building;
        building = new ArrayList<>();
    }

    /** Pieces hide behind screens, except the HUD editor, which needs to show them. */
    public static boolean hidden() {
        var s = Minecraft.getInstance().screen;
        return s != null && !(s instanceof dev.vantage.gui.HudEditScreen);
    }

    public static boolean editing() {
        return Minecraft.getInstance().screen instanceof dev.vantage.gui.HudEditScreen;
    }

    private static int idx(String corner) {
        for (int i = 0; i < CORNERS.length; i++) if (CORNERS[i].equals(corner)) return i;
        return 0;
    }

    /** Reserves h design units in the corner (plus the gap below it) and returns the top y of the reserved strip. */
    public static float take(String corner, float h) {
        return STACKS.take(idx(corner), h);
    }

    public static boolean right(String corner) {
        return corner.endsWith("Right");
    }

    /**
     * Top-left in design units for a piece w by h, or null when the user hid it (the piece then draws nothing and takes no space).
     * A pinned piece goes where its anchor and offset say on this screen; any other takes the next free spot in its corner.
     * A piece drawn for the first time (or again after being gone) comes in over a third of a second: it slides in from its
     * screen edge and Ui.alpha fades it up, so panels never pop. Ui.end puts the alpha back. The editor shows everything steady.
     * An element the user sent to another corner stacks there instead; one with a fixed default place (declared with defaultAt) sits
     * there until it is pinned or sent to a corner. An id nobody declared as an element is not saved as one: it stacks, or sits where
     * the old "panels" map of the config says.
     */
    public static float[] slot(String id, String corner, float w, float h) {
        HudElement e = HudElements.get(id);
        if (e != null && e.hidden()) return null;
        float x, y;
        boolean pinned;
        if (e != null) {
            e.setSize(w, h);
            e.convert(Ui.W(), Ui.H()); // an old absolute position becomes an anchor now that the size of the screen and of the piece are known
            if (e.stackCorner() != null) corner = e.stackCorner();
            pinned = e.pin() != null;
            if (pinned) {
                float[] at = e.position(Ui.W(), Ui.H());
                x = at[0];
                y = at[1];
            } else if (e.defaultPlace() != null && e.stackCorner() == null) {
                float[] at = new float[2];
                e.defaultPlace().at(w, h, Ui.W(), Ui.H(), at);
                x = HudLayout.clamp(at[0], Ui.W(), w);
                y = HudLayout.clamp(at[1], Ui.H(), h);
                pinned = true; // it is not in a stack: its intro slides from above like a pinned piece
            } else {
                y = take(corner, h);
                x = HudLayout.stackX(right(corner), w, Ui.W());
            }
            e.placed();
        } else {
            int[] own = Config.PANELS.get(id);
            pinned = own != null;
            if (pinned) {
                x = HudLayout.clamp(own[0], Ui.W(), w);
                y = HudLayout.clamp(own[1], Ui.H(), h);
            } else {
                y = take(corner, h);
                x = HudLayout.stackX(right(corner), w, Ui.W());
            }
        }
        building.add(new Slot(id, x, y, w, h));
        float t = e != null && !e.intro() ? 1f : enter(id);
        if (t < 1f) {
            // the slide goes into the pose at Ui.begin, so the slot itself (what the editor and the stack use) stays put
            Ui.alpha = t;
            float d = 8 * (1 - t);
            if (pinned) Ui.shiftY = -d * 0.75f;
            else Ui.shiftX = right(corner) ? d : -d;
        }
        return new float[] {x, y};
    }

    /** 0 to 1 eased: how far the piece has come in. 1 while the editor is open. */
    public static float enter(String id) {
        long now = System.currentTimeMillis();
        long[] seen = SEEN.get(id);
        if (seen == null) SEEN.put(id, seen = new long[] {now, now});
        else if (now - seen[1] > GONE_MS) seen[0] = now;
        seen[1] = now;
        if (now - lastPrune > 5000) {
            lastPrune = now;
            SEEN.values().removeIf(v -> now - v[1] > 60_000);
        }
        if (editing()) return 1f;
        float u = Math.min(1f, (now - seen[0]) / (float) INTRO_MS);
        return Ui.ease(u);
    }

    /** What has been placed so far this frame (Callouts keeps its cards clear of these). */
    public static List<Slot> current() {
        return building;
    }

    /** What was drawn last frame, for the editor. */
    public static List<Slot> slots() {
        return slots;
    }

    /** Pins a piece where it is dropped: the nearest of the nine anchors, with the offset from it. */
    public static void move(String id, float x, float y) {
        HudElement e = HudElements.get(id);
        if (e == null) Config.PANELS.put(id, new int[] {Math.round(x), Math.round(y)});
        else e.pinTo(HudLayout.pinFor(x, y, e.w(), e.h(), Ui.W(), Ui.H()));
        Config.markDirty();
    }

    /** Sends a piece back to its corner stack. */
    public static void unpin(String id) {
        HudElement e = HudElements.get(id);
        if (e == null) {
            if (Config.PANELS.remove(id) != null) Config.markDirty();
        } else if (e.moved()) {
            e.unpin();
            Config.markDirty();
        }
    }

    /** Sends every piece back to its corner. The menu's panel positions in Config.PANELS are not touched. */
    public static void unpinAll() {
        HudElements.unpinAll();
        Config.markDirty();
    }

    private Hud() {}
}
