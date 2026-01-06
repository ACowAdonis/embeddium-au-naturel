package me.jellysquid.mods.sodium.client.gl.arena.staging;

import me.jellysquid.mods.sodium.client.gl.buffer.GlBuffer;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;

import java.nio.ByteBuffer;

public interface StagingBuffer {
    void enqueueCopy(CommandList commandList, ByteBuffer data, GlBuffer dst, long writeOffset);

    void flush(CommandList commandList);

    void delete(CommandList commandList);

    void flip();

    /**
     * Returns the maximum number of bytes that should be uploaded per frame.
     * This is used to prevent large uploads from causing frame stalls.
     * @param frameDuration The duration of the previous frame in nanoseconds
     * @return The maximum upload size in bytes
     */
    long getUploadSizeLimit(long frameDuration);
}
