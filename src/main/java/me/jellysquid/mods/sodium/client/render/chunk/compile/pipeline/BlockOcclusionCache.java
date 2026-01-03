package me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline;

import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The block occlusion cache is responsible for performing occlusion testing of neighboring block faces.
 */
public class BlockOcclusionCache {
    private static final byte UNCACHED_VALUE = (byte) 127;
    private static final int MAX_CACHE_SIZE = 2048;

    private final Long2ByteLinkedOpenHashMap map;
    private final BlockPos.MutableBlockPos cpos = new BlockPos.MutableBlockPos();

    public BlockOcclusionCache() {
        this.map = new Long2ByteLinkedOpenHashMap(MAX_CACHE_SIZE, 0.5F);
        this.map.defaultReturnValue(UNCACHED_VALUE);
    }

    /**
     * @param selfState The state of the block in the world
     * @param view The world view for this render context
     * @param pos The position of the block
     * @param facing The facing direction of the side to check
     * @return True if the block side facing {@param dir} is not occluded, otherwise false
     */
    public boolean shouldDrawSide(BlockState selfState, BlockGetter view, BlockPos pos, Direction facing) {
        BlockPos.MutableBlockPos adjPos = this.cpos;
        adjPos.set(pos.getX() + facing.getStepX(), pos.getY() + facing.getStepY(), pos.getZ() + facing.getStepZ());

        BlockState adjState = view.getBlockState(adjPos);

        if (selfState.skipRendering(adjState, facing) || (adjState.hidesNeighborFace(view, adjPos, selfState, facing.getOpposite()) && selfState.supportsExternalFaceHiding())) {
            // Explicitly asked to skip rendering this face
            return false;
        } else if (adjState.canOcclude()) {
            VoxelShape selfShape = selfState.getFaceOcclusionShape(view, pos, facing);
            VoxelShape adjShape = adjState.getFaceOcclusionShape(view, adjPos, facing.getOpposite());

            if (selfShape == Shapes.block() && adjShape == Shapes.block()) {
                // If both blocks use full-cube occlusion shapes, then the neighbor certainly occludes us, and we
                // shouldn't render this face
                return false;
            } else if (selfShape.isEmpty()) {
                // If our occlusion shape is empty, then we cannot be occluded by anything, and we should render
                // this face
                return true;
            }

            // Consult the occlusion cache & do the voxel shape calculations if necessary
            return this.calculate(selfShape, adjShape);
        } else {
            // The neighboring block never occludes, we need to render this face
            return true;
        }
    }

    private boolean calculate(VoxelShape selfShape, VoxelShape adjShape) {
        // Pack identity hash codes of both shapes into a single long key
        // This eliminates object allocation on cache miss
        long key = (long) System.identityHashCode(selfShape) << 32 | (System.identityHashCode(adjShape) & 0xFFFFFFFFL);

        byte cached = this.map.getAndMoveToLast(key);

        if (cached != UNCACHED_VALUE) {
            return cached == 1;
        }

        boolean ret = Shapes.joinIsNotEmpty(selfShape, adjShape, BooleanOp.ONLY_FIRST);

        if (this.map.size() >= MAX_CACHE_SIZE) {
            this.map.removeFirstByte();
        }

        this.map.put(key, (byte) (ret ? 1 : 0));

        return ret;
    }
}
