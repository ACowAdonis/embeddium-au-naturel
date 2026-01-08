package me.jellysquid.mods.sodium.client.gl.arena;

import me.jellysquid.mods.sodium.client.util.UInt32;

class PendingBufferCopyCommand {
    private final int readOffset; /* UInt32 */
    private final int writeOffset; /* UInt32 */

    private int length; /* UInt32 */

    PendingBufferCopyCommand(long readOffset, long writeOffset, long length) {
        this.readOffset = UInt32.downcast(readOffset);
        this.writeOffset = UInt32.downcast(writeOffset);
        this.length = UInt32.downcast(length);
    }

    /* UInt32 */
    public long getReadOffset() {
        return UInt32.upcast(this.readOffset);
    }

    /* UInt32 */
    public long getWriteOffset() {
        return UInt32.upcast(this.writeOffset);
    }

    /* UInt32 */
    public long getLength() {
        return UInt32.upcast(this.length);
    }

    public void setLength(long length /* UInt32 */) {
        this.length = UInt32.downcast(length);
    }
}
