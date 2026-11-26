package dev.vantage;

import dev.vantage.core.Config;
import dev.vantage.core.Keys;
import dev.vantage.core.Modules;
import dev.vantage.core.Vantage;
import dev.vantage.core.event.BlockUpdateEvent;
import dev.vantage.core.event.ChunkDataEvent;
import dev.vantage.core.event.ChunkLoadEvent;
import dev.vantage.core.event.ChunkUnloadEvent;
import dev.vantage.core.event.RenderHudEvent;
import dev.vantage.core.event.RenderWorldEvent;
import dev.vantage.core.event.SectionBurstEvent;
import dev.vantage.core.event.TickEvent;
import dev.vantage.core.event.WorldEvents;
import dev.vantage.render.R3;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Entry point. Client-only: no packets of our own, no registry entries, no key mappings, no commands,
 * no language file. The server never receives anything from this mod.
 */
public class VantageClient implements ClientModInitializer {
    private static Level lastLevel;
    private static boolean sayConfigProblems;
    private static boolean drawErrorLogged;
    private static boolean missingHooksSaid;

    private static boolean lateHooksSaid;

    /** Once per session: the hooks that are judged by running and still have not run. */
    private static void sayLateHooks() {
        if (lateHooksSaid) return;
        lateHooksSaid = true;
        var silent = dev.vantage.core.hooks.OptionalHooks.lateMissing();
        if (dev.vantage.core.hooks.OptionalHooks.lateCount() > 0) Vantage.LOG.info("Hooks judged by whether they run: {}, silent after 10 seconds in a world: {}", dev.vantage.core.hooks.OptionalHooks.lateCount(), silent.size());
        if (silent.isEmpty()) return;
        for (String h : silent) Vantage.LOG.warn("A hook has not run 10 seconds into a world and is taken as inactive: {}", h);
        dev.vantage.core.Chat.info("This Sodium version changed something the client hooks into (" + String.join(", ", silent) + "), so Xray or the cave view may not draw correctly. Details are in the log.");
    }

    /** Once per session: hooks into Sodium or Voxy that did not fit the installed version, so the features that need them are named. */
    private static void sayMissingHooks() {
        var missing = dev.vantage.core.hooks.OptionalHooks.missing();
        if (missingHooksSaid || missing.isEmpty()) return;
        missingHooksSaid = true;
        boolean sodium = missing.stream().anyMatch(m -> m.startsWith("Sodium")), voxy = missing.stream().anyMatch(m -> m.startsWith("Voxy"));
        if (sodium) dev.vantage.core.Chat.info("This Sodium version changed something Xray hooks into, so Xray may not hide blocks correctly. Details are in the log.");
        if (voxy) dev.vantage.core.Chat.info("This Voxy version changed something the client hooks into, so distant terrain may be drawn where it should be hidden. Details are in the log.");
    }
    private static int sinceLevel;

