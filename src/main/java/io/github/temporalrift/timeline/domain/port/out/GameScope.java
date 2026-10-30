package io.github.temporalrift.timeline.domain.port.out;

import java.util.UUID;

/** Which inbound game records this deployment acts on. */
public interface GameScope {

    boolean admits(InboundRecord inbound);

    /**
     * An inbound record's game and its position in its source; a position element is {@code null} when the transport
     * does not report it.
     */
    record InboundRecord(String topic, Integer partition, Long offset, UUID gameId) {

        public boolean hasPosition() {
            return topic != null && partition != null && offset != null;
        }
    }
}
