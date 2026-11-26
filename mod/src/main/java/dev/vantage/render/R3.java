package dev.vantage.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vantage.core.util.ChunkMath;
import dev.vantage.core.util.Colors;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One frame of world drawing. Modules add boxes, lines and labels in world coordinates; at the end
 * everything is written per render type in one go (switching types mid-way would flush the buffer).
 */
public final class R3 {
    private record Line(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width, boolean xray, boolean tracer, int flags) {
        Line(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width, boolean xray) {
            this(x1, y1, z1, x2, y2, z2, color, width, xray, false, 0);
        }

        Line(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width, boolean xray, boolean tracer) {
            this(x1, y1, z1, x2, y2, z2, color, width, xray, tracer, 0);
        }
    }
    private record Quad(float[] v, int color, boolean xray, int flags) {
        Quad(float[] v, int color, boolean xray) {
            this(v, color, xray, 0);
        }
    }
    /** Many flat chunk-sized squares at one height. The keys array is read at flush and never copied: no object per tile. */
    private record TileBatch(long[] keys, int count, float y, int fill, int line, float width, int flags) {}
    private final List<TileBatch> tileBatches = new ArrayList<>();

    /**
     * A set of chunk tiles prepared once for drawing many times: neighbouring tiles are merged into larger rectangles and only the
     * outer border of the set is kept as lines. Measured on the home server, 10,000 separate tiles cost about 5.6 ms of client time
     * per frame (and 25 ms with an outline around every tile); the same 10,000 tiles as one prepared set cost a fraction of that,
     * because a frame then writes a few hundred rectangles instead of 10,000 (PERFORMANCE.md). Build it when the set changes
     * ({@link #tileSet}), keep it, and hand it to {@link #tiles(TileSet, double, int, int, float, int)} every frame.
     */
    public static final class TileSet {
        /** Rectangles and border segments are at most this many chunks long, so the distance fade still has steps (128 blocks). */
        static final int SPAN = 8;
        /** x0, z0, x1, z1 in chunks (end not included), four numbers per rectangle. */
        final int[] rects;
        /** x0, z0, x1, z1 in chunk corners, four numbers per border segment. */
        final int[] border;
        /** How many tiles went in (after dropping repeats). */
        public final int tiles;

        private TileSet(int[] rects, int[] border, int tiles) {
            this.rects = rects;
            this.border = border;
            this.tiles = tiles;
        }

        public int rectangles() {
            return rects.length / 4;
        }

        public int borderSegments() {
            return border.length / 4;
        }
    }

    private record TileSetDraw(TileSet set, float y, int fill, int line, float width, int flags) {}
    private final List<TileSetDraw> tileSets = new ArrayList<>();

    /** Sort key for a chunk: by z, then by x, both as signed numbers. */
    private static long rowKey(int x, int z) {
        return ((long) z << 32) | ((x ^ 0x80000000) & 0xFFFFFFFFL); // z as the signed high half, x shifted so that it sorts as signed in the unsigned low half
    }

    /**
     * Prepares {@code count} chunk keys (ChunkPos.asLong) for drawing. Costs a sort and a pass over the tiles; call it when the set
     * changes, not every frame. Repeated keys are counted once.
     */
    public static TileSet tileSet(long[] chunkKeys, int count) {
        int n = Math.min(count, chunkKeys.length);
        long[] k = new long[n];
        for (int i = 0; i < n; i++) k[i] = rowKey(net.minecraft.world.level.ChunkPos.getX(chunkKeys[i]), net.minecraft.world.level.ChunkPos.getZ(chunkKeys[i]));
        java.util.Arrays.sort(k);
        int m = 0;
        for (int i = 0; i < n; i++) if (i == 0 || k[i] != k[i - 1]) k[m++] = k[i];
        // rectangles: runs along x in each row, then rows with the same run stacked, both capped at SPAN
        List<int[]> rects = new ArrayList<>();
        java.util.Map<Long, int[]> open = new java.util.HashMap<>(), next = new java.util.HashMap<>();
        int i = 0;
        int lastRow = Integer.MIN_VALUE;
        while (i < m) {
            int z = (int) (k[i] >> 32);
            if (lastRow != Integer.MIN_VALUE && z != lastRow + 1) open.clear();
            next.clear();
            while (i < m && ((int) (k[i] >> 32)) == z) {
                int x0 = (int) k[i] ^ 0x80000000, x1 = x0 + 1;
                i++;
                while (i < m && ((int) (k[i] >> 32)) == z && ((int) k[i] ^ 0x80000000) == x1 && x1 - x0 < TileSet.SPAN) { x1++; i++; }
                long run = ((long) x0 << 32) | (x1 & 0xFFFFFFFFL);
                int[] above = open.get(run);
                if (above != null && above[3] == z && above[3] - above[1] < TileSet.SPAN) {
                    above[3] = z + 1;
                    next.put(run, above);
                } else {
                    int[] r = {x0, z, x1, z + 1};
                    rects.add(r);
                    next.put(run, r);
                }
            }
            java.util.Map<Long, int[]> t = open; open = next; next = t;
            lastRow = z;
        }
        // border: the sides of tiles that have no neighbour there, joined into segments of up to SPAN
        long[] across = new long[m * 2], along = new long[m * 2]; // segments running along x (key: line z, x) and along z (key: line x, z)
        int na = 0, nl = 0;
        for (int j = 0; j < m; j++) {
            int z = (int) (k[j] >> 32), x = (int) k[j] ^ 0x80000000;
            if (java.util.Arrays.binarySearch(k, 0, m, rowKey(x, z - 1)) < 0) across[na++] = rowKey(x, z);
            if (java.util.Arrays.binarySearch(k, 0, m, rowKey(x, z + 1)) < 0) across[na++] = rowKey(x, z + 1);
            if (java.util.Arrays.binarySearch(k, 0, m, rowKey(x - 1, z)) < 0) along[nl++] = rowKey(z, x);
            if (java.util.Arrays.binarySearch(k, 0, m, rowKey(x + 1, z)) < 0) along[nl++] = rowKey(z, x + 1);
        }
        java.util.Arrays.sort(across, 0, na);
        java.util.Arrays.sort(along, 0, nl);
        List<int[]> border = new ArrayList<>();
        for (int j = 0; j < na; ) {
            int z = (int) (across[j] >> 32), x0 = (int) across[j] ^ 0x80000000, x1 = x0 + 1;
            j++;
            while (j < na && ((int) (across[j] >> 32)) == z && ((int) across[j] ^ 0x80000000) == x1 && x1 - x0 < TileSet.SPAN) { x1++; j++; }
            border.add(new int[] {x0, z, x1, z});
        }
        for (int j = 0; j < nl; ) {
            int x = (int) (along[j] >> 32), z0 = (int) along[j] ^ 0x80000000, z1 = z0 + 1;
            j++;
            while (j < nl && ((int) (along[j] >> 32)) == x && ((int) along[j] ^ 0x80000000) == z1 && z1 - z0 < TileSet.SPAN) { z1++; j++; }
            border.add(new int[] {x, z0, x, z1});
        }
        int[] ra = new int[rects.size() * 4], ba = new int[border.size() * 4];
        for (int j = 0; j < rects.size(); j++) System.arraycopy(rects.get(j), 0, ra, j * 4, 4);
        for (int j = 0; j < border.size(); j++) System.arraycopy(border.get(j), 0, ba, j * 4, 4);
        return new TileSet(ra, ba, m);
    }
    /** Labels drawn with the depth test (hidden behind blocks); they do not take part in the label budget. */
    private final List<Label> depthLabels = new ArrayList<>();

    // ---- options every call takes (plan task 3.1). A call's options are these bits, so a shape costs no extra object. --------------
    /** Drawn through blocks (depth test off). Without it a shape is hidden by what is in front of it. */
    public static final int THROUGH_WALLS = 1;
    /** Keeps its full strength at any distance, whatever the user's distance fade says. */
    public static final int NO_FADE = 2;
    /** Fades with distance even when the user's distance fade is off. */
    public static final int FADE = 4;
    /** Lines only: no glow and no second "depth cue" copy, one segment per line. For large batches. */
    public static final int PLAIN_LINES = 8;

