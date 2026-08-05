package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

        assertThat(wire.gameId()).isEqualTo(gameId);
        assertThat(wire.eraNumber()).isEqualTo(1);
        assertThat(wire.terminalResolutions()).hasSize(2);

        var outcomeAppliedWire = wire.terminalResolutions().get(0);
        assertThat(outcomeAppliedWire.eventId()).isEqualTo(outcomeAppliedEventId);
        assertThat(outcomeAppliedWire.revealIndex()).isEqualTo(0);
        assertThat(outcomeAppliedWire.terminalState()).isEqualTo("OUTCOME_APPLIED");
        assertThat(outcomeAppliedWire.winningOutcomeId()).isEqualTo(winningOutcomeId);

        var cascadedWire = wire.terminalResolutions().get(1);
        assertThat(cascadedWire.eventId()).isEqualTo(cascadedEventId);
        assertThat(cascadedWire.revealIndex()).isEqualTo(1);
        assertThat(cascadedWire.terminalState()).isEqualTo("CASCADED");
        assertThat(cascadedWire.winningOutcomeId()).isNull();
    }
}
