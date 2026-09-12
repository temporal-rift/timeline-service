package io.github.temporalrift.timeline.application.saga;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.application.port.in.WeaverChainSagaUseCase;
import io.github.temporalrift.timeline.domain.event.ChainBrokenEvent;
import io.github.temporalrift.timeline.domain.event.ChainCompleted;
import io.github.temporalrift.timeline.domain.event.ChainCompletedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkAddedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidatedEvent;
import io.github.temporalrift.timeline.domain.event.ThreadRejectedEvent;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.FutureEventNotFoundException;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainRepository;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;
import io.github.temporalrift.timeline.domain.weaverchain.ChainLink;
import io.github.temporalrift.timeline.domain.weaverchain.InvalidChainLinkException;
import io.github.temporalrift.timeline.domain.weaverchain.ResolvedOutcome;

/**
 * Cross-era saga driving the {@code WeaverChain} aggregate from Weaver special plays. The saga stays open
 * across era boundaries until the third link completes it, an UNRAVEL breaks it, or the game ends. All
 * state is durable: chain links in the aggregate stream, orchestration flags in the saga table — a
 * restart resumes from what is persisted, with no in-memory timers or caches.
 */
@Service
class WeaverChainSaga implements WeaverChainSagaUseCase {

    private static final String AGGREGATE_TYPE = "WeaverChain";

    private static final String REASON_DID_NOT_RESOLVE = "OUTCOME_DID_NOT_RESOLVE";
    private static final String REASON_ALREADY_LINKED = "EVENT_ALREADY_LINKED";

    private final WeaverChainRepository chains;
    private final WeaverChainSagaRepository sagas;
    private final FutureEventRepository futureEvents;
    private final TimelineEventPublisher publisher;
    private final Clock clock;

    WeaverChainSaga(
            WeaverChainRepository chains,
            WeaverChainSagaRepository sagas,
            FutureEventRepository futureEvents,
            TimelineEventPublisher publisher,
            Clock clock) {
        this.chains = chains;
        this.sagas = sagas;
        this.futureEvents = futureEvents;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void playThread(UUID gameId, int eraNumber, UUID playerId, UUID targetEventId, UUID targetOutcomeId) {
        var saga = sagas.findOpenByGameAndPlayer(gameId, playerId).orElse(null);
        if (!resolvedAs(targetEventId, targetOutcomeId)) {
            publishRejected(
                    gameId,
                    eraNumber,
                    saga == null ? null : saga.chainId(),
                    playerId,
                    targetEventId,
                    targetOutcomeId,
                    REASON_DID_NOT_RESOLVE);
            return;
        }
        UUID chainId = saga == null ? UUID.randomUUID() : saga.chainId();
        if (saga == null) {
            chains.append(chainId, new WeaverChainStarted(chainId, playerId, gameId));
            sagas.save(new WeaverChainSagaState(chainId, gameId, playerId, WeaverChainSagaStatus.OPEN, false, null));
        }
        var chain = chains.findById(chainId);
        UUID previousLinkEventId = chain.links().isEmpty()
                ? null
                : chain.links().get(chain.links().size() - 1).eventId();
        List<? extends io.github.temporalrift.timeline.domain.event.WeaverChainEvent> facts;
        try {
            facts = chain.addLink(
                    targetEventId,
                    targetOutcomeId,
                    eraNumber,
                    Set.of(new ResolvedOutcome(targetEventId, targetOutcomeId)));
        } catch (InvalidChainLinkException _) {
            publishRejected(
                    gameId, eraNumber, chainId, playerId, targetEventId, targetOutcomeId, REASON_ALREADY_LINKED);
            return;
        }
        chains.appendAll(chainId, facts);
        var grown = chains.findById(chainId);
        publisher.publish(TimelineEventEnvelope.create(
                chainId,
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ChainLinkAddedEvent(
                        gameId, chainId, playerId, targetEventId, targetOutcomeId, grown.length(), previousLinkEventId),
                clock));
        if (facts.stream().anyMatch(fact -> fact instanceof ChainCompleted)) {
            publisher.publish(TimelineEventEnvelope.create(
                    chainId,
                    AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ChainCompletedEvent(gameId, eraNumber, chainId, playerId, toEntries(grown.links())),
                    clock));
            sagas.save(new WeaverChainSagaState(
                    chainId,
                    gameId,
                    playerId,
                    WeaverChainSagaStatus.COMPLETED,
                    false,
                    saga == null ? null : saga.tapestryUsedEra()));
        }
    }

    @Override
    @Transactional
    public void playTapestry(UUID gameId, int eraNumber, UUID playerId) {
        var saga = sagas.findOpenByGameAndPlayer(gameId, playerId).orElse(null);
        if (saga == null || Integer.valueOf(eraNumber).equals(saga.tapestryUsedEra())) {
            return;
        }
        var chain = chains.findById(saga.chainId());
        if (chain.length() < 2) {
            return;
        }
        sagas.save(new WeaverChainSagaState(
                saga.chainId(), gameId, playerId, WeaverChainSagaStatus.OPEN, true, eraNumber));
    }

    @Override
    @Transactional
    public void playUnravel(UUID gameId, int eraNumber, UUID actingPlayerId, UUID targetPlayerId) {
        var saga = sagas.findOpenByGameAndPlayer(gameId, targetPlayerId).orElse(null);
        if (saga == null) {
            return;
        }
        var chain = chains.findById(saga.chainId());
        int lengthAtBreak = chain.length();
        chains.append(saga.chainId(), chain.breakChain("UNRAVEL"));
        publisher.publish(TimelineEventEnvelope.create(
                saga.chainId(),
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ChainBrokenEvent(gameId, eraNumber, saga.chainId(), actingPlayerId, targetPlayerId, lengthAtBreak),
                clock));
        sagas.save(new WeaverChainSagaState(
                saga.chainId(), gameId, targetPlayerId, WeaverChainSagaStatus.BROKEN, false, saga.tapestryUsedEra()));
    }

    @Override
    @Transactional
    public void annihilateOutcome(UUID gameId, int eraNumber, UUID targetEventId, UUID targetOutcomeId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            var chain = chains.findById(saga.chainId());
            boolean linked = chain.links().stream()
                    .anyMatch(link -> link.eventId().equals(targetEventId)
                            && link.outcomeId().equals(targetOutcomeId));
            if (!linked) {
                continue;
            }
            if (saga.tapestryProtected()) {
                sagas.save(new WeaverChainSagaState(
                        saga.chainId(),
                        gameId,
                        saga.playerId(),
                        WeaverChainSagaStatus.OPEN,
                        false,
                        saga.tapestryUsedEra()));
                continue;
            }
            var invalidated = chain.invalidateLink(targetEventId, targetOutcomeId);
            if (invalidated.isEmpty()) {
                continue;
            }
            chains.append(saga.chainId(), invalidated.get());
            var current = chains.findById(saga.chainId());
            publisher.publish(TimelineEventEnvelope.create(
                    saga.chainId(),
                    AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ChainLinkInvalidatedEvent(
                            gameId,
                            eraNumber,
                            saga.chainId(),
                            saga.playerId(),
                            targetEventId,
                            targetOutcomeId,
                            current.length()),
                    clock));
        }
    }

