package io.github.temporalrift.timeline.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.ChainBrokenEvent;
import io.github.temporalrift.timeline.domain.event.ChainCompletedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.ChainLinkAddedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidatedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkThreaded;
import io.github.temporalrift.timeline.domain.event.ChainLinkThreadedEvent;
import io.github.temporalrift.timeline.domain.event.ChainProtectionArmedEvent;
import io.github.temporalrift.timeline.domain.event.ChainProtectionConsumedEvent;
import io.github.temporalrift.timeline.domain.event.ChainReAnchoredEvent;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.SpecialRejectedEvent;
import io.github.temporalrift.timeline.domain.event.ThreadRejectedEvent;
import io.github.temporalrift.timeline.domain.event.WeaverChainEvent;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainRepository;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;
import io.github.temporalrift.timeline.domain.weaverchain.ChainStatus;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;

@ExtendWith(MockitoExtension.class)
class WeaverChainSagaTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    // Late enough that openChainWithConfirmedLinks' eras (1..n) all precede it.
    private static final int ERA = 4;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    FutureEventEraIndexPort eraIndex;

    @Mock
    ProbabilityRulesPort probabilityRules;

    @Mock
    TimelineEventPublisher publisher;

    Clock clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

    private final Map<Integer, List<UUID>> liveEventsByEra = new HashMap<>();

    FakeChains chains;
    FakeSagas sagas;
    WeaverChainSaga saga;

    @BeforeEach
    void setUp() {
        chains = new FakeChains();
        sagas = new FakeSagas();
        saga = new WeaverChainSaga(chains, sagas, futureEvents, eraIndex, probabilityRules, publisher, clock);
    }

    private record Coordinate(UUID eventId, UUID outcomeId) {}

    /** Stubs a current-era, unresolved outcome — the precondition every THREAD test needs to reach. */
    private Coordinate stubValidCoordinate() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA)).willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(unresolvedEventWithOutcome(eventId, outcomeId));
        return new Coordinate(eventId, outcomeId);
    }

    private void stubThreadRewardRules() {
        given(probabilityRules.threadShift()).willReturn(10);
        given(probabilityRules.probabilityFloor()).willReturn(0);
        given(probabilityRules.probabilityCeiling()).willReturn(90);
    }

    @Test
    void thread_firstPlay_opensChainWithPendingLink() {
        var coordinate = stubValidCoordinate();
        stubThreadRewardRules();

        saga.playThread(GAME_ID, ERA, PLAYER_ID, coordinate.eventId(), coordinate.outcomeId());

        var state = sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID).orElseThrow();
        var chain = chains.findById(state.chainId());
        assertThat(chain.pendingLink().eventId()).isEqualTo(coordinate.eventId());
        assertThat(chain.pendingLink().outcomeId()).isEqualTo(coordinate.outcomeId());
        assertThat(chain.length()).isZero();
        var threaded = published(ChainLinkThreadedEvent.class);
        assertThat(threaded.eventId()).isEqualTo(coordinate.eventId());
        assertThat(threaded.outcomeId()).isEqualTo(coordinate.outcomeId());
    }

    @Test
    void thread_acceptedLink_appliesConfiguredShiftToNamedOutcome() {
        var coordinate = stubValidCoordinate();
        stubThreadRewardRules();

        saga.playThread(GAME_ID, ERA, PLAYER_ID, coordinate.eventId(), coordinate.outcomeId());

        then(futureEvents).should().append(eq(coordinate.eventId()), any());
    }

    @Test
    void thread_sealedOutcome_linkStillAccepted_probabilityUnmoved() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA)).willReturn(List.of(new IndexedEventId(eventId, 0)));
        var futureEvent = sealedUnresolvedEventWithOutcome(eventId, outcomeId);
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        stubThreadRewardRules();

        saga.playThread(GAME_ID, ERA, PLAYER_ID, eventId, outcomeId);

        // The pending link is opened regardless — only the reward's probability movement is blocked by the seal.
        var state = sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID).orElseThrow();
        assertThat(chains.findById(state.chainId()).pendingLink()).isNotNull();
        assertThat(futureEvent.sealBreach()).isFalse();
        assertThat(futureEvent.outcomes().getFirst().probability()).isEqualTo(34);
    }

    @Test
    void thread_missingCoordinate_rejectedWithoutOpeningAChain() {
        openChainWithConfirmedLinks(1);

        saga.playThread(GAME_ID, ERA, PLAYER_ID, null, null);

        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("MISSING_COORDINATE");
        publishedNever(ChainLinkThreadedEvent.class);
    }

    @Test
    void thread_notInCurrentEra_rejected() {
        openChainWithConfirmedLinks(1);
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA)).willReturn(List.of());

        saga.playThread(GAME_ID, ERA, PLAYER_ID, eventId, outcomeId);

        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("INVALID_COORDINATE");
        publishedNever(ChainLinkThreadedEvent.class);
    }

    @Test
    void thread_alreadyResolved_rejected() {
        openChainWithConfirmedLinks(1);
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA)).willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(resolvedEvent(eventId, outcomeId));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, eventId, outcomeId);

        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("INVALID_COORDINATE");
    }

    @Test
    void thread_alreadyAnnihilatedOutcome_rejected() {
        openChainWithConfirmedLinks(1);
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA)).willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(annihilatedUnresolvedEventWithOutcome(eventId, outcomeId));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, eventId, outcomeId);

        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("INVALID_COORDINATE");
        publishedNever(ChainLinkThreadedEvent.class);
    }

    @Test
    void thread_alreadyPending_rejected() {
        var chainId = openChainWithPendingLink();
        var coordinate = stubValidCoordinate();

        saga.playThread(GAME_ID, ERA, PLAYER_ID, coordinate.eventId(), coordinate.outcomeId());

        assertThat(chains.findById(chainId).pendingLink().eventId()).isNotEqualTo(coordinate.eventId());
        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("ALREADY_PENDING");
    }

    @Test
    void thread_eventAlreadyConfirmedLinked_rejected() {
        var chainId = openChainWithConfirmedLinks(1);
        var existing = chains.findById(chainId).links().getFirst();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA))
                .willReturn(List.of(new IndexedEventId(existing.eventId(), 0)));
        given(futureEvents.findById(existing.eventId()))
                .willReturn(unresolvedEventWithOutcome(existing.eventId(), existing.outcomeId()));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, existing.eventId(), existing.outcomeId());

        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("EVENT_ALREADY_LINKED");
    }

    @Test
    void thread_withoutActiveChain_opensNewChain() {
        var coordinate = stubValidCoordinate();
        stubThreadRewardRules();

        saga.playThread(GAME_ID, ERA, PLAYER_ID, coordinate.eventId(), coordinate.outcomeId());

        assertThat(sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID)).isPresent();
        published(ChainLinkThreadedEvent.class);
    }

    @Test
    void resolvePendingLink_predictedWin_confirmsAndGrows() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();

        saga.resolvePendingLink(GAME_ID, ERA, pending.eventId(), pending.outcomeId());

        var chain = chains.findById(chainId);
        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.pendingLink()).isNull();
        var added = published(ChainLinkAddedEvent.class);
        assertThat(added.linkedEventId()).isEqualTo(pending.eventId());
        assertThat(added.chainLength()).isEqualTo(1);
    }

    @Test
    void resolvePendingLink_thirdLink_completesChainAndEndsSaga() {
        var chainId = openChainWithConfirmedLinks(2);
        var pendingEvent = UUID.randomUUID();
        var pendingOutcome = UUID.randomUUID();
        chains.append(chainId, new ChainLinkThreaded(chainId, pendingEvent, pendingOutcome, ERA));

        saga.resolvePendingLink(GAME_ID, ERA, pendingEvent, pendingOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(3);
        published(ChainCompletedEvent.class);
        assertThat(sagas.findByChainId(chainId).orElseThrow().status()).isEqualTo(WeaverChainSagaStatus.COMPLETED);
    }

    @Test
    void resolvePendingLink_differentWinner_clearsWithoutPenalty() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();

        saga.resolvePendingLink(GAME_ID, ERA, pending.eventId(), UUID.randomUUID());

        var chain = chains.findById(chainId);
        assertThat(chain.length()).isZero();
        assertThat(chain.pendingLink()).isNull();
        var invalidated = published(ChainLinkInvalidatedEvent.class);
        assertThat(invalidated.invalidatedEventId()).isEqualTo(pending.eventId());
    }

    @Test
    void resolvePendingLink_noMatchingPending_noOp() {
        openChainWithConfirmedLinks(1);

        saga.resolvePendingLink(GAME_ID, ERA, UUID.randomUUID(), UUID.randomUUID());

        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    void stallPendingLink_clearsPredictionAndProtectionWithoutChangingConfirmedLinks() {
        var chainId = openChainWithConfirmedLinks(2);
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        chains.append(chainId, new ChainLinkThreaded(chainId, eventId, outcomeId, ERA));
        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);

        saga.stallPendingLink(GAME_ID, ERA, eventId);

        var chain = chains.findById(chainId);
        assertThat(chain.pendingLink()).isNull();
        assertThat(chain.length()).isEqualTo(2);
        assertThat(sagas.findByChainId(chainId).orElseThrow().tapestryProtected())
                .isFalse();
        var invalidated = published(ChainLinkInvalidatedEvent.class);
        assertThat(invalidated.invalidatedEventId()).isEqualTo(eventId);
        assertThat(invalidated.invalidatedOutcomeId()).isEqualTo(outcomeId);
        assertThat(invalidated.chainLength()).isEqualTo(2);
        publishedNever(ChainLinkAddedEvent.class);
        publishedNever(ChainCompletedEvent.class);
    }

    @Test
    void stallPendingLink_repeatedDeliveryThenNewThreadOnCarriedEventScoresOnlyNewLink() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();

        saga.stallPendingLink(GAME_ID, ERA, pending.eventId());
        saga.stallPendingLink(GAME_ID, ERA, pending.eventId());
        then(publisher)
                .should(times(1))
                .publish(argThat(envelope -> envelope.payload() instanceof ChainLinkInvalidatedEvent));

        chains.append(chainId, new ChainLinkThreaded(chainId, pending.eventId(), pending.outcomeId(), ERA + 1));
        saga.stallPendingLink(GAME_ID, ERA, pending.eventId());
        saga.resolvePendingLink(GAME_ID, ERA, pending.eventId(), pending.outcomeId());
        saga.confirmParadoxResolvedLink(GAME_ID, ERA, pending.eventId(), pending.outcomeId());
        saga.breakChainOnCascadedParadox(GAME_ID, ERA, pending.eventId(), pending.outcomeId(), UUID.randomUUID());
        assertThat(chains.findById(chainId).pendingLink().eraNumber()).isEqualTo(ERA + 1);
        saga.resolvePendingLink(GAME_ID, ERA + 1, pending.eventId(), pending.outcomeId());

        assertThat(chains.findById(chainId).links())
                .singleElement()
                .satisfies(link -> assertThat(link.eraNumber()).isEqualTo(ERA + 1));
        then(publisher)
                .should(times(1))
                .publish(argThat(envelope -> envelope.payload() instanceof ChainLinkAddedEvent));
    }

    @Test
    void stallPendingLink_repeatedInTwoErasDoesNotLeaveAnOpenPrediction() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();

        saga.stallPendingLink(GAME_ID, ERA, pending.eventId());
        chains.append(chainId, new ChainLinkThreaded(chainId, pending.eventId(), pending.outcomeId(), ERA + 1));
        saga.stallPendingLink(GAME_ID, ERA + 1, pending.eventId());

        assertThat(chains.findById(chainId).pendingLink()).isNull();
        assertThat(chains.findById(chainId).length()).isZero();
        then(publisher)
                .should(times(2))
                .publish(argThat(envelope -> envelope.payload() instanceof ChainLinkInvalidatedEvent));
    }

    @Test
    void resolvePendingLink_staleOriginCannotConfirmInLaterEra() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();

        saga.resolvePendingLink(GAME_ID, ERA + 1, pending.eventId(), pending.outcomeId());

        assertThat(chains.findById(chainId).pendingLink()).isNull();
        assertThat(chains.findById(chainId).length()).isZero();
        published(ChainLinkInvalidatedEvent.class);
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void annihilate_protectedPendingLink_confirmsAndConsumesProtection() {
        var chainId = openChainWithConfirmedLinks(2);
        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);
        published(ChainProtectionArmedEvent.class);
        var pendingEvent = UUID.randomUUID();
        var pendingOutcome = UUID.randomUUID();
        chains.append(chainId, new ChainLinkThreaded(chainId, pendingEvent, pendingOutcome, ERA));

        saga.annihilateOutcome(GAME_ID, ERA, pendingEvent, pendingOutcome);

        var chain = chains.findById(chainId);
        assertThat(chain.length()).isEqualTo(3);
        assertThat(chain.pendingLink()).isNull();
        var consumed = published(ChainProtectionConsumedEvent.class);
        assertThat(consumed.protectedEventId()).isEqualTo(pendingEvent);
        published(ChainCompletedEvent.class);
        assertThat(sagas.findByChainId(chainId).orElseThrow().tapestryProtected())
                .isFalse();
    }

    @Test
    void annihilate_oldEraPendingLink_preservesNewEraProtection() {
        var chainId = openChainWithConfirmedLinks(2);
        var pendingEvent = UUID.randomUUID();
        var pendingOutcome = UUID.randomUUID();
        chains.append(chainId, new ChainLinkThreaded(chainId, pendingEvent, pendingOutcome, ERA));
        saga.playTapestry(GAME_ID, ERA + 1, PLAYER_ID);

        saga.annihilateOutcome(GAME_ID, ERA + 1, pendingEvent, pendingOutcome);

        var chain = chains.findById(chainId);
        assertThat(chain.pendingLink()).isNull();
        assertThat(chain.length()).isEqualTo(2);
        assertThat(sagas.findByChainId(chainId).orElseThrow().tapestryProtected())
                .isTrue();
        published(ChainLinkInvalidatedEvent.class);
        publishedNever(ChainProtectionConsumedEvent.class);
        publishedNever(ChainLinkAddedEvent.class);
        publishedNever(ChainCompletedEvent.class);

        var newEvent = UUID.randomUUID();
        var newOutcome = UUID.randomUUID();
        chains.append(chainId, new ChainLinkThreaded(chainId, newEvent, newOutcome, ERA + 1));
        saga.annihilateOutcome(GAME_ID, ERA + 1, newEvent, newOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(3);
        published(ChainProtectionConsumedEvent.class);
        published(ChainLinkAddedEvent.class);
        published(ChainCompletedEvent.class);
    }

    @Test
    void annihilate_unprotectedPendingLink_leftPendingForParadoxDetection() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();

        saga.annihilateOutcome(GAME_ID, ERA, pending.eventId(), pending.outcomeId());

        assertThat(chains.findById(chainId).pendingLink()).isEqualTo(pending);
        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    void annihilate_nonMatchingCoordinate_changesNothing() {
        openChainWithPendingLink();

        saga.annihilateOutcome(GAME_ID, ERA, UUID.randomUUID(), UUID.randomUUID());

        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    void annihilate_unconsumedProtectionFromEarlierEra_doesNotApply() {
        var chainId = openChainWithConfirmedLinks(2);
        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);
        published(ChainProtectionArmedEvent.class);
        var pendingEvent = UUID.randomUUID();
        var pendingOutcome = UUID.randomUUID();
        chains.append(chainId, new ChainLinkThreaded(chainId, pendingEvent, pendingOutcome, ERA + 1));

        // A later era begins; the protection was never consumed in the era it was armed for.
        saga.annihilateOutcome(GAME_ID, ERA + 1, pendingEvent, pendingOutcome);

        assertThat(chains.findById(chainId).pendingLink()).isNotNull();
        publishedNever(ChainProtectionConsumedEvent.class);
    }

    @Test
    void confirmParadoxResolvedLink_confirmsMatchingPending() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();

        saga.confirmParadoxResolvedLink(GAME_ID, ERA, pending.eventId(), pending.outcomeId());

        var chain = chains.findById(chainId);
        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.pendingLink()).isNull();
        published(ChainLinkAddedEvent.class);
    }

    @Test
    void confirmParadoxResolvedLink_nonMatching_noOp() {
        openChainWithPendingLink();

        saga.confirmParadoxResolvedLink(GAME_ID, ERA, UUID.randomUUID(), UUID.randomUUID());

        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    void confirmParadoxResolvedLink_staleOriginExpiresWithoutScore() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();

        saga.confirmParadoxResolvedLink(GAME_ID, ERA + 1, pending.eventId(), pending.outcomeId());

        assertThat(chains.findById(chainId).pendingLink()).isNull();
        assertThat(chains.findById(chainId).length()).isZero();
        published(ChainLinkInvalidatedEvent.class);
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void breakChainOnCascadedParadox_breaksChainAndPublishes() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();
        var paradoxId = UUID.randomUUID();

        saga.breakChainOnCascadedParadox(GAME_ID, ERA, pending.eventId(), pending.outcomeId(), paradoxId);

        var chain = chains.findById(chainId);
        assertThat(chain.status()).isEqualTo(io.github.temporalrift.timeline.domain.weaverchain.ChainStatus.BROKEN);
        assertThat(chain.pendingLink()).isNull();
        var broken = published(ChainBrokenEvent.class);
        assertThat(broken.paradoxId()).isEqualTo(paradoxId);
        assertThat(broken.chainLengthAtBreak()).isZero();
        assertThat(sagas.findByChainId(chainId).orElseThrow().status()).isEqualTo(WeaverChainSagaStatus.BROKEN);
    }

    @Test
    void breakChainOnCascadedParadox_nonMatching_noOp() {
        openChainWithPendingLink();

        saga.breakChainOnCascadedParadox(GAME_ID, ERA, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    void breakChainOnCascadedParadox_staleOriginExpiresWithoutPenalty() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();

        saga.breakChainOnCascadedParadox(GAME_ID, ERA + 1, pending.eventId(), pending.outcomeId(), UUID.randomUUID());

        assertThat(chains.findById(chainId).pendingLink()).isNull();
        assertThat(chains.findById(chainId).status())
                .isEqualTo(io.github.temporalrift.timeline.domain.weaverchain.ChainStatus.ACTIVE);
        published(ChainLinkInvalidatedEvent.class);
        publishedNever(ChainBrokenEvent.class);
    }

    @Test
    void thread_afterPendingLinkConfirmation_isRejectedWithoutOpeningAnotherChain() {
        var chainId = openChainWithConfirmedLinks(2);
        var pendingEvent = UUID.randomUUID();
        var pendingOutcome = UUID.randomUUID();
        chains.append(chainId, chains.findById(chainId).threadPendingLink(pendingEvent, pendingOutcome, ERA));

        saga.resolvePendingLink(GAME_ID, ERA, pendingEvent, pendingOutcome);
        saga.playThread(GAME_ID, ERA + 1, PLAYER_ID, UUID.randomUUID(), UUID.randomUUID());

        assertThat(sagas.findByChainId(chainId).orElseThrow().status()).isEqualTo(WeaverChainSagaStatus.COMPLETED);
        assertThat(chains.findById(chainId).status()).isEqualTo(ChainStatus.COMPLETED);
        assertThat(published(ThreadRejectedEvent.class).reason()).isEqualTo("CHAIN_ALREADY_COMPLETED");
        assertThat(published(ChainCompletedEvent.class).eraNumber()).isEqualTo(ERA);
        assertThat(sagas.findAllByGameAndPlayer(GAME_ID, PLAYER_ID)).hasSize(1);
        then(publisher)
                .should(times(1))
                .publish(argThat(envelope -> envelope != null && envelope.payload() instanceof ChainCompletedEvent));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void reweave_pendingLink_reAimsWithoutGrowthOrShift(int confirmedLinks) {
        var pending = liveOutcome(ERA);
        var chainId = openChainWithPendingLink(confirmedLinks, pending);
        var target = liveOutcome(ERA);

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, target.eventId(), target.outcomeId());

        var chain = chains.findById(chainId);
        assertThat(chain.pendingLink().eventId()).isEqualTo(target.eventId());
        assertThat(chain.pendingLink().outcomeId()).isEqualTo(target.outcomeId());
        assertThat(chain.length()).isEqualTo(confirmedLinks);
        var reAnchored = published(ChainReAnchoredEvent.class);
        assertThat(reAnchored.discardedEventId()).isEqualTo(pending.eventId());
        assertThat(reAnchored.discardedOutcomeId()).isEqualTo(pending.outcomeId());
        assertThat(reAnchored.linkedEventId()).isEqualTo(target.eventId());
        assertThat(reAnchored.linkedOutcomeId()).isEqualTo(target.outcomeId());
        assertThat(reAnchored.chainLength()).isEqualTo(confirmedLinks);
        assertThat(sagas.findByChainId(chainId).orElseThrow().reweaveUsedEra()).isEqualTo(ERA);
        publishedNever(ChainLinkAddedEvent.class);
        publishedNever(ChainCompletedEvent.class);
        then(futureEvents).should(never()).append(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void reweave_withoutPendingLink_rejectedAsNoPendingLink(int confirmedLinks) {
        var chainId = openChainWithConfirmedLinks(confirmedLinks);
        var before = chains.findById(chainId).links();

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, UUID.randomUUID(), UUID.randomUUID());

        assertThat(published(SpecialRejectedEvent.class).reason()).isEqualTo("NO_PENDING_LINK");
        assertThat(chains.findById(chainId).links()).isEqualTo(before);
        assertThat(sagas.findByChainId(chainId).orElseThrow().reweaveUsedEra()).isNull();
    }

    @Test
    void reweave_pendingLinkFromEarlierEra_rejectedAsNoPendingLink() {
        var chainId = openChainWithPendingLink(0, new Coordinate(UUID.randomUUID(), UUID.randomUUID()));

        saga.playReweave(GAME_ID, ERA + 1, PLAYER_ID, UUID.randomUUID(), UUID.randomUUID());

        assertThat(published(SpecialRejectedEvent.class).reason()).isEqualTo("NO_PENDING_LINK");
        assertThat(chains.findById(chainId).pendingLink().eraNumber()).isEqualTo(ERA);
    }

    @Test
    void reweave_reAimedLink_confirmsWhenNewOutcomeWins() {
        var chainId = openChainWithPendingLink(1, liveOutcome(ERA));
        var target = liveOutcome(ERA);
        saga.playReweave(GAME_ID, ERA, PLAYER_ID, target.eventId(), target.outcomeId());

        saga.resolvePendingLink(GAME_ID, ERA, target.eventId(), target.outcomeId());

        assertThat(chains.findById(chainId).length()).isEqualTo(2);
        assertThat(published(ChainLinkAddedEvent.class).linkedEventId()).isEqualTo(target.eventId());
    }

    @Test
    void reweave_reAimedLink_clearsWhenNewOutcomeLoses() {
        var chainId = openChainWithPendingLink(1, liveOutcome(ERA));
        var target = liveOutcome(ERA);
        saga.playReweave(GAME_ID, ERA, PLAYER_ID, target.eventId(), target.outcomeId());

        saga.resolvePendingLink(GAME_ID, ERA, target.eventId(), UUID.randomUUID());

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        assertThat(chains.findById(chainId).pendingLink()).isNull();
        published(ChainLinkInvalidatedEvent.class);
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void reweave_twoConfirmedLinks_completesOnlyWhenReAimedLinkConfirms() {
        var chainId = openChainWithPendingLink(2, liveOutcome(ERA));
        var target = liveOutcome(ERA);

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, target.eventId(), target.outcomeId());

        assertThat(sagas.findByChainId(chainId).orElseThrow().status()).isEqualTo(WeaverChainSagaStatus.OPEN);
        publishedNever(ChainCompletedEvent.class);

        saga.resolvePendingLink(GAME_ID, ERA, target.eventId(), target.outcomeId());

        assertThat(chains.findById(chainId).status()).isEqualTo(ChainStatus.COMPLETED);
        assertThat(sagas.findByChainId(chainId).orElseThrow().status()).isEqualTo(WeaverChainSagaStatus.COMPLETED);
        then(publisher)
                .should(times(1))
                .publish(argThat(envelope -> envelope != null && envelope.payload() instanceof ChainCompletedEvent));
    }

    @Test
    void threadThenReweaveEveryEra_withoutACorrectPrediction_neverCompletes() {
        stubThreadRewardRules();
        for (var era : List.of(2, 3, 4)) {
            var threaded = liveOutcome(era);
            var reAimed = liveOutcome(era);
            saga.playThread(GAME_ID, era, PLAYER_ID, threaded.eventId(), threaded.outcomeId());
            saga.playReweave(GAME_ID, era, PLAYER_ID, reAimed.eventId(), reAimed.outcomeId());
            saga.resolvePendingLink(GAME_ID, era, reAimed.eventId(), UUID.randomUUID());
        }

        var state = sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID).orElseThrow();
        assertThat(chains.findById(state.chainId()).length()).isZero();
        publishedNever(ChainLinkAddedEvent.class);
        publishedNever(ChainCompletedEvent.class);
    }

    @Test
    void reweave_offAnnihilatedPendingOutcome_movesLinkToLiveOutcome() {
        var pending = liveOutcome(ERA);
        var chainId = openChainWithPendingLink(1, pending);
        saga.annihilateOutcome(GAME_ID, ERA, pending.eventId(), pending.outcomeId());
        var target = liveOutcome(ERA);

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, target.eventId(), target.outcomeId());

        assertThat(chains.findById(chainId).pendingLink().outcomeId()).isEqualTo(target.outcomeId());
        assertThat(chains.findById(chainId).length()).isEqualTo(1);
    }

    @Test
    void reweave_tapestryArmed_stillProtectsReAimedLink() {
        var chainId = openChainWithPendingLink(2, liveOutcome(ERA));
        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);
        var target = liveOutcome(ERA);
        saga.playReweave(GAME_ID, ERA, PLAYER_ID, target.eventId(), target.outcomeId());

        saga.annihilateOutcome(GAME_ID, ERA, target.eventId(), target.outcomeId());

        assertThat(published(ChainProtectionConsumedEvent.class).protectedEventId())
                .isEqualTo(target.eventId());
        assertThat(chains.findById(chainId).status()).isEqualTo(ChainStatus.COMPLETED);
    }

    @Test
    void reweave_pendingOutcomeItself_rejectedAsInvalidCoordinate() {
        var pending = liveOutcome(ERA);
        var chainId = openChainWithPendingLink(1, pending);

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, pending.eventId(), pending.outcomeId());

        assertThat(published(SpecialRejectedEvent.class).reason()).isEqualTo("INVALID_COORDINATE");
        assertThat(sagas.findByChainId(chainId).orElseThrow().reweaveUsedEra()).isNull();
    }

    @Test
    void reweave_sameEventOtherOutcome_accepted() {
        var pending = liveOutcome(ERA);
        var chainId = openChainWithPendingLink(0, pending);
        var otherOutcome = futureEvents.findById(pending.eventId()).outcomes().stream()
                .map(Outcome::outcomeId)
                .filter(id -> !id.equals(pending.outcomeId()))
                .findFirst()
                .orElseThrow();

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, pending.eventId(), otherOutcome);

        assertThat(chains.findById(chainId).pendingLink().outcomeId()).isEqualTo(otherOutcome);
    }

    @Test
    void reweave_annihilatedTarget_rejectedAsInvalidCoordinate() {
        openChainWithPendingLink(1, liveOutcome(ERA));
        var target = liveOutcome(ERA);
        futureEvents.findById(target.eventId()).annihilateOutcome(target.outcomeId());

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, target.eventId(), target.outcomeId());

        assertThat(published(SpecialRejectedEvent.class).reason()).isEqualTo("INVALID_COORDINATE");
    }

    @Test
    void reweave_resolvedOrOtherEraTarget_rejectedAsInvalidCoordinate() {
        openChainWithPendingLink(1, liveOutcome(ERA));
        var resolvedId = UUID.randomUUID();
        var resolvedWinner = UUID.randomUUID();
        given(futureEvents.findById(resolvedId)).willReturn(resolvedEvent(resolvedId, resolvedWinner, ERA));
        indexLive(ERA, resolvedId);
        var otherEra = liveOutcome(ERA + 1);

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, resolvedId, resolvedWinner);
        saga.playReweave(GAME_ID, ERA, PLAYER_ID, otherEra.eventId(), otherEra.outcomeId());

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(2)).publish(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(envelope -> ((SpecialRejectedEvent) envelope.payload()).reason())
                .containsExactly("INVALID_COORDINATE", "INVALID_COORDINATE");
    }

    @Test
    void reweave_targetEventAlreadyLinked_rejected() {
        var chainId = openChainWithConfirmedLinks(1);
        var linked = chains.findById(chainId).links().getFirst();
        // A Tapestry-confirmed link whose event Stalled is still live in a later era.
        indexLive(ERA, linked.eventId());
        given(futureEvents.findById(linked.eventId()))
                .willReturn(unresolvedEventWithOutcome(linked.eventId(), linked.outcomeId()));
        chains.append(
                chainId,
                chains.findById(chainId).threadPendingLink(liveOutcome(ERA).eventId(), UUID.randomUUID(), ERA));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, linked.eventId(), linked.outcomeId());

        assertThat(published(SpecialRejectedEvent.class).reason()).isEqualTo("TARGET_ALREADY_LINKED");
    }

    @Test
    void reweave_missingTargetCoordinate_rejected() {
        openChainWithPendingLink(1, liveOutcome(ERA));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, null, null);

        assertThat(published(SpecialRejectedEvent.class).reason()).isEqualTo("MISSING_COORDINATE");
    }

    @Test
    void reweave_alreadyUsedThisEra_rejectedWithoutChange() {
        var chainId = openChainWithPendingLink(1, liveOutcome(ERA));
        var first = liveOutcome(ERA);
        saga.playReweave(GAME_ID, ERA, PLAYER_ID, first.eventId(), first.outcomeId());
        var second = liveOutcome(ERA);

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, second.eventId(), second.outcomeId());

        assertThat(chains.findById(chainId).pendingLink().eventId()).isEqualTo(first.eventId());
        assertThat(published(SpecialRejectedEvent.class).reason()).isEqualTo("ALREADY_USED_THIS_ERA");
    }

    @Test
    void reweave_rejectedAttempt_doesNotCountTowardTheEraLimit() {
        var chainId = openChainWithPendingLink(1, liveOutcome(ERA));
        saga.playReweave(GAME_ID, ERA, PLAYER_ID, null, null);
        var target = liveOutcome(ERA);

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, target.eventId(), target.outcomeId());

        assertThat(chains.findById(chainId).pendingLink().eventId()).isEqualTo(target.eventId());
        published(ChainReAnchoredEvent.class);
    }

    @Test
    void reweave_usedInAnEarlierEra_succeedsAgainInALaterEra() {
        var chainId = openChainWithPendingLink(0, liveOutcome(ERA));
        var first = liveOutcome(ERA);
        saga.playReweave(GAME_ID, ERA, PLAYER_ID, first.eventId(), first.outcomeId());
        saga.resolvePendingLink(GAME_ID, ERA, first.eventId(), first.outcomeId());
        var next = liveOutcome(ERA + 1);
        chains.append(chainId, chains.findById(chainId).threadPendingLink(next.eventId(), next.outcomeId(), ERA + 1));
        var second = liveOutcome(ERA + 1);

        saga.playReweave(GAME_ID, ERA + 1, PLAYER_ID, second.eventId(), second.outcomeId());

        assertThat(chains.findById(chainId).pendingLink().eventId()).isEqualTo(second.eventId());
        assertThat(sagas.findByChainId(chainId).orElseThrow().reweaveUsedEra()).isEqualTo(ERA + 1);
    }

    @Test
    void reweave_withoutActiveChain_rejected() {
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("NO_ACTIVE_CHAIN");
        assertThat(rejected.specialAction()).isEqualTo("REWEAVE");
    }

    @Test
    void thread_sameEraAsTapestryConfirmedLink_rejectedAsNotSuccessive() {
        var chainId = openChainWithConfirmedLinks(1);
        var protectedEvent = UUID.randomUUID();
        var protectedOutcome = UUID.randomUUID();
        chains.append(chainId, new ChainLinkThreaded(chainId, protectedEvent, protectedOutcome, ERA));
        chains.append(chainId, new ChainLinkAdded(chainId, protectedEvent, protectedOutcome, ERA));
        var coordinate = stubValidCoordinate();

        saga.playThread(GAME_ID, ERA, PLAYER_ID, coordinate.eventId(), coordinate.outcomeId());

        var chain = chains.findById(chainId);
        assertThat(chain.pendingLink()).isNull();
        assertThat(chain.length()).isEqualTo(2);
        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("LINK_ERA_NOT_SUCCESSIVE");
        then(futureEvents).should(never()).append(eq(coordinate.eventId()), any());
    }

    @Test
    void tapestry_belowTwoLinks_rejected() {
        var chainId = openChainWithConfirmedLinks(1);

        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);

        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("CHAIN_TOO_SHORT");
        assertThat(sagas.findByChainId(chainId).orElseThrow().tapestryProtected())
                .isFalse();
    }

    @Test
    void tapestry_withoutActiveChain_rejected() {
        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);

        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("NO_ACTIVE_CHAIN");
    }

    @Test
    void tapestry_oncePerEra_secondPlayInSameEraIsRejected() {
        var chainId = openChainWithConfirmedLinks(2);

        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);
        published(ChainProtectionArmedEvent.class);
        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);

        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("ALREADY_USED_THIS_ERA");
        assertThat(chains.findById(chainId).length()).isEqualTo(2);
    }

    @Test
    void endGame_endsIncompleteChainWithNoFurtherEvents() {
        openChainWithConfirmedLinks(1);

        saga.endGame(GAME_ID);

        assertThat(sagas.findOpenByGame(GAME_ID)).isEmpty();
        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    void endGame_expiresPendingLinkBeforeEndingChain() {
        var chainId = openChainWithPendingLink();

        saga.endGame(GAME_ID);

        assertThat(chains.findById(chainId).pendingLink()).isNull();
        assertThat(sagas.findByChainId(chainId).orElseThrow().status()).isEqualTo(WeaverChainSagaStatus.ENDED);
        published(ChainLinkInvalidatedEvent.class);
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void restart_resumesOpenSagaForNextThread() {
        var chainId = openChainWithConfirmedLinks(1);
        // Simulate a restart: rebuild the saga service over the same durable repositories.
        var restarted = new WeaverChainSaga(chains, sagas, futureEvents, eraIndex, probabilityRules, publisher, clock);
        var coordinate = stubValidCoordinate();
        stubThreadRewardRules();

        restarted.playThread(GAME_ID, ERA, PLAYER_ID, coordinate.eventId(), coordinate.outcomeId());

        assertThat(chains.findById(chainId).pendingLink().eventId()).isEqualTo(coordinate.eventId());
        var threaded = published(ChainLinkThreadedEvent.class);
        assertThat(threaded.chainId()).isEqualTo(chainId);
    }

    /** Opens a chain with {@code linkCount} confirmed links (each threaded then immediately confirmed). */
    private UUID openChainWithConfirmedLinks(int linkCount) {
        var chainId = UUID.randomUUID();
        var history = new ArrayList<WeaverChainEvent>();
        history.add(new WeaverChainStarted(chainId, PLAYER_ID, GAME_ID));
        for (int index = 0; index < linkCount; index++) {
            var eventId = UUID.randomUUID();
            var outcomeId = UUID.randomUUID();
            history.add(new ChainLinkThreaded(chainId, eventId, outcomeId, index + 1));
            history.add(new ChainLinkAdded(chainId, eventId, outcomeId, index + 1));
        }
        chains.appendAll(chainId, history);
        sagas.save(
                new WeaverChainSagaState(chainId, GAME_ID, PLAYER_ID, WeaverChainSagaStatus.OPEN, false, null, null));
        return chainId;
    }

    /** Opens a fresh chain with one open pending link (no confirmed links yet). */
    private UUID openChainWithPendingLink() {
        var chainId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(chainId, PLAYER_ID, GAME_ID),
                new ChainLinkThreaded(chainId, UUID.randomUUID(), UUID.randomUUID(), ERA));
        chains.appendAll(chainId, history);
        sagas.save(
                new WeaverChainSagaState(chainId, GAME_ID, PLAYER_ID, WeaverChainSagaStatus.OPEN, false, null, null));
        return chainId;
    }

    /** Opens a chain with {@code confirmedLinks} confirmed links and a pending link on {@code pending} in ERA. */
    private UUID openChainWithPendingLink(int confirmedLinks, Coordinate pending) {
        var chainId = openChainWithConfirmedLinks(confirmedLinks);
        chains.append(chainId, new ChainLinkThreaded(chainId, pending.eventId(), pending.outcomeId(), ERA));
        return chainId;
    }

    /** Stubs a live outcome of {@code era}: its event is indexed in that era, unresolved, and not annihilated. */
    private Coordinate liveOutcome(int era) {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        indexLive(era, eventId);
        lenient().when(futureEvents.findById(eventId)).thenReturn(unresolvedEventWithOutcome(eventId, outcomeId));
        return new Coordinate(eventId, outcomeId);
    }

    private void indexLive(int era, UUID eventId) {
        if (liveEventsByEra.isEmpty()) {
            lenient()
                    .when(eraIndex.findByGameIdAndEraNumber(eq(GAME_ID), anyInt()))
                    .thenAnswer(invocation ->
                            liveEventsByEra.getOrDefault(invocation.<Integer>getArgument(1), List.of()).stream()
                                    .map(id -> new IndexedEventId(id, 0))
                                    .toList());
        }
        liveEventsByEra.computeIfAbsent(era, _ -> new ArrayList<>()).add(eventId);
    }

    private FutureEvent resolvedEvent(UUID eventId, UUID winnerId) {
        return resolvedEvent(eventId, winnerId, ERA - 1);
    }

    private FutureEvent resolvedEvent(UUID eventId, UUID winnerId, int eraNumber) {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(winnerId, "winner", 60),
                new Outcome(first, "first", 25),
                new Outcome(second, "second", 15));
        return FutureEvent.replay(
                eventId,
                List.of(
                        new FutureEventDrafted(eventId, outcomes),
                        new OutcomeApplied(GAME_ID, eraNumber, eventId, winnerId, outcomes)));
    }

    private FutureEvent unresolvedEventWithOutcome(UUID eventId, UUID outcomeId) {
        var outcomes = List.of(
                new Outcome(outcomeId, "first", 34),
                new Outcome(UUID.randomUUID(), "second", 33),
                new Outcome(UUID.randomUUID(), "third", 33));
        return FutureEvent.replay(eventId, List.of(new FutureEventDrafted(eventId, outcomes)));
    }

    private FutureEvent sealedUnresolvedEventWithOutcome(UUID eventId, UUID outcomeId) {
        var outcomes = List.of(
                new Outcome(outcomeId, "first", 34),
                new Outcome(UUID.randomUUID(), "second", 33),
                new Outcome(UUID.randomUUID(), "third", 33));
        var event = FutureEvent.replay(eventId, List.of(new FutureEventDrafted(eventId, outcomes)));
        event.sealOutcome(outcomeId);
        return event;
    }

    private FutureEvent annihilatedUnresolvedEventWithOutcome(UUID eventId, UUID outcomeId) {
        var outcomes = List.of(
                new Outcome(outcomeId, "first", 34),
                new Outcome(UUID.randomUUID(), "second", 33),
                new Outcome(UUID.randomUUID(), "third", 33));
        var event = FutureEvent.replay(eventId, List.of(new FutureEventDrafted(eventId, outcomes)));
        event.annihilateOutcome(outcomeId);
        return event;
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
        public Optional<WeaverChainSagaState> findCompletedByGameAndPlayer(UUID gameId, UUID playerId) {
            return states.values().stream()
                    .filter(state -> state.gameId().equals(gameId)
                            && state.playerId().equals(playerId)
                            && state.status() == WeaverChainSagaStatus.COMPLETED)
                    .findFirst();
        }

        @Override
        public List<WeaverChainSagaState> findAllByGameAndPlayer(UUID gameId, UUID playerId) {
            return states.values().stream()
                    .filter(state ->
                            state.gameId().equals(gameId) && state.playerId().equals(playerId))
                    .toList();
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
