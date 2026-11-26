package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.vantage.core.Ui;
import dev.vantage.core.util.ChunkMath;
import dev.vantage.core.util.Compass;
import dev.vantage.module.donut.BaseEsp;
import dev.vantage.module.finder.BaseFinder.Reason;
import dev.vantage.module.finder.BaseFinder.View;
import net.minecraft.world.phys.AABB;

/**
 * One entry per base. Base Finder rates single chunks and Base ESP finds storage groups, so one big base shows up as many
 * chunk marks and several groups. This joins them: touching chunks at about the same height (and the same wrecked-or-not
 * status) and the storage groups inside them become one place with one card. Pure logic, no game state, so the rig can feed it
 * made-up views and groups.
 */
public final class BaseSites {
    /** A guessed area (Likely area) with how far out the base could be. */
    public record ZoneIn(View view, int range, boolean weak) {
        public ZoneIn(View view, int range) { this(view, range, false); }
    }

    public record Site(int id, Set<Long> chunks, List<View> views, List<BaseEsp.Group> groups, int x, int y, int z, int minY, int maxY, int pct,
                       boolean approx, String label, String state, String condition, int color, boolean live, boolean griefed, boolean zone,
                       int zoneRange, int containers, int hoppers, int machines, int redstone, int chunkCount, List<Reason> reasons, int points,
                       String conf, int dist, String dir) {
        /** "132 containers · 100 hoppers · 55 redstone · 3 groups", parts that are zero left out. */
        public String stats() {
            List<String> p = new ArrayList<>();
            if (containers > 0) p.add(containers + " containers");
            if (hoppers > 0) p.add(hoppers + " hoppers");
            if (machines > 0) p.add(machines + " machines");
            if (redstone > 0) p.add(redstone + " redstone");
            if (!groups.isEmpty()) p.add(groups.size() + (groups.size() == 1 ? " group" : " groups"));
            return String.join(" · ", p);
        }

        /** "SW 32m · Y -48..-17 · 6 chunks". */
        public String subtitle() {
            return dir + " " + dist + "m · Y " + minY + ".." + maxY + (zone ? " · within " + zoneRange + "m" : " · " + chunkCount + (chunkCount == 1 ? " chunk" : " chunks"));
        }

        /** "99%", or "~60%" when the number is a guess from storage alone. */
        public String pctText() {
            return (approx ? "~" : "") + pct + "%";
        }
    }

    /** Same packing as ChunkPos.asLong, without loading ChunkPos (which needs the game's registries), so this stays testable on its own. */
    static long key(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | (cz & 0xFFFFFFFFL) << 32;
    }

    /** Vertical gap between two height ranges, 0 when they overlap. */
    static int yGap(int aMin, int aMax, int bMin, int bMax) {
        return Math.max(0, Math.max(aMin, bMin) - Math.min(aMax, bMax));
    }

