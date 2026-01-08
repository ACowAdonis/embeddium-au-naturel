package me.jellysquid.mods.sodium.client.gl.arena;

import me.jellysquid.mods.sodium.client.gl.arena.staging.StagingBuffer;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBuffer;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBufferUsage;
import me.jellysquid.mods.sodium.client.gl.buffer.GlMutableBuffer;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

public class GlBufferArena {
    static final boolean CHECK_ASSERTIONS = false;

    private static final GlBufferUsage BUFFER_USAGE = GlBufferUsage.STATIC_DRAW;

    /**
     * How much bigger than requested a buffer can be to be considered for reuse.
     */
    private static final float MAX_BUFFER_REUSE_SIZE_FACTOR = 1.4f;

    /**
     * How many segments we require to be present before we calculate an average size.
     */
    private static final int MIN_SEGMENTS_FOR_AVG = 16;

    /**
     * Growth factor to use when we have too few segments present.
     */
    private static final float FEW_SEGMENTS_GROWTH_FACTOR = 1.5f;

    /**
     * Factor to use when we are allocating with an expected size (based on average segment size).
     */
    private static final float EXPECTED_SIZE_TARGET_FACTOR = 1.5f;

    private final StagingBuffer stagingBuffer;
    private GlMutableBuffer arenaBuffer;

    private GlBufferSegment head;

    private long capacity;
    private long used;
    private int segmentCount;

    private final int stride;

    // Buffer pool for reusing GL buffers instead of constantly allocating/deallocating
    private static final GlMutableBuffer[] freeBuffers = new GlMutableBuffer[8];
    private static int freeBufferCount = 0;

    public GlBufferArena(CommandList commands, int initialCapacity, int stride, StagingBuffer stagingBuffer) {
        this.capacity = initialCapacity;
        this.stride = stride;

        this.head = new GlBufferSegment(this, 0, this.capacity);
        this.head.setFree(true);

        this.arenaBuffer = getBufferOfSizeAtLeast(commands, this.capacity * stride);
        // Capacity may be larger than requested if we reused a buffer
        this.capacity = this.arenaBuffer.getSize() / stride;

        this.stagingBuffer = stagingBuffer;
    }

    /**
     * Gets a buffer of at least the specified size, either from the pool or by creating a new one.
     */
    private static GlMutableBuffer getBufferOfSizeAtLeast(CommandList commandList, long size) {
        GlMutableBuffer buffer = null;

        if (freeBufferCount > 0) {
            // Get any buffer of at least the requested size but at most MAX_BUFFER_REUSE_SIZE_FACTOR larger
            long maxAcceptableSize = (long) (size * MAX_BUFFER_REUSE_SIZE_FACTOR);

            // Iterate buffers to get the smallest acceptable one
            int candidateIndex = -1;
            for (int i = 0; i < freeBuffers.length; i++) {
                GlMutableBuffer freeBuffer = freeBuffers[i];
                if (freeBuffer != null) {
                    long testSize = freeBuffer.getSize();
                    if (testSize >= size && testSize <= maxAcceptableSize &&
                            (buffer == null || testSize < buffer.getSize())) {
                        candidateIndex = i;
                        buffer = freeBuffer;
                    }
                }
            }
            if (buffer != null) {
                freeBuffers[candidateIndex] = null;
                freeBufferCount--;
            }
        }

        if (buffer == null) {
            buffer = commandList.createMutableBuffer();
            commandList.allocateStorage(buffer, size, BUFFER_USAGE);
        }
        return buffer;
    }

    /**
     * Releases a buffer for potential reuse instead of deleting it immediately.
     */
    private static void releaseBufferForReuse(CommandList commandList, GlMutableBuffer buffer) {
        // Find an empty slot if there is one
        if (freeBufferCount < freeBuffers.length) {
            for (int i = 0; i < freeBuffers.length; i++) {
                if (freeBuffers[i] == null) {
                    freeBuffers[i] = buffer;
                    freeBufferCount++;
                    return;
                }
            }
        }

        // Evict randomly if no empty slot available
        int evictIndex = (int) (Math.random() * freeBuffers.length);
        commandList.deleteBuffer(freeBuffers[evictIndex]);
        freeBuffers[evictIndex] = buffer;
    }

