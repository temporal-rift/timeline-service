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

import io.github.temporalrift.timeline.domain.event.ChainCompletedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.ChainLinkAddedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidatedEvent;
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
    private static final int ERA = 2;

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

    private record SourceCoordinate(UUID eventId, UUID outcomeId) {}

    /** Stubs a current-era, unresolved source outcome — the precondition every THREAD test needs to reach. */
    private SourceCoordinate stubValidSource() {
        var sourceEvent = UUID.randomUUID();
        var sourceOutcome = UUID.randomUUID();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA)).willReturn(List.of(new IndexedEventId(sourceEvent, 0)));
        given(futureEvents.findById(sourceEvent)).willReturn(unresolvedEventWithOutcome(sourceEvent, sourceOutcome));
        return new SourceCoordinate(sourceEvent, sourceOutcome);
    }

    private void stubThreadRewardRules() {
        given(probabilityRules.threadShift()).willReturn(10);
        given(probabilityRules.probabilityFloor()).willReturn(0);
        given(probabilityRules.probabilityCeiling()).willReturn(90);
    }

    @Test
    void thread_firstPlay_opensChainAndAddsFirstLink() {
        var source = stubValidSource();
        stubThreadRewardRules();
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, source.eventId(), source.outcomeId(), targetEvent, targetOutcome);

        var state = sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID).orElseThrow();
        var chain = chains.findById(state.chainId());
        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.links().getFirst().sourceEventId()).isEqualTo(source.eventId());
        assertThat(chain.links().getFirst().sourceOutcomeId()).isEqualTo(source.outcomeId());
        var added = published(ChainLinkAddedEvent.class);
        assertThat(added.chainLength()).isEqualTo(1);
        assertThat(added.previousLinkEventId()).isNull();
        assertThat(added.sourceEventId()).isEqualTo(source.eventId());
    }

    @Test
    void thread_acceptedLink_appliesConfiguredShiftToSourceOutcome() {
        var source = stubValidSource();
        stubThreadRewardRules();
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, source.eventId(), source.outcomeId(), targetEvent, targetOutcome);

        then(futureEvents).should().append(eq(source.eventId()), any());
    }

    @Test
    void thread_sealedSource_linkStillAccepted_probabilityUnmoved() {
        var sourceEvent = UUID.randomUUID();
        var sourceOutcome = UUID.randomUUID();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA)).willReturn(List.of(new IndexedEventId(sourceEvent, 0)));
        var sourceFutureEvent = sealedUnresolvedEventWithOutcome(sourceEvent, sourceOutcome);
        given(futureEvents.findById(sourceEvent)).willReturn(sourceFutureEvent);
        stubThreadRewardRules();
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, sourceEvent, sourceOutcome, targetEvent, targetOutcome);

        // The link is accepted regardless — only the reward's probability movement is blocked by the seal.
        var state = sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID).orElseThrow();
        assertThat(chains.findById(state.chainId()).length()).isEqualTo(1);
        assertThat(sourceFutureEvent.sealBreach()).isTrue();
        assertThat(sourceFutureEvent.outcomes().getFirst().probability()).isEqualTo(34);
    }

    @Test
    void thread_thirdValidLink_completesChainAndEndsSaga() {
        var chainId = openChainWithLinks(2);
        var source = stubValidSource();
        stubThreadRewardRules();
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, source.eventId(), source.outcomeId(), targetEvent, targetOutcome);

        var chain = chains.findById(chainId);
        assertThat(chain.length()).isEqualTo(3);
        published(ChainCompletedEvent.class);
        assertThat(sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID)).isEmpty();
        assertThat(sagas.findByChainId(chainId).orElseThrow().status()).isEqualTo(WeaverChainSagaStatus.COMPLETED);
    }

    @Test
    void thread_secondValidLink_staysOpenWithoutTerminalEvent() {
        var chainId = openChainWithLinks(1);
        var source = stubValidSource();
        stubThreadRewardRules();
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, source.eventId(), source.outcomeId(), targetEvent, targetOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(2);
        assertThat(sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID)).isPresent();
        publishedNever(ChainCompletedEvent.class);
    }

    @Test
    void thread_missingSourceCoordinate_rejectedWithoutGrowth() {
        openChainWithLinks(1);
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();

        saga.playThread(GAME_ID, ERA, PLAYER_ID, null, null, targetEvent, targetOutcome);

        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("MISSING_COORDINATE");
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void thread_sourceNotInCurrentEra_rejectedWithSourceReasonDistinctFromTargetReason() {
        openChainWithLinks(1);
        var sourceEvent = UUID.randomUUID();
        var sourceOutcome = UUID.randomUUID();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA)).willReturn(List.of());
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();

        saga.playThread(GAME_ID, ERA, PLAYER_ID, sourceEvent, sourceOutcome, targetEvent, targetOutcome);

        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("INVALID_SOURCE").isNotEqualTo("OUTCOME_DID_NOT_RESOLVE");
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void thread_sourceAlreadyResolved_rejected() {
        openChainWithLinks(1);
        var sourceEvent = UUID.randomUUID();
        var sourceOutcome = UUID.randomUUID();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA)).willReturn(List.of(new IndexedEventId(sourceEvent, 0)));
        given(futureEvents.findById(sourceEvent)).willReturn(resolvedEvent(sourceEvent, sourceOutcome));
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();

        saga.playThread(GAME_ID, ERA, PLAYER_ID, sourceEvent, sourceOutcome, targetEvent, targetOutcome);

        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("INVALID_SOURCE");
    }

    @Test
    void thread_unresolvedOutcome_rejectedPrivatelyWithoutGrowth() {
        var chainId = openChainWithLinks(1);
        var source = stubValidSource();
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(unresolvedEvent(targetEvent));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, source.eventId(), source.outcomeId(), targetEvent, targetOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.playerId()).isEqualTo(PLAYER_ID);
        assertThat(rejected.reason()).isEqualTo("OUTCOME_DID_NOT_RESOLVE");
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void thread_resolvedDifferently_rejectedWithoutGrowth() {
        var chainId = openChainWithLinks(1);
        var source = stubValidSource();
        var targetEvent = UUID.randomUUID();
        var actualWinner = UUID.randomUUID();
        var claimedOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, actualWinner));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, source.eventId(), source.outcomeId(), targetEvent, claimedOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        published(ThreadRejectedEvent.class);
    }

    @Test
    void thread_fullyAnnihilatedResolvedEvent_rejectedWithoutGrowth() {
        var chainId = openChainWithLinks(1);
        var source = stubValidSource();
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(fullyAnnihilatedEvent(targetEvent));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, source.eventId(), source.outcomeId(), targetEvent, targetOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("OUTCOME_DID_NOT_RESOLVE");
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void thread_invalidWithoutActiveChain_rejectedWithoutMaterializingAChain() {
        var source = stubValidSource();
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(unresolvedEvent(targetEvent));

        saga.playThread(GAME_ID, ERA, PLAYER_ID, source.eventId(), source.outcomeId(), targetEvent, targetOutcome);

        assertThat(sagas.findOpenByGameAndPlayer(GAME_ID, PLAYER_ID)).isEmpty();
        var rejected = published(ThreadRejectedEvent.class);
        assertThat(rejected.chainId()).isNull();
        assertThat(rejected.playerId()).isEqualTo(PLAYER_ID);
        publishedNever(ChainLinkAddedEvent.class);
    }

    @Test
    void reweave_recoversChainWhoseNewestLinkWasInvalidatedByAnErasure() {
        // An erasure shrinks the chain by removing its newest link; REWEAVE never grows a chain back — it
        // replaces whatever is now the newest link, giving the player a fresh anchor without spending a
        // new THREAD.
        var chainId = openChainWithLinks(2);
        var invalidatedLink = chains.findById(chainId).links().getLast();
        var survivingLink = chains.findById(chainId).links().getFirst();

        saga.annihilateOutcome(GAME_ID, ERA, invalidatedLink.eventId(), invalidatedLink.outcomeId());
        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        published(ChainLinkInvalidatedEvent.class);

        var replacementEvent = UUID.randomUUID();
        var replacementOutcome = UUID.randomUUID();
        given(futureEvents.findById(replacementEvent)).willReturn(resolvedEvent(replacementEvent, replacementOutcome));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, replacementEvent, replacementOutcome);

        var chain = chains.findById(chainId);
        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.links().getFirst().eventId())
                .isEqualTo(replacementEvent)
                .isNotEqualTo(survivingLink.eventId());
        published(ChainReAnchoredEvent.class);
    }

    @Test
    void reweave_success_replacesNewestLinkAndKeepsLength() {
        var chainId = openChainWithLinks(2);
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, targetEvent, targetOutcome);

        var chain = chains.findById(chainId);
        assertThat(chain.length()).isEqualTo(2);
        assertThat(chain.links().getLast().eventId()).isEqualTo(targetEvent);
        assertThat(chain.links().getLast().outcomeId()).isEqualTo(targetOutcome);
        var reAnchored = published(ChainReAnchoredEvent.class);
        assertThat(reAnchored.chainLength()).isEqualTo(2);
        assertThat(reAnchored.linkedEventId()).isEqualTo(targetEvent);
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
        openChainWithLinks(1);

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, null, null);

        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("MISSING_COORDINATE");
    }

    @Test
    void reweave_targetNotResolved_rejectedWithoutChange() {
        var chainId = openChainWithLinks(1);
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
        var chainId = openChainWithLinks(1);
        var existingLink = chains.findById(chainId).links().getFirst();
        given(futureEvents.findById(existingLink.eventId()))
                .willReturn(resolvedEvent(existingLink.eventId(), existingLink.outcomeId()));

        saga.playReweave(GAME_ID, ERA, PLAYER_ID, existingLink.eventId(), existingLink.outcomeId());

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("TARGET_ALREADY_LINKED");
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
        published(ChainProtectionArmedEvent.class);
        saga.annihilateOutcome(GAME_ID, ERA, linked.eventId(), linked.outcomeId());

        assertThat(chains.findById(chainId).length()).isEqualTo(2);
        var consumed = published(ChainProtectionConsumedEvent.class);
        assertThat(consumed.protectedEventId()).isEqualTo(linked.eventId());
        assertThat(sagas.findByChainId(chainId).orElseThrow().tapestryProtected())
                .isFalse();
    }

    @Test
    void tapestry_belowTwoLinks_rejected() {
        var chainId = openChainWithLinks(1);

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
        var chainId = openChainWithLinks(2);

        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);
        published(ChainProtectionArmedEvent.class);
        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);

        var rejected = published(SpecialRejectedEvent.class);
        assertThat(rejected.reason()).isEqualTo("ALREADY_USED_THIS_ERA");
        assertThat(chains.findById(chainId).length()).isEqualTo(2);
    }

    @Test
    void tapestry_unconsumedProtection_doesNotOutliveItsEra() {
        var chainId = openChainWithLinks(2);
        var linked = chains.findById(chainId).links().get(0);
        saga.playTapestry(GAME_ID, ERA, PLAYER_ID);

        // A later era begins; the protection was never consumed in the era it was armed for.
        saga.annihilateOutcome(GAME_ID, ERA + 1, linked.eventId(), linked.outcomeId());

        assertThat(chains.findById(chainId).length()).isEqualTo(1);
        var invalidated = published(ChainLinkInvalidatedEvent.class);
        assertThat(invalidated.invalidatedEventId()).isEqualTo(linked.eventId());
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
        var restarted = new WeaverChainSaga(chains, sagas, futureEvents, eraIndex, probabilityRules, publisher, clock);
        var source = stubValidSource();
        stubThreadRewardRules();
        var targetEvent = UUID.randomUUID();
        var targetOutcome = UUID.randomUUID();
        given(futureEvents.findById(targetEvent)).willReturn(resolvedEvent(targetEvent, targetOutcome));

        restarted.playThread(GAME_ID, ERA, PLAYER_ID, source.eventId(), source.outcomeId(), targetEvent, targetOutcome);

        assertThat(chains.findById(chainId).length()).isEqualTo(2);
        var added = published(ChainLinkAddedEvent.class);
        assertThat(added.chainId()).isEqualTo(chainId);
    }

    private UUID openChainWithLinks(int linkCount) {
        var chainId = UUID.randomUUID();
        var history = new ArrayList<WeaverChainEvent>();
        history.add(new WeaverChainStarted(chainId, PLAYER_ID, GAME_ID));
        for (int index = 0; index < linkCount; index++) {
            history.add(new ChainLinkAdded(
                    chainId, UUID.randomUUID(), UUID.randomUUID(), index + 1, UUID.randomUUID(), UUID.randomUUID()));
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
