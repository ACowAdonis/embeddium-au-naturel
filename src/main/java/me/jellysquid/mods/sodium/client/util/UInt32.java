package me.jellysquid.mods.sodium.client.util;

/**
 * Utility class for treating 32-bit integers as unsigned values.
 * This allows addressing up to 4GB of buffer space instead of 2GB with signed integers.
 */
public class UInt32 {
    /**
     * Converts an unsigned 32-bit value stored in an int to a long.
     */
    public static long upcast(int x) {
        return Integer.toUnsignedLong(x);
    }

    /**
     * Converts a long value to an unsigned 32-bit value stored in an int.
     * Throws if the value is out of range.
     */
    public static int downcast(long x) {
        if (x < 0) {
            throw new IllegalArgumentException("x < 0");
        } else if (x >= (1L << 32)) {
            throw new IllegalArgumentException("x >= (1 << 32)");
        }

        return (int) x;
    }

    /**
     * Converts a long value to an unsigned 32-bit value stored in an int.
     * Does not check bounds - use only when overflow is acceptable or impossible.
     */
    public static int uncheckedDowncast(long x) {
        return (int) x;
    }
}
