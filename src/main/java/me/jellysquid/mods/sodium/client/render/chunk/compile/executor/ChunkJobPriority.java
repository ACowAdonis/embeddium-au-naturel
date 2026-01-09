package me.jellysquid.mods.sodium.client.render.chunk.compile.executor;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;

/**
 * Stores priority information for a chunk build task.
 * Used to order tasks in the priority queue based on camera position and direction.
 */
class ChunkJobPriority implements Comparable<ChunkJobPriority> {
    private final ChunkJob job;
    private final ChunkUpdateType updateType;

    private final double distanceSq;
    private final double directionWeight;

    ChunkJobPriority(ChunkJob job, ChunkUpdateType updateType,
                     int chunkX, int chunkY, int chunkZ,
                     double cameraX, double cameraY, double cameraZ,
                     double cameraForwardX, double cameraForwardY, double cameraForwardZ) {
        this.job = job;
        this.updateType = updateType;

        // Calculate chunk center position
        double chunkCenterX = chunkX + 8.0;
        double chunkCenterY = chunkY + 8.0;
        double chunkCenterZ = chunkZ + 8.0;

        // Pre-calculate distance squared from camera to chunk center
        double dx = chunkCenterX - cameraX;
        double dy = chunkCenterY - cameraY;
        double dz = chunkCenterZ - cameraZ;
        this.distanceSq = dx * dx + dy * dy + dz * dz;

        // Calculate direction weight: dot product of normalized direction to chunk with camera forward
        // Higher values = chunk is more in front of camera
        double distance = Math.sqrt(this.distanceSq);
        if (distance > 0.0) {
            double dirX = dx / distance;
            double dirY = dy / distance;
            double dirZ = dz / distance;
            this.directionWeight = dirX * cameraForwardX + dirY * cameraForwardY + dirZ * cameraForwardZ;
        } else {
            this.directionWeight = 1.0; // Camera is inside chunk, highest priority
        }
    }

    public ChunkJob getJob() {
        return this.job;
    }

    /**
     * Returns numerical priority for update type.
     * Lower numbers = higher priority (processed first).
     */
    private int getUpdateTypePriority() {
        return switch (this.updateType) {
            case IMPORTANT_REBUILD -> 0;  // Highest priority
            case IMPORTANT_SORT -> 1;
            case REBUILD -> 2;
            case INITIAL_BUILD -> 3;
            case SORT -> 4;              // Lowest priority
        };
    }

    @Override
    public int compareTo(ChunkJobPriority other) {
        // 1. Update type priority (important rebuilds always first)
        int typeDiff = Integer.compare(this.getUpdateTypePriority(), other.getUpdateTypePriority());
        if (typeDiff != 0) {
            return typeDiff;
        }

        // 2. Direction weight (chunks in front of camera first)
        // Note: Negate comparison because higher weight = higher priority = should come first
        int directionDiff = Double.compare(other.directionWeight, this.directionWeight);
        if (directionDiff != 0) {
            return directionDiff;
        }

        // 3. Distance from camera (closer chunks first)
        return Double.compare(this.distanceSq, other.distanceSq);
    }
}
