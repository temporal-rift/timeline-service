package io.github.temporalrift.timeline.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
    void reweave_discardsPendingLink_replacesWithConfirmedTarget() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();
        var replacementEvent = UUID.randomUUID();
        var replacementOutcome = UUID.randomUUID();
        given(futureEvents.findById(replacementEvent)).willReturn(resolvedEvent(replacementEvent, replacementOutcome));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, replacementEvent, replacementOutcome);

        var chain = chains.findById(chainId);
        assertThat(chain.pendingLink()).isNull();
        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.links().getFirst().eventId())
                .isEqualTo(replacementEvent)
                .isNotEqualTo(pending.eventId());
        var reAnchored = published(ChainReAnchoredEvent.class);
        assertThat(reAnchored.discardedEventId()).isEqualTo(pending.eventId());
    }

    @Test
    void reweave_success_replacesNewestConfirmedLinkAndKeepsLength() {
        var chainId = openChainWithConfirmedLinks(2);
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        var chain = chains.findById(chainId);
        assertThat(chain.length()).isEqualTo(2);
        assertThat(chain.links().getLast().eventId()).isEqualTo(targetEvent);
        assertThat(chain.links().getLast().outcomeId()).isEqualTo(targetOutcome);
        assertThat(chain.links().getLast().eraNumber()).isEqualTo(ERA - 1);
        var reAnchored = published(ChainReAnchoredEvent.class);
        assertThat(reAnchored.chainLength()).isEqualTo(2);
        assertThat(reAnchored.linkedEventId()).isEqualTo(targetEvent);
    }

    @Test
    void reweave_alreadyUsedThisEra_rejectedWithoutChange() {
        var chainId = openChainWithConfirmedLinks(2);
        var firstTarget = UUID.randomUUID();
        var firstOutcome = UUID.randomUUID();
        given(futureEvents.findById(firstTarget)).willReturn(resolvedEvent(firstTarget, firstOutcome));
        saga.playReweave(GAME_ID, ERA, PLAYER_ID, firstTarget, firstOutcome);
        var secondTarget = UUID.randomUUID();
        var secondOutcome = UUID.randomUUID();

        // Rejected purely on the era-budget check — never even looks up the second target.
        saga.playReweave(GAME_ID, ERA, PLAYER_ID, secondTarget, secondOutcome);

        var chain = chains.findById(chainId);
        assertThat(chain.links().getLast().eventId()).isEqualTo(firstTarget);
        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("ALREADY_USED_THIS_ERA");
    }

    @Test
    void reweave_usedInAnEarlierEra_succeedsAgainInALaterEra() {
        var chainId = openChainWithConfirmedLinks(2);
        var firstTarget = UUID.randomUUID();
        var firstOutcome = UUID.randomUUID();
        given(futureEvents.findById(firstTarget)).willReturn(resolvedEvent(firstTarget, firstOutcome));
        saga.playReweave(GAME_ID, ERA, PLAYER_ID, firstTarget, firstOutcome);
        var secondTarget = UUID.randomUUID();
        var secondOutcome = UUID.randomUUID();
        given(futureEvents.findById(secondTarget)).willReturn(resolvedEvent(secondTarget, secondOutcome));

        saga.playReweave(GAME_ID, ERA + 1, PLAYER_ID, secondTarget, secondOutcome);

        var chain = chains.findById(chainId);
        assertThat(chain.links().getLast().eventId()).isEqualTo(secondTarget);
        published(ChainReAnchoredEvent.class);
    }

    @Test
    void reweave_rejectedAttempt_doesNotCountTowardTheEraLimit() {
        var chainId = openChainWithConfirmedLinks(1);

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, null, null);
        var target = UUID.randomUUID();
        var outcome = UUID.randomUUID();
        given(futureEvents.findById(target)).willReturn(resolvedEvent(target, outcome));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, target, outcome);

        assertThat(chains.findById(chainId).links().getLast().eventId()).isEqualTo(target);
        published(ChainReAnchoredEvent.class);
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
    void reweave_missingTargetCoordinate_rejected() {
        openChainWithConfirmedLinks(1);

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, null, null);

        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("MISSING_COORDINATE");
    }

    @Test
    void reweave_targetNotResolved_rejectedWithoutChange() {
        var chainId = openChainWithConfirmedLinks(1);
        var targetEvent = UUID.randomUUID();
        var actualWinner = UUID.randomUUID();
        var claimedOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, actualWinner));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, targetEvent, claimedOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("TARGET_NOT_RESOLVED");
    }

    @Test
    void reweave_targetAlreadyLinked_rejectedWithoutChange() {
        var chainId = openChainWithConfirmedLinks(1);
        var existingLink = chains.findById(chainId).links().getFirst();
        given(futureEvents.findById(existingLink.eventId()))
                .willReturn(resolvedEvent(existingLink.eventId(), existingLink.outcomeId()));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, existingLink.eventId(), existingLink.outcomeId());

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("TARGET_ALREADY_LINKED");
    }

    @Test
    void reweave_targetIsDrawnLowProbabilityWinner_accepted() {
        var chainId = openChainWithConfirmedLinks(1);
        var targetEvent = UUID.randomUUID();
        var drawnWinner = UUID.randomUUID();
        var favourite = UUID.randomUUID();
        given(futureEvents.findById(targetEvent))
                .willReturn(resolvedEventWithUnderdogWinner(targetEvent, drawnWinner, favourite));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, targetEvent, drawnWinner);

        assertThat(chains.findById(chainId).links().getLast().outcomeId()).isEqualTo(drawnWinner);
        published(ChainReAnchoredEvent.class);
    }

    @Test
    void reweave_targetIsHighestProbabilityLoser_rejectedAsNotResolved() {
        var chainId = openChainWithConfirmedLinks(1);
        var targetEvent = UUID.randomUUID();
        var drawnWinner = UUID.randomUUID();
        var favourite = UUID.randomUUID();
        given(futureEvents.findById(targetEvent))
                .willReturn(resolvedEventWithUnderdogWinner(targetEvent, drawnWinner, favourite));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, targetEvent, favourite);

        assertThat(chains.findById(chainId).links().getLast().eventId()).isNotEqualTo(targetEvent);
        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("TARGET_NOT_RESOLVED");
    }

    @Test
    void reweave_targetEraNotAfterPrecedingLink_rejectedWithoutChange() {
        var chainId = openChainWithConfirmedLinks(2);
        var before = chains.findById(chainId).links();
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome, 1));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        assertThat(chains.findById(chainId).links()).isEqualTo(before);
        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("LINK_ERA_NOT_SUCCESSIVE");
        assertThat(sagas.findByChainId(chainId).orElseThrow().reweaveUsedEra()).isNull();
    }

    @Test
    void reweave_chainWithNoLinks_rejectedAsNoLinkToReplace() {
        var chainId = openChainWithPendingLink();
        var pending = chains.findById(chainId).pendingLink();
        saga.resolvePendingLink(GAME_ID, ERA, pending.eventId(), UUID.randomUUID());
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("NO_LINK_TO_REPLACE");
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

    /** A resolved event whose weighted draw picked its least likely outcome over the favourite. */
    private FutureEvent resolvedEventWithUnderdogWinner(UUID eventId, UUID winnerId, UUID favouriteId) {
        var outcomes = List.of(
                new Outcome(favouriteId, "favourite", 70),
                new Outcome(UUID.randomUUID(), "middle", 20),
                new Outcome(winnerId, "underdog", 10));
        return FutureEvent.replay(
                eventId,
                List.of(
                        new FutureEventDrafted(eventId, outcomes),
                        new OutcomeApplied(GAME_ID, ERA - 1, eventId, winnerId, outcomes)));
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
