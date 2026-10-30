package io.github.temporalrift.timeline.application.command;

import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/**
 * Serialises the operator's open and close operations on one service instance. An open records its source fence only
 * while no context is open, so a close cannot interleave with it and no repeat of an open context can fence that
 * context's own records. Each simulation lane hosts one service instance.
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
