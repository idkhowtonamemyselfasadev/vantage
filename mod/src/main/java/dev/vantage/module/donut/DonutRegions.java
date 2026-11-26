package dev.vantage.module.donut;

import dev.vantage.core.Category;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Ui;
import dev.vantage.core.hud.HudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Donut SMP's overworld is split into a 9x9 grid of 50k cells, each on a server in one part of the world.
 * This panel shows the grid, where you are and which region that is. Numbers and regions are read off the map.
 */
public class DonutRegions extends Module {
    public static final String[] REGIONS = {"EU Central", "EU West", "NA East", "NA West", "Asia", "Oceania"};
    public static final int[] COLORS = {0xFF8FBF57, 0xFF0FA56A, 0xFF4F9FDC, 0xFF2F66B0, 0xFFE3B84E, 0xFFEE8A1F};
    private static final int EUC = 0, EUW = 1, NAE = 2, NAW = 3, ASI = 4, OCE = 5;

    private static final int[][] NUM = {
            {82, 100, 101, 102, 103, 104, 105, 106, 91},
            {83, 44, 75, 42, 41, 40, 39, 38, 92},
            {84, 45, 14, 13, 12, 11, 10, 37, 93},
            {85, 46, 74, 3, 2, 1, 25, 36, 94},
            {86, 47, 72, 71, 5, 4, 24, 35, 95},
            {87, 51, 17, 9, 8, 7, 23, 34, 96},
            {88, 54, 18, 61, 62, 21, 22, 33, 97},
            {89, 26, 27, 28, 29, 30, 59, 32, 98},
            {90, 107, 108, 109, 110, 111, 112, 113, 99}};
    private static final int[][] REGION = {
            {OCE, NAW, NAW, NAW, NAE, NAE, NAE, NAE, NAE},
            {OCE, NAW, NAW, NAW, NAE, NAE, NAE, NAE, NAE},
            {OCE, NAW, NAW, NAW, NAE, NAE, NAE, NAE, NAE},
            {OCE, OCE, OCE, NAW, NAE, NAE, NAE, NAE, NAE},
            {ASI, ASI, ASI, ASI, NAE, NAE, NAE, NAE, NAE},
            {ASI, EUW, EUW, EUC, EUC, EUC, EUC, EUC, NAE},
            {ASI, EUW, EUW, EUC, EUC, EUC, EUC, EUC, EUC},
            {EUC, EUW, EUC, EUC, EUC, EUC, EUC, EUC, EUC},
            {EUC, EUW, EUW, EUW, EUW, EUW, EUW, EUW, EUC}};

    private static final float W = 170, CELL = 14, PITCH = 15.4f;

    static {
        HudElements.declare("regions", "Regions", "Top Right", DonutRegions.class);
    }

    public DonutRegions() {
        super("Region Map", "Donut SMP region grid with your cell and server region", Category.DONUT);
    }

    /** Grid index of a coordinate, or -1 outside the map. The centre cell runs from -25k to 25k. */
    public static int cell(double v) {
        int i = (int) Math.floor((v + 225_000) / 50_000);
        return i < 0 || i > 8 ? -1 : i;
    }

    public static int number(int col, int row) {
        return NUM[row][col];
    }

    public static int region(int col, int row) {
        return REGION[row][col];
    }

    /** Which side of the grid a spot outside it is on. The real world runs past the map, the frames only show up to 225k. */
    private static String edge(double x, double z) {
        String ns = z < -225_000 ? "north" : z >= 225_000 ? "south" : "", ew = x < -225_000 ? "west" : x >= 225_000 ? "east" : "";
        return ns.isEmpty() || ew.isEmpty() ? ns + ew : ns + "-" + ew;
    }