    @Override
    @Transactional
    public void endGame(UUID gameId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            sagas.save(new WeaverChainSagaState(
                    saga.chainId(),
                    gameId,
                    saga.playerId(),
                    WeaverChainSagaStatus.ENDED,
                    false,
                    saga.tapestryUsedEra()));
        }
    }

    /**
     * Whether the referenced outcome actually resolved as the winner of its event — the causal-link
     * validity rule. Unknown events, unresolved events, and resolved-differently outcomes all fail.
     */
    private boolean resolvedAs(UUID eventId, UUID outcomeId) {
        final FutureEvent futureEvent;
        try {
            futureEvent = futureEvents.findById(eventId);
        } catch (FutureEventNotFoundException _) {
            return false;
        }
        if (!futureEvent.resolved()) {
            return false;
        }
        return winningOutcomeId(futureEvent.outcomes()).equals(outcomeId);
    }

    /** Highest-probability non-annihilated outcome, ties broken by smallest outcomeId — as resolved. */
    private static UUID winningOutcomeId(List<Outcome> outcomes) {
        return outcomes.stream()
                .filter(outcome -> !outcome.annihilated())
                .max(Comparator.comparingInt(Outcome::probability)
                        .thenComparing(Comparator.comparing(Outcome::outcomeId).reversed()))
                .map(Outcome::outcomeId)
                .orElse(null);
    }

    private static List<ChainCompletedEvent.ChainLinkEntry> toEntries(List<ChainLink> links) {
        return links.stream()
                .map(link -> new ChainCompletedEvent.ChainLinkEntry(link.eventId(), link.outcomeId(), link.eraNumber()))
                .toList();
    }

    private void publishRejected(
            UUID gameId,
            int eraNumber,
            UUID chainId,
            UUID playerId,
            UUID referencedEventId,
            UUID referencedOutcomeId,
            String reason) {
        publisher.publish(TimelineEventEnvelope.create(
                chainId == null ? playerId : chainId,
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ThreadRejectedEvent(
                        gameId, eraNumber, chainId, playerId, referencedEventId, referencedOutcomeId, reason),
                clock));
    }
}
