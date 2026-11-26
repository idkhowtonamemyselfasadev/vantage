package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a base could be when nothing built can be seen. Most clues say "a player spent time near here", not "the base is on this
 * spot": amethyst, kelp, dripstone and vines only grow in chunks a player keeps loaded, so they surround the place somebody stays.
 *
 * Rules:
 * 1. Every clue kind has odds: how often that clue alone would mean a base.
 * 2. One clue of a kind counts half, and each further one closes half the gap, so ten kelp stalks never beat two different clues.
 * 3. Kinds are independent, so they combine as 1 - (1-a)(1-b)...: the chance that at least one of them is right.
 * 4. A zone is clues within simulation distance of each other, as that's how far one player keeps chunks ticking.
 * 5. It takes 2 different kinds. One kind alone can be a natural oddity. Capped at 95%, this is never proof.
 * 6. With 2+ grown chunks the spot is worked out, not averaged: a player stands within simulation distance of every grown chunk, so the
 *    answer is where those squares overlap. Ungrown kelp and geodes vote against squares they sit in, at half weight, since a short stay
 *    grows little. What's left is the middle and the size of the ring. Anything less falls back on the middle of the clues.
 */
public final class BaseLogic {
    public record Kind(String name, double odds, String why) {}
    public record Clue(Kind kind, int cx, int cz, int x, int y, int z) {}
    public record Line(String text, int points, int count) {}
    /** range: blocks from the middle the player could have been. grown and ruledOut: how much went into narrowing it, 0 when it's just the middle. */
    public record Zone(int x, int y, int z, int minY, int maxY, int range, int pct, int kinds, int clues, int grown, int ruledOut, List<Line> lines, boolean anchored, String detail) {
        public Zone(int x, int y, int z, int minY, int maxY, int range, int pct, int kinds, int clues, int grown, int ruledOut, List<Line> lines, boolean anchored) {
            this(x, y, z, minY, maxY, range, pct, kinds, clues, grown, ruledOut, lines, anchored, "");
        }
    }
    public record Spot(int x, int z, int range, int ruledOut) {}

    public static final Kind AMETHYST = new Kind("grown amethyst", 0.30, "only grows while a player keeps it loaded");
    public static final Kind KELP = new Kind("grown kelp", 0.25, "only grows while a player keeps it loaded");
    public static final Kind FARM_KELP = new Kind("kelp under a roof", 0.40, "someone built this farm");
    public static final Kind DRIPSTONE = new Kind("grown dripstone", 0.25, "only grows while a player keeps it loaded");
    public static final Kind VINES = new Kind("grown vines", 0.25, "only grows while a player keeps it loaded");
    public static final Kind DIRT = new Kind("bare dirt", 0.15, "ground was dug or built on");
    public static final Kind TUNNEL = new Kind("hand-dug tunnel", 0.35, "caves don't run straight");
    public static final Kind SHAFT = new Kind("hand-dug shaft", 0.25, "someone went straight down");
    public static final Kind BUILT = new Kind("player blocks", 0.45, "worldgen doesn't place these");
    public static final Kind CHANGED = new Kind("changed since last scan", 0.50, "someone is still working here");
    public static final Kind TOUCHED = new Kind("used structure", 0.35, "a player was here for it");
    public static final Kind HIDDEN_STORAGE = new Kind("hidden containers", 0.60, "the server hides real storage");
    public static final Kind HIDDEN_LIGHT = new Kind("hidden light", 0.35, "something glows inside solid rock");
    public static final Kind HIDDEN_ACTIVITY = new Kind("hidden activity", 0.35, "blocks keep changing inside solid rock");
    public static final Kind HIDDEN_ENTITY = new Kind("hidden entity", 0.60, "a mob or frame sits inside solid rock");
    public static final Kind HIDDEN_ITEM = new Kind("hidden item", 0.40, "a dropped item sits inside solid rock");
    public static final Kind MINER = new Kind("someone mining", 0.30, "blocks break with nobody in sight");
    public static final Kind PATCHED = new Kind("patched shaft", 0.35, "a stone column runs through a blob, filled back in");
    public static final Kind SKY = new Kind("sky light fingerprint", 0.45, "the light engine touched rock nobody could have reached");
    public static final Kind HEARD = new Kind("heard activity", 0.40, "farm, storage or player sounds came from here");
    public static final Kind SIMULATED = new Kind("simulated by someone else", 0.55, "mobs and blocks tick where only another player can keep them ticking");

    /**
     * Shulker boxes in a find. No world generation outside the End makes one, and a stash with them is a likelier base than furnishing alone.
     * It only raises a zone that two other kinds already make: on its own, or beside nothing but one other kind, it makes no zone.
     */
    public static final Kind SHULKERS = new Kind("shulker boxes", 0.40, "only a player puts them here");

