package me.jellysquid.mods.sodium.client.render.chunk.compile.executor;

import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

class ChunkJobQueue {
    private final PriorityBlockingQueue<ChunkJobPriority> jobs = new PriorityBlockingQueue<>();

    private final Semaphore semaphore = new Semaphore(0);

    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    public boolean isRunning() {
        return this.isRunning.get();
    }

    public void add(ChunkJobPriority jobPriority) {
        Validate.isTrue(this.isRunning(), "Queue is no longer running");

        this.jobs.add(jobPriority);

        this.semaphore.release(1);
    }

    @Nullable
    public ChunkJob waitForNextJob() throws InterruptedException {
        if (!this.isRunning()) {
            return null;
        }

        this.semaphore.acquire();

        return this.getNextTask();
    }

    public boolean stealJob(ChunkJob job) {
        if (!this.semaphore.tryAcquire()) {
            return false;
        }

        // Find and remove the ChunkJobPriority wrapper containing this job
        ChunkJobPriority toRemove = null;
        for (ChunkJobPriority jobPriority : this.jobs) {
            if (jobPriority.getJob() == job) {
                toRemove = jobPriority;
                break;
            }
        }

        boolean success = false;
        if (toRemove != null) {
            success = this.jobs.remove(toRemove);
        }

        if (!success) {
            // If we didn't manage to actually steal the task, then we need to release the permit which we did steal
            this.semaphore.release(1);
        }

        return success;
    }

    @Nullable
    private ChunkJob getNextTask() {
        ChunkJobPriority jobPriority = this.jobs.poll();
        return jobPriority != null ? jobPriority.getJob() : null;
    }


    public Collection<ChunkJob> shutdown() {
        var list = new ArrayDeque<ChunkJob>();

        this.isRunning.set(false);

        while (this.semaphore.tryAcquire()) {
            var jobPriority = this.jobs.poll();

            if (jobPriority != null) {
                list.add(jobPriority.getJob());
            }
        }

        // force the worker threads to wake up and exit
        this.semaphore.release(Runtime.getRuntime().availableProcessors());

        return list;
    }

    public int size() {
        return this.semaphore.availablePermits();
    }

    public boolean isEmpty() {
        return this.size() == 0;
    }
}