    private void resize(CommandList commandList, long newCapacity) {
        if (this.used > newCapacity) {
            throw new UnsupportedOperationException("New capacity must be larger than used size");
        }

        this.checkAssertions();

        long tail = newCapacity - this.used;

        List<GlBufferSegment> usedSegments = this.getUsedSegments();
        List<PendingBufferCopyCommand> pendingCopies = this.buildTransferList(usedSegments, tail);

        this.transferSegments(commandList, pendingCopies, newCapacity);

        this.head = new GlBufferSegment(this, 0, tail);
        this.head.setFree(true);

        if (usedSegments.isEmpty()) {
            this.head.setNext(null);
        } else {
            this.head.setNext(usedSegments.get(0));
            this.head.getNext()
                    .setPrev(this.head);
        }

        this.checkAssertions();
    }

    private List<PendingBufferCopyCommand> buildTransferList(List<GlBufferSegment> usedSegments, long base) {
        List<PendingBufferCopyCommand> pendingCopies = new ArrayList<>();
        PendingBufferCopyCommand currentCopyCommand = null;

        long writeOffset = base;

        for (int i = 0; i < usedSegments.size(); i++) {
            GlBufferSegment s = usedSegments.get(i);

            if (currentCopyCommand == null || currentCopyCommand.getReadOffset() + currentCopyCommand.getLength() != s.getOffset()) {
                if (currentCopyCommand != null) {
                    pendingCopies.add(currentCopyCommand);
                }

                currentCopyCommand = new PendingBufferCopyCommand(s.getOffset(), writeOffset, s.getLength());
            } else {
                currentCopyCommand.setLength(currentCopyCommand.getLength() + s.getLength());
            }

            s.setOffset(writeOffset);

            if (i + 1 < usedSegments.size()) {
                s.setNext(usedSegments.get(i + 1));
            } else {
                s.setNext(null);
            }

            if (i - 1 < 0) {
                s.setPrev(null);
            } else {
                s.setPrev(usedSegments.get(i - 1));
            }

            writeOffset += s.getLength();
        }

        if (currentCopyCommand != null) {
            pendingCopies.add(currentCopyCommand);
        }

        return pendingCopies;
    }

    private void transferSegments(CommandList commandList, Collection<PendingBufferCopyCommand> list, long capacity) {
        long bufferSize = capacity * this.stride;

        if (bufferSize >= (1L << 32)) {
            throw new IllegalArgumentException("Maximum arena buffer size is 4 GiB");
        }

        GlMutableBuffer srcBufferObj = this.arenaBuffer;
        GlMutableBuffer dstBufferObj = getBufferOfSizeAtLeast(commandList, bufferSize);

        for (PendingBufferCopyCommand cmd : list) {
            commandList.copyBufferSubData(srcBufferObj, dstBufferObj,
                    cmd.getReadOffset() * this.stride,
                    cmd.getWriteOffset() * this.stride,
                    cmd.getLength() * this.stride);
        }

        releaseBufferForReuse(commandList, srcBufferObj);

        this.arenaBuffer = dstBufferObj;
        // Capacity may be larger than requested if we reused a buffer
        this.capacity = this.arenaBuffer.getSize() / this.stride;
    }

    private ArrayList<GlBufferSegment> getUsedSegments() {
        ArrayList<GlBufferSegment> used = new ArrayList<>();
        GlBufferSegment seg = this.head;

        while (seg != null) {
            GlBufferSegment next = seg.getNext();

            if (!seg.isFree()) {
                used.add(seg);
            }

            seg = next;
        }

        return used;
    }

