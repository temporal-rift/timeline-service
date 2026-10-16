package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.futureevent.CardGrade;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.BufferedAction;

public interface RoundActionBufferPortContract {

    RoundActionBufferPort actionBuffer();

    @Test
    @DisplayName("action buffer: a round returns exactly the actions saved to it, with every field intact")
    default void actionBuffer_roundReturnsItsOwnActions() {
        var gameId = UUID.randomUUID();
        var card = card();
        var special = special();
        actionBuffer().save(gameId, 1, 1, card);
        actionBuffer().save(gameId, 1, 1, special);
        actionBuffer().save(gameId, 1, 2, special());
        actionBuffer().save(gameId, 2, 1, special());
        actionBuffer().save(UUID.randomUUID(), 1, 1, special());

        assertThat(actionBuffer().findByRound(gameId, 1, 1)).containsExactlyInAnyOrder(card, special);
        assertThat(actionBuffer().findByRound(gameId, 1, 2)).hasSize(1);
        assertThat(actionBuffer().findByRound(gameId, 3, 1)).isEmpty();
    }

    private static BufferedAction card() {
        return new BufferedAction(
                ActionKind.CARD_PLAYED,
                "SCAN",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                List.of(UUID.randomUUID(), UUID.randomUUID()),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                List.of(UUID.randomUUID()),
                CardGrade.II,
                UUID.randomUUID());
    }

    private static BufferedAction special() {
        return new BufferedAction(
                ActionKind.SPECIAL_ACTION_PLAYED,
                null,
                "SEAL",
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                List.of(),
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(),
                null,
                UUID.randomUUID());
    }
}
