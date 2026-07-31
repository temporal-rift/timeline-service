package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;

class TimelineEventWireMapperTest {

    private final TimelineEventWireMapper mapper = new TimelineEventWireMapperImpl();

    @Test
    void toWire_eraResolutionCompleted_producesExactWireShapePerTerminalState() {
        var gameId = UUID.randomUUID();
        var outcomeAppliedEventId = UUID.randomUUID();
        var winningOutcomeId = UUID.randomUUID();
        var cascadedEventId = UUID.randomUUID();
        var event = new EraResolutionCompleted(
                gameId,
                1,
                List.of(
                        new TerminalResolution(
                                outcomeAppliedEventId,
                                0,
                                TerminalResolution.TerminalState.OUTCOME_APPLIED,
                                winningOutcomeId),
                        new TerminalResolution(cascadedEventId, 1, TerminalResolution.TerminalState.CASCADED, null)));

        var wire = mapper.toWire(event);

        assertThat(wire.getGameId()).isEqualTo(gameId);
        assertThat(wire.getEraNumber()).isEqualTo(1);
        assertThat(wire.getTerminalResolutions()).hasSize(2);

        @SuppressWarnings("unchecked")
        var outcomeAppliedWire =
                (Map<String, Object>) wire.getTerminalResolutions().get(0);
        assertThat(outcomeAppliedWire)
                .containsEntry("eventId", outcomeAppliedEventId)
                .containsEntry("revealIndex", 0)
                .containsEntry("terminalState", "OUTCOME_APPLIED")
                .containsEntry("winningOutcomeId", winningOutcomeId);

        @SuppressWarnings("unchecked")
        var cascadedWire = (Map<String, Object>) wire.getTerminalResolutions().get(1);
        assertThat(cascadedWire)
                .containsEntry("eventId", cascadedEventId)
                .containsEntry("revealIndex", 1)
                .containsEntry("terminalState", "CASCADED")
                .doesNotContainKey("winningOutcomeId");
    }
}
