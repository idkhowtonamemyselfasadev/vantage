package dev.vantage.core;

import dev.vantage.core.event.Events;
import dev.vantage.core.event.PacketHandledEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * What the core services (not modules) read from server packets: the hidden-storage bookkeeping that several finders share, and
 * the leak probe. Read only: nothing is changed, cancelled or sent. These listeners are always on; they used to be lines inside a
 * mixin, next to the calls into the modules, which now listen for themselves.
 */
public final class CoreTaps implements Events.Owner {
    private static final CoreTaps OWNER = new CoreTaps();
    private static final Events.Group GROUP = new Events.Group(OWNER);

    private CoreTaps() {}

    @Override
    public String ownerName() {
        return "core services";
    }

    /** Before every module (modules start at rank 1), as it was inside the mixin for chunks. */
    @Override
    public int ownerRank() {
        return 0;
    }

    /** Called once at start-up. */
    public static void init() {
        if (GROUP.declared() > 0) return;
        // chunk and block-entity data first: the modules' own listeners for the same packet run after and may read the result
        GROUP.listen(PacketHandledEvent.class, 10, e -> {
            if (e.packet instanceof ClientboundLevelChunkWithLightPacket p) {
                HiddenStorage.onChunk(p.getX(), p.getZ(), p.getChunkData());
                Minecraft mc = Minecraft.getInstance();
                if (Modules.get(dev.vantage.module.finder.BaseFinder.class).on() && mc.level != null
                        && mc.level.getChunkSource().getChunk(p.getX(), p.getZ(), ChunkStatus.FULL, false) instanceof LevelChunk c) {
                    LeakProbe.onChunk(p, c);
                }
            } else if (e.packet instanceof ClientboundBlockEntityDataPacket p) {
                HiddenStorage.onBlockEntity(p.getPos(), p.getType());
            } else if (e.packet instanceof ClientboundBlockEventPacket p) {
                HiddenStorage.heard(p.getPos(), HiddenStorage.typeOfBlockEvent(p.getBlock()));
            }
        });
        // block and chunk changes become the block, burst and chunk events the modules hear (VantageClient sorts small changes from bursts)
        GROUP.listen(PacketHandledEvent.class, 5, e -> {
            if (e.packet instanceof ClientboundBlockUpdatePacket p) {
                dev.vantage.VantageClient.blockUpdate(p.getPos(), p.getBlockState());
            } else if (e.packet instanceof ClientboundSectionBlocksUpdatePacket p) {
                dev.vantage.VantageClient.sectionUpdate(p);
            } else if (e.packet instanceof ClientboundLevelChunkWithLightPacket p) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null && mc.level.getChunkSource().getChunk(p.getX(), p.getZ(), ChunkStatus.FULL, false) instanceof LevelChunk c) dev.vantage.VantageClient.chunkData(c);
            }
        });
        // sounds and level events last, after the modules that listen to the same packet (the order the mixin had)
        GROUP.listen(PacketHandledEvent.class, -10, e -> {
            if (e.packet instanceof ClientboundSoundPacket p) {
                HiddenStorage.onSound(p.getX(), p.getY(), p.getZ(), p.getSound().value().location().getPath());
            } else if (e.packet instanceof ClientboundLevelEventPacket p) {
                if (!p.isGlobalEvent()) HiddenStorage.heard(p.getPos(), HiddenStorage.typeOfLevelEvent(p.getType()));
            }
        });
        GROUP.attach();
    }
}
