package io.github.temporalrift.timeline.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.ChainBrokenEvent;
import io.github.temporalrift.timeline.domain.event.ChainCompletedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.ChainLinkAddedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidatedEvent;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ThreadRejectedEvent;
import io.github.temporalrift.timeline.domain.event.WeaverChainEvent;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainRepository;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;

@ExtendWith(MockitoExtension.class)
class WeaverChainSagaTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final int ERA = 2;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    TimelineEventPublisher publisher;

    Clock clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

    FakeChains chains;
    FakeSagas sagas;
    WeaverChainSaga saga;

    @BeforeEach
    void setUp() {
        chains = new FakeChains();
        sagas = new FakeSagas();
        saga = new WeaverChainSaga(chains, sagas, futureEvents, publisher, clock);
    }

    @Test
    void thread_firstPlay_opensChainAndAddsFirstLink() {
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        var state = sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID).orElseThrow();
        var chain = chains.findById(state.chainId());
        assertThat(chain.length()).isEqualTo(1);
        var added = published(ChainLinkAddedEvent.class);
        assertThat(added.chainLength()).isEqualTo(1);
        assertThat(added.previousLinkEventId()).isNull();
    }

    @Test
    void thread_thirdValidLink_completesChainAndEndsSaga() {
        var chainId = openChainWithLinks(2);
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        var chain = chains.findById(chainId);
        assertThat(chain.length()).isEqualTo(3);
        published(ChainCompletedEvent.class);
        assertThat(sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID)).isEmpty();
        assertThat(sagas.findByChainId(chainId).orElseThrow().status()).isEqualTo(WeaverChainSagaStatus.COMPLETED);
    }

    @Test
    void thread_secondValidLink_staysOpenWithoutTerminalEvent() {
        var chainId = openChainWithLinks(1);
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(2);
        assertThat(sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID)).isPresent();
        publishedNever(ChainCompletedEvent.class);
    }

    @Test
    void thread_unresolvedOutcome_rejectedPrivatelyWithoutGrowth() {
        var chainId = openChainWithLinks(1);
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(unresolvedEvent(targetEvent));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.playerId()).isEqualTo(PLAYER_ID);
        assertThat(rejected.reason()).isEqualTo("OUTCOME_DID_NOT_RESOLVE");
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void thread_resolvedDifferently_rejectedWithoutGrowth() {
        var chainId = openChainWithLinks(1);
        var targetEvent = UUID.randomUUID();
        var actualWinner = UUID.randomUUID();
        var claimedOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, actualWinner));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, targetEvent, claimedOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        published(ThreadRejectedEvent.class);
    }

    @Test
    void thread_fullyAnnihilatedResolvedEvent_rejectedWithoutGrowth() {
        var chainId = openChainWithLinks(1);
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(fullyAnnihilatedEvent(targetEvent));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("OUTCOME_DID_NOT_RESOLVE");
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void thread_invalidWithoutActiveChain_rejectedWithoutMaterializingAChain() {
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(unresolvedEvent(targetEvent));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        assertThat(sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID)).isEmpty();
        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.chainId()).isNull();
        assertThat(rejected.playerId()).isEqualTo(PLAYER_ID);
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void unravel_breaksActiveChainAndEndsSagaInFailure() {
        openChainWithLinks(2);
        var unraveler = UUID.randomUUID();

        saga.playUnravel(GAME_ID, ERA, unraveler, PLAYER_ID);

        var broken = published(ChainBrokenEvent.class);
        assertThat(broken.targetPlayerId()).isEqualTo(PLAYER_ID);
        assertThat(broken.brokenByPlayerId()).isEqualTo(unraveler);
        assertThat(broken.chainLengthAtBreak()).isEqualTo(2);
        assertThat(sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID)).isEmpty();
    }

    @Test
    void unravel_withoutActiveChain_isNoOp() {
        saga.playUnravel(GAME_ID, ERA, UUID.randomUUID(), PLAYER_ID);

        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    void annihilate_unprotectedLink_invalidatesAndDecrements() {
        var chainId = openChainWithLinks(2);
        var linked = chains.findById(chainId).links().get(0);

        saga.annihilateOutcome(GAME_ID, ERA, linked.eventId(), linked.outcomeId());

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        var invalidated = published(ChainLinkInvalidatedEvent.class);
        assertThat(invalidated.chainLength()).isEqualTo(1);
        assertThat(invalidated.invalidatedEventId()).isEqualTo(linked.eventId());
    }

    @Test
    void annihilate_unlinkedOutcome_changesNothing() {
        openChainWithLinks(1);

        saga.annihilateOutcome(GAME_ID, ERA, UUID.randomUUID(), UUID.randomUUID());

        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    void tapestry_protectsNextLinkFromOneAnnihilate() {
        var chainId = openChainWithLinks(2);
        var linked = chains.findById(chainId).links().get(0);

        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);
        saga.annihilateOutcome(GAME_ID, ERA, linked.eventId(), linked.outcomeId());

        assertThat(chains.findById(chainId).length()).isEqualTo(2);
        then(publisher).shouldHaveNoInteractions();
        assertThat(sagas.findByChainId(chainId).orElseThrow().tapestryProtected())
                .isFalse();
    }

    @Test
    void tapestry_belowTwoLinks_grantsNothing() {
        var chainId = openChainWithLinks(1);

        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);

        assertThat(sagas.findByChainId(chainId).orElseThrow().tapestryProtected())
                .isFalse();
    }

    @Test
    void tapestry_oncePerEra_secondPlayInSameEraIsNoOp() {
        var chainId = openChainWithLinks(2);

        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);
        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);
        var linked = chains.findById(chainId).links().get(0);
        saga.annihilateOutcome(GAME_ID, ERA, linked.eventId(), linked.outcomeId());

        // Only one protection existed: the single annihilate consumed it, the chain is unchanged,
        // and no second protection was ever armed.
        assertThat(chains.findById(chainId).length()).isEqualTo(2);
        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    void endGame_endsIncompleteChainWithNoFurtherEvents() {
        openChainWithLinks(1);

        saga.endGame(GAME_ID);

        assertThat(sagas.findOpenByGame(GAME_ID)).isEmpty();
        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    void restart_resumesOpenSagaForNextThread() {
        var chainId = openChainWithLinks(1);
        // Simulate a restart: rebuild the saga service over the same durable repositories.
        var restarted = new WeaverChainSaga(chains, sagas, futureEvents, publisher, clock);
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        restarted.playThread(GAME_ID, ERA + 1, PLAYER_ID, targetEvent, targetOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(2);
        var added = published(ChainLinkAddedEvent.class);
        assertThat(added.chainId()).isEqualTo(chainId);
    }

    private UUID openChainWithLinks(int linkCount) {
        var chainId = UUID.randomUUID();
        var history = new ArrayList<WeaverChainEvent>();
        history.add(new WeaverChainStarted(chainId, PLAYER_ID, GAME_ID));
        for (int index = 0; index < linkCount; index++) {
            history.add(new ChainLinkAdded(chainId, UUID.randomUUID(), UUID.randomUUID(), index + 1));
        }
        chains.appendAll(chainId, history);
        sagas.save(new WeaverChainSagaState(chainId, GAME_ID, PLAYER_ID, WeaverChainSagaStatus.OPEN, false, null));
        return chainId;
    }

    private FutureEvent resolvedEvent(UUID eventId, UUID winnerId) {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(winnerId, "winner", 60),
                new Outcome(first, "first", 25),
                new Outcome(second, "second", 15));
        return FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(eventId, outcomes), appliedWinner(eventId, winnerId, outcomes)));
    }

    private OutcomeApplied appliedWinner(UUID eventId, UUID winnerId, List<Outcome> outcomes) {
        return new OutcomeApplied(GAME_ID, ERA - 1, eventId, winnerId, outcomes);
    }

    private FutureEvent unresolvedEvent(UUID eventId) {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "first", 34),
                new Outcome(UUID.randomUUID(), "second", 33),
                new Outcome(UUID.randomUUID(), "third", 33));
        return FutureEvent.replay(eventId, List.of(new FutureEventDrafted(eventId, outcomes)));
    }

    private FutureEvent fullyAnnihilatedEvent(UUID eventId) {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "first", 34, false, true),
                new Outcome(UUID.randomUUID(), "second", 33, false, true),
                new Outcome(UUID.randomUUID(), "third", 33, false, true));
        return FutureEvent.replay(
                eventId,
                List.of(
                        new FutureEventDrafted(eventId, outcomes),
                        new OutcomeApplied(
                                GAME_ID, ERA - 1, eventId, outcomes.get(0).outcomeId(), outcomes)));
    }

    @SuppressWarnings("unchecked")
    private <T> T published(Class<T> payloadType) {
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(atLeastOnce()).publish(captor.capture());
        return captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .filter(payloadType::isInstance)
                .map(payload -> (T) payload)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No published event of type " + payloadType));
    }

    private <T> void publishedNever(Class<T> payloadType) {
        then(publisher)
                .should(never())
                .publish(argThat(envelope -> envelope != null && payloadType.isInstance(envelope.payload())));
    }

    static class FakeChains implements WeaverChainRepository {
        private final Map<UUID, List<WeaverChainEvent>> streams = new HashMap<>();

        @Override
        public WeaverChain findById(UUID chainId) {
            var history = streams.get(chainId);
            if (history == null || history.isEmpty()) {
                throw new io.github.temporalrift.timeline.domain.weaverchain.WeaverChainNotFoundException(chainId);
            }
            return WeaverChain.replay(chainId, history);
        }

        @Override
        public void append(UUID chainId, WeaverChainEvent domainEvent) {
            streams.computeIfAbsent(chainId, key -> new ArrayList<>()).add(domainEvent);
        }

        @Override
        public void appendAll(UUID chainId, List<? extends WeaverChainEvent> domainEvents) {
            streams.computeIfAbsent(chainId, key -> new ArrayList<>()).addAll(domainEvents);
        }
    }

    static class FakeSagas implements WeaverChainSagaRepository {
        private final Map<UUID, WeaverChainSagaState> states = new HashMap<>();

        @Override
        public Optional<WeaverChainSagaState> findByChainId(UUID chainId) {
            return Optional.ofNullable(states.get(chainId));
        }

        @Override
        public Optional<WeaverChainSagaState> findOpenByGameAndPlayer(UUID gameId, UUID playerId) {
            return states.values().stream()
                    .filter(state -> state.gameId().equals(gameId)
                            && state.playerId().equals(playerId)
                            && state.status() == WeaverChainSagaStatus.OPEN)
                    .findFirst();
        }

        @Override
        public List<WeaverChainSagaState> findOpenByGame(UUID gameId) {
            return states.values().stream()
                    .filter(state -> state.gameId().equals(gameId) && state.status() == WeaverChainSagaStatus.OPEN)
                    .toList();
        }

        @Override
        public void save(WeaverChainSagaState state) {
            states.put(state.chainId(), state);
        }
    }
}
