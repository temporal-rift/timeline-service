package io.github.temporalrift.timeline.domain.weaverchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.event.ChainBroken;
import io.github.temporalrift.timeline.domain.event.ChainCompleted;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;

class WeaverChainTest {

    private static final UUID CHAIN_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID GAME_ID = UUID.randomUUID();

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
    }

    @Test
    void replay_linkBeforeStarted_throwsIllegalState() {
        var link = new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 1);

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, List.of(link))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_secondStarted_throwsIllegalState() {
        var started = new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID);

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, List.of(started, started)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_threeLinksWithoutTerminal_derivesCompleted() {
        var history = List.<Object>of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 1),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 3));

        var chain = WeaverChain.replay(CHAIN_ID, history);

        assertThat(chain.length()).isEqualTo(3);
        assertThat(chain.status()).isEqualTo(ChainStatus.COMPLETED);
    }

    @Test
    void addLink_zombieStreamWithThreeLinksAndNoTerminal_rejectsFourth() {
        var zombie = WeaverChain.replay(
                CHAIN_ID,
                List.<Object>of(
                        new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                        new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 1),
                        new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2),
                        new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 3)));
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        assertThatThrownBy(() -> zombie.addLink(eventId, outcomeId, 4, Set.of(new ResolvedOutcome(eventId, outcomeId))))
                .isInstanceOf(WeaverChainCompletedException.class);
        assertThat(zombie.length()).isEqualTo(3);
    }

    @Test
    void replay_repeatedChainCompleted_isIdempotentEcho() {
        var history = List.<Object>of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 1),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 3),
                new ChainCompleted(CHAIN_ID),
                new ChainCompleted(CHAIN_ID));

        var chain = WeaverChain.replay(CHAIN_ID, history);

        assertThat(chain.length()).isEqualTo(3);
        assertThat(chain.status()).isEqualTo(ChainStatus.COMPLETED);
    }

    @Test
    void replay_chainBrokenAfterCompleted_throwsIllegalState() {
        var history = List.<Object>of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 1),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 3),
                new ChainCompleted(CHAIN_ID),
                new ChainBroken(CHAIN_ID, "UNRAVEL"));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_eventAfterTerminal_throwsIllegalState() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var history = List.<Object>of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkAdded(CHAIN_ID, eventId, outcomeId, 1),
                new ChainBroken(CHAIN_ID, "UNRAVEL"),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addLink_resolvedOutcome_appendsAndGrows() {
        var chain = started();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        var facts = chain.addLink(eventId, outcomeId, 1, Set.of(new ResolvedOutcome(eventId, outcomeId)));

        assertThat(facts).containsExactly(new ChainLinkAdded(CHAIN_ID, eventId, outcomeId, 1));
        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
    }

    @Test
    void addLink_unresolvedOutcome_rejectedAndChainUnchanged() {
        var chain = started();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        assertThatThrownBy(() -> chain.addLink(eventId, outcomeId, 1, Set.of()))
                .isInstanceOf(InvalidChainLinkException.class);
        assertThat(chain.length()).isZero();
        assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
    }

    @Test
    void addLink_wrongOutcomeForResolvedEvent_rejected() {
        var chain = started();
        var eventId = UUID.randomUUID();

        assertThatThrownBy(() -> chain.addLink(
                        eventId, UUID.randomUUID(), 1, Set.of(new ResolvedOutcome(eventId, UUID.randomUUID()))))
                .isInstanceOf(InvalidChainLinkException.class);
        assertThat(chain.length()).isZero();
    }

    @Test
    void addLink_duplicateEvent_rejected() {
        var chain = started();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        chain.addLink(eventId, outcomeId, 1, Set.of(new ResolvedOutcome(eventId, outcomeId)));

        var duplicateOutcomeId = UUID.randomUUID();
        assertThatThrownBy(() -> chain.addLink(
                        eventId, duplicateOutcomeId, 2, Set.of(new ResolvedOutcome(eventId, duplicateOutcomeId))))
                .isInstanceOf(InvalidChainLinkException.class);
        assertThat(chain.length()).isEqualTo(1);
    }

    @Test
    void addLink_thirdLink_completesChain() {
        var chain = started();
        var resolved = new HashSet<ResolvedOutcome>();
        for (int era = 1; era <= 3; era++) {
            var eventId = UUID.randomUUID();
            var outcomeId = UUID.randomUUID();
            resolved.add(new ResolvedOutcome(eventId, outcomeId));
            var facts = chain.addLink(eventId, outcomeId, era, resolved);
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
    void addLink_afterCompleted_throws() {
        var chain = completedChain();

        assertThatThrownBy(() -> chain.addLink(UUID.randomUUID(), UUID.randomUUID(), 4, Set.of()))
                .isInstanceOf(WeaverChainCompletedException.class);
    }

    @Test
    void breakChain_activeChain_marksBrokenAndPreservesLinks() {
        var chain = started();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        chain.addLink(eventId, outcomeId, 1, Set.of(new ResolvedOutcome(eventId, outcomeId)));

        var broken = chain.breakChain("UNRAVEL");

        assertThat(broken).isEqualTo(new ChainBroken(CHAIN_ID, "UNRAVEL"));
        assertThat(chain.status()).isEqualTo(ChainStatus.BROKEN);
        assertThat(chain.links()).containsExactly(new ChainLink(eventId, outcomeId, 1));
    }

    @Test
    void addLink_afterBroken_throws() {
        var chain = started();
        chain.breakChain("UNRAVEL");

        assertThatThrownBy(() -> chain.addLink(UUID.randomUUID(), UUID.randomUUID(), 2, Set.of()))
                .isInstanceOf(WeaverChainBrokenException.class);
    }

    @Test
    void breakChain_completedChain_throws() {
        var chain = completedChain();

        assertThatThrownBy(() -> chain.breakChain("UNRAVEL")).isInstanceOf(WeaverChainCompletedException.class);
    }

    @Test
    void breakChain_brokenChain_throws() {
        var chain = started();
        chain.breakChain("UNRAVEL");

        assertThatThrownBy(() -> chain.breakChain("UNRAVEL")).isInstanceOf(WeaverChainBrokenException.class);
    }

    @Test
    void replay_fullHistory_rebuildsCompletedChain() {
        var firstEvent = UUID.randomUUID();
        var firstOutcome = UUID.randomUUID();
        var history = List.<Object>of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkAdded(CHAIN_ID, firstEvent, firstOutcome, 1),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 3),
                new ChainCompleted(CHAIN_ID));

        var chain = WeaverChain.replay(CHAIN_ID, history);

        assertThat(chain.length()).isEqualTo(3);
        assertThat(chain.status()).isEqualTo(ChainStatus.COMPLETED);
        assertThat(chain.links().getFirst()).isEqualTo(new ChainLink(firstEvent, firstOutcome, 1));
    }

    @Test
    void restore_snapshotPlusTail_matchesFullReplay() {
        var firstEvent = UUID.randomUUID();
        var firstOutcome = UUID.randomUUID();
        var secondEvent = UUID.randomUUID();
        var secondOutcome = UUID.randomUUID();
        var fullHistory = List.<Object>of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkAdded(CHAIN_ID, firstEvent, firstOutcome, 1),
                new ChainLinkAdded(CHAIN_ID, secondEvent, secondOutcome, 2));
        var fromHistory = WeaverChain.replay(CHAIN_ID, fullHistory);

        var snapshot = new WeaverChainSnapshot(
                CHAIN_ID, PLAYER_ID, GAME_ID, List.of(new ChainLink(firstEvent, firstOutcome, 1)), ChainStatus.ACTIVE);
        var fromSnapshot =
                WeaverChain.restore(snapshot, List.of(new ChainLinkAdded(CHAIN_ID, secondEvent, secondOutcome, 2)));

        assertThat(fromSnapshot.snapshot()).isEqualTo(fromHistory.snapshot());
    }

    @Test
    void replay_startedForDifferentChain_throwsIllegalState() {
        var otherChainId = UUID.randomUUID();

        assertThatThrownBy(() ->
                        WeaverChain.replay(CHAIN_ID, List.of(new WeaverChainStarted(otherChainId, PLAYER_ID, GAME_ID))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_linkFromDifferentChain_throwsIllegalState() {
        var otherChainId = UUID.randomUUID();
        var history = List.<Object>of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkAdded(otherChainId, UUID.randomUUID(), UUID.randomUUID(), 1));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restore_tailFromDifferentChain_throwsIllegalState() {
        var snapshot = new WeaverChainSnapshot(CHAIN_ID, PLAYER_ID, GAME_ID, List.of(), ChainStatus.ACTIVE);
        var tail = List.<Object>of(new ChainLinkAdded(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1));

        assertThatThrownBy(() -> WeaverChain.restore(snapshot, tail)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restore_tailWithStarted_throws() {
        var snapshot = new WeaverChainSnapshot(CHAIN_ID, PLAYER_ID, GAME_ID, List.of(), ChainStatus.ACTIVE);

        assertThatThrownBy(() ->
                        WeaverChain.restore(snapshot, List.of(new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static WeaverChain started() {
        return WeaverChain.replay(CHAIN_ID, List.of(new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID)));
    }

    private static WeaverChain completedChain() {
        var chain = started();
        var resolved = new HashSet<ResolvedOutcome>();
        for (int era = 1; era <= 3; era++) {
            var eventId = UUID.randomUUID();
            var outcomeId = UUID.randomUUID();
            resolved.add(new ResolvedOutcome(eventId, outcomeId));
            chain.addLink(eventId, outcomeId, era, resolved);
        }
        return chain;
    }
}
