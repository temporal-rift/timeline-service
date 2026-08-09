package io.github.temporalrift.timeline.application.saga;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.ParadoxCascaded;
import io.github.temporalrift.timeline.domain.event.ParadoxResolutionPhaseStarted;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionRulesPort;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

/**
 * Business logic for {@code sagas.md} Saga 5 (only the force-cascade branch this slice — design.md Non-Goals).
 * Holds no direct dependency on timer scheduling: {@link ParadoxResolutionPhaseOpener} and
 * {@link ParadoxResolutionTimeoutProcessor} compose this class with {@link ParadoxResolutionTimerScheduler}, which
 * avoids a circular dependency (scheduler -> timeout processor -> this class).
 */
@Component
class ParadoxResolutionSagaImpl {

    private static final Logger log = LoggerFactory.getLogger(ParadoxResolutionSagaImpl.class);

    private static final String ERA_AGGREGATE_TYPE = "Era";
    private static final String FUTURE_EVENT_AGGREGATE_TYPE = "FutureEvent";

    private final ParadoxResolutionPhaseStateManager stateManager;
    private final FutureEventRepository futureEvents;
    private final FutureEventEraIndexPort eraIndex;
    private final TimelineEventPublisher publisher;
    private final ParadoxResolutionRulesPort rules;
    private final Clock clock;

    ParadoxResolutionSagaImpl(
            ParadoxResolutionPhaseStateManager stateManager,
            FutureEventRepository futureEvents,
            FutureEventEraIndexPort eraIndex,
            TimelineEventPublisher publisher,
            ParadoxResolutionRulesPort rules,
            Clock clock) {
        this.stateManager = stateManager;
        this.futureEvents = futureEvents;
        this.eraIndex = eraIndex;
        this.publisher = publisher;
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * Creates the phase if none exists yet for this era (atomic — {@link ParadoxResolutionPhaseStateManager}); the
     * returned {@link OpenResult#phase()} is always authoritative for the era, whether this call created it or a
     * prior one did. {@code created()} is false when a redelivered/duplicate resolution attempt raced (or simply
     * followed) an already-open phase — the caller must not (re)announce {@code ParadoxResolutionPhaseStarted} or
     * (re)schedule a timer in that case, but still needs the authoritative {@code pendingParadoxes} (with their
     * real, already-published {@code paradoxId}s) to avoid announcing fresh ids nobody will ever cascade.
     */
    OpenResult openPhase(
            UUID gameId,
            int eraNumber,
            List<PendingParadox> pendingParadoxes,
            List<TerminalResolution> resolvedTerminalResolutions) {
        var timerSeconds = rules.paradoxResolutionTimerSeconds();
        var timerExpiresAt = clock.instant().plusSeconds(timerSeconds);
        var candidate = new ParadoxResolutionPhase(
                UUID.randomUUID(),
                gameId,
                eraNumber,
                ParadoxResolutionPhaseStatus.WAITING,
                pendingParadoxes,
                resolvedTerminalResolutions,
                timerExpiresAt);
        var result = stateManager.createIfAbsent(candidate);
        if (result.created()) {
            publisher.publish(TimelineEventEnvelope.create(
                    gameId,
                    ERA_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ParadoxResolutionPhaseStarted(
                            gameId,
                            eraNumber,
                            result.phase().pendingParadoxes().stream()
                                    .map(PendingParadox::paradoxId)
                                    .toList(),
                            timerSeconds),
                    clock));
        }
        return new OpenResult(result.phase(), result.created());
    }

    void handleTimerExpiry(UUID sagaId) {
        stateManager
                .findBySagaIdWithLock(sagaId)
                .ifPresentOrElse(
                        this::forceCascade,
                        () -> log.debug("handleTimerExpiry: phase {} not found (stale or duplicate fire)", sagaId));
    }

    private void forceCascade(ParadoxResolutionPhase phase) {
        if (phase.status() == ParadoxResolutionPhaseStatus.COMPLETED) {
            log.debug("handleTimerExpiry: phase {} already COMPLETED", phase.sagaId());
            return;
        }

        var cascadedTerminalResolutions = new ArrayList<TerminalResolution>();
        for (var pending : phase.pendingParadoxes()) {
            var futureEvent = futureEvents.findById(pending.affectedEventId());
            eraIndex.add(pending.affectedEventId(), phase.gameId(), phase.eraNumber() + 1, pending.revealIndex());
            publisher.publish(TimelineEventEnvelope.create(
                    pending.affectedEventId(),
                    FUTURE_EVENT_AGGREGATE_TYPE,
                    phase.gameId(),
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ParadoxCascaded(
                            phase.gameId(),
                            phase.eraNumber(),
                            pending.paradoxId(),
                            pending.affectedEventId(),
                            futureEvent.outcomes()),
                    clock));
            cascadedTerminalResolutions.add(new TerminalResolution(
                    pending.affectedEventId(), pending.revealIndex(), TerminalResolution.TerminalState.CASCADED, null));
        }

        stateManager.complete(phase);

        var terminalResolutions = new ArrayList<>(phase.resolvedTerminalResolutions());
        terminalResolutions.addAll(cascadedTerminalResolutions);
        terminalResolutions.sort(Comparator.comparingInt(TerminalResolution::revealIndex));
        publisher.publish(TimelineEventEnvelope.create(
                phase.gameId(),
                ERA_AGGREGATE_TYPE,
                phase.gameId(),
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new EraResolutionCompleted(phase.gameId(), phase.eraNumber(), terminalResolutions),
                clock));
    }

    record OpenResult(ParadoxResolutionPhase phase, boolean created) {}
}
