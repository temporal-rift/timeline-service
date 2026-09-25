package io.github.temporalrift.timeline.domain.weaverchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.event.ChainBroken;
import io.github.temporalrift.timeline.domain.event.ChainCompleted;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidated;
import io.github.temporalrift.timeline.domain.event.ChainLinkThreaded;
import io.github.temporalrift.timeline.domain.event.ChainReAnchored;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;

class WeaverChainTest {

    private static final UUID CHAIN_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID GAME_ID = UUID.randomUUID();

    @Test
    void replay_chainLinkAddedWithMismatchedOutcome_throwsIllegalState() {
        var eventId = UUID.randomUUID();
        var pendingOutcomeId = UUID.randomUUID();
        var wrongOutcomeId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkThreaded(CHAIN_ID, eventId, pendingOutcomeId, 1),
                new ChainLinkAdded(CHAIN_ID, eventId, wrongOutcomeId, 1));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_chainLinkInvalidatedWithMismatchedOutcome_throwsIllegalState() {
        var eventId = UUID.randomUUID();
        var pendingOutcomeId = UUID.randomUUID();
        var wrongOutcomeId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkThreaded(CHAIN_ID, eventId, pendingOutcomeId, 1),
                new ChainLinkInvalidated(CHAIN_ID, eventId, wrongOutcomeId));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_chainReAnchoredWithMismatchedDiscardedOutcome_throwsIllegalState() {
        var confirmedEventId = UUID.randomUUID();
        var confirmedOutcomeId = UUID.randomUUID();
        var wrongOutcomeId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkThreaded(CHAIN_ID, confirmedEventId, confirmedOutcomeId, 1),
                new ChainLinkAdded(CHAIN_ID, confirmedEventId, confirmedOutcomeId, 1),
                new io.github.temporalrift.timeline.domain.event.ChainReAnchored(
                        CHAIN_ID, confirmedEventId, wrongOutcomeId, UUID.randomUUID(), UUID.randomUUID(), 2));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_emptyHistory_throwsNotFound() {
        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, List.of()))
                .isInstanceOf(WeaverChainNotFoundException.class);
    }

    @Test
    void replay_startedOnly_buildsEmptyActiveChain() {
        var chain = WeaverChain.replay(CHAIN_ID, List.of(new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID)));

        assertThat(chain.length()).isZero();
        assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
        assertThat(chain.playerId()).isEqualTo(PLAYER_ID);
        assertThat(chain.gameId()).isEqualTo(GAME_ID);
        assertThat(chain.pendingLink()).isNull();
    }

    @Test
    void replay_linkBeforeStarted_throwsIllegalState() {
        var history = List.of(new ChainLinkThreaded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 1));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_secondStarted_throwsIllegalState() {
        var started = new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID);
        var history = List.of(started, started);

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_threeConfirmedLinksWithoutTerminal_derivesCompleted() {
        var replayed = WeaverChain.replay(CHAIN_ID, threeConfirmedLinkHistory());

        assertThat(replayed.length()).isEqualTo(3);
        assertThat(replayed.status()).isEqualTo(ChainStatus.COMPLETED);
    }

    @Test
    void replay_repeatedChainCompleted_isIdempotentEcho() {
        var history = new java.util.ArrayList<>(threeConfirmedLinkHistory());
        history.add(new ChainCompleted(CHAIN_ID));
        history.add(new ChainCompleted(CHAIN_ID));

        var chain = WeaverChain.replay(CHAIN_ID, history);

        assertThat(chain.length()).isEqualTo(3);
        assertThat(chain.status()).isEqualTo(ChainStatus.COMPLETED);
    }

    @Test
    void replay_chainBrokenAfterCompleted_throwsIllegalState() {
        var history = new java.util.ArrayList<>(threeConfirmedLinkHistory());
        history.add(new ChainCompleted(CHAIN_ID));
        history.add(new ChainBroken(CHAIN_ID, "reason"));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_duplicateEventLink_throwsIllegalState() {
        var eventId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkThreaded(CHAIN_ID, eventId, UUID.randomUUID(), 1),
                new ChainLinkAdded(CHAIN_ID, eventId, UUID.randomUUID(), 1),
                new ChainLinkThreaded(CHAIN_ID, eventId, UUID.randomUUID(), 2));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_completionBelowThreeLinks_throwsIllegalState() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkThreaded(CHAIN_ID, eventId, outcomeId, 1),
                new ChainLinkAdded(CHAIN_ID, eventId, outcomeId, 1),
                new ChainCompleted(CHAIN_ID));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_eventAfterTerminal_throwsIllegalState() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkThreaded(CHAIN_ID, eventId, outcomeId, 1),
                new ChainLinkAdded(CHAIN_ID, eventId, outcomeId, 1),
                new ChainBroken(CHAIN_ID, "reason"),
                new ChainLinkThreaded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    private static List<io.github.temporalrift.timeline.domain.event.WeaverChainEvent> threeConfirmedLinkHistory() {
        var history = new java.util.ArrayList<io.github.temporalrift.timeline.domain.event.WeaverChainEvent>();
        history.add(new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID));
        for (int era = 1; era <= 3; era++) {
            var eventId = UUID.randomUUID();
            var outcomeId = UUID.randomUUID();
            history.add(new ChainLinkThreaded(CHAIN_ID, eventId, outcomeId, era));
            history.add(new ChainLinkAdded(CHAIN_ID, eventId, outcomeId, era));
        }
        return history;
    }

    @Test
    void threadPendingLink_currentEraOutcome_opensPendingLink() {
        var chain = started();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        var fact = chain.threadPendingLink(eventId, outcomeId, 1);

        assertThat(fact).isEqualTo(new ChainLinkThreaded(CHAIN_ID, eventId, outcomeId, 1));
        assertThat(chain.pendingLink()).isEqualTo(new ChainLink(eventId, outcomeId, 1));
        assertThat(chain.length()).isZero();
        assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
    }

    @Test
    void threadPendingLink_alreadyPending_rejected() {
        var chain = started();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 1);
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        assertThatThrownBy(() -> chain.threadPendingLink(eventId, outcomeId, 1))
                .isInstanceOf(InvalidChainLinkException.class);
    }

    @Test
    void threadPendingLink_eventAlreadyConfirmedLinked_rejected() {
        var chain = started();
        var eventId = UUID.randomUUID();
        chain.threadPendingLink(eventId, UUID.randomUUID(), 1);
        chain.confirmPendingLink();
        var retryOutcomeId = UUID.randomUUID();

        assertThatThrownBy(() -> chain.threadPendingLink(eventId, retryOutcomeId, 2))
                .isInstanceOf(InvalidChainLinkException.class);
    }

    @Test
    void threadPendingLink_afterCompleted_throws() {
        var chain = completedChain();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        assertThatThrownBy(() -> chain.threadPendingLink(eventId, outcomeId, 4))
                .isInstanceOf(WeaverChainCompletedException.class);
    }

    @Test
    void threadPendingLink_afterBroken_throws() {
        var chain = started();
        chain.breakChain("CHAIN_CONFLICT paradox cascaded unresolved");
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        assertThatThrownBy(() -> chain.threadPendingLink(eventId, outcomeId, 2))
                .isInstanceOf(WeaverChainBrokenException.class);
    }

    @Test
    void confirmPendingLink_noPending_throws() {
        var chain = started();

        assertThatThrownBy(chain::confirmPendingLink).isInstanceOf(InvalidChainLinkException.class);
    }

    @Test
    void confirmPendingLink_growsChainAndClearsPending() {
        var chain = started();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        chain.threadPendingLink(eventId, outcomeId, 1);

        var facts = chain.confirmPendingLink();

        assertThat(facts).containsExactly(new ChainLinkAdded(CHAIN_ID, eventId, outcomeId, 1));
        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.pendingLink()).isNull();
        assertThat(chain.links()).containsExactly(new ChainLink(eventId, outcomeId, 1));
        assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
    }

    @Test
    void confirmPendingLink_thirdLink_completesChain() {
        var chain = started();
        for (int era = 1; era <= 3; era++) {
            var eventId = UUID.randomUUID();
            var outcomeId = UUID.randomUUID();
            chain.threadPendingLink(eventId, outcomeId, era);
            var facts = chain.confirmPendingLink();
            if (era < 3) {
                assertThat(facts).hasSize(1);
                assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
            } else {
                assertThat(facts)
                        .containsExactly(
                                new ChainLinkAdded(CHAIN_ID, eventId, outcomeId, era), new ChainCompleted(CHAIN_ID));
                assertThat(chain.status()).isEqualTo(ChainStatus.COMPLETED);
            }
        }
        assertThat(chain.length()).isEqualTo(3);
    }

    @Test
    void clearPendingLink_noPending_throws() {
        var chain = started();

        assertThatThrownBy(chain::clearPendingLink).isInstanceOf(InvalidChainLinkException.class);
    }

    @Test
    void clearPendingLink_clearsWithoutPenalty() {
        var chain = started();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        chain.threadPendingLink(eventId, outcomeId, 1);

        var fact = chain.clearPendingLink();

        assertThat(fact).isEqualTo(new ChainLinkInvalidated(CHAIN_ID, eventId, outcomeId));
        assertThat(chain.pendingLink()).isNull();
        assertThat(chain.length()).isZero();
        assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
    }

    @Test
    void clearPendingLink_afterConfirmedLinksExist_leavesThemUntouched() {
        var chain = started();
        var firstEventId = UUID.randomUUID();
        chain.threadPendingLink(firstEventId, UUID.randomUUID(), 1);
        chain.confirmPendingLink();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 2);

        chain.clearPendingLink();

        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.links().getFirst().eventId()).isEqualTo(firstEventId);
    }

    @Test
    void reAnchor_targetEventAlreadyLinkedWithADifferentOutcome_rejected() {
        var chain = started();
        var linkedEventId = UUID.randomUUID();
        var linkedOutcomeId = UUID.randomUUID();
        chain.threadPendingLink(linkedEventId, linkedOutcomeId, 1);
        chain.confirmPendingLink();
        var secondEventId = UUID.randomUUID();
        chain.threadPendingLink(secondEventId, UUID.randomUUID(), 2);
        chain.confirmPendingLink();
        // An event resolves to exactly one outcome, so a caller can never legitimately claim another outcome of
        // an already-linked event; the aggregate must reject it without relying on the saga's pre-check.
        var claimedResolved = new ResolvedOutcome(linkedEventId, UUID.randomUUID(), 3);

        assertThatThrownBy(() -> chain.reAnchor(claimedResolved)).isInstanceOf(InvalidChainLinkException.class);
        assertThat(chain.length()).isEqualTo(2);
    }

    @Test
    void reAnchor_noLinksAtAll_rejected() {
        var chain = started();
        var target = new ResolvedOutcome(UUID.randomUUID(), UUID.randomUUID(), 1);

        assertThatThrownBy(() -> chain.reAnchor(target)).isInstanceOf(InvalidChainLinkException.class);
    }

    @Test
    void reAnchor_discardsPendingLink_replacesWithConfirmedTarget() {
        var chain = started();
        var pendingEventId = UUID.randomUUID();
        var pendingOutcomeId = UUID.randomUUID();
        chain.threadPendingLink(pendingEventId, pendingOutcomeId, 1);
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();

        var facts = chain.reAnchor(new ResolvedOutcome(targetEventId, targetOutcomeId, 1));
        var fact = (ChainReAnchored) facts.getFirst();

        assertThat(fact.discardedEventId()).isEqualTo(pendingEventId);
        assertThat(fact.discardedOutcomeId()).isEqualTo(pendingOutcomeId);
        assertThat(chain.pendingLink()).isNull();
        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.links()).containsExactly(new ChainLink(targetEventId, targetOutcomeId, 1));
        assertThat(facts).hasSize(1);
    }

    @Test
    void reAnchor_pendingThirdLink_emitsCompletionInSameTransitionOnlyOnce() {
        var chain = started();
        for (var era : List.of(1, 2)) {
            chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), era);
            chain.confirmPendingLink();
        }
        var pendingEventId = UUID.randomUUID();
        chain.threadPendingLink(pendingEventId, UUID.randomUUID(), 4);
        var facts = chain.reAnchor(new ResolvedOutcome(UUID.randomUUID(), UUID.randomUUID(), 4));

        assertThat(facts).hasSize(2);
        assertThat(facts.getFirst()).isInstanceOf(ChainReAnchored.class);
        assertThat(facts.getLast()).isEqualTo(new ChainCompleted(CHAIN_ID));
        assertThat(chain.status()).isEqualTo(ChainStatus.COMPLETED);
        assertThat(chain.length()).isEqualTo(3);
    }

    @Test
    void reAnchor_discardsConfirmedNewestLink_lengthUnchanged() {
        var chain = started();
        var firstEventId = UUID.randomUUID();
        var firstOutcomeId = UUID.randomUUID();
        chain.threadPendingLink(firstEventId, firstOutcomeId, 1);
        chain.confirmPendingLink();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();

        var facts = chain.reAnchor(new ResolvedOutcome(targetEventId, targetOutcomeId, 2));
        var fact = (ChainReAnchored) facts.getFirst();

        assertThat(fact.discardedEventId()).isEqualTo(firstEventId);
        assertThat(fact.discardedOutcomeId()).isEqualTo(firstOutcomeId);
        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.links()).containsExactly(new ChainLink(targetEventId, targetOutcomeId, 2));
        assertThat(facts).hasSize(1);
    }

    @Test
    void threadPendingLink_sameEraAsNewestConfirmedLink_rejected() {
        var chain = started();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 2);
        chain.confirmPendingLink();

        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        assertThatThrownBy(() -> chain.threadPendingLink(eventId, outcomeId, 2))
                .isInstanceOf(LinkEraNotSuccessiveException.class);
        assertThat(chain.pendingLink()).isNull();
        assertThat(chain.length()).isEqualTo(1);
    }

    @Test
    void threadPendingLink_laterEraThanNewestConfirmedLink_accepted() {
        var chain = started();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 2);
        chain.confirmPendingLink();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        chain.threadPendingLink(eventId, outcomeId, 3);

        assertThat(chain.pendingLink()).isEqualTo(new ChainLink(eventId, outcomeId, 3));
    }

    @Test
    void confirmPendingLink_unrelatedEventsInSuccessiveEras_completesChain() {
        var chain = started();
        for (var era : List.of(1, 2, 4)) {
            chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), era);
            chain.confirmPendingLink();
        }

        assertThat(chain.status()).isEqualTo(ChainStatus.COMPLETED);
        assertThat(chain.links()).extracting(ChainLink::eraNumber).containsExactly(1, 2, 4);
    }

    @Test
    void reAnchor_confirmedNewestLinkToEraNotAfterPrecedingLink_rejected() {
        var chain = started();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 1);
        chain.confirmPendingLink();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 3);
        chain.confirmPendingLink();
        var before = chain.links();

        var target = new ResolvedOutcome(UUID.randomUUID(), UUID.randomUUID(), 1);

        assertThatThrownBy(() -> chain.reAnchor(target)).isInstanceOf(LinkEraNotSuccessiveException.class);
        assertThat(chain.links()).isEqualTo(before);
    }

    @Test
    void reAnchor_confirmedNewestLinkToEraAfterPrecedingLink_keepsTargetEra() {
        var chain = started();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 1);
        chain.confirmPendingLink();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 3);
        chain.confirmPendingLink();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();

        chain.reAnchor(new ResolvedOutcome(targetEventId, targetOutcomeId, 2));

        assertThat(chain.links().getLast()).isEqualTo(new ChainLink(targetEventId, targetOutcomeId, 2));
    }

    @Test
    void reAnchor_pendingLinkToEraOfNewestConfirmedLink_rejected() {
        var chain = started();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 2);
        chain.confirmPendingLink();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 3);

        var target = new ResolvedOutcome(UUID.randomUUID(), UUID.randomUUID(), 2);

        assertThatThrownBy(() -> chain.reAnchor(target)).isInstanceOf(LinkEraNotSuccessiveException.class);
        assertThat(chain.pendingLink()).isNotNull();
    }

    @Test
    void replay_threadedLinkNotAfterNewestConfirmedLink_throwsIllegalState() {
        var firstEventId = UUID.randomUUID();
        var firstOutcomeId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkThreaded(CHAIN_ID, firstEventId, firstOutcomeId, 2),
                new ChainLinkAdded(CHAIN_ID, firstEventId, firstOutcomeId, 2),
                new ChainLinkThreaded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_reAnchoredLinkNotAfterPrecedingLink_throwsIllegalState() {
        var firstEventId = UUID.randomUUID();
        var firstOutcomeId = UUID.randomUUID();
        var secondEventId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkThreaded(CHAIN_ID, firstEventId, firstOutcomeId, 2),
                new ChainLinkAdded(CHAIN_ID, firstEventId, firstOutcomeId, 2),
                new ChainLinkThreaded(CHAIN_ID, secondEventId, secondOutcomeId, 3),
                new io.github.temporalrift.timeline.domain.event.ChainReAnchored(
                        CHAIN_ID, secondEventId, secondOutcomeId, UUID.randomUUID(), UUID.randomUUID(), 1));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void breakChain_activeChain_marksBrokenPreservesLinksAndClearsPending() {
        var chain = started();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        chain.threadPendingLink(eventId, outcomeId, 1);
        chain.confirmPendingLink();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 2);

        var broken = chain.breakChain("CHAIN_CONFLICT paradox cascaded unresolved");

        assertThat(broken).isEqualTo(new ChainBroken(CHAIN_ID, "CHAIN_CONFLICT paradox cascaded unresolved"));
        assertThat(chain.status()).isEqualTo(ChainStatus.BROKEN);
        assertThat(chain.links()).containsExactly(new ChainLink(eventId, outcomeId, 1));
        assertThat(chain.pendingLink()).isNull();
    }

    @Test
    void breakChain_completedChain_throws() {
        var chain = completedChain();

        assertThatThrownBy(() -> chain.breakChain("reason")).isInstanceOf(WeaverChainCompletedException.class);
    }

    @Test
    void breakChain_brokenChain_throws() {
        var chain = started();
        chain.breakChain("reason");

        assertThatThrownBy(() -> chain.breakChain("reason")).isInstanceOf(WeaverChainBrokenException.class);
    }

    @Test
    void replay_fullHistory_rebuildsCompletedChain() {
        var chain = started();
        var firstEvent = UUID.randomUUID();
        var firstOutcome = UUID.randomUUID();
        chain.threadPendingLink(firstEvent, firstOutcome, 1);
        chain.confirmPendingLink();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 2);
        chain.confirmPendingLink();
        chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), 3);
        chain.confirmPendingLink();

        var replayed = WeaverChain.replay(CHAIN_ID, streamOf(chain));

        assertThat(replayed.length()).isEqualTo(3);
        assertThat(replayed.status()).isEqualTo(ChainStatus.COMPLETED);
        assertThat(replayed.links().getFirst()).isEqualTo(new ChainLink(firstEvent, firstOutcome, 1));
    }

    @Test
    void restore_snapshotPlusTail_matchesFullReplay() {
        var chain = started();
        var firstEvent = UUID.randomUUID();
        var firstOutcome = UUID.randomUUID();
        chain.threadPendingLink(firstEvent, firstOutcome, 1);
        chain.confirmPendingLink();
        var secondEvent = UUID.randomUUID();
        var secondOutcome = UUID.randomUUID();
        chain.threadPendingLink(secondEvent, secondOutcome, 2);
        chain.confirmPendingLink();
        var fromHistory = WeaverChain.replay(CHAIN_ID, streamOf(chain));

        var snapshot = new WeaverChainSnapshot(
                CHAIN_ID,
                PLAYER_ID,
                GAME_ID,
                List.of(new ChainLink(firstEvent, firstOutcome, 1)),
                null,
                ChainStatus.ACTIVE);
        var fromSnapshot = WeaverChain.restore(
                snapshot,
                List.of(
                        new ChainLinkThreaded(CHAIN_ID, secondEvent, secondOutcome, 2),
                        new ChainLinkAdded(CHAIN_ID, secondEvent, secondOutcome, 2)));

        assertThat(fromSnapshot.snapshot()).isEqualTo(fromHistory.snapshot());
    }

    @Test
    void restore_tailFromDifferentChain_throwsIllegalState() {
        var snapshot = new WeaverChainSnapshot(CHAIN_ID, PLAYER_ID, GAME_ID, List.of(), null, ChainStatus.ACTIVE);
        var tail = List.of(new ChainLinkThreaded(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1));

        assertThatThrownBy(() -> WeaverChain.restore(snapshot, tail)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restore_tailWithStarted_throws() {
        var snapshot = new WeaverChainSnapshot(CHAIN_ID, PLAYER_ID, GAME_ID, List.of(), null, ChainStatus.ACTIVE);
        var tail = List.of(new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID));

        assertThatThrownBy(() -> WeaverChain.restore(snapshot, tail)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_startedForDifferentChain_throwsIllegalState() {
        var otherChainId = UUID.randomUUID();
        var history = List.of(new WeaverChainStarted(otherChainId, PLAYER_ID, GAME_ID));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_linkFromDifferentChain_throwsIllegalState() {
        var otherChainId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkThreaded(otherChainId, UUID.randomUUID(), UUID.randomUUID(), 1));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    private static WeaverChain started() {
        return WeaverChain.replay(CHAIN_ID, List.of(new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID)));
    }

    private static void completeWithThreeLinks(WeaverChain chain) {
        completeWithThreeLinks(chain, 1);
    }

    private static void completeWithThreeLinks(WeaverChain chain, int startEra) {
        for (int era = startEra; era < startEra + 3; era++) {
            chain.threadPendingLink(UUID.randomUUID(), UUID.randomUUID(), era);
            chain.confirmPendingLink();
        }
    }

    private static WeaverChain completedChain() {
        var chain = started();
        completeWithThreeLinks(chain);
        return chain;
    }

    /** Rebuilds this chain's fact stream by replaying a fresh chain against the same THREAD/confirm sequence. */
    private static List<io.github.temporalrift.timeline.domain.event.WeaverChainEvent> streamOf(WeaverChain chain) {
        var rebuilt = new java.util.ArrayList<io.github.temporalrift.timeline.domain.event.WeaverChainEvent>();
        rebuilt.add(new WeaverChainStarted(chain.chainId(), chain.playerId(), chain.gameId()));
        for (var link : chain.links()) {
            rebuilt.add(new ChainLinkThreaded(chain.chainId(), link.eventId(), link.outcomeId(), link.eraNumber()));
            rebuilt.add(new ChainLinkAdded(chain.chainId(), link.eventId(), link.outcomeId(), link.eraNumber()));
        }
        if (chain.status() == ChainStatus.COMPLETED) {
            rebuilt.add(new ChainCompleted(chain.chainId()));
        }
        return rebuilt;
    }
}
