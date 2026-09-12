package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.event.ChainBrokenEvent;
import io.github.temporalrift.timeline.domain.event.ChainCompletedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkAddedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidatedEvent;
import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.ParadoxResolved;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.event.ThreadRejectedEvent;

class TimelineEventWireMapperTest {

    private final TimelineEventWireMapper mapper = new TimelineEventWireMapperImpl();

    @Test
    void toWire_eraResolutionCompleted_mapsEveryFieldPerTerminalState() {
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

    @Test
    void toWire_paradoxResolved_mapsEveryField() {
        var gameId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var resolvedByPlayerId = UUID.randomUUID();
        var event = new ParadoxResolved(gameId, 1, paradoxId, resolvedByPlayerId);

        var wire = mapper.toWire(event);

        assertThat(wire.gameId()).isEqualTo(gameId);
        assertThat(wire.eraNumber()).isEqualTo(1);
        assertThat(wire.paradoxId()).isEqualTo(paradoxId);
        assertThat(wire.resolvedByPlayerId()).isEqualTo(resolvedByPlayerId);
    }

    @Test
    void toWire_chainLinkAdded_mapsEveryField() {
        var gameId = UUID.randomUUID();
        var chainId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var linkedEventId = UUID.randomUUID();
        var linkedOutcomeId = UUID.randomUUID();
        var previousLinkEventId = UUID.randomUUID();
        var event = new ChainLinkAddedEvent(
                gameId, chainId, playerId, linkedEventId, linkedOutcomeId, 2, previousLinkEventId);

        var wire = mapper.toWire(event);

        assertThat(wire.gameId()).isEqualTo(gameId);
        assertThat(wire.chainId()).isEqualTo(chainId);
        assertThat(wire.playerId()).isEqualTo(playerId);
        assertThat(wire.linkedEventId()).isEqualTo(linkedEventId);
        assertThat(wire.linkedOutcomeId()).isEqualTo(linkedOutcomeId);
        assertThat(wire.chainLength()).isEqualTo(2);
        assertThat(wire.previousLinkEventId()).isEqualTo(previousLinkEventId);
    }

    @Test
    void toWire_chainCompleted_mapsLinksWithEras() {
        var gameId = UUID.randomUUID();
        var chainId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var firstEvent = UUID.randomUUID();
        var firstOutcome = UUID.randomUUID();
        var event = new ChainCompletedEvent(
                gameId,
                3,
                chainId,
                playerId,
                List.of(new ChainCompletedEvent.ChainLinkEntry(firstEvent, firstOutcome, 1)));

        var wire = mapper.toWire(event);

        assertThat(wire.gameId()).isEqualTo(gameId);
        assertThat(wire.eraNumber()).isEqualTo(3);
        assertThat(wire.chainId()).isEqualTo(chainId);
        assertThat(wire.playerId()).isEqualTo(playerId);
        assertThat(wire.links()).hasSize(1);
        assertThat(wire.links().get(0).eventId()).isEqualTo(firstEvent);
        assertThat(wire.links().get(0).outcomeId()).isEqualTo(firstOutcome);
        assertThat(wire.links().get(0).eraNumber()).isEqualTo(1);
    }

    @Test
    void toWire_chainBroken_mapsEveryField() {
        var gameId = UUID.randomUUID();
        var chainId = UUID.randomUUID();
        var brokenByPlayerId = UUID.randomUUID();
        var targetPlayerId = UUID.randomUUID();
        var event = new ChainBrokenEvent(gameId, 2, chainId, brokenByPlayerId, targetPlayerId, 2);

        var wire = mapper.toWire(event);

        assertThat(wire.gameId()).isEqualTo(gameId);
        assertThat(wire.eraNumber()).isEqualTo(2);
        assertThat(wire.chainId()).isEqualTo(chainId);
        assertThat(wire.brokenByPlayerId()).isEqualTo(brokenByPlayerId);
        assertThat(wire.targetPlayerId()).isEqualTo(targetPlayerId);
        assertThat(wire.chainLengthAtBreak()).isEqualTo(2);
    }

    @Test
    void toWire_chainLinkInvalidated_mapsEveryField() {
        var gameId = UUID.randomUUID();
        var chainId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var invalidatedEventId = UUID.randomUUID();
        var invalidatedOutcomeId = UUID.randomUUID();
        var event = new ChainLinkInvalidatedEvent(
                gameId, 2, chainId, playerId, invalidatedEventId, invalidatedOutcomeId, 1);

        var wire = mapper.toWire(event);

        assertThat(wire.gameId()).isEqualTo(gameId);
        assertThat(wire.eraNumber()).isEqualTo(2);
        assertThat(wire.chainId()).isEqualTo(chainId);
        assertThat(wire.playerId()).isEqualTo(playerId);
        assertThat(wire.invalidatedEventId()).isEqualTo(invalidatedEventId);
        assertThat(wire.invalidatedOutcomeId()).isEqualTo(invalidatedOutcomeId);
        assertThat(wire.chainLength()).isEqualTo(1);
    }

    @Test
    void toWire_threadRejected_mapsEveryField() {
        var gameId = UUID.randomUUID();
        var chainId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var referencedEventId = UUID.randomUUID();
        var referencedOutcomeId = UUID.randomUUID();
        var event = new ThreadRejectedEvent(
                gameId, 2, chainId, playerId, referencedEventId, referencedOutcomeId, "OUTCOME_DID_NOT_RESOLVE");

        var wire = mapper.toWire(event);

        assertThat(wire.gameId()).isEqualTo(gameId);
        assertThat(wire.eraNumber()).isEqualTo(2);
        assertThat(wire.chainId()).isEqualTo(chainId);
        assertThat(wire.playerId()).isEqualTo(playerId);
        assertThat(wire.referencedEventId()).isEqualTo(referencedEventId);
        assertThat(wire.referencedOutcomeId()).isEqualTo(referencedOutcomeId);
        assertThat(wire.reason()).isEqualTo("OUTCOME_DID_NOT_RESOLVE");
    }
}
