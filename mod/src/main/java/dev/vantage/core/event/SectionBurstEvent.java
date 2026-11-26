package dev.vantage.core.event;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One section packet changed more than VantageClient.BURST blocks: fired once instead of one {@link BlockUpdateEvent} per block, with the first block as a sample.
 * One object is reused for every firing (it would otherwise be allocated every burst); a listener must not keep it.
 */
public final class SectionBurstEvent {
    public static final SectionBurstEvent INSTANCE = new SectionBurstEvent();
    public static final Events.Channel<SectionBurstEvent> CHANNEL = Events.channel(SectionBurstEvent.class);
    public SectionPos section;
    public int count;
    public BlockPos first;
    public BlockState firstState;

    private SectionBurstEvent() {}

    public static void fire(SectionPos section, int count, BlockPos first, BlockState firstState) {
        INSTANCE.section = section;
        INSTANCE.count = count;
        INSTANCE.first = first;
        INSTANCE.firstState = firstState;
        CHANNEL.fire(INSTANCE);
    }
}