    private static boolean grey(View v) {
        return v.color() == Ui.GREY;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    private static void join(int[] p, int a, int b) {
        a = find(p, a); b = find(p, b);
        if (a != b) p[Math.max(a, b)] = Math.min(a, b);
    }

    private static double axisGap(double a0, double a1, double b0, double b1) {
        return Math.max(0, Math.max(a0, b0) - Math.min(a1, b1));
    }

    private static boolean isLive(Reason r) {
        return r.text().endsWith(" is under the ground here") || r.text().equals("live player signal");
    }

    private static int espPct(String state) {
        return switch (state) { case "Active" -> 70; case "Likely intact" -> 60; case "Griefed" -> 20; default -> 40; };
    }

    public static List<Site> build(List<View> views, List<ZoneIn> zones, List<BaseEsp.Group> groups, List<Site> previous, int[] nextId, double meX, double meZ) {
        int nv = views.size(), ng = groups.size();
        int[] p = new int[nv + ng];
        for (int i = 0; i < p.length; i++) p[i] = i;
        // (a) touching chunks, at about the same height, both wrecked or both not
        for (int i = 0; i < nv; i++) {
            View a = views.get(i);
            for (int j = i + 1; j < nv; j++) {
                View b = views.get(j);
                if (Math.max(Math.abs(a.cx() - b.cx()), Math.abs(a.cz() - b.cz())) > 1) continue;
                if (yGap(a.minY(), a.maxY(), b.minY(), b.maxY()) > 16 || grey(a) != grey(b)) continue;
                join(p, i, j);
            }
        }
        // (b) a storage group lying in (or right next to) a flagged chunk
        for (int g = 0; g < ng; g++) {
            AABB box = groups.get(g).box();
            for (int i = 0; i < nv; i++) {
                View v = views.get(i);
                double x0 = (v.cx() << 4) - 2, z0 = (v.cz() << 4) - 2, x1 = (v.cx() << 4) + 18, z1 = (v.cz() << 4) + 18;
                if (box.maxX < x0 || box.minX > x1 || box.maxZ < z0 || box.minZ > z1) continue;
                if (yGap(v.minY(), v.maxY(), (int) Math.floor(box.minY), (int) Math.ceil(box.maxY)) > 16) continue;
                join(p, nv + g, i);
            }
        }
        // (c) groups Base ESP cut apart only because of its Cluster Radius
        for (int g = 0; g < ng; g++) for (int h = g + 1; h < ng; h++) {
            AABB a = groups.get(g).box(), b = groups.get(h).box();
            if (axisGap(a.minX, a.maxX, b.minX, b.maxX) <= 8 && axisGap(a.minY, a.maxY, b.minY, b.maxY) <= 8 && axisGap(a.minZ, a.maxZ, b.minZ, b.maxZ) <= 8)
                join(p, nv + g, nv + h);
        }
        Map<Integer, List<Integer>> comps = new LinkedHashMap<>();
        for (int i = 0; i < p.length; i++) comps.computeIfAbsent(find(p, i), k -> new ArrayList<>()).add(i);

        List<Draft> drafts = new ArrayList<>();
        for (List<Integer> c : comps.values()) {
            Draft d = new Draft();
            for (int i : c) if (i < nv) d.views.add(views.get(i)); else d.groups.add(groups.get(i - nv));
            drafts.add(d);
        }
        for (Draft d : drafts) d.work(meX, meZ);
        // a guessed area around a sure base is that base's area: one line on its card instead of a ring and a tag of its own
        List<Draft> zoneDrafts = new ArrayList<>();
        for (ZoneIn zi : zones) {
            View zv = zi.view();
            Draft into = null;
            double best = Double.MAX_VALUE;
            for (Draft d : drafts) {
                if (d.pct < 50) continue;
                double dd = Math.hypot(d.x - zv.x(), d.z - zv.z());
                if (dd <= zi.range() && dd < best) { best = dd; into = d; }
            }
            if (into != null) {
                if (into.zoneLine == null || zv.pct() > into.zonePct) { into.zoneLine = new Reason("inside a likely area " + zv.pct() + "%", 0, 0); into.zonePct = zv.pct(); }
                continue;
            }
            Draft z = new Draft();
            z.views.add(zv);
            z.zone = true;
            z.zoneRange = zi.range();
            z.weak = zi.weak();
            z.work(meX, meZ);
            zoneDrafts.add(z);
        }
        drafts.addAll(zoneDrafts);

        List<Site> out = new ArrayList<>();
        Set<Integer> usedIds = new HashSet<>();
        drafts.sort((a, b) -> a.weak != b.weak ? (a.weak ? 1 : -1) : a.pct != b.pct ? b.pct - a.pct : a.dist - b.dist); // a guess with nothing player-only behind it ranks below every real place
        for (Draft d : drafts) {
            int id = -1;
            double bestJ = 0.3 - 1e-9;
            if (previous != null) for (Site s : previous) {
                if (s.zone() != d.zone || usedIds.contains(s.id())) continue;
                double j = jaccard(s.chunks(), d.chunks);
                if (j >= bestJ && (id == -1 || j > bestJ)) { bestJ = j; id = s.id(); }
            }
            if (id == -1) id = nextId[0]++;
            usedIds.add(id);
            out.add(d.site(id));
        }
        return out;
    }

    static double jaccard(Set<Long> a, Set<Long> b) {
        if (a.isEmpty() && b.isEmpty()) return 1;
        int both = 0;
        for (long k : a) if (b.contains(k)) both++;
        int any = a.size() + b.size() - both;
        return any == 0 ? 0 : both / (double) any;
    }

    private static final class Draft {
        final List<View> views = new ArrayList<>();
        final List<BaseEsp.Group> groups = new ArrayList<>();
        final Set<Long> chunks = new HashSet<>();
        boolean zone, approx, live, griefed, weak;
        int zoneRange, x, y, z, minY, maxY, pct, points, dist, containers, hoppers, machines, redstone, color, zonePct;
        String label, state, condition, conf, dir;
        Reason zoneLine;
        List<Reason> reasons;

        void work(double meX, double meZ) {
            View top = null;
            for (View v : views) if (top == null || v.pct() > top.pct() || v.pct() == top.pct() && v.points() > top.points()) top = v;
            BaseEsp.Group big = null;
            for (BaseEsp.Group g : groups) if (big == null || g.score() > big.score()) big = g;
            minY = Integer.MAX_VALUE; maxY = Integer.MIN_VALUE;
            for (View v : views) {
                if (!zone) chunks.add(key(v.cx(), v.cz()));
                minY = Math.min(minY, v.minY()); maxY = Math.max(maxY, v.maxY());
                pct = Math.max(pct, v.pct());
                points = Math.max(points, v.points());
                if (v.color() == Ui.BLUE) live = true;
            }
            if (zone && top != null) chunks.add(key(top.cx(), top.cz()));
            boolean anyIntact = false, anyActive = false, allGrief = true;
            for (BaseEsp.Group g : groups) {
                AABB b = g.box();
                for (int cx = ChunkMath.chunk(b.minX); cx <= ChunkMath.chunk(b.maxX - 1e-6); cx++)
                    for (int cz = ChunkMath.chunk(b.minZ); cz <= ChunkMath.chunk(b.maxZ - 1e-6); cz++) chunks.add(key(cx, cz));
                minY = Math.min(minY, (int) Math.floor(b.minY)); maxY = Math.max(maxY, (int) Math.ceil(b.maxY) - 1);
                containers += g.containers(); hoppers += g.hoppers(); machines += g.machines(); redstone += g.redstone();
                if (g.state().equals("Likely intact")) anyIntact = true;
                if (g.state().equals("Active")) anyActive = true;
                if (!g.state().equals("Griefed")) allGrief = false;
            }
            for (View v : views) if (!grey(v)) allGrief = false;
            griefed = allGrief;
            if (top != null) {
                x = (top.cx() << 4) + 8; y = top.y(); z = (top.cz() << 4) + 8;
                if (zone) { x = top.x(); z = top.z(); }
                label = top.label();
                conf = top.conf();
            } else {
                var c = big.box().getCenter();
                x = (int) Math.floor(c.x); y = (int) Math.floor(c.y); z = (int) Math.floor(c.z);
                label = "Storage room";
                approx = true;
                String st = griefed ? "Griefed" : anyActive ? "Active" : anyIntact ? "Likely intact" : "Unclear";
                pct = espPct(st);
                points = big.score();
                conf = pct >= 60 ? "MEDIUM" : "LOW";
            }
            state = griefed ? "Griefed" : live || anyActive ? "Active" : anyIntact ? "Likely intact" : "Unclear";
            color = zone ? Ui.TEAL : griefed ? Ui.GREY : live ? Ui.BLUE : pct >= 50 ? Ui.GREEN : Ui.YELLOW;
            dist = (int) Math.sqrt((x - meX) * (x - meX) + (z - meZ) * (z - meZ));
            dir = Compass.name(x - meX, z - meZ);
            // every reason once, the strongest copy kept; the live line first
            Map<String, Reason> seen = new LinkedHashMap<>();
            for (View v : views) for (Reason r : v.reasons()) {
                if (r.text().endsWith("next door flagged too")) continue;
                String k = r.text().toLowerCase(Locale.ROOT);
                Reason old = seen.get(k);
                if (old == null || r.points() > old.points()) seen.put(k, r);
            }
            List<Reason> rs = new ArrayList<>(seen.values());
            rs.sort((a, b) -> b.points() - a.points());
            Reason liveLine = null;
            for (Reason r : rs) if (isLive(r) && (liveLine == null || r.points() > liveLine.points())) liveLine = r;
            if (liveLine != null) { rs.remove(liveLine); rs.add(0, liveLine); }
            reasons = rs;
            // row 3: what state it is in, said once
            String grief = null;
            for (View v : views) if (grey(v) && v.foot() != null && !v.foot().isEmpty()) { grief = v.foot(); break; }
            if (grief == null) for (BaseEsp.Group g : groups) if (g.state().equals("Griefed")) { grief = g.evidence(); break; }
            String ev = null;
            for (BaseEsp.Group g : groups) {
                String e = g.evidence();
                if (e == null || e.equals("Not enough clear condition evidence")) continue;
                String low = e.toLowerCase(Locale.ROOT);
                boolean said = false;
                for (Reason r : rs) if (r.text().toLowerCase(Locale.ROOT).contains(low)) { said = true; break; }
                if (!said) { ev = e; break; }
            }
            boolean farmCard = top != null && !griefed && BaseFinder.whyFoot(top.foot()); // v2: a farm says why it is one, not water or floating
            condition = grief != null && griefed ? grief : farmCard ? top.foot() : ev != null ? ev : top != null ? top.foot() : "";
            if (minY == Integer.MAX_VALUE) { minY = y; maxY = y; }
        }

        Site site(int id) {
            List<Reason> rs = new ArrayList<>(reasons);
            if (zoneLine != null) rs.add(zoneLine);
            return new Site(id, Set.copyOf(chunks), List.copyOf(views), List.copyOf(groups), x, y, z, minY, maxY, pct, approx, label, state,
                    condition == null ? "" : condition, color, live, griefed, zone, zoneRange, containers, hoppers, machines, redstone,
                    zone ? 0 : chunks.size(), List.copyOf(rs), points, conf, dist, dir);
        }
    }

    /** chunk -> site, for the non-zone sites. */
    public static Map<Long, Site> byChunk(List<Site> sites) {
        Map<Long, Site> m = new HashMap<>();
        for (Site s : sites) if (!s.zone()) for (long k : s.chunks()) m.putIfAbsent(k, s);
        return m;
    }

    private BaseSites() {}
}
