package dev.vantage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.vantage.module.client.ClickGuiModule;
import dev.vantage.module.client.HudModule;
import dev.vantage.module.finder.*;
import dev.vantage.module.render.*;

public final class Modules {
    private static final List<Module> ALL = new ArrayList<>();
    /** ALL without locked betas: what every normal place iterates. */
    private static List<Module> visible = List.of();
    private static final boolean BETA = betaFlag();

    /** Whether the file was there when the game started (the switch on the settings page compares the wanted state with this one). */
    private static final boolean BETA_FILE = betaFileExists();

    private static boolean betaFlag() {
        return Boolean.getBoolean("vantage.beta") || betaFileExists();
    }

    /** The unlock file config/vantage/beta exists right now (this is the state the "Beta features" switch shows and changes). */
    public static boolean betaFileExists() {
        try {
            return java.nio.file.Files.exists(Config.dir().resolve("beta"));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Creates (on) or deletes (off) the unlock file. Betas themselves only change at the next game start, because modules are
     * registered once at startup. Returns null when done, otherwise why it did not work.
     */
    public static String setBetaFile(boolean on) {
        try {
            java.nio.file.Path f = Config.dir().resolve("beta");
            if (on) {
                java.nio.file.Files.createDirectories(f.getParent());
                if (!java.nio.file.Files.exists(f)) java.nio.file.Files.write(f, new byte[0]);
            } else {
                java.nio.file.Files.deleteIfExists(f);
            }
            return null;
        } catch (Exception e) {
            return "could not " + (on ? "create" : "delete") + " config/vantage/beta (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** True when the file now differs from how this game start saw it: the change needs a restart. */
    public static boolean betaNeedsRestart() {
        return betaFileExists() != BETA_FILE;
    }

    /** True when betas are unlocked: file config/vantage/beta exists, or -Dvantage.beta=true. Read once at startup; the "Beta features" switch on the settings page writes the file for the next start. */
    public static boolean betaUnlocked() {
        return betaOverride != null ? betaOverride : BETA;
    }

    private static Boolean betaOverride;
    private static final boolean DEV = Boolean.getBoolean("vantage.dev") || System.getProperty("vantage.ui") != null; // the in-game check rig counts as development
    private static Boolean devOverride;

    /** True when developer modules (see {@link Module#developer}) exist: -Dvantage.dev=true, or the in-game check rig (-Dvantage.ui). Read once at startup. */
    public static boolean devUnlocked() {
        return devOverride != null ? devOverride : DEV;
    }

    /** Test hook (UiTest only, the same kind as betaForTest): forces developer modules locked or unlocked (null = the real state). */
    public static void devForTest(Boolean unlocked) {
        devOverride = unlocked;
        refresh();
    }

    /** Test hook (UiTest only): marks or unmarks an existing module as beta and forces locked/unlocked (null = the real state). */
    public static void betaForTest(Module m, boolean beta, Boolean unlocked) {
        m.setBeta(beta);
        betaOverride = unlocked;
        refresh();
    }

    /** Every module including locked betas: only for config load/save, which must keep a locked beta's stored data. */
    public static List<Module> everything() {
        return Collections.unmodifiableList(ALL);
    }

    /** Rebuilds the visible list (after a beta mark or the unlock changed). */
    static void refresh() {
        visible = Collections.unmodifiableList(ALL.stream().filter(m -> !m.locked()).toList());
    }

    /** True when the name is a module that exists but is a locked beta (profiles skip it silently). */
    public static boolean lockedName(String name) {
        for (Module m : ALL) if (m.locked() && m.name.equalsIgnoreCase(name)) return true;
        return false;
    }
    private static final java.util.Map<Class<?>, Module> BY_CLASS = new java.util.HashMap<>();
    private static String section = "";
    /** The master switch: while paused every module is off and the list remembers what to bring back. */
    private static boolean paused;
    private static final List<String> PAUSED = new ArrayList<>();

    /** Where the event bus sends a listener that threw: a module is switched off for the session, anything else is logged. */
    private static void fault(dev.vantage.core.event.Events.Owner owner, Class<?> eventType, Throwable error) {
        if (owner instanceof Module m) m.fault(eventType.getSimpleName(), error);
        else dev.vantage.core.log.Log.core("events").once("fault " + owner.ownerName() + " " + eventType.getSimpleName(), error, () -> owner.ownerName() + " failed while handling " + eventType.getSimpleName() + " (later failures are only counted)");
    }

    public static void init() {
        dev.vantage.core.event.Events.faultSink(Modules::fault);
        section("Hunting");
        BaseFinder base = add(new BaseFinder());
        add(base.part(base.structures, new BaseFinder2()));
        add(base.part(base.amethyst, new AmethystFinder()));
        add(base.bundle("Stash Finder", new StashFinder()));
        add(base.bundle("Trail Finder", new TrailFinder()));
        add(base.bundle("Activated Spawners", new ActivatedSpawners()));
        add(new NewChunks());
        add(new DebrisFinder());
        add(new SeedCrack());
        add(new AnchorFinder());
        // beta: Sound Detector is a scanner of Base Finder (own switch and card on its page); locked it keeps its own row exactly as before
        if (betaUnlocked()) { SoundDetector sd = base.bundle("Sound Detector", new SoundDetector()); sd.keepsStandalone = true; add(sd); }
        else add(new SoundDetector());
        add(new Waypoints());
        section("Players");
        add(base.bundle("Player Notifier", new PlayerNotifier()));
        add(base.bundle("Entity Scan", new EntityScan()));
        add(base.bundle("Hidden Players", new HiddenPlayers()));
        add(base.bundle("Vanish Watch", new VanishWatch()));
        add(base.bundle("Palette Scan", new PaletteScan()));
        add(base.bundle("Light Scan", new LightScan()));
        add(base.bundle("Deep Leaks", new DeepLeaks()));
        add(base.bundle("Sky Fingerprint", new SkyFingerprint()));
        add(base.bundle("Simulation Ring", new SimulationRing()));
        add(base.bundle("Owed Growth", new OwedGrowth()));
        add(base.bundle("Storage ESP", new StorageEsp()));
        add(base.bundle("Block ESP", new BlockEsp()));
        add(base.bundle("Entity ESP", new EntityEsp()));
        add(base.bundle("Item ESP", new dev.vantage.module.render.ItemEsp()));
        add(base.bundle("Hole ESP", new HoleEsp()));
        add(base.bundle("Search", new Search()));
        EspStyle style = new EspStyle();
        add(style);
        section("World");
        add(new Xray());
        add(base.bundle("Fullbright", new Fullbright()));
        add(new NoRender());
        add(new Freecam());
        add(new dev.vantage.module.render.FreeLook());
        section("HUD");
        add(new Radar());
        add(new ModuleList());
        add(new TargetHud());
        add(new dev.vantage.module.render.LogoutSpots());
        add(new Trajectories());
        add(base.bundle("Hand View", new dev.vantage.module.render.HandView()));
        add(new dev.vantage.module.cosmetic.Viewmodel());
        add(new dev.vantage.module.cosmetic.MotionBlur());
        add(new dev.vantage.module.cosmetic.Crosshair());
        add(new dev.vantage.module.cosmetic.HitColor());
        add(new dev.vantage.module.cosmetic.TimeChanger());
        var outline = add(new dev.vantage.module.cosmetic.BlockOutline());
        add(new dev.vantage.module.cosmetic.ColorGrading());
        add(new dev.vantage.module.cosmetic.HandAura());
        add(new dev.vantage.module.cosmetic.Atmosphere());
        add(new dev.vantage.module.cosmetic.Zoom());
        add(new dev.vantage.module.cosmetic.CustomFov());
        add(new dev.vantage.module.cosmetic.SwingAnimation());
        add(new dev.vantage.module.cosmetic.HitParticles());
        add(new dev.vantage.module.cosmetic.JumpCircles());
        add(new dev.vantage.module.cosmetic.LowHealth());
        add(new dev.vantage.module.cosmetic.CustomGlint());
        add(new dev.vantage.module.cosmetic.Orbs());
        add(new dev.vantage.module.cosmetic.TotemSpirit());
        add(new dev.vantage.module.cosmetic.Pet());
        add(new dev.vantage.module.cosmetic.DigBurst());
        add(new dev.vantage.module.cosmetic.Footprints());
        add(new dev.vantage.module.cosmetic.LandingRing());
        add(new dev.vantage.module.cosmetic.BladeTrail());
        add(new dev.vantage.module.cosmetic.DamageNumbers());
        add(new dev.vantage.module.cosmetic.KillEffect());
        add(new dev.vantage.module.cosmetic.LootSparkle());
        add(new dev.vantage.module.cosmetic.PayPop());
        add(new dev.vantage.module.cosmetic.FindPulse());
        section("");
        add(new dev.vantage.module.donut.DonutRegions());
        add(base.bundle("Base ESP", new dev.vantage.module.donut.BaseEsp()));
        add(new dev.vantage.module.donut.FarmOptimizer());
        add(new dev.vantage.module.donut.ProfitTracker());
        add(base.bundle("Shard Watch", new dev.vantage.module.donut.ShardWatch()));
        add(base.bundle("Donut Wheel", new dev.vantage.module.donut.DonutWheel()));
        add(new dev.vantage.module.donut.RtpLoop());
        add(new dev.vantage.module.donut.Macro());
        dev.vantage.module.donut.VoidRescue voidRescue = new dev.vantage.module.donut.VoidRescue();
        add(voidRescue);
        add(new ClickGuiModule());
        add(new HudModule());
        add(new dev.vantage.module.client.Relog());
        add(new dev.vantage.module.client.FriendsModule());
        add(new dev.vantage.module.client.FindLogModule());
        add(new dev.vantage.module.client.FrameBudget());
        add(new dev.vantage.module.client.VantageOnline());
        add(new dev.vantage.module.client.FastXp());
        add(new dev.vantage.module.client.StreamerMode());
        add(new dev.vantage.module.client.PanicKey());
        add(new dev.vantage.module.client.ServiceDemo());
        validate();
        refresh();
    }

    private static void section(String name) {
        section = name;
    }

    private static final java.util.Map<String, Module> BY_ID = new java.util.HashMap<>();

    /** Registers a module: one call per module in init(). Two modules with the same id are a programming error and stop the start. */
    static <M extends Module> M add(M m) {
        Module clash = BY_ID.putIfAbsent(m.id, m);
        if (clash != null && clash != m) throw new IllegalStateException("module id '" + m.id + "' is used by both " + clash.getClass().getName() + " and " + m.getClass().getName());
        m.group = section;
        ALL.add(m);
        BY_CLASS.put(m.getClass(), m);
        m.registered(ALL.size());
        return m;
    }

    public static List<Module> all() {
        return visible;
    }

    public static List<Module> in(Category c) {
        return visible.stream().filter(m -> m.category == c && m.owner == null).toList();
    }

    @SuppressWarnings("unchecked")
    public static <T extends Module> T get(Class<T> type) {
        return (T) BY_CLASS.get(type);
    }

    /** The module with this stable id (locked betas included), or null. */
    public static Module byId(String id) {
        return BY_ID.get(id);
    }

    /**
     * Checks what the modules declare about each other, once at start: a "requires" chain must not come back to where it began,
     * and a module must not require one it conflicts with. Both are programming errors and stop the start with the names.
     */
    static void validate() {
        for (Module m : ALL) {
            java.util.Deque<Module> todo = new java.util.ArrayDeque<>();
            java.util.Set<Module> seen = new java.util.HashSet<>();
            for (Class<? extends Module> c : m.required()) if (get(c) != null) todo.add(get(c));
            while (!todo.isEmpty()) {
                Module r = todo.pop();
                if (r == m) throw new IllegalStateException("module '" + m.id + "' requires itself through other modules");
                if (!seen.add(r)) continue;
                if (m.conflicting().contains(r.getClass()) || r.conflicting().contains(m.getClass())) throw new IllegalStateException("module '" + m.id + "' requires '" + r.id + "' but they are declared as conflicting");
                for (Class<? extends Module> c : r.required()) if (get(c) != null) todo.add(get(c));
            }
        }
    }

    public static Module byName(String name) {
        for (Module m : visible) if (m.name.equalsIgnoreCase(name)) return m;
        return null;
    }

    public static boolean paused() {
        return paused;
    }

    /** What the master switch would bring back. */
    public static List<String> pausedList() {
        return Collections.unmodifiableList(PAUSED);
    }

    /** Off: remembers what is on (except the menu itself) and switches it all off. On: brings that set back. */
    public static void setPaused(boolean off) {
        if (off == paused) return;
        paused = off;
        if (off) {
            PAUSED.clear();
            for (Module m : visible) {
                if (m.owner != null || m instanceof dev.vantage.module.client.ClickGuiModule || m instanceof dev.vantage.module.client.PanicKey || !m.on()) continue;
                PAUSED.add(m.name);
                m.setEnabled(false);
            }
        } else {
            for (String n : PAUSED) { Module m = byName(n); if (m != null) m.setEnabled(true); }
            PAUSED.clear();
        }
        for (Module m : visible) if (!m.parts.isEmpty()) m.syncParts(); // by-hand parts follow Panic-style pausing through handOn(), their flag is kept and saved
        Config.markDirty();
    }

    /** Used when loading the config: restores a paused state without touching the modules (they were saved off). */
    static void loadPaused(List<String> names) {
        paused = true;
        PAUSED.clear();
        PAUSED.addAll(names);
    }

    private Modules() {}
}