    /**
     * Scoring v2 (beta), rule 1b: amethyst, kelp, dripstone and vines give one reason ("only grows while a player keeps it loaded"), so
     * they are ONE clue family: it counts as one kind and contributes its strongest member plus FAMILY_BONUS per further member, never
     * more than FAMILY_CAP (a hair above the best single growth clue, 0.30, and well under a built-block clue, 0.45).
     */
    public static final double FAMILY_BONUS = 0.02, FAMILY_CAP = 0.33;
    public static final String FAMILY_NAME = "grown plants and crystals";
    private static final double CAP = 0.95;
    private static final int MAX_ZONES = 6, MIN_KINDS = 2, MIN_CLUES = 2, MIN_PCT = 20;

    /** Base Finder's reason text to a clue kind, or null when it isn't one. */
    public static Kind kindOf(String reason) {
        if (reason.startsWith("+")) return CHANGED;
        return switch (reason) {
            case "amethyst grown out" -> AMETHYST;
            case "kelp growing underground" -> FARM_KELP;
            case "kelp grown past worldgen height" -> KELP;
            case "dripstone grown past worldgen length" -> DRIPSTONE;
            case "cave and nether vines grown long" -> VINES;
            case "bare dirt, grass hasn't grown back" -> DIRT;
            case "hand-dug tunnel" -> TUNNEL;
            case "hand-dug shaft" -> SHAFT;
            case "patched shaft" -> PATCHED;
            default -> null;
        };
    }

    /** Rule 2. */
    private static double strength(Kind k, int count) {
        return k.odds() * (1 - Math.pow(0.5, count));
    }

    /**
     * A zone stays at or under this until it holds one clue that world generation cannot make. Growth, "simulated" chunks, bare dirt,
     * tunnels and shafts, and rock glow all have natural causes (or the user's own presence); a placed block, stored container,
     * hidden mob or item, a used structure and the like do not.
     */
    public static final double UNANCHORED_CAP = 0.50;

    /** Can world generation (or the user's own presence) explain this clue alone? */
    public static boolean generatable(Kind k) {
        return grown(k) || k == SIMULATED || k == DIRT || k == TUNNEL || k == SHAFT || k == HIDDEN_LIGHT || k == HIDDEN_ACTIVITY
                || k == TOUCHED || k == SKY; // a generated structure nobody entered reads as "used", and a light-engine quirk is not a visit either: neither is an anchor
    }

    /** Only these prove a chunk was kept loaded. */
    private static boolean grown(Kind k) {
        return k == AMETHYST || k == KELP || k == DRIPSTONE || k == VINES;
    }

    /** What a hidden surface find may still contribute under Underground only: growth that needs a player nearby. Nobody digs a shaft to their base (the owner), so no digging clues. */
    public static boolean guessOnly(Kind k) {
        return grown(k) || k == FARM_KELP;
    }

    /** Pure rule 1b: the family's odds from the strength of each member present (0 when none). */
    public static double familyStrength(List<Double> members) {
        if (members.isEmpty()) return 0;
        double best = 0;
        for (double m : members) best = Math.max(best, m);
        return Math.min(FAMILY_CAP, best + FAMILY_BONUS * (members.size() - 1));
    }

    /** Two grown chunks on opposite sides of one player are up to twice the distance apart. */
    private static boolean near(Clue a, Clue b, int r) {
        int reach = grown(a.kind()) && grown(b.kind()) ? r * 2 : r;
        return Math.abs(a.cx() - b.cx()) <= reach && Math.abs(a.cz() - b.cz()) <= reach;
    }

