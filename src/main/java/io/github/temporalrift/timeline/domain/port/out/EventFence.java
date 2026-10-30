package io.github.temporalrift.timeline.domain.port.out;

/**
 * Fences inbound records that were already in their source when the open execution context opened. Such a record
 * belongs to an earlier context or to none, whatever its game, so it must never act on the open one, including a
 * replay of a closed case.
 */
public interface EventFence {

    /** Whether {@code offset} in {@code topic}/{@code partition} predates the last capture. */
    boolean isFenced(String topic, int partition, long offset);

    /** Captures the source's end offsets, so every record already present is fenced. */
    void capture();
}
