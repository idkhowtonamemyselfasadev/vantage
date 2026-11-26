package dev.vantage.core.event;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server changed one block (a single update, or one block of a small section update).
 * One object is reused for every firing (it would otherwise be allocated every block); a listener must not keep it.
 */
public final class BlockUpdateEvent {
    public static final BlockUpdateEvent INSTANCE = new BlockUpdateEvent();
    public static final Events.Channel<BlockUpdateEvent> CHANNEL = Events.channel(BlockUpdateEvent.class);
    public BlockPos pos;
    public BlockState state;

    private BlockUpdateEvent() {}

    public static void fire(BlockPos pos, BlockState state) {
        INSTANCE.pos = pos;
        INSTANCE.state = state;
        CHANNEL.fire(INSTANCE);
    }
}
