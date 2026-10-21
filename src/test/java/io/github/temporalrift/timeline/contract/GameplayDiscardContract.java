package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.membership.GameMembership;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.GameMembershipPort;
import io.github.temporalrift.timeline.domain.port.out.GameplayDiscard;

/** The irreversible whole-lane discard, run against both storage adapters. */
public interface GameplayDiscardContract extends TransactionalContract {

    GameplayDiscard gameplayDiscard();

    GameMembershipPort gameplayMemberships();

    ExecutionRepository gameplayExecutions();

    @Test
    @DisplayName("gameplay discard empties every gameplay table and keeps the simulation control state")
    default void discard_emptiesGameplayAndKeepsControlState() {
        var game = UUID.fromString("10000000-0000-4000-8000-000000000001");
        var context = ExecutionContextTestData.context("42");
        var execution = Execution.configure(context);
        gameplayMemberships().save(new GameMembership(game, UUID.randomUUID(), MemberFaction.WEAVERS));
        gameplayExecutions().insertIfAbsent(execution);
        gameplayExecutions().bindGame(context.caseKey(), game);
        gameplayExecutions().close(context.caseKey());

        assertThat(gameplayDiscard().isClean()).isFalse();

        gameplayDiscard().discardAll();

        assertThat(gameplayDiscard().isClean()).isTrue();
        assertThat(gameplayExecutions().find()).isEmpty();
        assertThat(gameplayExecutions().lastClosedCaseKey()).contains(context.caseKey());
        assertThat(gameplayExecutions().isClosedGame(game)).isTrue();
    }
}
