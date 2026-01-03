package me.jellysquid.mods.sodium.client.render.chunk.compile;

import com.google.common.primitives.Floats;
import it.unimi.dsi.fastutil.ints.IntArrays;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.BitSet;

public class ChunkBufferSorter {
    private static final int ELEMENTS_PER_PRIMITIVE = 6;
    private static final int VERTICES_PER_PRIMITIVE = 4;

    private static final int FAKE_STATIC_CAMERA_OFFSET = 1000;

    public static int getIndexBufferSize(int numPrimitives) {
        // Use Math.multiplyExact to detect integer overflow
        // numPrimitives * 6 * 4 can overflow for large primitive counts
        try {
            return Math.multiplyExact(Math.multiplyExact(numPrimitives, ELEMENTS_PER_PRIMITIVE), 4);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Index buffer size calculation overflow for " + numPrimitives + " primitives", e);
        }
    }

    public static NativeBuffer generateSimpleIndexBuffer(NativeBuffer indexBuffer, int numPrimitives, int offset) {
        // Use long arithmetic to prevent overflow, then check bounds
        long minimumRequiredBufferSize = (long) getIndexBufferSize(numPrimitives) + ((long) offset * 4L);
        if (minimumRequiredBufferSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Required buffer size exceeds maximum: " + minimumRequiredBufferSize);
        }
        if(indexBuffer.getLength() < minimumRequiredBufferSize) {
            throw new IllegalStateException("Given index buffer has length " + indexBuffer.getLength() + " but we need " + minimumRequiredBufferSize);
        }
        long ptr = MemoryUtil.memAddress(indexBuffer.getDirectBuffer()) + (offset * 4L);

        for (int primitiveIndex = 0; primitiveIndex < numPrimitives; primitiveIndex++) {
            int indexOffset = primitiveIndex * ELEMENTS_PER_PRIMITIVE;
            int vertexOffset = primitiveIndex * VERTICES_PER_PRIMITIVE;

            MemoryUtil.memPutInt(ptr + (indexOffset + 0) * 4, vertexOffset + 0);
            MemoryUtil.memPutInt(ptr + (indexOffset + 1) * 4, vertexOffset + 1);
            MemoryUtil.memPutInt(ptr + (indexOffset + 2) * 4, vertexOffset + 2);

            MemoryUtil.memPutInt(ptr + (indexOffset + 3) * 4, vertexOffset + 2);
            MemoryUtil.memPutInt(ptr + (indexOffset + 4) * 4, vertexOffset + 3);
            MemoryUtil.memPutInt(ptr + (indexOffset + 5) * 4, vertexOffset + 0);
        }

        return indexBuffer;
    }

    private static NativeBuffer generateIndexBuffer(NativeBuffer indexBuffer, int[] primitiveMapping) {
        int bufferSize = getIndexBufferSize(primitiveMapping.length);
        if(indexBuffer.getLength() != bufferSize) {
            throw new IllegalStateException("Given index buffer has length " + indexBuffer.getLength() + " but we expected " + bufferSize);
        }
        long ptr = MemoryUtil.memAddress(indexBuffer.getDirectBuffer());

        for (int primitiveIndex = 0; primitiveIndex < primitiveMapping.length; primitiveIndex++) {
            int indexOffset = primitiveIndex * ELEMENTS_PER_PRIMITIVE;

            // Map to the desired primitive
            int vertexOffset = primitiveMapping[primitiveIndex] * VERTICES_PER_PRIMITIVE;

            MemoryUtil.memPutInt(ptr + (indexOffset + 0) * 4, vertexOffset + 0);
            MemoryUtil.memPutInt(ptr + (indexOffset + 1) * 4, vertexOffset + 1);
            MemoryUtil.memPutInt(ptr + (indexOffset + 2) * 4, vertexOffset + 2);

            MemoryUtil.memPutInt(ptr + (indexOffset + 3) * 4, vertexOffset + 2);
            MemoryUtil.memPutInt(ptr + (indexOffset + 4) * 4, vertexOffset + 3);
            MemoryUtil.memPutInt(ptr + (indexOffset + 5) * 4, vertexOffset + 0);
        }

        return indexBuffer;
    }