    @Override
    public void onInitializeClient() {
        dev.vantage.core.Terms.init(); // first: decides whether Vantage runs at all this launch
        dev.vantage.core.CoreCommands.register(); // before the modules: the core commands come first in the help list, the modules add theirs
        Modules.init();
        dev.vantage.core.CoreTaps.init();
        dev.vantage.core.chunk.Chunks.init();
        dev.vantage.core.FakeOres.init();
        Config.load();
        dev.vantage.module.client.Relog.register();

        ClientTickEvents.END_CLIENT_TICK.register(VantageClient::tick);
        dev.vantage.core.Presence.init();
        var gui = Modules.get(dev.vantage.module.client.ClickGuiModule.class);
        if (gui != null && gui.updates.on()) dev.vantage.core.Updater.check();
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> ChunkLoadEvent.fire(chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            dev.vantage.core.HiddenStorage.unload(chunk.getPos().x, chunk.getPos().z);
            ChunkUnloadEvent.fire(chunk.getPos().x, chunk.getPos().z);
        });
        WorldRenderEvents.END_MAIN.register(VantageClient::render3d);
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_CHAT.register(VantageClient::chatCommand);
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.COMMAND.register(command -> {
            var s = dev.vantage.core.Modules.get(dev.vantage.module.donut.ShardWatch.class);
            if (s != null && s.on()) s.onCommand(command);
        });
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            var p = dev.vantage.core.Modules.get(dev.vantage.module.donut.ProfitTracker.class);
            if (p != null && p.on()) p.onMessage(message.getString(), overlay);
            var pay = dev.vantage.core.Modules.get(dev.vantage.module.cosmetic.PayPop.class);
            if (pay != null && pay.on()) pay.onMessage(message.getString(), overlay);
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("vantage", "hud"), VantageClient::render2d);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { try { var bfm = dev.vantage.core.Modules.get(dev.vantage.module.finder.BaseFinder.class); if (bfm != null) bfm.flushOnExit(); } catch (Throwable t) { Vantage.LOG.warn("ledger not flushed on exit", t); } Config.save(); dev.vantage.core.LeakLog.flush(); dev.vantage.core.ScanMemory.save(); dev.vantage.core.log.Log.writeRepeatCounts(); dev.vantage.core.store.Stores.flush(); });
    }

    /**
     * A line typed in chat. A line that looks like a client command is answered by {@link dev.vantage.core.command.Commands} and stays on
     * this side whatever happens to it (BUGS.md B-185); anything else goes on to the server. Returns false to keep the line off the server.
     */
    public static boolean chatCommand(String msg) {
        return !dev.vantage.core.command.Commands.main().run(msg);
    }

    private static void tick(Minecraft mc) {
        if (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) dev.vantage.core.Updater.reachedTitle();
        if (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && dev.vantage.core.Updater.pending() != null && !dev.vantage.core.Updater.asked()) {
            dev.vantage.core.Updater.markAsked();
            mc.setScreen(new dev.vantage.gui.UpdateScreen(mc.screen));
        }
        if (System.getProperty("vantage.ui") == null) dev.vantage.core.PresenceConsent.askOnTitle(mc.screen, mc::setScreen);
        dev.vantage.core.Terms.askOnTitle(mc.screen, mc::setScreen);
        if (dev.vantage.core.Terms.off()) return; // the terms gate: nothing else of Vantage ticks (the launch guard above is local and stays)
        dev.vantage.module.render.NoRender.syncVoxyFog();
        // beta: the one-time welcome on the title screen (after the update and presence questions, which take the screen first)
        if (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && dev.vantage.core.Welcome.due()
                && (dev.vantage.core.Updater.pending() == null || dev.vantage.core.Updater.asked())) {
            mc.setScreen(new dev.vantage.gui.WelcomeScreen(mc.screen));
        }
        if (mc.level != lastLevel) {
            Level before = lastLevel;
            lastLevel = mc.level;
            dev.vantage.core.HiddenStorage.clear();
            dev.vantage.render.R3.forgetWorld();
            dev.vantage.core.LeakLog.clear();
            dev.vantage.core.ScanMemory.save();
            if (mc.level == null) { dev.vantage.core.notify.Notify.worldLeft(); dev.vantage.core.ChunkTracker.clearAll(); dev.vantage.core.FakeOres.reset(); dev.vantage.core.store.Stores.flush(); }
            if (mc.level != null) sayConfigProblems = true;
            WorldEvents.fire(before != null, mc.level != null, mc.level);
            sinceLevel = 0;
        }
        if (mc.level != null && sinceLevel == 200 && mc.player != null) sayLateHooks(); // 10 seconds of chunks being built and drawn
        if (mc.level != null && ++sinceLevel == 60) {
            try { dev.vantage.core.SeedCheck.onWorld(); } catch (Throwable t) { Vantage.LOG.error("seed check", t); }
        }
        Keys.tick(mc);
        if (mc.level != null && mc.level.getGameTime() % 40 == 0) dev.vantage.core.LeakLog.flush();
        dev.vantage.core.ScanMemory.tick();
        dev.vantage.core.LeakProbe.tick();
        dev.vantage.core.ScanBudget.newTick();
        if (sayConfigProblems && mc.player != null) { // chat needs a player
            sayConfigProblems = false;
            Config.showProblems();
            sayMissingHooks();
        }
        if (mc.level != null && mc.player != null) TickEvent.fire();
        Config.tick();
        dev.vantage.core.notify.Notify.tick();
        dev.vantage.core.store.Stores.tick();
        if (mc.player != null && dev.vantage.core.Notices.hasUnshown()) dev.vantage.core.Notices.show(); // saved-data notices from stores that loaded after the join
    }

    private static void render3d(WorldRenderContext ctx) {
        if (dev.vantage.core.Terms.off()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        R3 r = new R3(mc.gameRenderer.getMainCamera(), mc.getDeltaTracker().getGameTimeDeltaPartialTick(true), dev.vantage.module.render.EspStyle.snapshot());
        RenderWorldEvent.fire(r);
        // The flush belongs to no module, so the module system does not catch its errors: an error here costs this frame's overlays
        // and is logged once, it must never reach the game.
        long t0 = System.nanoTime();
        try {
            if (!r.empty()) r.flush(ctx.matrices(), ctx.consumers());
        } catch (Throwable t) {
            if (!drawErrorLogged) { drawErrorLogged = true; Vantage.LOG.error("The world overlays could not be drawn (logged once)", t); }
        }
        dev.vantage.core.Perf.add("R3 flush", System.nanoTime() - t0);
    }

    /** Test hook (UiTest): runs once with the next HUD frame's GuiGraphics. */
    static volatile java.util.function.Consumer<GuiGraphics> testOnce;
    /** Rig: runs at the start of every HUD frame until it is set to null. */
    static volatile java.util.function.Consumer<GuiGraphics> testEach;

    /** HUD frames drawn so far: the rig waits for new ones before it grabs a picture (the software renderer shows 1-7 a second). */
    static volatile long frames;

    /** A screen that hides the game HUD (the menu, in beta) counts its own frames, since the HUD callback does not run then. */
    public static void screenFrame() {
        frames++;
    }

    private static long lateFaultAt;

    /** A Callouts/Toasts draw threw: log it (at most every 10 s, it repeats every frame) and carry on with a clean scale stack. */
    private static void lateFault(String what, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lateFaultAt < 10_000) return;
        lateFaultAt = now;
        dev.vantage.core.Vantage.LOG.warn("{} failed while drawing, scale stack closed", what, t);
    }

    private static void render2d(GuiGraphics g, DeltaTracker delta) {
        frames++;
        if (dev.vantage.core.Terms.off()) return; // the terms gate: no HUD, no toasts
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        dev.vantage.core.Hud.reset();
        var once = testOnce;
        if (once != null) { testOnce = null; once.accept(g); }
        var each = testEach;
        if (each != null) each.accept(g);
        // client HUD first, then render, then finder panels, so the corners stack in a stable order
        RenderHudEvent.fire(g, delta); // the category order is the listeners' priority (Module.hudPriority)
        // Callouts and Toasts draw after the HUD pass, so the channel's own unwind does not cover them: close whatever a failing one left open
        // (a callout that throws on every frame would otherwise leave the scale on and the menu tiny for the whole session).
        int mark = dev.vantage.core.Ui.depth();
        long tc = System.nanoTime();
        try {
            dev.vantage.core.Callouts.flush(g);
        } catch (Throwable t) {
            lateFault("Callouts", t);
        } finally {
            dev.vantage.core.Ui.unwind(g, mark);
        }
        dev.vantage.core.Perf.add("Callouts", System.nanoTime() - tc);
        long t0 = System.nanoTime();
        try {
            dev.vantage.core.Toasts.render(g);
        } catch (Throwable t) {
            lateFault("Toasts", t);
        } finally {
            dev.vantage.core.Ui.unwind(g, mark);
        }
        dev.vantage.core.Perf.add("Toasts", System.nanoTime() - t0);
        dev.vantage.core.Perf.frame();
    }

    // ---- called from core/CoreTaps (packet events), on the client thread --------------------------

    private static boolean trackChunks() {
        return dev.vantage.core.Modules.get(dev.vantage.module.finder.NewChunks.class).on()
                || dev.vantage.core.Modules.get(dev.vantage.module.finder.TrailFinder.class).on();
    }

    public static void blockUpdate(BlockPos pos, BlockState state) {
        if (dev.vantage.core.Terms.off()) return;
        dev.vantage.core.HiddenStorage.onBlock(pos, state);
        if (trackChunks()) dev.vantage.core.ChunkTracker.onBlock(pos, state);
        BlockUpdateEvent.fire(pos, state);
    }

    /** A section packet above this many blocks is a burst (Donut filling or un-filling a slab sends whole sections, 50k blocks a frame). */
    public static final int BURST = 64;
    private static final java.util.Map<Long, Long> burstChunkSeen = new java.util.HashMap<>();

    /**
     * One section packet. Small ones go block by block as before. Big ones cost 50k hook calls a frame the per-block way (20+ ms in
     * The owner's stutter log), so they get one onSectionBurst per module, the cheap HiddenStorage pass, and one onChunkData per chunk per tick.
     */
    public static void sectionUpdate(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket p) {
        if (dev.vantage.core.Terms.off()) return;
        int[] n = {0};
        BlockPos[] first = {null};
        BlockState[] firstState = {null};
        p.runUpdates((pos, st) -> { if (n[0]++ == 0) { first[0] = pos.immutable(); firstState[0] = st; } });
        if (n[0] <= BURST) { p.runUpdates((pos, st) -> blockUpdate(pos.immutable(), st)); return; }
        long t0 = System.nanoTime();
        dev.vantage.core.HiddenStorage.onBurst(p);
        if (trackChunks()) p.runUpdates(dev.vantage.core.ChunkTracker::onBlock);
        net.minecraft.core.SectionPos sp = net.minecraft.core.SectionPos.of(first[0]);
        SectionBurstEvent.fire(sp, n[0], first[0], firstState[0]);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            long key = net.minecraft.world.level.ChunkPos.asLong(sp.x(), sp.z()), now = mc.level.getGameTime();
            Long last = burstChunkSeen.put(key, now);
            if (burstChunkSeen.size() > 4096) burstChunkSeen.clear();
            if ((last == null || last != now) && mc.level.getChunkSource().getChunk(sp.x(), sp.z(), ChunkStatus.FULL, false) instanceof LevelChunk c)
                ChunkDataEvent.fire(c);
        }
        dev.vantage.core.Perf.add("block burst", System.nanoTime() - t0);
    }

    public static void chunkData(LevelChunk chunk) {
        if (dev.vantage.core.Terms.off()) return;
        if (trackChunks()) dev.vantage.core.ChunkTracker.onChunkData(chunk);
        ChunkDataEvent.fire(chunk);
    }
}
