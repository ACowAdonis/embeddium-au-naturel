package me.jellysquid.mods.sodium.client.world.cloned;

import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class ClonedChunkSectionCache {
    private static final int MAX_CACHE_SIZE = 512; /* number of entries */
    private static final long MAX_CACHE_DURATION = TimeUnit.SECONDS.toNanos(5); /* number of nanoseconds */

    private final Level world;

    private final Long2ReferenceLinkedOpenHashMap<ClonedChunkSection> positionToEntry = new Long2ReferenceLinkedOpenHashMap<>();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    private volatile long time; // updated once per frame to be the elapsed time since application start

    public ClonedChunkSectionCache(Level world) {
        this.world = world;
        this.time = getMonotonicTimeSource();
    }

    public void cleanup() {
        this.writeLock.lock();
        try {
            this.time = getMonotonicTimeSource();
            this.positionToEntry.values()
                    .removeIf(entry -> this.time > (entry.getLastUsedTimestamp() + MAX_CACHE_DURATION));
        } finally {
            this.writeLock.unlock();
        }
    }

    @Nullable
    public ClonedChunkSection acquire(int x, int y, int z) {
        var pos = SectionPos.asLong(x, y, z);

        // Fast path: try read lock first for cache hit
        // Note: we use get() instead of getAndMoveToLast() since get() is non-mutating
        // LRU behavior is preserved via timestamp-based cleanup rather than access order
        this.readLock.lock();
        try {
            var section = this.positionToEntry.get(pos);
            if (section != null) {
                section.setLastUsedTimestamp(this.time);
                return section;
            }
        } finally {
            this.readLock.unlock();
        }

        // Slow path: need to clone and add to cache
        this.writeLock.lock();
        try {
            // Double-check after acquiring write lock
            var section = this.positionToEntry.get(pos);
            if (section != null) {
                section.setLastUsedTimestamp(this.time);
                return section;
            }

            section = this.clone(x, y, z);

            while (this.positionToEntry.size() >= MAX_CACHE_SIZE) {
                this.positionToEntry.removeFirst();
            }

            this.positionToEntry.putAndMoveToLast(pos, section);
            section.setLastUsedTimestamp(this.time);

            return section;
        } finally {
            this.writeLock.unlock();
        }
    }

    @NotNull
    private ClonedChunkSection clone(int x, int y, int z) {
        LevelChunk chunk = this.world.getChunk(x, z);

        if (chunk == null) {
            throw new RuntimeException("Chunk is not loaded at: " + SectionPos.asLong(x, y, z));
        }

        @Nullable LevelChunkSection section = null;

        if (!this.world.isOutsideBuildHeight(SectionPos.sectionToBlockCoord(y))) {
            LevelChunkSection[] sections = chunk.getSections();
            int sectionIndex = this.world.getSectionIndexFromSectionY(y);
            // Defensive bounds check to prevent ArrayIndexOutOfBoundsException
            if (sectionIndex >= 0 && sectionIndex < sections.length) {
                section = sections[sectionIndex];
            }
        }

        return new ClonedChunkSection(this.world, chunk, section, SectionPos.of(x, y, z));
    }

    public void invalidate(int x, int y, int z) {
        this.writeLock.lock();
        try {
            this.positionToEntry.remove(SectionPos.asLong(x, y, z));
        } finally {
            this.writeLock.unlock();
        }
    }

    private static long getMonotonicTimeSource() {
        // Should be monotonic in JDK 17 on sane platforms...
        return System.nanoTime();
    }
}
