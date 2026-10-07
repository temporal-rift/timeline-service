package io.github.temporalrift.timeline.infrastructure.config;

import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler for in-memory one-shot timers. With logical time the wall clock must not fire a deadline, so the
 * one-shot timers stay unarmed and the database-driven sweep, which reads the logical clock, owns every expiry.
 */
class TimerTaskScheduler extends ThreadPoolTaskScheduler {

    private final boolean logicalTime;

    TimerTaskScheduler(boolean logicalTime) {
        this.logicalTime = logicalTime;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return logicalTime ? new UnarmedTimer() : super.schedule(task, startTime);
    }

    private static final class UnarmedTimer implements ScheduledFuture<Object> {

        private volatile boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return Long.MAX_VALUE;
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return isCancelled();
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException("An unarmed timer never completes");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("An unarmed timer never completes");
        }
    }
}
