package dev.vantage.core;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.event.BlockUpdateEvent;
import dev.vantage.core.event.ChunkDataEvent;
import dev.vantage.core.event.ChunkLoadEvent;
import dev.vantage.core.event.ChunkUnloadEvent;
import dev.vantage.core.event.DimensionChangeEvent;
import dev.vantage.core.event.Events;
import dev.vantage.core.event.RenderHudEvent;
import dev.vantage.core.event.RenderWorldEvent;
import dev.vantage.core.event.SectionBurstEvent;
import dev.vantage.core.event.TickEvent;
import dev.vantage.core.event.WorldJoinEvent;
import dev.vantage.core.event.WorldLeaveEvent;
import dev.vantage.render.R3;
import dev.vantage.setting.Setting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Base for every feature. Everything runs on the client thread and only reads what the client already has.
 *
 * A module hears about the game through the event bus ({@link Events}). The hooks below ({@code onTick}, {@code onRender3D}, ...)
 * are the usual way to use it: the constructor subscribes every hook the subclass overrides, and those listeners are live exactly
 * while the module is switched on. For events without a hook a module calls {@link #listen} in its constructor.
 */
public abstract class Module implements Events.Owner {
    /** What saved files, profiles and commands call this module. Never changes once released; made from the name unless given. */
    public final String id;
    public final String name;
    public final String description;
    public final Category category;
    public final List<Setting<?>> settings = new ArrayList<>();
    /** A scanner living inside this module: runs while this module and its switch are both on. */
    public record Part(dev.vantage.setting.BoolSetting toggle, Module module, dev.vantage.setting.BoolSetting hand, java.util.concurrent.atomic.AtomicBoolean cameBackAsWith) {
        /** Beta part states: OFF, WITH (starts when the owner does), HAND (runs on its own, owner on or off). Without the beta unlock only toggle counts, as always. */
        public enum State { OFF, WITH, HAND }

        public boolean handOn() {
            return Modules.betaUnlocked() && hand.on() && !Panic.active() && !Modules.paused(); // Panic and the master switch stop it without losing the flag
        }

        public State state() {
            return Modules.betaUnlocked() && hand.on() ? State.HAND : toggle.on() ? State.WITH : State.OFF;
        }

        /** The kept key (beta): running (with or by hand) -> off; off -> the state it had before, by hand when there is none. */
        public void keyToggle() {
            if (Panic.active() || Modules.paused()) return;
            State s = state();
            if (s != State.OFF) { cameBackAsWith.set(s == State.WITH); hand.set(false); toggle.set(false); }
            else if (cameBackAsWith.get()) { hand.set(false); toggle.set(true); }
            else { toggle.set(true); hand.set(true); }
        }

        /** Click order in the menu (beta): off, with the owner, by hand, off. */
        public void cycle() {
            if (Panic.active() || Modules.paused()) return; // nothing is started while Panic or the master switch has everything off
            if (hand.on()) { hand.set(false); toggle.set(false); }
            else if (toggle.on()) hand.set(true);
            else toggle.set(true);
        }
    }
    public final List<Part> parts = new ArrayList<>();
    /** The module this one is a part of. Parts have no row, key or Module List line of their own. */
    public Module owner;
    /** Section inside its category in the click GUI. */
    public String group = "";
    private boolean enabled;
    private boolean beta;
    /** Switched off for this session because one of its hooks threw. The saved state still says "on" (see {@link #fault}). */
    private boolean faulted;
    /** Position in the registry, set by Modules when the module is added; decides the order of hooks between modules. */
    int rank;
    /** Listeners that are live while the module is switched on. */
    private final Events.Group listeners = new Events.Group(this);
    /** Listeners that are live from registration on, whether the module is on or off (join, leave and dimension change: a module that is off still has to drop what it kept about the old world). */
    private final Events.Group always = new Events.Group(this);
    /** GLFW key code, or -1 for none. Mouse buttons are stored as -100 - button. */
    public int bind = -1;
    /** Shown in the module list next to the name, e.g. a count. */
    public String info() { return null; }

    protected final Minecraft mc = Minecraft.getInstance();

    protected Module(String name, String description, Category category) {
        this(Ids.of(name), name, description, category);
    }

    /** For a module whose display name changed after release: the id stays what it was. */
    protected Module(String id, String name, String description, Category category) {
        if (!Ids.valid(id)) throw new IllegalArgumentException("bad module id '" + id + "'");
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        subscribeHooks();
    }

    /** What a world label of this module is, for the label budget: a player or place name, a summary of a find, evidence for one, or anything else. */
    public static final int LABEL_NAME = 0, LABEL_SUMMARY = 1, LABEL_EVIDENCE = 2, LABEL_OTHER = 3;

    /** One of the LABEL_ constants. Names always show, summaries rank highest, then evidence. Modules with labels of the first three kinds override it. */
    public int labelClass() {
        return LABEL_OTHER;
    }

    /** Whether Declutter may hold this module's small boxes back and merge touching ones into one outline with a count. */
    public boolean groupsBoxes() {
        return false;
    }

    @Override
    public String ownerName() {
        return name;
    }

    @Override
    public int ownerRank() {
        return rank;
    }

    /** Listens to an event for as long as this module is on. Call it in the constructor. The handler is skipped while the module is muted. */
    protected final <E> void listen(Class<E> type, Events.Handler<? super E> handler) {
        listeners.listen(type, e -> { if (on()) handler.on(e); });
    }

    /** True when the subclass has its own version of a hook; hooks left empty are not subscribed, so they cost nothing per tick or frame. */
    private boolean overrides(String hook, Class<?>... args) {
        for (Class<?> c = getClass(); c != null && c != Module.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(hook, args);
                return true;
            } catch (NoSuchMethodException e) {
                // not declared at this level, look one class up
            }
        }
        return false;
    }

    private java.util.List<dev.vantage.core.hud.HudPanel> hudPanels;

    /**
     * Declares a HUD panel of this module (call it in the constructor) and returns it to be finished with {@code size} and {@code draw}
     * (see {@link dev.vantage.core.hud.HudPanel}). The element is listed and can be hidden or moved while the module is off; the panel is
     * drawn only while it is on. Several panels are drawn in the order they were declared.
     */
    protected final dev.vantage.core.hud.HudPanel hudPanel(String id, String title, String corner) {
        var el = dev.vantage.core.hud.HudElements.declare(id, title, corner).ownedBy(this::isEnabled);
        var panel = new dev.vantage.core.hud.HudPanel(el);
        if (hudPanels == null) {
            hudPanels = new java.util.ArrayList<>(2);
            final String t = name + " panel";
            listeners.listen(RenderHudEvent.class, hudPriority(), e -> {
                if (!on()) return;
                long t0 = System.nanoTime();
                for (var p : hudPanels) p.render(e.g);
                Perf.add(t, System.nanoTime() - t0);
            });
        }
        hudPanels.add(panel);
        return panel;
    }

    /** The HUD is drawn category by category so that the screen corners stack in a stable order: client first, finder panels last. */
    private int hudPriority() {
        return switch (category) { case CLIENT -> 4; case RENDER -> 3; case COSMETIC -> 2; case DONUT -> 1; default -> 0; };
    }

    private void subscribeHooks() {
        // the timing labels are built once here; building them per call would allocate a string per module per hook per frame
        final String tTick = name + " tick", t3d = name + " 3d", t2d = name + " 2d", tBlock = name + " block", tBurst = name + " burst", tChunk = name + " chunk";
        if (overrides("onTick")) listeners.listen(TickEvent.class, e -> { if (!on()) return; long t0 = System.nanoTime(); onTick(); Perf.add(tTick, System.nanoTime() - t0); });
        if (overrides("onRender3D", R3.class)) listeners.listen(RenderWorldEvent.class, e -> { if (!on()) return; long t0 = System.nanoTime(); e.r.from(this); onRender3D(e.r); Perf.add(t3d, System.nanoTime() - t0); });
        if (overrides("onRender2D", GuiGraphics.class, DeltaTracker.class)) listeners.listen(RenderHudEvent.class, hudPriority(), e -> {
            if (!on()) return;
            long t0 = System.nanoTime();
            int mark = Ui.depth();
            try {
                onRender2D(e.g, e.delta);
            } finally {
                if (Ui.depth() != mark) Ui.unwind(e.g, mark); // a module that failed halfway must not leave its scale on for the rest of the frame
                Perf.add(t2d, System.nanoTime() - t0);
            }
        });
        if (overrides("onChunkLoad", LevelChunk.class)) listeners.listen(ChunkLoadEvent.class, e -> { if (on()) onChunkLoad(e.chunk); });
        if (overrides("onChunkUnload", int.class, int.class)) listeners.listen(ChunkUnloadEvent.class, e -> { if (on()) onChunkUnload(e.cx, e.cz); });
        if (overrides("onChunkData", LevelChunk.class)) listeners.listen(ChunkDataEvent.class, e -> { if (!on()) return; long t0 = System.nanoTime(); onChunkData(e.chunk); Perf.add(tChunk, System.nanoTime() - t0); });
        if (overrides("onBlockUpdate", BlockPos.class, BlockState.class)) listeners.listen(BlockUpdateEvent.class, e -> { if (!on()) return; long t0 = System.nanoTime(); onBlockUpdate(e.pos, e.state); Perf.add(tBlock, System.nanoTime() - t0); });
        if (overrides("onSectionBurst", net.minecraft.core.SectionPos.class, int.class, BlockPos.class, BlockState.class))
            listeners.listen(SectionBurstEvent.class, e -> { if (!on()) return; long t0 = System.nanoTime(); onSectionBurst(e.section, e.count, e.first, e.firstState); Perf.add(tBurst, System.nanoTime() - t0); });
        if (overrides("onJoinWorld")) always.listen(WorldJoinEvent.class, e -> { if (!locked()) onJoinWorld(); });
        if (overrides("onLeaveWorld")) always.listen(WorldLeaveEvent.class, e -> { if (!locked()) onLeaveWorld(); });
        if (overrides("onDimensionChange")) always.listen(DimensionChangeEvent.class, e -> { if (!locked()) onDimensionChange(); });
    }

    /** Called by Modules when the module is registered: from now on it hears the world being joined, left and switched, also while it is off. */
    void registered(int rank) {
        this.rank = rank;
        always.attach();
    }

    // ---- modules that need or exclude each other -------------------------------------------------------------------------------

    private final List<Class<? extends Module>> requires = new ArrayList<>(), conflicts = new ArrayList<>();
    /** Set while this module is in the middle of being switched, so that two modules naming each other cannot loop. */
    private boolean switching;

    /** This module only works while the named ones are on: switching it on switches them on first, switching one of them off switches this off. Call it in the constructor. */
    @SafeVarargs
    protected final void requires(Class<? extends Module>... modules) {
        for (Class<? extends Module> c : modules) requires.add(c);
    }

    /** This module and the named ones cannot run together: switching one on switches the other off. Call it in the constructor (one side is enough). */
    @SafeVarargs
    protected final void conflictsWith(Class<? extends Module>... modules) {
        for (Class<? extends Module> c : modules) conflicts.add(c);
    }

    public List<Class<? extends Module>> required() {
        return java.util.Collections.unmodifiableList(requires);
    }

    public List<Class<? extends Module>> conflicting() {
        return java.util.Collections.unmodifiableList(conflicts);
    }

    private void tell(dev.vantage.core.notify.Level level, String text) {
        if (mc != null && mc.player != null) dev.vantage.core.notify.Notify.post(this, level, null, -1, text, null);
    }

    /** Before switching on: required modules on, conflicting ones off. False when a required module cannot be switched on. */
    private boolean prepareOn() {
        for (Class<? extends Module> c : requires) {
            Module r = Modules.get(c);
            if (r == null || r.isEnabled()) continue;
            r.setEnabled(true);
            if (!r.isEnabled()) {
                tell(dev.vantage.core.notify.Level.WARNING, name + " needs " + r.name + ", which could not be switched on.");
                return false;
            }
            tell(dev.vantage.core.notify.Level.INFO, r.name + " was switched on because " + name + " needs it.");
        }
        for (Module other : Modules.everything()) {
            if (other == this || !other.isEnabled()) continue;
            if (conflicts.contains(other.getClass()) || other.conflicts.contains(getClass())) {
                other.setEnabled(false);
                tell(dev.vantage.core.notify.Level.INFO, other.name + " was switched off because it cannot run together with " + name + ".");
            }
        }
        return true;
    }

    /** After switching off: everything that needs this module goes off too. */
    private void dependentsOff() {
        for (Module other : Modules.everything()) {
            if (other != this && other.isEnabled() && other.requires.contains(getClass())) {
                other.setEnabled(false);
                tell(dev.vantage.core.notify.Level.INFO, other.name + " was switched off because it needs " + name + ".");
            }
        }
    }

    /** True while the module is off for this session because of an error in one of its hooks. */
    public boolean faulted() {
        return faulted;
    }

    /**
     * One of this module's hooks threw. The module is switched off for the rest of the session, the user is told once and the error
     * is in the log. The saved state is left alone ({@link Panic#persisted}), so after a restart the module is on again: one bad
     * frame must not disable a feature for ever. Switching it on by hand clears the fault.
     */
    void fault(String where, Throwable error) {
        Vantage.LOG.error("{} failed in {} and is switched off for this session", name, where, error);
        if (!enabled) return;
        faulted = true;
        enabled = false;
        listeners.detach();
        dev.vantage.core.chunk.Chunks.cancel(this);
        try {
            onDisable();
        } catch (Throwable t) {
            Vantage.LOG.error("{} also failed while switching off", name, t);
        }
        syncParts();
        dependentsOff();
        if (mc != null && mc.player != null) dev.vantage.core.notify.Notify.error(this, id + ":" + where, name + " ran into an error and was switched off. The details are in the log.");
    }

    private volatile dev.vantage.core.log.SourceLog log;

    /**
     * This module's logger: lines are marked with the module id; {@code debug(...)} and {@code once(...)} are described there. Created on
     * first use and safe to call from any thread (a scan job on a worker may be the first caller): the holder is volatile and the
     * creation is locked, so every caller gets the same object. Not created eagerly in the constructor: a logger that exists makes the
     * module's id a known debug source (BUGS.md B-205), which a module nobody has used should not be.
     */
    public final dev.vantage.core.log.SourceLog log() {
        dev.vantage.core.log.SourceLog l = log;
        if (l == null) {
            synchronized (this) {
                l = log;
                if (l == null) log = l = dev.vantage.core.log.Log.core(id);
            }
        }
        return l;
    }

    /**
     * Declares a chat command of this module (call it in the constructor). It exists only while the module is not a locked beta, and
     * while the module is off the dispatcher answers "&lt;Module&gt; is off. Turn it on with .toggle &lt;id&gt;" instead of running it.
     * A command that is meant to work while the module is off (listing saved data, starting the module itself) says so with
     * {@code command(Command.named(...).usableWhileOff()...)}.
     */
    protected final dev.vantage.core.command.Command command(dev.vantage.core.command.Command c) {
        dev.vantage.core.command.Commands.main().register(c.availableIf(() -> !locked()).ownedBy(name, id, this::isEnabled));
        return c;
    }

    protected <S extends Setting<?>> S add(S s) {
        for (Setting<?> other : settings) {
            if (other.id().equals(s.id()) && isSaved(other) && isSaved(s)) throw new IllegalStateException(name + " has two settings with the id '" + s.id() + "' (" + other.name + ", " + s.name + ")");
        }
        s.ownedBy(this);
        settings.add(s);
        return s;
    }

    /** Headers, buttons and info lines hold nothing and are never saved, so they may share a name. */
    private static boolean isSaved(Setting<?> s) {
        return !(s instanceof dev.vantage.setting.Header || s instanceof dev.vantage.setting.ActionSetting || s instanceof dev.vantage.setting.InfoSetting);
    }

    /** Called by a setting of this module after its value changed by any route (menu, command, profile, config load). */
    public final void settingChanged(Setting<?> setting) {
        try {
            onSettingChanged(setting);
        } catch (Throwable t) {
            fault("onSettingChanged", t);
        }
    }

    /**
     * Override to react to a changed setting, for example to rebuild something derived from it. Called on the client thread, after the
     * new value is in place, from two routes:
     * <ul>
     * <li>{@code set} / {@code trySet} / {@code reset} (the menu, the {@code .set} command, code): only when the value really changed.</li>
     * <li>{@code load} (the config file at start-up, loading a profile, importing a share code): for every value read from the file,
     * also when it equals the one already there, and without a world: at start-up {@code mc.level} is still null, and the module's
     * {@code onEnable} may not have run yet.</li>
     * </ul>
     * It fires whether the module is on or off. A hook that touches the world, or something {@code onEnable} sets up, starts with
     * {@code if (!activeInWorld()) return;}. A throw faults the module like any other hook.
     */
    protected void onSettingChanged(Setting<?> setting) {}

    /** True while this module is on and the client has a world: the state in which a hook may use the world and what {@code onEnable} built. */
    protected final boolean activeInWorld() {
        return on() && mc != null && mc.level != null;
    }

    /** Set by the rigs around a screenshot: the module keeps its state but every hook sees it as off, so the picture shows one feature only. */
    public boolean muted;
    /** Beta part that used to be a module of its own (Sound Detector): its old saved switch is kept while it is a part, so going back to locked does not switch it on. */
    public boolean keepsStandalone;
    public boolean standaloneEnabled;

    public boolean on() {
        return enabled && !muted && !locked() && !Terms.off(); // the terms gate: nothing of Vantage is on until the terms are confirmed
    }

    /**
     * Marks this module as a beta: call {@code beta();} in its constructor body. Only the owner sees betas (see Modules.betaUnlocked).
     * Promoting a module later = deleting that one call. A part is beta when its owner is.
     */
    protected final void beta() {
        beta = true;
    }

    /** Developer module: exists only where developer modules are unlocked (see Modules.devUnlocked). */
    private boolean developerOnly;

    /** Marks this module as a developer module: call {@code developer();} in its constructor body. It is locked like a beta, so no list, command or saved config can reach it without {@code -Dvantage.dev=true}. */
    protected final void developer() {
        developerOnly = true;
    }

    private boolean startsOn;

    /**
     * Marks this module as on for someone who has never switched it: call {@code onByDefault();} in its constructor body. It
     * applies on the first start and whenever a saved config has no entry for the module yet; a saved "off" always wins.
     */
    protected final void onByDefault() {
        startsOn = true;
    }

    public boolean startsOn() {
        return startsOn;
    }

    /** Test hook (UiTest): marks or unmarks an existing module at runtime; call Modules.refresh() after. */
    void setBeta(boolean b) {
        beta = b;
    }

    public boolean isBeta() {
        return beta || (owner != null && owner.beta);
    }

    /** A beta while beta is not unlocked, or a developer module while developer modules are not: the module does not exist for the user. */
    public boolean locked() {
        return (isBeta() && !Modules.betaUnlocked()) || (developerOnly && !Modules.devUnlocked());
    }

    /** The real switch state, also while a rig screenshot has the module muted (config profiles save this). */
    public boolean isEnabled() {
        return enabled;
    }

    public <M extends Module> M part(dev.vantage.setting.BoolSetting toggle, M m) {
        m.owner = this;
        // saved like any setting, never shown as a row: the part card in the menu is the control (beta)
        dev.vantage.setting.BoolSetting hand = add(new dev.vantage.setting.BoolSetting(toggle.name + " (by hand)", "Runs by itself, whether " + name + " is on or not", false)).visibleIf(() -> false);
        hand.storedIf(Modules::betaUnlocked);
        parts.add(new Part(toggle, m, hand, new java.util.concurrent.atomic.AtomicBoolean(false)));
        toggle.onChange(this::syncParts);
        hand.onChange(this::syncParts);
        return m;
    }

    /** Beta: one of this module's scanners is running by hand (so it shows as active even while this module is off). */
    public boolean hasHandRunning() {
        for (Part p : parts) if (p.handOn() && p.module.isEnabled()) return true;
        return false;
    }

    /** Beta: this is a part that runs by hand while its owner is off, so it gets its own line in the Module List. */
    public boolean aloneByHand() {
        if (owner == null || owner.isEnabled()) return false;
        for (Part p : owner.parts) if (p.module == this && p.handOn() && isEnabled()) return true;
        return false;
    }

    public void syncParts() {
        for (Part p : parts) p.module.setEnabled(enabled && p.toggle.on() || p.handOn());
    }

    /** The parts the user can see (locked betas left out). */
    public List<Part> visibleParts() {
        return parts.stream().filter(p -> !p.module.locked()).toList();
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean v) {
        if (v == enabled || (v && locked())) {
            if (!v && faulted) { faulted = false; Config.markDirty(); } // the user switched a faulted module off: now it really is off
            return;
        }
        if (switchTo(v)) syncParts();
        Config.markDirty();
    }

    /** Used when loading the config: the same switch as setEnabled, without marking the config as changed. */
    void loadEnabled(boolean v) {
        if (v == enabled || (v && locked())) return;
        switchTo(v);
    }

    /**
     * Flips the switch, runs onEnable or onDisable and attaches or detaches the listeners. A module whose onEnable throws ends up
     * off and faulted, not half on. Returns false in that case.
     */
    private boolean switchTo(boolean v) {
        if (switching) return false; // already on its way: a module that names itself through others must not loop
        switching = true;
        try {
            faulted = false;
            if (v) {
                if (!prepareOn()) return false;
                enabled = true;
                try {
                    onEnable();
                } catch (Throwable t) {
                    fault("onEnable", t);
                    return false;
                }
                listeners.attach();
            } else {
                enabled = false;
                listeners.detach();
                dev.vantage.core.chunk.Chunks.cancel(this); // queued chunk jobs and running scans of this module are dropped, so switching on again does not deliver old results
                try {
                    onDisable();
                } catch (Throwable t) {
                    Vantage.LOG.error("{} failed while switching off", name, t);
                }
                dependentsOff();
            }
            return true;
        } finally {
            switching = false;
        }
    }

    /** True when the key opens something while held instead of switching the module on and off. */
    public boolean holdKey() { return false; }
    public void onBindPress() {}

    protected void onEnable() {}
    protected void onDisable() {}
    public void onTick() {}
    public void onRender3D(R3 r) {}
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {}
    public void onChunkLoad(LevelChunk chunk) {}
    public void onChunkUnload(int cx, int cz) {}
    /** A block changed because the server told us (single update or section update). */
    public void onBlockUpdate(BlockPos pos, BlockState state) {}
    /**
     * A section packet with more than VantageClient.BURST blocks (a server filling or un-filling a slab): called once instead of
     * onBlockUpdate per block, with the first block as a sample; onChunkData follows once per chunk and tick.
     */
    public void onSectionBurst(net.minecraft.core.SectionPos section, int count, BlockPos first, BlockState firstState) {}
    /** A chunk's data arrived from the server (called after onChunkLoad for a new chunk, again for a re-sent chunk and after a burst): the one place to scan it. */
    public void onChunkData(LevelChunk chunk) {}
    /** The client got a world where there was none (joined a server, opened a world). Called also while the module is off. */
    public void onJoinWorld() {}
    /** The client's world is gone (left the server, back on the title screen). Drop what was kept about it. Called also while the module is off. */
    public void onLeaveWorld() {}
    /** The world was replaced by another one in the same session (nether portal, respawn elsewhere). Called also while the module is off. */
    public void onDimensionChange() {}
}
