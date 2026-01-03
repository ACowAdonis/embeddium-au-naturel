package me.jellysquid.mods.sodium.client.model.light.data;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import java.util.Arrays;

/**
 * A light data cache which uses a flat-array to store the light data for the blocks in a given chunk and its direct
 * neighbors. This is considerably faster than using a hash table to lookup values for a given block position and
 * can be re-used by {@link WorldSlice} to avoid allocations.
 */
public class ArrayLightDataCache extends LightDataAccess {
    private static final int NEIGHBOR_BLOCK_RADIUS = 2;
    private static final int BLOCK_LENGTH = 16 + (NEIGHBOR_BLOCK_RADIUS * 2);

    /**
     * Sentinel value indicating an uncomputed cache entry.
     * This value is impossible for compute() to return because the AO field (bits 12-27)
     * would need to be 0xFFFF, representing ao > 15.0f, but ao is always in [0.0, 1.0].
     */
    private static final int UNCOMPUTED_SENTINEL = 0xFFFFFFFF;

    private final int[] light;

    private int xOffset, yOffset, zOffset;

    public ArrayLightDataCache(BlockAndTintGetter world) {
        this.world = world;
        this.light = new int[BLOCK_LENGTH * BLOCK_LENGTH * BLOCK_LENGTH];
    }

    public void reset(SectionPos origin) {
        this.xOffset = origin.minBlockX() - NEIGHBOR_BLOCK_RADIUS;
        this.yOffset = origin.minBlockY() - NEIGHBOR_BLOCK_RADIUS;
        this.zOffset = origin.minBlockZ() - NEIGHBOR_BLOCK_RADIUS;

        Arrays.fill(this.light, UNCOMPUTED_SENTINEL);
    }

    private int index(int x, int y, int z) {
        int x2 = x - this.xOffset;
        int y2 = y - this.yOffset;
        int z2 = z - this.zOffset;

        return (z2 * BLOCK_LENGTH * BLOCK_LENGTH) + (y2 * BLOCK_LENGTH) + x2;
    }

    @Override
    public int get(int x, int y, int z) {
        int l = this.index(x, y, z);

        int word = this.light[l];

        if (word != UNCOMPUTED_SENTINEL) {
            return word;
        }

        return this.light[l] = this.compute(x, y, z);
    }
}