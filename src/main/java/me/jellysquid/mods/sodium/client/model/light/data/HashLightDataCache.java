package me.jellysquid.mods.sodium.client.model.light.data;

import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

/**
 * A light data cache which uses a hash table to store previously accessed values.
 */
public class HashLightDataCache extends LightDataAccess {
    /**
     * Sentinel value indicating an uncomputed/missing cache entry.
     * This value is impossible for compute() to return because the AO field (bits 12-27)
     * would need to be 0xFFFF, representing ao > 15.0f, but ao is always in [0.0, 1.0].
     */
    private static final int UNCOMPUTED_SENTINEL = 0xFFFFFFFF;

    private final Long2IntLinkedOpenHashMap map;

    public HashLightDataCache(BlockAndTintGetter world) {
        this.world = world;
        this.map = new Long2IntLinkedOpenHashMap(1024, 0.50f);
        this.map.defaultReturnValue(UNCOMPUTED_SENTINEL);
    }

    @Override
    public int get(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        int word = this.map.getAndMoveToFirst(key);

        if (word == UNCOMPUTED_SENTINEL) {
            if (this.map.size() > 1024) {
                this.map.removeLastInt();
            }

            this.map.put(key, word = this.compute(x, y, z));
        }

        return word;
    }

    public void clearCache() {
        this.map.clear();
    }
}