    private static void buildStaticDistanceArray(float[] centers, float[] distanceArray, float x, float y, float z,
                                                 float normX, float normY, float normZ, int quadCount, boolean[] normalSigns) {
        for (int quadIdx = 0; quadIdx < quadCount; ++quadIdx) {
            int centerIdx = quadIdx * 3;

            // Compute distance using projection of vector from camera->quad center onto shared normal, flipped by sign
            // to accommodate backwards-facing quads in the same plane extensions

            float qX = centers[centerIdx + 0] - x;
            float qY = centers[centerIdx + 1] - y;
            float qZ = centers[centerIdx + 2] - z;

            distanceArray[quadIdx] = (normX * qX + normY * qY + normZ * qZ) * (normalSigns[quadIdx] ? 1 : -1);
        }
    }

    /**
     * Convert BitSet to boolean[] for faster access in hot loops.
     * boolean[] has less overhead than BitSet.get() which requires word index and bit mask calculations.
     */
    private static boolean[] bitSetToBooleanArray(BitSet bitSet, int length) {
        boolean[] result = new boolean[length];
        for (int i = 0; i < length; i++) {
            result[i] = bitSet.get(i);
        }
        return result;
    }

    private static void buildDynamicDistanceArray(float[] centers, float[] distanceArray, int quadCount, float x,
                                                  float y, float z) {
        // Sort using distance to camera directly
        for (int quadIdx = 0; quadIdx < quadCount; ++quadIdx) {
            int centerIdx = quadIdx * 3;

            float qX = centers[centerIdx + 0] - x;
            float qY = centers[centerIdx + 1] - y;
            float qZ = centers[centerIdx + 2] - z;
            distanceArray[quadIdx] = qX * qX + qY * qY + qZ * qZ;
        }
    }

    public static NativeBuffer sort(NativeBuffer indexBuffer, @Nullable TranslucentQuadAnalyzer.SortState chunkData, float x, float y, float z) {
        if (chunkData == null || chunkData.level() == TranslucentQuadAnalyzer.Level.NONE || chunkData.centers().length < 3) {
            return indexBuffer;
        }

        float[] centers = chunkData.centers();
        int quadCount = centers.length / 3;
        int[] indicesArray = new int[quadCount];
        float[] distanceArray = new float[quadCount];
        boolean isStatic = chunkData.level() == TranslucentQuadAnalyzer.Level.STATIC;
        for (int quadIdx = 0; quadIdx < quadCount; ++quadIdx) {
            indicesArray[quadIdx] = quadIdx;
        }

        if (isStatic) {
            // Convert BitSet to boolean[] for faster access in the hot loop
            boolean[] normalSignsArray = bitSetToBooleanArray(chunkData.normalSigns(), quadCount);
            buildStaticDistanceArray(centers, distanceArray,
                    centers[0] + chunkData.sharedNormal().x * FAKE_STATIC_CAMERA_OFFSET,
                    centers[1] + chunkData.sharedNormal().y * FAKE_STATIC_CAMERA_OFFSET,
                    centers[2] + chunkData.sharedNormal().z * FAKE_STATIC_CAMERA_OFFSET,
                    chunkData.sharedNormal().x,
                    chunkData.sharedNormal().y,
                    chunkData.sharedNormal().z,
                    quadCount,
                    normalSignsArray);
        } else {
            buildDynamicDistanceArray(centers, distanceArray, quadCount, x, y, z);
        }

        IntArrays.mergeSort(indicesArray, (a, b) -> Floats.compare(distanceArray[b], distanceArray[a]));

        return generateIndexBuffer(indexBuffer, indicesArray);
    }
}