    private static String range(int i) {
        int a = -225 + i * 50, b = a + 50;
        return a + "k.." + b + "k";
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        // goes by the kind of world, not its name: servers can run the overworld under their own world names
        boolean overworld = !mc.level.dimensionType().hasCeiling() && mc.level.dimensionType().hasSkyLight();
        var p = mc.player.position();
        int col = overworld ? cell(p.x) : -1, row = overworld ? cell(p.z) : -1;
        boolean mapped = col >= 0 && row >= 0;

        float h = mapped ? 270 : 262;
        float s = Ui.scale();
        float[] at = Hud.slot("regions", "Top Right", W, h);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        Ui.panel(g, x, y, W, h);
        Ui.text(g, "DONUT REGIONS", x + 10, y + 8, Ui.FAINT, 0.85f, false);
        if (mapped) Ui.right(g, String.valueOf(NUM[row][col]), x + W - 10, y + 7, Ui.TEXT, 1.25f, false);
        else Ui.right(g, "Unmapped", x + W - 10, y + 7, Ui.SUB, 1.25f, false);
        Ui.hline(g, x + 10, y + 24, W - 20, Ui.LINE);

        float gx = x + 21, gyy = y + 33;
        float dim = mapped ? 1f : 0.42f;
        for (int r = 0; r < 9; r++) {
            Ui.center(g, String.valueOf(r + 1), x + 13, gyy + r * PITCH + 3, Ui.fade(Ui.FAINT, dim), 0.9f, false);
            for (int c = 0; c < 9; c++) {
                float cx = gx + c * PITCH, cy = gyy + r * PITCH;
                boolean here = c == col && r == row;
                if (here) {
                    Ui.rr(g, cx, cy, CELL, CELL, 2.2f, Ui.fade(COLORS[REGION[r][c]], dim), 0xFFFFFFFF);
                    Ui.rr(g, cx + 0.8f, cy + 0.8f, CELL - 1.6f, CELL - 1.6f, 1.6f, 0, 0xFFFFFFFF);
                } else Ui.rect(g, cx + 0.4f, cy + 0.4f, CELL - 0.8f, CELL - 0.8f, Ui.fade(COLORS[REGION[r][c]], dim)); // one fill per cell: 81 cells a frame
                if (here) continue;
                Ui.center(g, String.valueOf(NUM[r][c]), cx + CELL / 2, cy + 3.6f, Ui.fade(0xFFFFFFFF, mapped ? 0.95f : 0.5f), 0.68f, false);
            }
        }
        float ly = gyy + 9 * PITCH + 3;
        for (int c = 0; c < 9; c++) Ui.center(g, String.valueOf((char) ('A' + c)), gx + c * PITCH + CELL / 2, ly, Ui.fade(Ui.FAINT, dim), 0.85f, false);
        ly += 14;
        int[] order = {0, 3, 1, 4, 2, 5}; // two columns: EU Central | NA West, EU West | Asia, NA East | Oceania
        for (int i = 0; i < 6; i++) {
            float lx = x + (i % 2 == 0 ? 10 : 76), yy = ly + (i / 2) * 14.7f;
            Ui.rr(g, lx, yy + 1.5f, 6, 6, 3, Ui.fade(COLORS[order[i]], dim), 0);
            Ui.text(g, REGIONS[order[i]], lx + 10, yy, Ui.fade(Ui.SUB, mapped ? 1f : 0.7f), 0.95f, false);
        }
        ly += 3 * 14.7f + 1;
        Ui.hline(g, x + 10, ly, W - 20, Ui.LINE);
        ly += 6;
        if (mapped) {
            Ui.text(g, String.format("X: %.0f  Z: %.0f", p.x, p.z), x + 10, ly, Ui.TEXT, 0.95f, false);
            Ui.right(g, REGIONS[REGION[row][col]], x + W - 10, ly, COLORS[REGION[row][col]], 0.95f, false);
            Ui.text(g, range(col) + "  " + range(row), x + 10, ly + 13, Ui.FAINT, 0.85f, false);
        } else {
            Ui.text(g, String.format("X: %.0f  Z: %.0f", p.x, p.z), x + 10, ly, Ui.TEXT, 0.95f, false);
            Ui.text(g, overworld ? "Past the " + edge(p.x, p.z) + " edge, no map data" : "Unmapped dimension", x + 10, ly + 13, Ui.SUB, 0.85f, false);
        }
        Ui.end(g);
    }
}