    /** Rule 6, in chunks. Null without grown chunks. */
    public static Spot locate(List<int[]> grown, List<int[]> ungrown, int r) {
        if (grown.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (int[] g : grown) { x0 = Math.min(x0, g[0]); x1 = Math.max(x1, g[0]); z0 = Math.min(z0, g[1]); z1 = Math.max(z1, g[1]); }
        double best = -1e9, bestPlain = -1e9;
        int w = x1 - x0 + 2 * r + 1, h = z1 - z0 + 2 * r + 1;
        double[] score = new double[w * h];
        int[] plain = new int[w * h];
        for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) {
            int cx = x0 - r + i, cz = z0 - r + j, hits = 0, against = 0;
            for (int[] g : grown) if (Math.abs(g[0] - cx) <= r && Math.abs(g[1] - cz) <= r) hits++;
            for (int[] u : ungrown) if (Math.abs(u[0] - cx) <= r && Math.abs(u[1] - cz) <= r) against++;
            plain[i * h + j] = hits;
            score[i * h + j] = hits - against * 0.5;
            best = Math.max(best, score[i * h + j]);
            bestPlain = Math.max(bestPlain, hits);
        }
        int n = 0, before = 0, lx = Integer.MAX_VALUE, hx = Integer.MIN_VALUE, lz = Integer.MAX_VALUE, hz = Integer.MIN_VALUE;
        for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) {
            if (plain[i * h + j] == bestPlain) before++;
            if (score[i * h + j] < best - 0.01) continue;
            n++;
            int cx = x0 - r + i, cz = z0 - r + j;
            lx = Math.min(lx, cx); hx = Math.max(hx, cx); lz = Math.min(lz, cz); hz = Math.max(hz, cz);
        }
        int range = (Math.max(hx - lx, hz - lz) + 1) * 8; // half the box, in blocks
        return new Spot((lx + hx + 1) * 8, (lz + hz + 1) * 8, range, Math.max(0, before - n));
    }

    private static boolean anchor(Clue c, java.util.Set<Clue> weak) {
        return !(generatable(c.kind()) || weak.contains(c));
    }

    /**
     * How often a kind is counted: every clue, except that used structures next to an anchor count once. A mineshaft or a ruin around a build
     * gives one "used" clue per spot, and several of them must not carry a zone past one whose evidence is what players put there.
     */
    private static int counted(Kind k, int n, boolean anchored) {
        return anchored && k == TOUCHED ? Math.min(1, n) : n;
    }

    /** Rule 3 for the clues around one centre. */
    private static double odds(List<Clue> around, boolean v2, java.util.Set<Clue> weak) {
        boolean anchored = around.stream().anyMatch(c -> anchor(c, weak));
        Map<Kind, Integer> counts = new HashMap<>();
        for (Clue c : around) counts.merge(c.kind(), 1, Integer::sum);
        counts.replaceAll((k, n) -> counted(k, n, anchored));
        int base = counts.size() - (counts.containsKey(SHULKERS) ? 1 : 0);
        if (!v2) {
            if (base < MIN_KINDS || around.size() < MIN_CLUES) return 0;
            double miss = 1;
            for (var e : counts.entrySet()) miss *= 1 - strength(e.getKey(), e.getValue());
            return Math.min(CAP, 1 - miss);
        }
        List<Double> fam = new ArrayList<>();
        int kinds = 0;
        double miss = 1;
        for (var e : counts.entrySet()) {
            double st = strength(e.getKey(), e.getValue());
            if (grown(e.getKey())) fam.add(st);
            else { kinds++; miss *= 1 - st; }
        }
        if (!fam.isEmpty()) { kinds++; miss *= 1 - familyStrength(fam); }
        if (kinds - (counts.containsKey(SHULKERS) ? 1 : 0) < MIN_KINDS || around.size() < MIN_CLUES) return 0;
        return Math.min(CAP, 1 - miss);
    }

    private static List<Clue> around(Clue centre, List<Clue> all, int r) {
        List<Clue> out = new ArrayList<>();
        for (Clue c : all) if (near(centre, c, r)) out.add(c);
        return out;
    }

    public static List<Zone> zones(List<Clue> input, int radius) {
        return zones(input, List.of(), radius);
    }

    public static List<Zone> zones(List<Clue> input, List<int[]> ungrown, int radius) {
        return zones(input, ungrown, radius, false);
    }

    /** v2: growth kinds are one clue family (see FAMILY_CAP). */
    public static List<Zone> zones(List<Clue> input, List<int[]> ungrown, int radius, boolean v2) {
        return zones(input, ungrown, radius, v2, java.util.Set.of());
    }

    /** weak: clues of a kind that could be an anchor but that world generation can also explain here (a find below the real-find line, anything built in a world with one player); they count for the odds, not as the anchor. */
    public static List<Zone> zones(List<Clue> input, List<int[]> ungrown, int radius, boolean v2, java.util.Set<Clue> weak) {
        List<Clue> left = new ArrayList<>(input);
        List<Zone> out = new ArrayList<>();
        while (out.size() < MAX_ZONES && !left.isEmpty()) {
            List<Clue> best = null;
            double bestOdds = 0;
            for (Clue c : left) {
                List<Clue> a = around(c, left, radius);
                double o = odds(a, v2, weak);
                if (o > bestOdds) { bestOdds = o; best = a; }
            }
            boolean anchored = best == null || !best.stream().allMatch(c -> generatable(c.kind()) || weak.contains(c));
            if (!anchored) bestOdds = Math.min(bestOdds, UNANCHORED_CAP);
            int pct = (int) Math.round(bestOdds * 100);
            if (best == null || pct < MIN_PCT) break;
            List<Clue> anchorClues = new ArrayList<>();
            for (Clue c : best) if (!(generatable(c.kind()) || weak.contains(c))) anchorClues.add(c);
            Zone z0 = zone(best, ungrown, pct, radius, v2, anchored, anchorClues);
            // diagnostic for bug reports: which clues anchor the zone (non-generatable, not weak) and the kinds with their chunks
            StringBuilder d = new StringBuilder("anchors:");
            int na = 0;
            for (Clue c : best) if (!(generatable(c.kind()) || weak.contains(c)) && na++ < 6) d.append(' ').append(c.kind().name()).append('@').append(c.cx()).append(',').append(c.cz());
            if (na == 0) d.append(" none");
            d.append(" | kinds:");
            Map<String, Integer> kc = new java.util.TreeMap<>();
            for (Clue c : best) kc.merge(c.kind().name(), 1, Integer::sum);
            kc.forEach((k, n) -> d.append(' ').append(k).append('x').append(n));
            out.add(new Zone(z0.x(), z0.y(), z0.z(), z0.minY(), z0.maxY(), z0.range(), z0.pct(), z0.kinds(), z0.clues(), z0.grown(), z0.ruledOut(), z0.lines(), z0.anchored(), d.toString()));
            left.removeAll(best);
        }
        return out;
    }

    private static Zone zone(List<Clue> clues, List<int[]> ungrown, int pct, int radius, boolean v2, boolean anchored, List<Clue> anchorClues) {
        double w = 0, x = 0, y = 0, z = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        Map<Kind, Integer> counts = new LinkedHashMap<>();
        for (Clue c : clues) {
            double o = c.kind().odds();
            w += o; x += c.x() * o; y += c.y() * o; z += c.z() * o;
            minY = Math.min(minY, c.y()); maxY = Math.max(maxY, c.y());
            counts.merge(c.kind(), 1, Integer::sum);
        }
        List<Line> lines = new ArrayList<>();
        int kindCount = counts.size();
        if (!v2) counts.forEach((k, n) -> lines.add(new Line(k.name() + ": " + k.why(), (int) Math.round(strength(k, counted(k, n, anchored)) * 100), n)));
        else {
            List<Double> fam = new ArrayList<>();
            int famClues = 0;
            StringBuilder famNames = new StringBuilder();
            for (var e : counts.entrySet()) {
                if (!grown(e.getKey())) { lines.add(new Line(e.getKey().name() + ": " + e.getKey().why(), (int) Math.round(strength(e.getKey(), counted(e.getKey(), e.getValue(), anchored)) * 100), e.getValue())); continue; }
                fam.add(strength(e.getKey(), e.getValue()));
                famClues += e.getValue();
                famNames.append(famNames.length() == 0 ? "" : ", ").append(e.getKey().name().replace("grown ", ""));
            }
            if (!fam.isEmpty()) {
                lines.add(new Line(FAMILY_NAME + " (" + famNames + "): only grows while a player keeps it loaded, counted once", (int) Math.round(familyStrength(fam) * 100), famClues));
                kindCount = kindCount - fam.size() + 1;
            }
        }
        lines.sort((a, b) -> b.points() - a.points());
        Map<Long, int[]> chunks = new HashMap<>();
        for (Clue c : clues) if (grown(c.kind())) chunks.put(((long) c.cx() << 32) ^ (c.cz() & 0xFFFFFFFFL), new int[] {c.cx(), c.cz()});
        // growth only adds percent, it does not move the zone: with anchors (a real build, storage, a hidden mob) the centre sits on them
        if (!anchorClues.isEmpty()) {
            double ax = 0, az = 0;
            for (Clue c : anchorClues) { ax += c.x(); az += c.z(); }
            ax /= anchorClues.size(); az /= anchorClues.size();
            double spread = 0;
            for (Clue c : anchorClues) spread = Math.max(spread, Math.hypot(c.x() - ax, c.z() - az));
            int range = (int) Math.min(radius * 16, Math.max(32, Math.ceil(spread) + 16));
            return new Zone((int) Math.round(ax), (int) (y / w), (int) Math.round(az), minY, maxY, range, pct, kindCount, clues.size(), chunks.size(), 0, lines, anchored);
        }
        Spot s = chunks.size() >= 2 ? locate(new ArrayList<>(chunks.values()), ungrown, radius) : null;
        if (s == null) return new Zone((int) (x / w), (int) (y / w), (int) (z / w), minY, maxY, radius * 16, pct, kindCount, clues.size(), 0, 0, lines, anchored);
        if (s.ruledOut() > 0) lines.add(new Line("ungrown kelp and geodes ruled out spots", 0, s.ruledOut()));
        return new Zone(s.x(), (int) (y / w), s.z(), minY, maxY, s.range(), pct, kindCount, clues.size(), chunks.size(), s.ruledOut(), lines, anchored);
    }

    private BaseLogic() {}
}