    public long getDeviceUsedMemory() {
        return this.used * this.stride;
    }

    public long getDeviceAllocatedMemory() {
        return this.capacity * this.stride;
    }

    private GlBufferSegment alloc(int size) {
        GlBufferSegment a = this.findFree(size);

        if (a == null) {
            return null;
        }

        GlBufferSegment result;

        if (a.getLength() == size) {
            a.setFree(false);

            result = a;
        } else {
            GlBufferSegment b = new GlBufferSegment(this, a.getEnd() - size, size);
            b.setNext(a.getNext());
            b.setPrev(a);

            if (b.getNext() != null) {
                b.getNext()
                        .setPrev(b);
            }

            a.setLength(a.getLength() - size);
            a.setNext(b);

            result = b;
        }

        this.used += result.getLength();
        this.segmentCount++;
        this.checkAssertions();

        return result;
    }

    private GlBufferSegment findFree(int size) {
        GlBufferSegment entry = this.head;
        GlBufferSegment best = null;

        while (entry != null) {
            if (entry.isFree()) {
                if (entry.getLength() == size) {
                    return entry;
                } else if (entry.getLength() >= size) {
                    if (best == null || best.getLength() > entry.getLength()) {
                        best = entry;
                    }
                }
            }

            entry = entry.getNext();
        }

        return best;
    }

    public void free(GlBufferSegment entry) {
        if (entry.isFree()) {
            throw new IllegalStateException("Already freed");
        }

        entry.setFree(true);

        this.used -= entry.getLength();
        this.segmentCount--;

        GlBufferSegment next = entry.getNext();

        if (next != null && next.isFree()) {
            entry.mergeInto(next);
        }

        GlBufferSegment prev = entry.getPrev();

        if (prev != null && prev.isFree()) {
            prev.mergeInto(entry);
        }

        this.checkAssertions();
    }

    public void delete(CommandList commands) {
        commands.deleteBuffer(this.arenaBuffer);
    }

    public boolean isEmpty() {
        return this.used <= 0;
    }

    public GlBuffer getBufferObject() {
        return this.arenaBuffer;
    }

    public boolean upload(CommandList commandList, Stream<PendingUpload> stream) {
        // Record the buffer object before we start any work
        // If the arena needs to re-allocate a buffer, this will allow us to check and return an appropriate flag
        GlBuffer buffer = this.arenaBuffer;

        // A linked list is used as we'll be randomly removing elements and want O(1) performance
        long totalUploadSize = 0;
        List<PendingUpload> queue = new LinkedList<>();
        for (var upload : (Iterable<PendingUpload>) stream::iterator) {
            totalUploadSize += upload.getDataBuffer().getLength();
            queue.add(upload);
        }

        // Try to upload all the data into free segments first,
        // but only attempt this if there is enough free space assuming no fragmentation
        if (totalUploadSize < (this.capacity - this.used) * this.stride) {
            this.tryUploads(commandList, queue);
        }

        // If we weren't able to upload some buffers, they will have been left behind in the queue
        if (!queue.isEmpty()) {
            // Resize to the new estimated capacity using adaptive growth
            this.resize(commandList, estimateNewCapacity(queue));

            // Try again to upload any buffers that failed last time
            this.tryUploads(commandList, queue);

            // If we still had failures, something has gone wrong
            if (!queue.isEmpty()) {
                throw new RuntimeException("Failed to upload all buffers");
            }
        }

        return this.arenaBuffer != buffer;
    }

    private void tryUploads(CommandList commandList, List<PendingUpload> queue) {
        queue.removeIf(upload -> this.tryUpload(commandList, upload));
        this.stagingBuffer.flush(commandList);
    }

