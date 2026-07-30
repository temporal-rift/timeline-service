package io.github.temporalrift.timeline.domain.futureevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;

class FutureEventTest {

    static final UUID GAME_ID = UUID.randomUUID();
    static final int ERA_NUMBER = 1;

    @Test
    void resolve_highestProbability_wins() {
        var id = UUID.randomUUID();
        var winner = new Outcome(UUID.randomUUID(), "winner", 45);
        var second = new Outcome(UUID.randomUUID(), "second", 35);
        var third = new Outcome(UUID.randomUUID(), "third", 20);
        var event = drafted(id, winner, second, third);

        var outcomeApplied = event.resolve(GAME_ID, ERA_NUMBER);

        assertThat(outcomeApplied.winningOutcomeId()).isEqualTo(winner.outcomeId());
        assertThat(outcomeApplied.gameId()).isEqualTo(GAME_ID);
        assertThat(outcomeApplied.eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(outcomeApplied.eventId()).isEqualTo(id);
        assertThat(event.resolved()).isTrue();
    }

    @Test
    void resolve_tiedProbabilities_smallestOutcomeIdWins() {
        var id = UUID.randomUUID();
        var lower = new Outcome(UUID.fromString("00000000-0000-0000-0000-000000000001"), "lower", 40);
        var higher = new Outcome(UUID.fromString("00000000-0000-0000-0000-000000000002"), "higher", 40);
        var third = new Outcome(UUID.randomUUID(), "third", 20);
        var event = drafted(id, higher, lower, third);

        var outcomeApplied = event.resolve(GAME_ID, ERA_NUMBER);

        assertThat(outcomeApplied.winningOutcomeId()).isEqualTo(lower.outcomeId());
    }

    @Test
    void resolve_alreadyResolved_throws() {
        var id = UUID.randomUUID();
        var event = drafted(id, new Outcome(UUID.randomUUID(), "only", 100));
        event.resolve(GAME_ID, ERA_NUMBER);

        assertThatThrownBy(() -> event.resolve(GAME_ID, ERA_NUMBER))
                .isInstanceOf(FutureEventAlreadyResolvedException.class);
    }

    @Test
    void replay_emptyHistory_throwsNotFound() {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> FutureEvent.replay(id, List.of())).isInstanceOf(FutureEventNotFoundException.class);
    }

    @Test
    void replay_outcomeAppliedBeforeDrafted_throwsIllegalState() {
        var id = UUID.randomUUID();
        var outcomeApplied = new OutcomeApplied(GAME_ID, ERA_NUMBER, id, UUID.randomUUID(), List.of());

        assertThatThrownBy(() -> FutureEvent.replay(id, List.of(outcomeApplied)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_draftedThenApplied_reconstructsResolvedState() {
        var id = UUID.randomUUID();
        var outcome = new Outcome(UUID.randomUUID(), "only", 100);
        var drafted = new FutureEventDrafted(id, List.of(outcome));
        var applied = new OutcomeApplied(GAME_ID, ERA_NUMBER, id, outcome.outcomeId(), List.of(outcome));

        var event = FutureEvent.replay(id, List.of(drafted, applied));

        assertThat(event.resolved()).isTrue();
        assertThatThrownBy(() -> event.resolve(GAME_ID, ERA_NUMBER))
                .isInstanceOf(FutureEventAlreadyResolvedException.class);
    }

    private static FutureEvent drafted(UUID id, Outcome... outcomes) {
        return FutureEvent.replay(id, List.of(new FutureEventDrafted(id, List.of(outcomes))));
    }
}
