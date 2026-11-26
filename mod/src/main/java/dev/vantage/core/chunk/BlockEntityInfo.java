package dev.vantage.core.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** What finders read of a block entity: where it is, what kind it is and the block state there. Immutable, safe to hand to any thread. */
public record BlockEntityInfo(BlockPos pos, BlockEntityType<?> type, BlockState state) {}
