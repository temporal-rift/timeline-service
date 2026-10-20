package io.github.temporalrift.timeline.domain.port.out;

import java.util.Map;
import java.util.Optional;

/** The rules of the game an isolated deployment currently executes. */
public interface ExecutionRules {

    /** The open context's rules, or empty when no context is open. */
    Optional<Rules> current();

    /**
     * @param digest identity of the rules document
     * @param properties every rule as a flat property key to its text value; array values are indexed
     */
    record Rules(String digest, Map<String, String> properties) {

        public Rules {
            properties = Map.copyOf(properties);
        }
    }
}
