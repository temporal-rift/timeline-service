package io.github.temporalrift.timeline.domain.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;

class ParadoxResolutionPhaseTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 1;
    private static final Instant TIMER_EXPIRES_AT = Instant.parse("2026-08-09T00:01:00Z");

    @Test
    void construction_unknownRosterWithPendingPlayers_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ParadoxResolutionPhase(
                        UUID.randomUUID(),
                        GAME_ID,
                        ERA_NUMBER,
                        ParadoxResolutionPhaseStatus.WAITING,
                        List.of(),
                        List.of(),
                        false,
                        List.of(UUID.randomUUID()),
                        List.of(),
                        TIMER_EXPIRES_AT));
    }

    @Test
    void allPlayersSubmitted_unknownRoster_isFalseEvenWithNoPendingPlayers() {
        assertThat(unknownRosterPhase(List.of()).allPlayersSubmitted()).isFalse();
    }

    @Test
    void allPlayersSubmitted_knownEmptyRoster_isTrue() {
        assertThat(knownRosterPhase(List.of(), List.of()).allPlayersSubmitted()).isTrue();
    }

    @Test
    void accepts_knownRoster_onlyPendingPlayers() {
        var pendingPlayerId = UUID.randomUUID();
        var phase = knownRosterPhase(List.of(pendingPlayerId), List.of());

        assertThat(phase.accepts(pendingPlayerId)).isTrue();
        assertThat(phase.accepts(UUID.randomUUID())).isFalse();
    }

    @Test
    void accepts_unknownRoster_anyPlayerWhoHasNotSubmittedYet() {
        var submittedPlayerId = UUID.randomUUID();
        var phase = unknownRosterPhase(List.of(push(submittedPlayerId)));

        assertThat(phase.accepts(UUID.randomUUID())).isTrue();
        assertThat(phase.accepts(submittedPlayerId)).isFalse();
    }

    @Test
    void withSubmission_unknownRoster_recordsWithoutMakingTheRosterKnown() {
        var playerId = UUID.randomUUID();

        var phase = unknownRosterPhase(List.of()).withSubmission(push(playerId));

        assertThat(phase.submissions()).extracting(Submission::playerId).containsExactly(playerId);
        assertThat(phase.rosterKnown()).isFalse();
        assertThat(phase.allPlayersSubmitted()).isFalse();
    }

    @Test
    void withSubmission_unknownRoster_ignoresASecondSubmissionFromTheSamePlayer() {
        var playerId = UUID.randomUUID();
        var phase = unknownRosterPhase(List.of(push(playerId)));

        assertThat(phase.withSubmission(push(playerId))).isEqualTo(phase);
    }

    @Test
    void withRoster_leavesPendingEveryPlayerWhoHasNotSubmittedYet() {
        var submittedPlayerId = UUID.randomUUID();
        var otherPlayerId = UUID.randomUUID();
        var thirdPlayerId = UUID.randomUUID();

        var phase = unknownRosterPhase(List.of(push(submittedPlayerId)))
                .withRoster(List.of(submittedPlayerId, otherPlayerId, thirdPlayerId));

        assertThat(phase.rosterKnown()).isTrue();
        assertThat(phase.pendingPlayerIds()).containsExactly(otherPlayerId, thirdPlayerId);
        assertThat(phase.allPlayersSubmitted()).isFalse();
    }

    @Test
    void withRoster_everyRosteredPlayerAlreadySubmitted_meetsTheAllSubmittedTrigger() {
        var firstPlayerId = UUID.randomUUID();
        var secondPlayerId = UUID.randomUUID();

        var phase = unknownRosterPhase(List.of(push(firstPlayerId), push(secondPlayerId)))
                .withRoster(List.of(firstPlayerId, secondPlayerId));

        assertThat(phase.allPlayersSubmitted()).isTrue();
    }

    @Test
    void withRoster_rosterAlreadyKnown_isIgnored() {
        var pendingPlayerId = UUID.randomUUID();
        var phase = knownRosterPhase(List.of(pendingPlayerId), List.of());

        assertThat(phase.withRoster(List.of(UUID.randomUUID(), UUID.randomUUID())))
                .isEqualTo(phase);
    }

    private static Submission push(UUID playerId) {
        return new Submission(playerId, "PUSH", UUID.randomUUID(), UUID.randomUUID());
    }

    private static ParadoxResolutionPhase unknownRosterPhase(List<Submission> submissions) {
        return ParadoxResolutionPhase.withUnknownRoster(
                UUID.randomUUID(),
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(),
                List.of(),
                submissions,
                TIMER_EXPIRES_AT);
    }

    private static ParadoxResolutionPhase knownRosterPhase(List<UUID> pendingPlayerIds, List<Submission> submissions) {
        return ParadoxResolutionPhase.withKnownRoster(
                UUID.randomUUID(),
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(),
                List.of(),
                pendingPlayerIds,
                submissions,
                TIMER_EXPIRES_AT);
    }
}
