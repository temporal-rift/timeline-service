package io.github.temporalrift.timeline.domain.weaverchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidated;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;

class WeaverChainInvalidationTest {

    private static final UUID CHAIN_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID GAME_ID = UUID.randomUUID();

    @Test
    void invalidateLink_removesMatchingLinkAndStaysActive() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var chain = WeaverChain.replay(
                CHAIN_ID,
                List.of(
                        new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                        new ChainLinkAdded(CHAIN_ID, eventId, outcomeId, 1),
                        new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2)));

        var invalidated = chain.invalidateLink(eventId, outcomeId);

        assertThat(invalidated).contains(new ChainLinkInvalidated(CHAIN_ID, eventId, outcomeId));
        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
    }

    @Test
    void invalidateLink_unknownOutcome_returnsEmptyAndKeepsChain() {
        var chain = WeaverChain.replay(
                CHAIN_ID,
                List.of(
                        new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                        new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 1)));

        var invalidated = chain.invalidateLink(UUID.randomUUID(), UUID.randomUUID());

        assertThat(invalidated).isEmpty();
        assertThat(chain.length()).isEqualTo(1);
    }

    @Test
    void invalidateLink_afterCompletion_returnsEmpty() {
        var chain = WeaverChain.replay(
                CHAIN_ID,
                List.of(
                        new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                        new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 1),
                        new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2),
                        new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 3)));
        var linked = chain.links().get(0);

        assertThat(chain.invalidateLink(linked.eventId(), linked.outcomeId())).isEmpty();
    }

    @Test
    void replay_invalidatedLink_rebuildsShortenedChain() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkAdded(CHAIN_ID, eventId, outcomeId, 1),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2),
                new ChainLinkInvalidated(CHAIN_ID, eventId, outcomeId));

        var chain = WeaverChain.replay(CHAIN_ID, history);

        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
    }

    @Test
    void replay_invalidationOfUnknownLink_throwsIllegalState() {
        var history = List.of(
                new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 1),
                new ChainLinkInvalidated(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> WeaverChain.replay(CHAIN_ID, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidatedChain_acceptsRebuildLink() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var replacementEvent = UUID.randomUUID();
        var replacementOutcome = UUID.randomUUID();
        var chain = WeaverChain.replay(
                CHAIN_ID,
                List.of(
                        new WeaverChainStarted(CHAIN_ID, PLAYER_ID, GAME_ID),
                        new ChainLinkAdded(CHAIN_ID, eventId, outcomeId, 1),
                        new ChainLinkAdded(CHAIN_ID, UUID.randomUUID(), UUID.randomUUID(), 2)));
        chain.invalidateLink(eventId, outcomeId);

        var facts = chain.addLink(
                replacementEvent,
                replacementOutcome,
                3,
                Set.of(new ResolvedOutcome(replacementEvent, replacementOutcome)));

        assertThat(facts).hasSize(1);
        assertThat(chain.length()).isEqualTo(2);
    }
}
