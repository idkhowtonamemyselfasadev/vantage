package dev.vantage.core.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The corner stacks with the panels of 3.2 step 2 in them: for realistic sizes on the window sizes of the rig and of a 4K screen, at every
 * GUI scale Minecraft allows there, no two panels overlap. The numbers are design units (720 tall), as the layout works in them.
 */
class HudStacksTest {
    private record Box(String id, float x, float y, float w, float h) {
        boolean hits(Box o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }
    }

    private static final float PAD = 32f / 3f, GAP = 8f, CHAT_CLEAR_GUI_PX = 40;

    /** Every panel that stacks, as (corner, id, w, h), in a realistic busy state: lists with rows, the finder panels on. */
    private static final Object[][] STACKED = {
        {0, "watermark", 190f, 22f}, {0, "service-demo", 150f, 34f}, {0, "vantage-online", 150f, 68f}, {0, "profit", 150f, 74f}, {0, "wheel-rtp", 144f, 86f},
        {1, "regions", 170f, 270f}, {1, "vanish-watch", 240f, 109f}, {1, "crack", 200f, 72f},
        {2, "framebudget", 120f, 20f}, {2, "debris", 257f, 83f}, {2, "sound-detector", 340f, 139f},
        {3, "coords", 160f, 61f},
    };

    private static List<Box> layout(int winW, int winH, int guiScale) {
        float sh = 720, sw = sh * winW / winH;
        float scale = winH / 720f / guiScale; // GUI units per design unit, what Ui.scale() is at Panel size 1
        HudLayout.Stacks stacks = new HudLayout.Stacks();
        stacks.reset(sh, PAD, GAP, CHAT_CLEAR_GUI_PX / scale);
        List<Box> out = new ArrayList<>();
        for (Object[] p : STACKED) {
            int corner = (int) p[0];
            float w = (float) p[2], h = (float) p[3];
            out.add(new Box((String) p[1], HudLayout.stackX(corner % 2 == 1, w, sw), stacks.take(corner, h), w, h));
        }
        // the panels with a place of their own, placed the way their declared defaults say
        float[] at = new float[2];
        String[] ids = {"targethud", "paypop", "wheel-ready"};
        float[][] sizes = {{190, 84}, {120, 56}, {170, 58}};
        HudLayout.Place[] places = {
            (w, h, s, t, o) -> { o[0] = s / 2 + 18; o[1] = t / 2 + 14; },
            (w, h, s, t, o) -> { o[0] = s / 2 - 18 - w; o[1] = t / 2 + 34 - 14 * 1.7f; },
            (w, h, s, t, o) -> { o[0] = (s - w) / 2 - 165; o[1] = 13; },
        };
        for (int i = 0; i < ids.length; i++) {
            places[i].at(sizes[i][0], sizes[i][1], sw, sh, at);
            out.add(new Box(ids[i], HudLayout.clamp(at[0], sw, sizes[i][0]), HudLayout.clamp(at[1], sh, sizes[i][1]), sizes[i][0], sizes[i][1]));
        }
        return out;
    }

    @Test
    void noTwoPanelsOverlapAtAnyWindowSizeAndGuiScale() {
        int[][] windows = {{854, 480}, {1920, 1080}, {3840, 2160}};
        int checked = 0;
        for (int[] win : windows) {
            for (int gs = 1; gs <= 4; gs++) {
                if (win[0] / gs < 320 || win[1] / gs < 240) continue; // Minecraft does not offer a scale that leaves less than 320 x 240 GUI pixels
                List<Box> boxes = layout(win[0], win[1], gs);
                checked++;
                for (int i = 0; i < boxes.size(); i++) {
                    Box a = boxes.get(i);
                    assertTrue(a.x >= 0 && a.y >= 0 && a.x + a.w <= 720f * win[0] / win[1] + 0.01f && a.y + a.h <= 720.01f,
                            a.id + " is off the screen at " + win[0] + "x" + win[1] + " scale " + gs);
                    for (int j = i + 1; j < boxes.size(); j++) {
                        assertFalse(a.hits(boxes.get(j)), a.id + " overlaps " + boxes.get(j).id + " at " + win[0] + "x" + win[1] + " scale " + gs);
                    }
                }
            }
        }
        assertEquals(2 + 4 + 4, checked, "854 wide allows scales 1 and 2, 1920 wide 1 to 4, 3840 wide 1 to 4");
    }

    @Test
    void aStackedPanelTakesTheStripBelowTheLastOneInItsCorner() {
        HudLayout.Stacks s = new HudLayout.Stacks();
        s.reset(720, PAD, GAP, 60);
        assertEquals(PAD, s.take(0, 30), 0.001);
        assertEquals(PAD + 30 + GAP, s.take(0, 20), 0.001);
        assertEquals(PAD, s.take(1, 10), 0.001, "each corner has its own stack");
        assertEquals(720 - PAD - 40, s.take(3, 40), 0.001, "bottom corners grow upward from the bottom edge");
        assertEquals(720 - PAD - 40 - GAP - 25, s.take(3, 25), 0.001);
        assertEquals(720 - 60 - 15, s.take(2, 15), 0.001, "the bottom left starts above the chat box");
    }
}
