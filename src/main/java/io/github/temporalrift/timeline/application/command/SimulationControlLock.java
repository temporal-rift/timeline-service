package io.github.temporalrift.timeline.application.command;

import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/**
 * Serialises the operator's open and close operations on one service instance. A close must finish recording its
 * source fence before the next context can open, or a record published by the new game could be mistaken for the
 * closed context's backlog. Each simulation lane hosts one service instance.
 */
@Component
class SimulationControlLock {

    private final ReentrantLock lock = new ReentrantLock();

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }
}
