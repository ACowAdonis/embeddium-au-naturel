package me.jellysquid.mods.sodium.client.gl.arena;

import me.jellysquid.mods.sodium.client.util.UInt32;

public class GlBufferSegment {
    private final GlBufferArena arena;

    private boolean free = false;

    private int offset; /* UInt32 */
    private int length; /* UInt32 */

    private GlBufferSegment next;
    private GlBufferSegment prev;

    public GlBufferSegment(GlBufferArena arena, long offset, long length) {
        this.arena = arena;
        this.offset = UInt32.downcast(offset);
        this.length = UInt32.downcast(length);
    }

    public void delete() {
        this.arena.free(this);
    }

    /* UInt32 */
    protected long getEnd() {
        return this.getOffset() + this.getLength();
    }

    /* UInt32 */
    public long getLength() {
        return UInt32.upcast(this.length);
    }

    protected void setLength(long len /* UInt32 */) {
        this.length = UInt32.downcast(len);
    }

    /* UInt32 */
    public long getOffset() {
        return UInt32.upcast(this.offset);
    }

    protected void setOffset(long offset /* UInt32 */) {
        this.offset = UInt32.downcast(offset);
    }

    protected void setFree(boolean free) {
        this.free = free;
    }

    protected boolean isFree() {
        return this.free;
    }

    protected void setNext(GlBufferSegment next) {
        this.next = next;
    }

    protected GlBufferSegment getNext() {
        return this.next;
    }

    protected GlBufferSegment getPrev() {
        return this.prev;
    }

    protected void setPrev(GlBufferSegment prev) {
        this.prev = prev;
    }

    protected void mergeInto(GlBufferSegment entry) {
        this.setLength(this.getLength() + entry.getLength());
        this.setNext(entry.getNext());

        if (this.getNext() != null) {
            this.getNext()
                    .setPrev(this);
        }
    }
}
