package io.github.temporalrift.timeline.domain.port.out;

/**
 * Fences inbound records that were already in the source when an execution context closed. Such a record belongs to
 * the closed context even when that context never bound a game, so it must never be admitted to the next one.
 */
public interface EventFence {

    /** Whether {@code offset} in {@code topic}/{@code partition} predates the last close. */
    boolean isFenced(String topic, int partition, long offset);

    /** Captures the source's end offsets, so every record already present is fenced. */
    void record();
}