    private boolean tryUpload(CommandList commandList, PendingUpload upload) {
        ByteBuffer data = upload.getDataBuffer()
                .getDirectBuffer();

        int elementCount = data.remaining() / this.stride;

        GlBufferSegment dst = this.alloc(elementCount);

        if (dst == null) {
            return false;
        }

        // Copy the data into our staging buffer, then copy it into the arena's buffer
        this.stagingBuffer.enqueueCopy(commandList, data, this.arenaBuffer, dst.getOffset() * this.stride);

        upload.setResult(dst);

        return true;
    }

    /**
     * Estimates the new capacity needed to accommodate the remaining uploads using adaptive growth.
     * Uses average segment size when enough segments are present, otherwise uses a simple growth factor.
     */
    private long estimateNewCapacity(List<PendingUpload> queue) {
        // Calculate the amount of memory needed for the remaining uploads
        long remainingUploadSize = 0;
        for (var upload : queue) {
            remainingUploadSize += upload.getDataBuffer().getLength();
        }

        // Convert size to elements by dividing by the stride
        long remainingElements = remainingUploadSize / this.stride;

        // The required total size after the uploads are allocated
        long requiredTotalSize = remainingElements + this.used;

        int newSegmentCount = this.segmentCount + queue.size();

        // Use average segment size if we have enough segments to make it an accurate value
        long newCapacity;
        if (newSegmentCount >= MIN_SEGMENTS_FOR_AVG) {
            // Find the average segment size after the remaining uploads are allocated
            long averageNewSegmentSize = (requiredTotalSize / newSegmentCount) + 1; // +1 to round up

            // Use the average segment size to determine a new capacity, with some overshoot applied for safety
            newCapacity = (long) (averageNewSegmentSize * newSegmentCount * EXPECTED_SIZE_TARGET_FACTOR);
        } else {
            // Not enough segments for accurate average, use simple growth factor
            newCapacity = (long) (requiredTotalSize * FEW_SEGMENTS_GROWTH_FACTOR);
        }

        return newCapacity;
    }

    private void checkAssertions() {
        if (CHECK_ASSERTIONS) {
            this.checkAssertions0();
        }
    }

    private void checkAssertions0() {
        GlBufferSegment seg = this.head;
        long used = 0;

        while (seg != null) {
            // Note: getOffset() returns unsigned value so it's never negative
            if (seg.getEnd() > this.capacity) {
                throw new IllegalStateException("segment.end > arena.capacity: out of bounds");
            }

            if (!seg.isFree()) {
                used += seg.getLength();
            }

            GlBufferSegment next = seg.getNext();

            if (next != null) {
                if (next.getOffset() < seg.getEnd()) {
                    throw new IllegalStateException("segment.next.start < segment.end: overlapping segments (corrupted)");
                } else if (next.getOffset() > seg.getEnd()) {
                    throw new IllegalStateException("segment.next.start > segment.end: not truly connected (sparsity error)");
                }

                if (next.isFree() && next.getNext() != null) {
                    if (next.getNext().isFree()) {
                        throw new IllegalStateException("segment.free && segment.next.free: not merged consecutive segments");
                    }
                }
            }

            GlBufferSegment prev = seg.getPrev();

            if (prev != null) {
                if (prev.getEnd() > seg.getOffset()) {
                    throw new IllegalStateException("segment.prev.end > segment.start: overlapping segments (corrupted)");
                } else if (prev.getEnd() < seg.getOffset()) {
                    throw new IllegalStateException("segment.prev.end < segment.start: not truly connected (sparsity error)");
                }

                if (prev.isFree() && prev.getPrev() != null) {
                    if (prev.getPrev().isFree()) {
                        throw new IllegalStateException("segment.free && segment.prev.free: not merged consecutive segments");
                    }
                }
            }

            seg = next;
        }

        if (this.used < 0) {
            throw new IllegalStateException("arena.used < 0: failure to track");
        } else if (this.used > this.capacity) {
            throw new IllegalStateException("arena.used > arena.capacity: failure to track");
        }

        if (this.used != used) {
            throw new IllegalStateException("arena.used is invalid");
        }
    }

}