    /** The colour with this opacity (0 to 1) as its alpha: for a module that keeps colour and opacity as two settings. */
    public static int opacity(int color, double opacity) {
        int a = (int) Math.round(Math.max(0, Math.min(1, opacity)) * 255);
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** The distance fade itself: full strength up to 64 blocks, down to 45% at 256 (the same curve as ESP Style's Distance fade). */
    public static float fadeAt(double dist) {
        if (dist <= 64) return 1f;
        double t = Math.min(1, (dist - 64) / 192);
        return (float) (1 - 0.55 * t * t);
    }

    /** How strong a shape with these flags is at this distance, given what the global look says for its kind. */
    private static float strength(int flags, double dist, boolean globalFade) {
        if ((flags & NO_FADE) != 0) return 1f;
        if ((flags & FADE) != 0 || globalFade) return fadeAt(dist);
        return 1f;
    }

    /** For the in-game checks: tiles handed in and tiles really written (those in front of the camera) in the last frame. */
    public static int dTilesIn, dTiles, dTileRects, dDepthLabels;
    private record Label(Vec3 pos, String text, int color, float scale, dev.vantage.core.Module owner, int boost) {
        Label(Vec3 pos, String text, int color, float scale, dev.vantage.core.Module owner) {
            this(pos, text, color, scale, owner, 0);
        }
    }
    /** A small box held back for Declutter (beta): its outline and/or fill, who drew it and what kind of thing it marks. */
    private record SBox(AABB b, int lineColor, float width, int fillColor, boolean xray, dev.vantage.core.Module owner, String kind) {}
    private final List<SBox> sboxes = new ArrayList<>();
    private final java.util.Map<AABB, int[]> pendingFill = new java.util.HashMap<>();
    private String kind;

    public final Minecraft mc = Minecraft.getInstance();
    public final Camera camera;
    /** The frame's look, filled by ESP Style once per frame; R3 reads nothing else about styling. */
    private final RenderStyle style;
    public final Vec3 cam;
    public final float partial;
    private final List<Line> lines = new ArrayList<>();
    private final List<Quad> quads = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    /** The module whose onRender3D is running, so labels know who made them (set by VantageClient). */
    private dev.vantage.core.Module owner;
    private int columnsSkippedNow;

    // ---- label budget stats of the last flush, for the rig ----
    /** The rig sets this so the old look also measures label rectangles and overlaps. */
    public static boolean measure;
    public static int labelsIn, labelsDrawn, labelOverlaps, labelsMerged, columnsSkipped, columnsNear, labelsNudged, labelOverlapsAfter;
    /** Rig stats of the last flush (only with {@link #measure}): smallest and largest outline width asked for (tracers left out), smallest and largest fill alpha byte. */
    public static float outlineMin, outlineMax;
    public static int fillMin, fillMax;
    public static List<String> drawnTexts = List.of();

    /**
     * What R3's label budget and floor-to-sky columns ask of Base Finder, as an interface so the drawing layer does not know the module.
     * Base Finder registers itself from its constructor ({@link #setPolicy}); with none registered R3 behaves as if the policy's
     * declutter were off.
     */
    public interface Policy {
        /** Base Finder is on and its Declutter switch is on: the label budget and the thin columns apply. */
        boolean declutterOn();

        /** Evidence labels farther than this (blocks) are hidden unless they lie in the focused base. */
        double labelRange();

        /** The most non-name labels the budget keeps. */
        int labelCap();

        /** A base the policy already marks covers this spot, so another marker over it would say the same. */
        boolean siteNear(double x, double z);

        /** Chunk keys (ChunkPos.asLong) of the base the player looks at, or null when there is none. */
        java.util.Set<Long> focusChunks();

        /** Columns closer than this (blocks) are left out and fade in over the next 48. */
        double columnFade();
    }

    private static Policy policy;

    /** Registered once by the module that owns the policy; null removes it. */
    public static void setPolicy(Policy p) {
        policy = p;
    }

    public R3(Camera camera, float partial, RenderStyle style) {
        this.style = style;
        this.camera = camera;
        this.cam = camera.position();
        this.partial = partial;
    }

    // ---- adding ---------------------------------------------------------------------------------

    public void from(dev.vantage.core.Module m) {
        owner = m;
        kind = null;
    }

    /** What the boxes drawn next mark ("chest", "barrel"): Declutter's group label says "32 chests". Reset when the next module starts. */
    public void kind(String noun) {
        kind = noun;
    }

    public int quadCount() {
        return quads.size();
    }

    public void line(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width, boolean xray) {
        lines.add(new Line(x1, y1, z1, x2, y2, z2, color, width, xray));
    }

    /** A line with options (THROUGH_WALLS, NO_FADE, FADE, PLAIN_LINES). */
    public void line(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width, int flags) {
        lines.add(new Line(x1, y1, z1, x2, y2, z2, color, width, (flags & THROUGH_WALLS) != 0, false, flags));
    }

    /** A tracer with options; without THROUGH_WALLS it is hidden where blocks are in the way. */
    public void tracer(Vec3 to, int color, float width, int flags) {
        var f = camera.forwardVector();
        lines.add(new Line(cam.x + f.x(), cam.y + f.y(), cam.z + f.z(), to.x, to.y, to.z, color, width, (flags & THROUGH_WALLS) != 0, true, flags));
    }

    /** An outlined box with options. Drawn as it is: boxes with options are not held back for grouping. */
    public void box(AABB b, int color, float width, int flags) {
        double x0 = b.minX, y0 = b.minY, z0 = b.minZ, x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
        line(x0, y0, z0, x1, y0, z0, color, width, flags);
        line(x0, y0, z1, x1, y0, z1, color, width, flags);
        line(x0, y1, z0, x1, y1, z0, color, width, flags);
        line(x0, y1, z1, x1, y1, z1, color, width, flags);
        line(x0, y0, z0, x0, y0, z1, color, width, flags);
        line(x1, y0, z0, x1, y0, z1, color, width, flags);
        line(x0, y1, z0, x0, y1, z1, color, width, flags);
        line(x1, y1, z0, x1, y1, z1, color, width, flags);
        line(x0, y0, z0, x0, y1, z0, color, width, flags);
        line(x1, y0, z0, x1, y1, z0, color, width, flags);
        line(x0, y0, z1, x0, y1, z1, color, width, flags);
        line(x1, y0, z1, x1, y1, z1, color, width, flags);
    }

    /** A filled box with options. */
    public void fill(AABB b, int color, int flags) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        quad(color, flags, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(color, flags, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(color, flags, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(color, flags, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(color, flags, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(color, flags, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    /** One quad with options; the twelve numbers are four corners. */
    public void quad(int color, int flags, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4) {
        quads.add(new Quad(new float[] {x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4}, color, (flags & THROUGH_WALLS) != 0, flags));
    }

    /**
     * A highlight over one whole chunk between two heights: translucent sides and top plus its outline. A plain shape: whether a
     * chunk deserves one, and when, is the caller's business.
     */
    public void chunkColumn(int chunkX, int chunkZ, double y0, double y1, int fillColor, int lineColor, float width, int flags) {
        AABB b = new AABB(chunkX << 4, y0, chunkZ << 4, (chunkX << 4) + 16, y1, (chunkZ << 4) + 16);
        if ((fillColor >>> 24) != 0) fill(b, fillColor, flags);
        if ((lineColor >>> 24) != 0) box(b, lineColor, width, flags);
    }

    /** One flat chunk-sized square at a height, with an optional outline (a colour with alpha 0 leaves that part out). */
    public void tile(int chunkX, int chunkZ, double y, int fillColor, int lineColor, float width, int flags) {
        tileBatches.add(new TileBatch(new long[] {net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ)}, 1, (float) y, fillColor, lineColor, width, flags));
    }

    /**
     * Many flat chunk tiles of one look in one call: {@code count} chunk keys (ChunkPos.asLong) from the array. The array is read
     * when the frame is drawn and is not copied, so it must not be changed before then; nothing is allocated per tile. Tiles behind
     * the camera are skipped. Tile outlines are always plain lines (one segment per edge).
     */
    public void tiles(long[] chunkKeys, int count, double y, int fillColor, int lineColor, float width, int flags) {
        if (count > 0) tileBatches.add(new TileBatch(chunkKeys, Math.min(count, chunkKeys.length), (float) y, fillColor, lineColor, width, flags));
    }

    /**
     * A prepared set of chunk tiles at a height: filled (colour with alpha above 0) and/or with a line around the outside of the set.
     * This is the way to draw many tiles; see {@link TileSet}.
     */
    public void tiles(TileSet set, double y, int fillColor, int lineColor, float width, int flags) {
        if (set != null && set.tiles > 0) tileSets.add(new TileSetDraw(set, (float) y, fillColor, lineColor, width, flags));
    }

    /** A text label with options: without THROUGH_WALLS it is hidden behind blocks like any object in the world. */
    public void label(Vec3 pos, String text, int color, float scale, int flags) {
        if ((flags & THROUGH_WALLS) != 0) labels.add(new Label(pos, text, color, scale, owner));
        else depthLabels.add(new Label(pos, text, color, scale, owner));
    }

    /** A line from just in front of the crosshair to a world point. */
    public void tracer(Vec3 to, int color, float width) {
        var f = camera.forwardVector();
        lines.add(new Line(cam.x + f.x(), cam.y + f.y(), cam.z + f.z(), to.x, to.y, to.z, color, width, true, true));
    }

    public void box(AABB b, int color, float width, boolean xray) {
        if (holdBox(b, color, width, xray)) return;
        boxLines(b, color, width, xray);
    }

    private void boxLines(AABB b, int color, float width, boolean xray) {
        double x0 = b.minX, y0 = b.minY, z0 = b.minZ, x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
        line(x0, y0, z0, x1, y0, z0, color, width, xray);
        line(x0, y0, z1, x1, y0, z1, color, width, xray);
        line(x0, y1, z0, x1, y1, z0, color, width, xray);
        line(x0, y1, z1, x1, y1, z1, color, width, xray);
        line(x0, y0, z0, x0, y0, z1, color, width, xray);
        line(x1, y0, z0, x1, y0, z1, color, width, xray);
        line(x0, y1, z0, x0, y1, z1, color, width, xray);
        line(x1, y1, z0, x1, y1, z1, color, width, xray);
        line(x0, y0, z0, x0, y1, z0, color, width, xray);
        line(x1, y0, z0, x1, y1, z0, color, width, xray);
        line(x0, y0, z1, x0, y1, z1, color, width, xray);
        line(x1, y0, z1, x1, y1, z1, color, width, xray);
    }

    /** The six faces of a box, translucent. */
    public void fill(AABB b, int color, boolean xray) {
        if (holdFill(b, color, xray)) return;
        fillQuads(b, color, xray);
    }

    private void fillQuads(AABB b, int color, boolean xray) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        quad(color, xray, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(color, xray, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(color, xray, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(color, xray, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(color, xray, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(color, xray, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    /** A flat horizontal rectangle at height y (e.g. a chunk overlay or a hole floor). */
    public void flat(double xa, double za, double xb, double zb, double y, int color, boolean xray) {
        float y0 = (float) y;
        quad(color, xray, (float) xa, y0, (float) za, (float) xb, y0, (float) za, (float) xb, y0, (float) zb, (float) xa, y0, (float) zb);
    }

    /**
     * One outline around a group of touching blocks (Anubis' BlockClusterGeometry): faces only where no neighbour of the
     * group sits, edges only where the surface bends (convex: neither neighbour, concave: both neighbours but not the
     * diagonal), unit edges joined into long lines. With merging off every block gets its own cube.
     */
    public void outline(java.util.Collection<net.minecraft.core.BlockPos> blocks, int color, float width, boolean xray, int fillAlpha) {
        outline(blocks, color, width, xray, fillAlpha, true);
    }

    /** When each outline was first and last drawn, keyed by its lowest block and colour, so a new vein can fade in. */
    private static final java.util.Map<Long, long[]> REVEAL = new java.util.HashMap<>();
    private static final long REVEAL_MS = 320, REVEAL_GONE = 1000;
    private static long revealPrune;

    /** 0 to 1: how far in a vein drawn now has come. 1 once it has been on screen for a third of a second. */
    private float reveal(long low, int color) {
        if (!style.reveal) return 1f;
        long key = low * 31 + (color & 0xFFFFFF);
        long now = System.currentTimeMillis();
        long[] seen = REVEAL.get(key);
        if (seen == null) REVEAL.put(key, seen = new long[] {now, now});
        else if (now - seen[1] > REVEAL_GONE) seen[0] = now;
        seen[1] = now;
        if (now - revealPrune > 2000) {
            revealPrune = now;
            REVEAL.values().removeIf(v -> now - v[1] > REVEAL_GONE);
        }
        return dev.vantage.core.Ui.ease((now - seen[0]) / (float) REVEAL_MS);
    }

    public void outline(java.util.Collection<net.minecraft.core.BlockPos> blocks, int color, float width, boolean xray, int fillAlpha, boolean lines) {
        outline(blocks, color, width, xray, fillAlpha, lines, false);
    }

    /** forceMerge: always the merged faces, even with ESP Style's Merge outlines off (Base ESP's container fill). */
    public void outline(java.util.Collection<net.minecraft.core.BlockPos> blocks, int color, float width, boolean xray, int fillAlpha, boolean lines, boolean forceMerge) {
        if (blocks.isEmpty()) return;
        outline(prepare(blocks), color, width, xray, fillAlpha, lines, forceMerge);
    }

    /**
     * A block set prepared for {@link #outline(Outline, int, float, boolean, int)}: the exposed faces and the merged edges, worked
     * out once (a hash set, six neighbour tests per block and the edge joining cost real time on a set of hundreds of blocks) and stored
     * in plain arrays. Build it when a module's set changes, keep it, draw it every frame. Order and repeats in the input do not matter.
     */
    public static final class Outline {
        /** x, y, z of each block, sorted by BlockPos.asLong. */
        final int[] pos;
        /** Per block, the faces with no neighbour in the set: bit 0 below, 1 above, 2 north, 3 south, 4 west, 5 east. */
        final byte[] exposed;
        /** The merged edges as edges() gives them (axis, from, to, u, v), five numbers each, in a fixed order. */
        final int[] edges;
        /** The lowest BlockPos.asLong in the set: the outline's identity for the fade-in. */
        final long low;
        /** How many faces are exposed (the quads a fill emits). */
        public final int faces;

        private Outline(int[] pos, byte[] exposed, int[] edges, long low, int faces) {
            this.pos = pos;
            this.exposed = exposed;
            this.edges = edges;
            this.low = low;
            this.faces = faces;
        }

        public int blocks() {
            return pos.length / 3;
        }

        public int edgeCount() {
            return edges.length / 5;
        }
    }

    /**
     * For a module that draws several block lists every frame: hands back the prepared outline of a list and rebuilds it only when the
     * list's blocks differ from the last frame's (same blocks in the same order = same outline). Never stale, because the check is on the
     * content; it costs one pass of cheap comparisons instead of a hash set and the edge joining. The lists given must not be changed
     * after they are handed in. Call {@link #endFrame} after the frame's last {@link #get} so lists that stopped being drawn are let go.
     */
    public static final class OutlineCache {
        private record Entry(List<net.minecraft.core.BlockPos> blocks, Outline outline, int stamp) {}
        private final java.util.Map<Object, Entry> entries = new java.util.HashMap<>();
        private int stamp;

        public Outline get(Object key, List<net.minecraft.core.BlockPos> blocks) {
            Entry e = entries.get(key);
            if (e == null || !e.blocks.equals(blocks)) e = new Entry(blocks, prepare(blocks), stamp);
            else if (e.stamp != stamp) e = new Entry(e.blocks, e.outline, stamp);
            entries.put(key, e);
            return e.outline;
        }

        public void endFrame() {
            int now = stamp++;
            entries.values().removeIf(e -> e.stamp != now);
        }

        public void clear() {
            entries.clear();
        }
    }

    private static final Outline NO_OUTLINE = new Outline(new int[0], new byte[0], new int[0], Long.MAX_VALUE, 0);

    /** Prepares a block set for drawing many times; see {@link Outline}. An empty collection gives an outline that draws nothing. */
    public static Outline prepare(java.util.Collection<net.minecraft.core.BlockPos> blocks) {
        if (blocks.isEmpty()) return NO_OUTLINE;
        java.util.Set<Long> set = new java.util.HashSet<>();
        for (var p : blocks) set.add(p.asLong());
        long[] keys = new long[set.size()];
        int n = 0;
        for (long k : set) keys[n++] = k;
        java.util.Arrays.sort(keys);
        int[] pos = new int[n * 3];
        byte[] exposed = new byte[n];
        int faces = 0;
        for (int i = 0; i < n; i++) {
            int x = net.minecraft.core.BlockPos.getX(keys[i]), y = net.minecraft.core.BlockPos.getY(keys[i]), z = net.minecraft.core.BlockPos.getZ(keys[i]);
            pos[i * 3] = x; pos[i * 3 + 1] = y; pos[i * 3 + 2] = z;
            int m = 0;
            if (!set.contains(net.minecraft.core.BlockPos.asLong(x, y - 1, z))) m |= 1;
            if (!set.contains(net.minecraft.core.BlockPos.asLong(x, y + 1, z))) m |= 2;
            if (!set.contains(net.minecraft.core.BlockPos.asLong(x, y, z - 1))) m |= 4;
            if (!set.contains(net.minecraft.core.BlockPos.asLong(x, y, z + 1))) m |= 8;
            if (!set.contains(net.minecraft.core.BlockPos.asLong(x - 1, y, z))) m |= 16;
            if (!set.contains(net.minecraft.core.BlockPos.asLong(x + 1, y, z))) m |= 32;
            exposed[i] = (byte) m;
            faces += Integer.bitCount(m);
        }
        List<int[]> e = edges(set);
        e.sort((a, b) -> {
            for (int i : new int[] {0, 3, 4, 1}) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
            return 0;
        });
        int[] flat = new int[e.size() * 5];
        for (int i = 0; i < e.size(); i++) System.arraycopy(e.get(i), 0, flat, i * 5, 5);
        return new Outline(pos, exposed, flat, keys[0], faces);
    }

    /** The prepared form of {@link #outline(java.util.Collection, int, float, boolean, int)}: same lines and quads, nothing rebuilt. */
    public void outline(Outline o, int color, float width, boolean xray, int fillAlpha) {
        outline(o, color, width, xray, fillAlpha, true, false);
    }

    public void outline(Outline o, int color, float width, boolean xray, int fillAlpha, boolean lines) {
        outline(o, color, width, xray, fillAlpha, lines, false);
    }

    /** The one drawing path of outlines. What depends on the frame (fade-in, Merge outlines on or off) is decided here, per call. */
    public void outline(Outline o, int color, float width, boolean xray, int fillAlpha, boolean lines, boolean forceMerge) {
        if (o.pos.length == 0) return;
        float in = reveal(o.low, color);
        if (in < 1f) {
            color = Colors.fade(color, in);
            fillAlpha = Math.round(fillAlpha * in);
        }
        int[] pos = o.pos;
        if (!forceMerge && !style.merge) {
            for (int i = 0; i < pos.length; i += 3) {
                AABB b = new AABB(pos[i], pos[i + 1], pos[i + 2], pos[i] + 1, pos[i + 1] + 1, pos[i + 2] + 1);
                if (fillAlpha > 0) fill(b, (color & 0xFFFFFF) | (fillAlpha << 24), xray);
                if (lines) box(b, color, width, xray);
            }
            return;
        }
        if (fillAlpha > 0) {
            int fc = (color & 0xFFFFFF) | (fillAlpha << 24);
            for (int i = 0; i < o.exposed.length; i++) {
                int m = o.exposed[i];
                float x0 = pos[i * 3], y0 = pos[i * 3 + 1], z0 = pos[i * 3 + 2], x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
                if ((m & 1) != 0) quad(fc, xray, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
                if ((m & 2) != 0) quad(fc, xray, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
                if ((m & 4) != 0) quad(fc, xray, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
                if ((m & 8) != 0) quad(fc, xray, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
                if ((m & 16) != 0) quad(fc, xray, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
                if ((m & 32) != 0) quad(fc, xray, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
            }
        }
        if (lines) {
            int[] e = o.edges;
            for (int i = 0; i < e.length; i += 5) {
                switch (e[i]) {
                    case 0 -> line(e[i + 1], e[i + 3], e[i + 4], e[i + 2], e[i + 3], e[i + 4], color, width, xray);
                    case 1 -> line(e[i + 3], e[i + 1], e[i + 4], e[i + 3], e[i + 2], e[i + 4], color, width, xray);
                    default -> line(e[i + 3], e[i + 4], e[i + 1], e[i + 3], e[i + 4], e[i + 2], color, width, xray);
                }
            }
        }
    }

    /** Lines for edges as edges() gives them ({axis, from, to, u, v}). */
    public void edgeLines(List<int[]> edges, int color, float width, boolean xray) {
        for (int[] e : edges) {
            switch (e[0]) {
                case 0 -> line(e[1], e[3], e[4], e[2], e[3], e[4], color, width, xray);
                case 1 -> line(e[3], e[1], e[4], e[3], e[2], e[4], color, width, xray);
                default -> line(e[3], e[4], e[1], e[3], e[4], e[2], color, width, xray);
            }
        }
    }

    /**
     * A floor-to-sky column over a find (Base Finder's Columns, Stash Finder, Sky Fingerprint, Simulation Ring). The old look draws the
     * box (and fill). With Base Finder's Declutter on it is one thin line at cx, cz instead, left out over a base Base Finder already
     * marks, and left out close by (Column fade), fading in over the next 48 blocks: a column only helps to find a place from afar.
     */
    public void column(AABB legacy, int lineColor, float legacyWidth, int fillColor, double cx, double cz) {
        Policy bf = policy;
        if (bf == null || !bf.declutterOn()) {
            if (fillColor != 0) fill(legacy, fillColor, true);
            box(legacy, lineColor, legacyWidth, true);
            return;
        }
        if (bf.siteNear(cx, cz)) { columnsSkippedNow++; return; }
        double d = Math.hypot(cx - cam.x, cz - cam.z);
        float a = (float) Math.max(0, Math.min(1, (d - bf.columnFade()) / 48.0));
        if (a <= 0) return;
        line(cx, legacy.minY, cz, cx, legacy.maxY, cz, Colors.fade(lineColor, a), RenderStyle.WEIGHT, true);
    }

    private record EdgeKey(int axis, int u, int v) {}

    /** The visible edges of a block set as {axis, from, to, u, v}: axis 0 = x (u = y, v = z), 1 = y (u = x, v = z), 2 = z (u = x, v = y). */
    public static List<int[]> edges(java.util.Set<Long> set) {
        java.util.Map<EdgeKey, List<Integer>> runs = new java.util.HashMap<>();
        for (long k : set) {
            int x = net.minecraft.core.BlockPos.getX(k), y = net.minecraft.core.BlockPos.getY(k), z = net.minecraft.core.BlockPos.getZ(k);
            for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) {
                int da = a == 1 ? 1 : -1, db = b == 1 ? 1 : -1;
                // along x: neighbours across y and z
                if (keep(set, x, y + da, z, x, y, z + db, x, y + da, z + db)) runs.computeIfAbsent(new EdgeKey(0, y + a, z + b), q -> new ArrayList<>()).add(x);
                // along y: neighbours across x and z
                if (keep(set, x + da, y, z, x, y, z + db, x + da, y, z + db)) runs.computeIfAbsent(new EdgeKey(1, x + a, z + b), q -> new ArrayList<>()).add(y);
                // along z: neighbours across x and y
                if (keep(set, x + da, y, z, x, y + db, z, x + da, y + db, z)) runs.computeIfAbsent(new EdgeKey(2, x + a, y + b), q -> new ArrayList<>()).add(z);
            }
        }
        List<int[]> out = new ArrayList<>();
        for (var e : runs.entrySet()) {
            List<Integer> l = e.getValue();
            l.sort(null);
            int from = l.get(0), to = from + 1;
            for (int i = 1; i < l.size(); i++) {
                int c = l.get(i);
                if (c == to) { to = c + 1; continue; }
                out.add(new int[] {e.getKey().axis, from, to, e.getKey().u, e.getKey().v});
                from = c; to = c + 1;
            }
            out.add(new int[] {e.getKey().axis, from, to, e.getKey().u, e.getKey().v});
        }
        return out;
    }

    private static boolean keep(java.util.Set<Long> s, int x1, int y1, int z1, int x2, int y2, int z2, int x3, int y3, int z3) {
        boolean n1 = s.contains(net.minecraft.core.BlockPos.asLong(x1, y1, z1)), n2 = s.contains(net.minecraft.core.BlockPos.asLong(x2, y2, z2));
        if (!n1 && !n2) return true;
        return n1 && n2 && !s.contains(net.minecraft.core.BlockPos.asLong(x3, y3, z3));
    }

    /** Geometry a module draws by itself with the frame's buffers (textured, lit, writing depth), before the flat quads. Positions are sent minus the camera (cx, cy, cz). */
    public interface Custom { void draw(PoseStack.Pose pose, MultiBufferSource src, float cx, float cy, float cz); }

    private final List<Custom> customs = new ArrayList<>();

    public void custom(Custom c) { customs.add(c); }

    public void quad(int color, boolean xray, float... v) {
        quads.add(new Quad(v, color, xray));
    }

    public void label(Vec3 pos, String text, int color) {
        labels.add(new Label(pos, text, color, 1f, owner));
    }

    public void label(Vec3 pos, String text, int color, float scale) {
        labels.add(new Label(pos, text, color, scale, owner));
    }

    public boolean empty() {
        return customs.isEmpty() && lines.isEmpty() && quads.isEmpty() && labels.isEmpty() && sboxes.isEmpty() && pendingFill.isEmpty() && tileBatches.isEmpty() && tileSets.isEmpty() && depthLabels.isEmpty();
    }

    // ---- drawing --------------------------------------------------------------------------------

    public void flush(PoseStack ps, MultiBufferSource src) {
        flushes++;
        dLabelsHidden = 0;
        if (style.declutter) resolveBoxes();
        else { dIn = dDrawn = dGroups = dDropped = dLabelsReplaced = 0; }
        dLines = lines.size();
        dQuads = quads.size();
        dev.vantage.core.Perf.add("R3 lines (count)", lines.size());
        dev.vantage.core.Perf.add("R3 quads (count)", quads.size());
        if (measure) {
            outlineMin = Float.MAX_VALUE; outlineMax = 0; fillMin = 255; fillMax = 0;
            for (Line l : lines) if (!l.tracer) { outlineMin = Math.min(outlineMin, l.width); outlineMax = Math.max(outlineMax, l.width); }
            for (Quad q : quads) { int al = q.color >>> 24; fillMin = Math.min(fillMin, al); fillMax = Math.max(fillMax, al); }
        }
        PoseStack.Pose pose = ps.last();
        float cx = (float) cam.x, cy = (float) cam.y, cz = (float) cam.z;
        for (Custom c : customs) c.draw(pose, src, cx, cy, cz);
        dTilesIn = dTiles = dTileRects = 0;
        for (TileBatch b : tileBatches) dTilesIn += b.count;
        for (TileSetDraw d : tileSets) dTilesIn += d.set.tiles;
        var look = camera.forwardVector();
        final float fx = look.x(), fy = look.y(), fz = look.z();
        for (boolean xray : new boolean[] {false, true}) {
            boolean tileFills = false, tileLines = false;
            for (TileBatch b : tileBatches) {
                if (((b.flags & THROUGH_WALLS) != 0) != xray) continue;
                tileFills |= (b.fill >>> 24) != 0;
                tileLines |= (b.line >>> 24) != 0;
            }
            for (TileSetDraw d : tileSets) {
                if (((d.flags & THROUGH_WALLS) != 0) != xray) continue;
                tileFills |= (d.fill >>> 24) != 0;
                tileLines |= (d.line >>> 24) != 0;
            }
            if (tileFills || quads.stream().anyMatch(q -> q.xray == xray)) {
                VertexConsumer vc = src.getBuffer(Pipelines.quads(xray));
                boolean fadeFills = style.fillsFade;
                for (Quad q : quads) {
                    if (q.xray != xray) continue;
                    float[] v = q.v;
                    int col = q.color;
                    if (fadeFills || (q.flags & FADE) != 0) {
                        double mx = (v[0] + v[6]) / 2 - cx, my = (v[1] + v[7]) / 2 - cy, mz = (v[2] + v[8]) / 2 - cz;
                        double dist = Math.sqrt(mx * mx + my * my + mz * mz);
                        col = Colors.fade(col, q.flags == 0 ? style.fade(dist) : strength(q.flags, dist, fadeFills));
                    }
                    for (int i = 0; i < 12; i += 3) vc.addVertex(pose, v[i] - cx, v[i + 1] - cy, v[i + 2] - cz).setColor(col);
                }
                // the tile batches: straight from the key arrays into the buffer
                for (TileBatch b : tileBatches) {
                    if (((b.flags & THROUGH_WALLS) != 0) != xray || (b.fill >>> 24) == 0) continue;
                    boolean fades = (b.flags & NO_FADE) == 0 && (fadeFills || (b.flags & FADE) != 0);
                    float y = b.y - cy;
                    long[] keys = b.keys;
                    for (int i = 0; i < b.count; i++) {
                        float x0 = (net.minecraft.world.level.ChunkPos.getX(keys[i]) << 4) - cx, z0 = (net.minecraft.world.level.ChunkPos.getZ(keys[i]) << 4) - cz;
                        float mx = x0 + 8, mz = z0 + 8;
                        if (mx * fx + y * fy + mz * fz < -24) continue; // behind the camera
                        int col = fades ? Colors.fade(b.fill, fadeAt(Math.sqrt(mx * mx + y * y + mz * mz))) : b.fill;
                        vc.addVertex(pose, x0, y, z0).setColor(col);
                        vc.addVertex(pose, x0 + 16, y, z0).setColor(col);
                        vc.addVertex(pose, x0 + 16, y, z0 + 16).setColor(col);
                        vc.addVertex(pose, x0, y, z0 + 16).setColor(col);
                        dTiles++;
                    }
                }
                // the prepared sets: a few merged rectangles each
                for (TileSetDraw d : tileSets) {
                    if (((d.flags & THROUGH_WALLS) != 0) != xray || (d.fill >>> 24) == 0) continue;
                    boolean fades = (d.flags & NO_FADE) == 0 && (fadeFills || (d.flags & FADE) != 0);
                    float y = d.y - cy;
                    int[] rc = d.set.rects;
                    for (int i = 0; i < rc.length; i += 4) {
                        float x0 = (rc[i] << 4) - cx, z0 = (rc[i + 1] << 4) - cz, x1 = (rc[i + 2] << 4) - cx, z1 = (rc[i + 3] << 4) - cz;
                        float mx = (x0 + x1) / 2, mz = (z0 + z1) / 2;
                        if (mx * fx + y * fy + mz * fz < -TileSet.SPAN * 16) continue; // behind the camera, with room for the largest rectangle
                        int col = fades ? Colors.fade(d.fill, fadeAt(Math.sqrt(mx * mx + y * y + mz * mz))) : d.fill;
                        vc.addVertex(pose, x0, y, z0).setColor(col);
                        vc.addVertex(pose, x1, y, z0).setColor(col);
                        vc.addVertex(pose, x1, y, z1).setColor(col);
                        vc.addVertex(pose, x0, y, z1).setColor(col);
                        dTileRects++;
                    }
                    dTiles += d.set.tiles;
                }
                long tq = System.nanoTime();
                if (src instanceof MultiBufferSource.BufferSource bs) bs.endBatch(Pipelines.quads(xray));
                dev.vantage.core.Perf.add("R3 submit quads", System.nanoTime() - tq);
            }
            // the depth cue: through-wall lines are drawn twice, dim everywhere and crisp again where nothing hides them
            boolean cue = style.depth;
            boolean any = tileLines || lines.stream().anyMatch(l -> l.xray == xray || (cue && l.xray && !xray && (l.flags & PLAIN_LINES) == 0));
            if (any) {
                VertexConsumer vc = src.getBuffer(Pipelines.lines(xray));
                boolean glow = style.glow;
                for (Line l : lines) {
                    boolean plain = (l.flags & PLAIN_LINES) != 0;
                    if (l.xray != xray && !(cue && l.xray && !xray && !plain)) continue;
                    double x1 = l.x1 - cam.x, y1 = l.y1 - cam.y, z1 = l.z1 - cam.z, x2 = l.x2 - cam.x, y2 = l.y2 - cam.y, z2 = l.z2 - cam.z;
                    double mx = (x1 + x2) / 2, my = (y1 + y2) / 2, mz = (z1 + z2) / 2;
                    double d = Math.sqrt(mx * mx + my * my + mz * mz);
                    float a = (l.flags & (NO_FADE | FADE)) == 0 ? style.fade(d) : strength(l.flags, d, false);
                    if (cue && l.xray && xray && !plain) a *= 0.38f; // the hidden copy
                    int col = Colors.fade(l.color, a);
                    float w = style.widthAt(l.width, d);
                    // the glow: the same line three times as wide at 18% alpha, drawn first so the crisp line sits on top; tracers stay thin
                    if (glow && !l.tracer && !plain) lineVerts(vc, pose, x1, y1, z1, x2, y2, z2, Colors.fade(col, 0.18f), w * 3);
                    lineVerts(vc, pose, x1, y1, z1, x2, y2, z2, col, w);
                }
                // tile outlines: four plain segments per tile
                for (TileBatch b : tileBatches) {
                    if (((b.flags & THROUGH_WALLS) != 0) != xray || (b.line >>> 24) == 0) continue;
                    boolean fades = (b.flags & NO_FADE) == 0 && (style.fade(200) < 1f || (b.flags & FADE) != 0);
                    double y = b.y - cam.y;
                    long[] keys = b.keys;
                    for (int i = 0; i < b.count; i++) {
                        double x0 = (net.minecraft.world.level.ChunkPos.getX(keys[i]) << 4) - cam.x, z0 = (net.minecraft.world.level.ChunkPos.getZ(keys[i]) << 4) - cam.z;
                        double mx = x0 + 8, mz = z0 + 8;
                        if (mx * fx + y * fy + mz * fz < -24) continue;
                        double d = Math.sqrt(mx * mx + y * y + mz * mz);
                        int col = fades ? Colors.fade(b.line, fadeAt(d)) : b.line;
                        float w = style.widthAt(b.width, d);
                        lineVerts(vc, pose, x0, y, z0, x0 + 16, y, z0, col, w);
                        lineVerts(vc, pose, x0 + 16, y, z0, x0 + 16, y, z0 + 16, col, w);
                        lineVerts(vc, pose, x0 + 16, y, z0 + 16, x0, y, z0 + 16, col, w);
                        lineVerts(vc, pose, x0, y, z0 + 16, x0, y, z0, col, w);
                    }
                }
                // the prepared sets: only the outside border, in long segments
                for (TileSetDraw d : tileSets) {
                    if (((d.flags & THROUGH_WALLS) != 0) != xray || (d.line >>> 24) == 0) continue;
                    boolean fades = (d.flags & NO_FADE) == 0 && (style.fade(200) < 1f || (d.flags & FADE) != 0);
                    double y = d.y - cam.y;
                    int[] bd = d.set.border;
                    for (int i = 0; i < bd.length; i += 4) {
                        double x0 = (bd[i] << 4) - cam.x, z0 = (bd[i + 1] << 4) - cam.z, x1 = (bd[i + 2] << 4) - cam.x, z1 = (bd[i + 3] << 4) - cam.z;
                        double mx = (x0 + x1) / 2, mz = (z0 + z1) / 2;
                        if (mx * fx + y * fy + mz * fz < -TileSet.SPAN * 16) continue;
                        double dist = Math.sqrt(mx * mx + y * y + mz * mz);
                        lineVerts(vc, pose, x0, y, z0, x1, y, z1, fades ? Colors.fade(d.line, fadeAt(dist)) : d.line, style.widthAt(d.width, dist));
                    }
                }
                long tl = System.nanoTime();
                if (src instanceof MultiBufferSource.BufferSource bs) bs.endBatch(Pipelines.lines(xray));
                dev.vantage.core.Perf.add("R3 submit lines", System.nanoTime() - tl);
            }
        }
        if (measure) { // only the in-game checks read this, and each of them runs with measure on
            columnsNear = 0;
            for (Line l : lines) {
                if (Math.abs(l.y2 - l.y1) <= 128) continue;
                double mx = (l.x1 + l.x2) / 2 - cam.x, mz = (l.z1 + l.z2) / 2 - cam.z;
                if (mx * mx + mz * mz <= 48 * 48) columnsNear++;
            }
        }
        columnsSkipped = columnsSkippedNow;
        dDepthLabels = depthLabels.size();
        if (!depthLabels.isEmpty()) {
            for (Label l : depthLabels) drawLabel(ps, src, l, 0f, Font.DisplayMode.NORMAL);
            if (src instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
        }
        long tb = System.nanoTime();
        List<Label> show = budget();
        if (style.declutter) show = declutterLabels(show);
        dev.vantage.core.Perf.add("R3 label budget", System.nanoTime() - tb);
        if (!show.isEmpty()) {
            long tn = System.nanoTime();
            nudge(show);
            dev.vantage.core.Perf.add("R3 label nudge", System.nanoTime() - tn);
            if (style.labelBacking) {
                VertexConsumer vc = src.getBuffer(Pipelines.quads(true));
                for (int i = 0; i < show.size(); i++) drawPill(ps, vc, show.get(i), i < NUDGE_MAX ? nudgeUp[i] : 0f);
                if (src instanceof MultiBufferSource.BufferSource bs) bs.endBatch(Pipelines.quads(true));
            }
            for (int i = 0; i < show.size(); i++) drawLabel(ps, src, show.get(i), i < NUDGE_MAX ? nudgeUp[i] : 0f);
            if (measure) {
                List<float[]> rs = new ArrayList<>();
                List<String> ts = new ArrayList<>();
                List<Boolean> nm = new ArrayList<>();
                for (int i = 0; i < show.size(); i++) { float[] r = rect(show.get(i), i < NUDGE_MAX ? nudgeUp[i] : 0f); if (r != null) { rs.add(r); ts.add(show.get(i).text); nm.add(kind(show.get(i).owner) == NAME); } }
                dLabelNames = nm;
                dLabelRects = rs;
                dTexts = ts;
            }
            if (src instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
        } else if (measure) { // a frame that draws no label leaves no label rectangles behind (the rig used to read the last scene's)
            dLabelNames = List.of();
            dLabelRects = List.of();
            dTexts = List.of();
        }
    }


    // ---- Declutter (beta): group outlines, box cap -----------------------------------------------------------------------

    /** Rig stats of the last flush: small boxes seen, boxes + groups drawn, groups, folded into "+N more", member labels replaced, labels hidden. */
    public static long flushes;
    public static List<String> dTexts = List.of();
    public static int dLabelsShifted, dIn, dDrawn, dGroups, dDropped, dLabelsReplaced, dLabelsHidden, dLines, dQuads;
    /** Screen rectangles of the labels the last flush drew (design units), only with {@link #measure}. */
    public static List<float[]> dLabelRects = List.of();
    /** Rig debug: the card rectangles declutterLabels avoided in the last flush. */
    public static List<String> dCardsSeen = List.of();
    /** Rig: for each rect of dLabelRects, whether it is a player/waypoint name (those always show, only nudged). */
    public static List<Boolean> dLabelNames = List.of();
    public static final int GROUP_EXTENT = 24;
    /** Group count labels are this much bigger than other labels and do not shrink with distance: readable at 640 px wide. */
    public static final float GROUP_LABEL = 1.9f;
    /** Rig: silhouette cache hits and misses of the last flush, and the time resolveBoxes took (ns). */
    public static int dCacheHit, dCacheMiss;
    public static long dResolveNs;
    /** Rig: the silhouette edges of every group drawn in the last flush. */
    public static List<List<int[]>> dGroupEdges = new ArrayList<>();
    private static final java.util.Map<Long, Object[]> SIL = new java.util.HashMap<>();

    private static boolean small(AABB b) {
        return b.getXsize() <= 3.0 && b.getYsize() <= 3.0 && b.getZsize() <= 3.0;
    }

    private boolean holdable(AABB b) {
        return style.declutter && owner != null && owner.groupsBoxes() && small(b);
    }

    private boolean holdFill(AABB b, int color, boolean xray) {
        if (!holdable(b)) return false;
        pendingFill.put(b, new int[] {color, xray ? 1 : 0});
        return true;
    }

    private boolean holdBox(AABB b, int color, float width, boolean xray) {
        if (!holdable(b)) return false;
        int[] f = pendingFill.remove(b);
        sboxes.add(new SBox(b, color, width, f == null ? 0 : f[0], xray, owner, kind));
        return true;
    }

    private static final class Item {
        AABB b;
        int n = 1;
        SBox first;
        boolean group;
        double dist;
        Label label;
        java.util.Set<Long> cells;
        long sig;
        Item(AABB b, SBox first) { this.b = b; this.first = first; }
    }

    private static double boxDist(AABB b, Vec3 p) {
        double dx = Math.max(0, Math.max(b.minX - p.x, p.x - b.maxX)), dy = Math.max(0, Math.max(b.minY - p.y, p.y - b.maxY)), dz = Math.max(0, Math.max(b.minZ - p.z, p.z - b.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String plural(String noun, int n) {
        return n + " " + (noun.endsWith("s") ? noun : noun + "s");
    }

    private static int find(int[] up, int i) {
        while (up[i] != i) { up[i] = up[up[i]]; i = up[i]; }
        return i;
    }

    /**
     * Turns the held small boxes into what is drawn: touching boxes of one owner, kind and colour (within a block of each other, no
     * cluster wider than {@link #GROUP_EXTENT}) become one outline with a count label once there are Group-from of them; the rest
     * stay single. Groups first and then singles by distance are kept up to Max boxes, the rest is folded into one "+N more".
     * Standing inside a group it is shown as its ten nearest boxes instead, with the count label above the nearest.
     */
    private void resolveBoxes() {
        long t0 = System.nanoTime();
        try { resolveBoxes0(); } finally { dResolveNs = System.nanoTime() - t0; dev.vantage.core.Perf.add("R3 declutter", dResolveNs); }
    }

    private void resolveBoxes0() {
        dCacheHit = 0; dCacheMiss = 0;
        dGroupEdges = new ArrayList<>();
        for (var e : pendingFill.entrySet()) sboxes.add(new SBox(e.getKey(), 0, 0f, e.getValue()[0], e.getValue()[1] == 1, owner, null)); // fill-only marks
        pendingFill.clear();
        int n = sboxes.size();
        dIn = n; dDrawn = 0; dGroups = 0; dDropped = 0; dLabelsReplaced = 0;
        if (n == 0) return;
        boolean groupOn = style.groupBoxes;
        int min = style.groupMin;
        int[] up = new int[n];
        for (int i = 0; i < n; i++) up[i] = i;
        double[][] ext = new double[n][6];
        for (int i = 0; i < n; i++) { var b = sboxes.get(i).b; ext[i] = new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ}; }
        if (groupOn) {
            java.util.Map<Long, List<Integer>> cells = new java.util.HashMap<>();
            for (int i = 0; i < n; i++) {
                var c = sboxes.get(i).b.getCenter();
                cells.computeIfAbsent(cellKey((int) Math.floor(c.x / 2), (int) Math.floor(c.y / 2), (int) Math.floor(c.z / 2)), k -> new ArrayList<>()).add(i);
            }
            for (int i = 0; i < n; i++) {
                SBox a = sboxes.get(i);
                var c = a.b.getCenter();
                int cx = (int) Math.floor(c.x / 2), cy = (int) Math.floor(c.y / 2), cz = (int) Math.floor(c.z / 2);
                AABB near = a.b.inflate(0.3);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    List<Integer> cell = cells.get(cellKey(cx + dx, cy + dy, cz + dz));
                    if (cell == null) continue;
                    for (int j : cell) {
                        if (j <= i) continue;
                        SBox b = sboxes.get(j);
                        if (b.owner != a.owner || b.xray != a.xray || (b.lineColor & 0xFFFFFF) != (a.lineColor & 0xFFFFFF) || b.lineColor == 0 != (a.lineColor == 0)
                                || !java.util.Objects.equals(b.kind, a.kind) || !near.intersects(b.b)) continue;
                        int ra = find(up, i), rb = find(up, j);
                        if (ra == rb) continue;
                        double[] ea = ext[ra], eb = ext[rb];
                        boolean ok = true;
                        for (int k = 0; k < 3; k++) if (Math.max(ea[k + 3], eb[k + 3]) - Math.min(ea[k], eb[k]) > GROUP_EXTENT) { ok = false; break; }
                        if (!ok) continue;
                        up[rb] = ra;
                        for (int k = 0; k < 3; k++) { ea[k] = Math.min(ea[k], eb[k]); ea[k + 3] = Math.max(ea[k + 3], eb[k + 3]); }
                    }
                }
            }
        }
        java.util.Map<Integer, List<Integer>> byRoot = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) byRoot.computeIfAbsent(find(up, i), k -> new ArrayList<>()).add(i);
        List<Item> groups = new ArrayList<>(), singles = new ArrayList<>();
        List<Label> add = new ArrayList<>();
        List<AABB> groupBounds = new ArrayList<>();
        List<dev.vantage.core.Module> groupOwners = new ArrayList<>();
        for (var e : byRoot.entrySet()) {
            List<Integer> m = e.getValue();
            SBox f = sboxes.get(m.get(0));
            if (m.size() < min) {
                for (int i : m) { var sb = sboxes.get(i); var it = new Item(sb.b, sb); it.dist = boxDist(sb.b, cam); singles.add(it); }
                continue;
            }
            double[] x = ext[e.getKey()];
            AABB gb = new AABB(x[0], x[1], x[2], x[3], x[4], x[5]);
            String noun = f.kind != null ? f.kind : f.owner == null ? "marks" : f.owner.name.toLowerCase();
            String text = plural(noun, m.size());
            groupBounds.add(gb.inflate(2.5));
            groupOwners.add(f.owner);
            if (gb.inflate(0.5).contains(cam)) {
                // inside the cluster: the ten nearest boxes stay, the count hangs above the nearest one
                List<Integer> byD = new ArrayList<>(m);
                byD.sort(java.util.Comparator.comparingDouble(i -> sboxes.get(i).b.getCenter().distanceToSqr(cam)));
                for (int k = 0; k < Math.min(10, byD.size()); k++) { var sb = sboxes.get(byD.get(k)); var it = new Item(sb.b, sb); it.dist = boxDist(sb.b, cam); singles.add(it); }
                var nb = sboxes.get(byD.get(0)).b;
                add.add(new Label(new Vec3(nb.getCenter().x, nb.maxY + 0.7, nb.getCenter().z), text, f.lineColor != 0 ? f.lineColor : f.fillColor, GROUP_LABEL, f.owner, 30));
                continue;
            }
            var it = new Item(gb, f);
            it.n = m.size(); it.group = true; it.dist = boxDist(gb, cam);
            it.cells = new java.util.HashSet<>();
            for (int i : m) it.cells.add(net.minecraft.core.BlockPos.containing(sboxes.get(i).b.getCenter()).asLong());
            long h = it.cells.size();
            for (long c : it.cells) h += (c ^ (c >>> 29)) * 0x9E3779B97F4A7C15L;
            it.sig = h * 31 + (f.lineColor & 0xFFFFFF);
            groups.add(it);
            if (it.dist < 200) it.label = new Label(new Vec3((x[0] + x[3]) / 2, x[4] + 0.6, (x[2] + x[5]) / 2), text, f.lineColor != 0 ? f.lineColor : f.fillColor, GROUP_LABEL, f.owner, 30); // far groups lose the label rather than show an unreadable one
        }
        // the labels of members are replaced by the group's one
        if (!groupBounds.isEmpty()) {
            int before = labels.size();
            labels.removeIf(l -> {
                for (int i = 0; i < groupBounds.size(); i++) if (l.owner == groupOwners.get(i) && l.boost == 0 && groupBounds.get(i).contains(l.pos)) return true;
                return false;
            });
            dLabelsReplaced = before - labels.size();
        }
        groups.sort(java.util.Comparator.comparingDouble(i -> i.dist));
        singles.sort(java.util.Comparator.comparingDouble(i -> i.dist));
        List<Item> all = new ArrayList<>(groups);
        all.addAll(singles);
        int cap = style.maxBoxes;
        Item nearestDropped = null;
        double bestDot = -2;
        int dropped = 0;
        for (int i = 0; i < all.size(); i++) {
            Item it = all.get(i);
            if (i >= cap) {
                dropped += it.n;
                // the label goes on the dropped box closest to where the player looks, so it is on screen when anything of them is
                var f0 = camera.forwardVector();
                var c0 = it.b.getCenter().subtract(cam);
                double dot = c0.length() < 1e-6 ? 1 : (c0.x * f0.x() + c0.y * f0.y() + c0.z * f0.z()) / c0.length();
                if (nearestDropped == null || dot > bestDot) { nearestDropped = it; bestDot = dot; }
                continue;
            }
            SBox f = it.first;
            if (it.group && style.groupOutline) {
                // the real silhouette of the cluster: outer edges only, no fill unless asked (a fill per group is what stacked into slabs)
                List<int[]> edges = silhouette(it);
                if (measure) dGroupEdges.add(edges);
                edgeLines(edges, f.lineColor != 0 ? f.lineColor : f.fillColor, Math.max(f.width, RenderStyle.WEIGHT) * 1.2f, f.xray);
                if (style.groupFill && f.fillColor != 0) outline(it.cells.stream().map(net.minecraft.core.BlockPos::of).toList(), f.fillColor | 0xFF000000, 0f, f.xray, f.fillColor >>> 24, false, true);
            } else {
                if (f.fillColor != 0 && !(it.group && !style.groupFill)) fillQuads(it.b, f.fillColor, f.xray);
                if (f.lineColor != 0) boxLines(it.b, it.group ? f.lineColor : f.lineColor, it.group ? f.width * 1.2f : f.width, f.xray);
            }
            dDrawn++;
            if (it.group) { dGroups++; if (it.label != null) add.add(it.label); } // only a group that is drawn gets its count
        }
        dDropped = dropped;
        if (nearestDropped != null) {
            var c = nearestDropped.b.getCenter();
            SBox f = nearestDropped.first;
            labels.add(new Label(new Vec3(c.x, nearestDropped.b.minY - 0.4, c.z), "+" + dropped + " more", f.lineColor != 0 ? f.lineColor : f.fillColor, 0.8f, f.owner, 35));
        }
        labels.addAll(add);
    }

    /** The outer edges of the union of a group's cells, kept per group (keyed by its member set) and rebuilt only when the members change. */
    private List<int[]> silhouette(Item it) {
        Object[] hit = SIL.get(it.sig);
        if (hit != null) { hit[1] = flushes; dCacheHit++; @SuppressWarnings("unchecked") List<int[]> e = (List<int[]>) hit[0]; return e; }
        dCacheMiss++;
        List<int[]> e = edges(it.cells);
        SIL.put(it.sig, new Object[] {e, flushes});
        if (SIL.size() > 400) SIL.values().removeIf(v -> flushes - (long) v[1] > 200);
        return e;
    }

    /**
     * Forgets what the drawing layer remembers about the world that was just left: the silhouette cache, the fade-in times of veins
     * (keyed by block position, so a new world must not inherit them) and the card rectangles of the last frames. Called from the
     * world-change block of VantageClient.
     */
    public static void forgetWorld() {
        SIL.clear();
        REVEAL.clear();
        CARD_HIST[0] = CARD_HIST[1] = CARD_HIST[2] = null;
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // Java cannot create an array of a generic type directly
    private static final List<float[]>[] CARD_HIST = new List[3];

    private static int priority(Label l) {
        int k = kind(l.owner);
        return (k == SUMMARY ? 60 : k == EVIDENCE ? 40 : 20) + l.boost;
    }

    /**
     * Declutter's label pass over what the budget kept: off-screen and out-of-range labels go, player names stay (the nudge moves them),
     * the rest are taken by importance (group counts, then summaries, evidence, others; nearer first) and one that would sit on
     * a taken label or a card is hidden, up to Max labels.
     */
    private List<Label> declutterLabels(List<Label> in) {
        int cap = style.maxLabels;
        List<float[]> taken = new ArrayList<>();
        List<Label> rest = new ArrayList<>();
        java.util.Set<Label> keep = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Label l : in) {
            if (rangeFade(cam.distanceTo(l.pos)) <= 0f) continue;
            float[] r = rect(l);
            if (r == null) continue;
            if (kind(l.owner) == NAME) { keep.add(l); taken.add(r); } else rest.add(l);
        }
        rest.sort((a, b) -> priority(a) != priority(b) ? priority(b) - priority(a) : Double.compare(cam.distanceToSqr(a.pos), cam.distanceToSqr(b.pos)));
        // a dead zone round the crosshair: a label that would sit on it shifts like it would for any other label
        taken.add(new float[] {dev.vantage.core.Ui.W() / 2 - 14, dev.vantage.core.Ui.H() / 2 - 14, 28, 28});
        // cards are laid out after the labels in a frame, and one can flip between two places from frame to frame: labels keep clear of where they were in the last three frames
        CARD_HIST[2] = CARD_HIST[1]; CARD_HIST[1] = CARD_HIST[0]; CARD_HIST[0] = dev.vantage.core.Callouts.lastRects();
        List<float[]> cards = new ArrayList<>();
        for (List<float[]> h : CARD_HIST) if (h != null) cards.addAll(h);
        if (measure) dCardsSeen = cards.stream().map(java.util.Arrays::toString).toList(); // the strings are only for the rig's info line
        int n = 0, shifted = 0;
        List<Label> moved = new ArrayList<>();
        var up = camera.upVector();
        for (Label l : rest) {
            if (n >= cap) break;
            // first where it is, then lifted up to three label heights (shifting is better than hiding), else it is hidden
            float step = labelScale(l, cam.distanceTo(l.pos)) * 11f;
            Label use = null;
            float[] at = null;
            for (int k = 0; k <= 3 && use == null; k++) {
                Label c = k == 0 ? l : new Label(l.pos.add(up.x() * k * step, up.y() * k * step, up.z() * k * step), l.text, l.color, l.scale, l.owner, l.boost);
                float[] r = rect(c);
                if (r == null) break;
                boolean free = true;
                for (float[] t : taken) if (overlap(r, t, 3)) { free = false; break; }
                if (free) for (float[] t : cards) if (overlap(r, t, 3)) { free = false; break; }
                if (free) { use = c; at = r; if (k > 0) shifted++; }
            }
            if (use == null) continue;
            moved.add(use);
            taken.add(at);
            n++;
        }
        List<Label> out = new ArrayList<>();
        for (Label l : in) if (keep.contains(l)) out.add(l);
        out.addAll(moved);
        dLabelsHidden = in.size() - out.size();
        dLabelsShifted = shifted;
        return out;
    }

    // ---- label look: size, range fade, nudge, pill ------------------------------------------------------------------

    private static final int NUDGE_MAX = 64, NUDGE_STEPS = 6;
    /** Reused every frame: where the first NUDGE_MAX labels sit on screen and how far (world units, camera up) each was moved. */
    private static final float[][] nudgeRect = new float[NUDGE_MAX][4];
    private static final float[] nudgeUp = new float[NUDGE_MAX];

    /** The world scale of a label's text at this distance (before any nudge). */
    private float labelScale(Label l, double dist) {
        // a label keeps one screen size at any distance (world size grows with it, from 2 blocks out); with shrinking it eases down to the minimum by about 15 blocks
        float scale = 0.021f * l.scale * style.labelSize * (float) Math.max(0.25, dist / 8.0);
        if (style.shrink && !(l.boost == 30 && style.declutter)) scale *= (float) Math.max(style.minLabel, Math.min(1.0, 8.0 / dist));
        return scale;
    }

    /** 1 close up, down to 0 over the last third of Label range, on top of the distance fade. */
    private float rangeFade(double dist) {
        float range = style.labelRange;
        if (dist >= range) return 0f;
        float start = range * 0.67f;
        return dist <= start ? 1f : (float) ((range - dist) / (range - start));
    }

    private static boolean hits(float[] r, int n, float pad) {
        for (int i = 0; i < n; i++) if (overlap(r, nudgeRect[i], pad)) return true;
        return false;
    }

    /**
     * The simple nudge: labels are placed in order; one that would sit on top of one already placed climbs a label height at a time
     * (up to six times) until it is free, and stays put when no spot is free. Only the first 64 labels are looked at.
     */
    private void nudge(List<Label> show) {
        int n = Math.min(show.size(), NUDGE_MAX), placed = 0, moved = 0;
        for (int i = 0; i < n; i++) {
            nudgeUp[i] = 0f;
            Label l = show.get(i);
            double dist = cam.distanceTo(l.pos);
            if (rangeFade(dist) <= 0f) { nudgeRect[i][2] = 0; continue; }
            float[] r = rect(l, 0f);
            if (r == null) { nudgeRect[i][2] = 0; continue; }
            if (hits(r, placed, 1f)) {
                float step = labelScale(l, dist) * 11f;
                for (int k = 1; k <= NUDGE_STEPS; k++) {
                    float[] r2 = rect(l, k * step);
                    if (r2 == null) break;
                    if (!hits(r2, placed, 1f)) { r = r2; nudgeUp[i] = k * step; moved++; break; }
                }
            }
            System.arraycopy(r, 0, nudgeRect[placed], 0, 4);
            placed++;
        }
        labelsNudged = moved;
        if (measure) {
            int over = 0;
            for (int i = 0; i < placed; i++) for (int j = i + 1; j < placed; j++) if (overlap(nudgeRect[i], nudgeRect[j], 0)) over++;
            labelOverlapsAfter = over;
        }
    }

    // ---- the world label budget ------------------------------------------------------------------

    private static final int NAME = dev.vantage.core.Module.LABEL_NAME, SUMMARY = dev.vantage.core.Module.LABEL_SUMMARY, EVIDENCE = dev.vantage.core.Module.LABEL_EVIDENCE, OTHER = dev.vantage.core.Module.LABEL_OTHER;

    private static int kind(dev.vantage.core.Module m) {
        return m == null ? OTHER : m.labelClass();
    }

    /** Screen rectangle of a label in design units (it hangs below its point), or null when it is not on screen. */
    private float[] rect(Label l) {
        return rect(l, 0f);
    }

    /** The same, with the label moved {@code lift} world units up the camera's up axis (the nudge). */
    private float[] rect(Label l, float lift) {
        var up = camera.upVector();
        Vec3 pos = lift == 0f ? l.pos : l.pos.add(up.x() * lift, up.y() * lift, up.z() * lift);
        float[] c = dev.vantage.core.Ui.project(pos);
        if (c == null) return null;
        double dist = cam.distanceTo(l.pos);
        float scale = labelScale(l, dist);
        double w = (mc.font.width(dev.vantage.core.Ui.inter(l.text, false)) + 8) * scale, h = 12 * scale;
        var left = camera.leftVector();
        float[] side = dev.vantage.core.Ui.project(pos.add(left.x() * w / 2, left.y() * w / 2, left.z() * w / 2));
        float[] low = dev.vantage.core.Ui.project(pos.subtract(up.x() * h, up.y() * h, up.z() * h));
        if (side == null || low == null) return null;
        float hw = Math.abs(side[0] - c[0]), hh = Math.max(1, low[1] - c[1]);
        float W = dev.vantage.core.Ui.W(), H = dev.vantage.core.Ui.H();
        float[] r = {c[0] - hw, c[1], 2 * hw, hh};
        if (r[0] + r[2] < 0 || r[0] > W || r[1] + r[3] < 0 || r[1] > H) return null;
        return r;
    }

    private static boolean overlap(float[] a, float[] b, float pad) {
        return a[0] < b[0] + b[2] + pad && a[0] + a[2] + pad > b[0] && a[1] < b[1] + b[3] + pad && a[1] + a[3] + pad > b[1];
    }

    /**
     * Which labels get drawn. The old look: all of them. With Base Finder's Declutter: summary labels over a base Base Finder already
     * describes go, small evidence labels only show close by (or inside the base you look at), repeats within 8 blocks become one
     * "text x6", and then labels are taken by importance until Max labels, skipping any that would overlap one already taken or a
     * card. Player names are always drawn and never counted.
     */
    private List<Label> budget() {
        labelsIn = labels.size();
        Policy bf = policy;
        boolean active = bf != null && bf.declutterOn();
        if (!active) {
            int n = 0, over = 0;
            List<String> texts = new ArrayList<>();
            List<float[]> rs = new ArrayList<>();
            List<Integer> kinds = new ArrayList<>();
            for (Label l : labels) {
                int k = kind(l.owner);
                if (k != NAME) { n++; texts.add(l.text); }
                if (measure) {
                    float[] r = rect(l);
                    if (r == null) continue;
                    for (int i = 0; i < rs.size(); i++) if (!(k == NAME && kinds.get(i) == NAME) && overlap(r, rs.get(i), 0)) over++;
                    rs.add(r);
                    kinds.add(k);
                }
            }
            labelsDrawn = n;
            labelOverlaps = measure ? over : 0;
            labelsMerged = 0;
            drawnTexts = texts;
            return labels;
        }
        double range = bf.labelRange();
        var focus = bf.focusChunks();
        List<Label> keep = new ArrayList<>();
        for (Label l : labels) {
            int k = kind(l.owner);
            if (k == SUMMARY && bf.siteNear(l.pos.x, l.pos.z)) continue;
            if (k == EVIDENCE && cam.distanceTo(l.pos) > range) {
                boolean inFocus = focus != null && focus.contains(net.minecraft.world.level.ChunkPos.asLong(ChunkMath.chunk(l.pos.x), ChunkMath.chunk(l.pos.z)));
                if (!inFocus || cam.distanceTo(l.pos) > 64) continue;
            }
            keep.add(l);
        }
        // repeats: same words and colour within 8 blocks of each other become one label at their middle
        List<Label> merged = new ArrayList<>();
        int mergedAway = 0;
        boolean[] done = new boolean[keep.size()];
        for (int i = 0; i < keep.size(); i++) {
            if (done[i]) continue;
            Label a = keep.get(i);
            done[i] = true;
            if (kind(a.owner) == NAME) { merged.add(a); continue; }
            List<Label> group = new ArrayList<>();
            group.add(a);
            for (int gi = 0; gi < group.size(); gi++) {
                Label g = group.get(gi);
                for (int j = i + 1; j < keep.size(); j++) {
                    if (done[j]) continue;
                    Label b = keep.get(j);
                    if (b.color != a.color || !b.text.equals(a.text) || kind(b.owner) == NAME) continue;
                    if (b.pos.distanceToSqr(g.pos) <= 64) { done[j] = true; group.add(b); }
                }
            }
            if (group.size() == 1) { merged.add(a); continue; }
            double x = 0, y = 0, z = 0;
            for (Label g : group) { x += g.pos.x; y += g.pos.y; z += g.pos.z; }
            int n = group.size();
            mergedAway += n - 1;
            merged.add(new Label(new Vec3(x / n, y / n, z / n), a.text + " x" + n, a.color, a.scale, a.owner));
        }
        labelsMerged = mergedAway;
        int max = bf.labelCap();
        List<float[]> taken = new ArrayList<>();
        List<Integer> takenKinds = new ArrayList<>();
        List<Label> out = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Object[]> rest = new ArrayList<>();
        for (Label l : merged) {
            float[] r = rect(l);
            if (r == null) continue;
            int k = kind(l.owner);
            if (k == NAME) { out.add(l); taken.add(r); takenKinds.add(NAME); continue; }
            int prio = k == SUMMARY ? 60 : k == EVIDENCE ? 40 : 20;
            if (k == EVIDENCE && focus != null && focus.contains(net.minecraft.world.level.ChunkPos.asLong(ChunkMath.chunk(l.pos.x), ChunkMath.chunk(l.pos.z)))) prio = 50;
            rest.add(new Object[] {l, r, prio, cam.distanceTo(l.pos)});
        }
        rest.sort((a, b) -> (int) b[2] != (int) a[2] ? (int) b[2] - (int) a[2] : Double.compare((double) a[3], (double) b[3]));
        List<float[]> cards = dev.vantage.core.Callouts.lastRects();
        int n = 0;
        for (Object[] o : rest) {
            if (n >= max) break;
            float[] r = (float[]) o[1];
            boolean free = true;
            for (float[] t : taken) if (overlap(r, t, 3)) { free = false; break; }
            if (free) for (float[] t : cards) if (overlap(r, t, 3)) { free = false; break; }
            if (!free) continue;
            Label l = (Label) o[0];
            out.add(l);
            taken.add(r);
            takenKinds.add(-1);
            texts.add(l.text);
            n++;
        }
        int over = 0;
        for (int i = 0; i < taken.size(); i++) for (int j = i + 1; j < taken.size(); j++)
            if (!(takenKinds.get(i) == NAME && takenKinds.get(j) == NAME) && overlap(taken.get(i), taken.get(j), 0)) over++;
        labelsDrawn = n;
        labelOverlaps = over;
        drawnTexts = texts;
        return out;
    }

    private static void lineVerts(VertexConsumer vc, PoseStack.Pose pose, double x1, double y1, double z1,
                                  double x2, double y2, double z2, int argb, float width) {
        float dx = (float) (x2 - x1), dy = (float) (y2 - y1), dz = (float) (z2 - z1);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) return;
        dx /= len; dy /= len; dz /= len;
        vc.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(argb).setNormal(pose, dx, dy, dz).setLineWidth(width);
        vc.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(argb).setNormal(pose, dx, dy, dz).setLineWidth(width);
    }

    private static final float[] COS = new float[7], SIN = new float[7];
    static {
        for (int i = 0; i < 7; i++) { double a = Math.PI * (i / 6.0 - 0.5); COS[i] = (float) Math.cos(a); SIN[i] = (float) Math.sin(a); }
    }

    /** Moves the pose to a label: at its point, facing the camera, scaled so one text unit is a label pixel, y pointing down. */
    private void place(PoseStack ps, Label l, float scale, float lift) {
        var up = camera.upVector();
        ps.translate(l.pos.x - cam.x + up.x() * lift, l.pos.y - cam.y + up.y() * lift, l.pos.z - cam.z + up.z() * lift);
        ps.mulPose(camera.rotation());
        ps.scale(scale, -scale, scale);
    }

    private int labelColor(Label l, float fade) {
        return Colors.fade(l.color | (((l.color >>> 24) == 0) ? 0xFF000000 : 0), fade);
    }

    private float labelFade(double dist) {
        float far = 1f;
        if (style.farLabels && dist > 40) far = (float) (1 - 0.5 * Math.min(1, (dist - 40) / (style.labelRange * 0.5)));
        return style.fade(dist) * rangeFade(dist) * far;
    }

    /** The backing: a flat pill (full-round ends, like the panels' rounded corners) in the menu's glass colour. */
    private void drawPill(PoseStack ps, VertexConsumer vc, Label l, float lift) {
        double dist = cam.distanceTo(l.pos);
        float fade = labelFade(dist);
        if (fade <= 0f) return;
        float scale = labelScale(l, dist);
        ps.pushPose();
        place(ps, l, scale, lift);
        PoseStack.Pose pose = ps.last();
        float hw = (mc.font.width(dev.vantage.core.Ui.inter(l.text, false)) + 8) / 2f, y0 = -1.5f, y1 = 10.5f, rad = (y1 - y0) / 2f, cy = (y0 + y1) / 2f;
        float xl = -hw + rad, xr = hw - rad;
        int col = Colors.fade(dev.vantage.core.Ui.GLASS, fade * 0.92f);
        vc.addVertex(pose, xl, y0, 0).setColor(col);
        vc.addVertex(pose, xr, y0, 0).setColor(col);
        vc.addVertex(pose, xr, y1, 0).setColor(col);
        vc.addVertex(pose, xl, y1, 0).setColor(col);
        for (int side = 0; side < 2; side++) {
            float cx = side == 0 ? xr : xl, dir = side == 0 ? 1f : -1f;
            for (int i = 0; i < 6; i++) {
                // a triangle of the half disc, sent as a quad with a repeated corner
                vc.addVertex(pose, cx, cy, 0).setColor(col);
                vc.addVertex(pose, cx + dir * COS[i] * rad, cy + SIN[i] * rad, 0).setColor(col);
                vc.addVertex(pose, cx + dir * COS[i + 1] * rad, cy + SIN[i + 1] * rad, 0).setColor(col);
                vc.addVertex(pose, cx + dir * COS[i + 1] * rad, cy + SIN[i + 1] * rad, 0).setColor(col);
            }
        }
        ps.popPose();
    }

    /** Text that faces the camera, keeps a readable size far away and shows through walls. */
    private void drawLabel(PoseStack ps, MultiBufferSource src, Label l, float lift) {
        drawLabel(ps, src, l, lift, Font.DisplayMode.SEE_THROUGH);
    }

    private void drawLabel(PoseStack ps, MultiBufferSource src, Label l, float lift, Font.DisplayMode mode) {
        double dist = cam.distanceTo(l.pos);
        float fade = labelFade(dist);
        if (fade <= 0f) return;
        float scale = labelScale(l, dist);
        int color = labelColor(l, fade);
        ps.pushPose();
        place(ps, l, scale, lift);
        Font font = mc.font;
        var text = dev.vantage.core.Ui.inter(l.text, false);
        font.drawInBatch(text, -font.width(text) / 2f, 0, color, !style.labelBacking, ps.last().pose(), src, mode, 0, 0xF000F0);
        ps.popPose();
    }
}
