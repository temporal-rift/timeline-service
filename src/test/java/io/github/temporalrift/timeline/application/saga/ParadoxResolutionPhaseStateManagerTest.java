package io.github.temporalrift.timeline.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

@ExtendWith(MockitoExtension.class)
class ParadoxResolutionPhaseStateManagerTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 1;
    private static final Instant TIMER_EXPIRES_AT = Instant.parse("2026-08-09T00:01:00Z");

    @Mock
    ParadoxResolutionPhaseRepository repository;

    @Mock
    EraPlayersPort eraPlayers;

    private ParadoxResolutionPhaseStateManager stateManager;

    @BeforeEach
    void setUp() {
        stateManager = new ParadoxResolutionPhaseStateManager(repository, eraPlayers);
    }

    @Test
    void markSubmitted_unknownRosterNowAvailable_adoptsItAndRecordsTheSubmission() {
        var submittingPlayerId = UUID.randomUUID();
        var otherPlayerId = UUID.randomUUID();
        givenLockedPhase(unknownRosterPhase(List.of()));
        given(eraPlayers.find(GAME_ID, ERA_NUMBER)).willReturn(Optional.of(List.of(submittingPlayerId, otherPlayerId)));
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        var phase = stateManager.markSubmitted(GAME_ID, ERA_NUMBER, push(submittingPlayerId));

        assertThat(phase).isPresent();
        assertThat(phase.get().rosterKnown()).isTrue();
        assertThat(phase.get().pendingPlayerIds()).containsExactly(otherPlayerId);
        assertThat(phase.get().allPlayersSubmitted()).isFalse();
    }

    @Test
    void markSubmitted_rosterStillAbsent_recordsTheSubmissionWithTheRosterLeftUnknown() {
        var submittingPlayerId = UUID.randomUUID();
        givenLockedPhase(unknownRosterPhase(List.of()));
        given(eraPlayers.find(GAME_ID, ERA_NUMBER)).willReturn(Optional.empty());
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        var phase = stateManager.markSubmitted(GAME_ID, ERA_NUMBER, push(submittingPlayerId));

        assertThat(phase).isPresent();
        assertThat(phase.get().rosterKnown()).isFalse();
        assertThat(phase.get().submissions()).extracting(Submission::playerId).containsExactly(submittingPlayerId);
        assertThat(phase.get().allPlayersSubmitted()).isFalse();
    }

    @Test
    void markSubmitted_knownRoster_doesNotRereadTheRoster() {
        var submittingPlayerId = UUID.randomUUID();
        givenLockedPhase(knownRosterPhase(List.of(submittingPlayerId), List.of()));
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        var phase = stateManager.markSubmitted(GAME_ID, ERA_NUMBER, push(submittingPlayerId));

        assertThat(phase).isPresent();
        assertThat(phase.get().allPlayersSubmitted()).isTrue();
        then(eraPlayers).should(never()).find(any(), anyInt());
    }

    @Test
    void markSubmitted_playerAbsentFromAKnownRoster_recordsNothing() {
        givenLockedPhase(knownRosterPhase(List.of(UUID.randomUUID()), List.of()));

        var phase = stateManager.markSubmitted(GAME_ID, ERA_NUMBER, push(UUID.randomUUID()));

        assertThat(phase).isEmpty();
        then(repository).should(never()).save(any());
    }

    @Test
    void markSubmitted_rosterLandedButSubmissionRejected_stillPersistsTheAdoptedRoster() {
        var alreadySubmittedPlayerId = UUID.randomUUID();
        var otherPlayerId = UUID.randomUUID();
        givenLockedPhase(unknownRosterPhase(List.of(push(alreadySubmittedPlayerId))));
        given(eraPlayers.find(GAME_ID, ERA_NUMBER))
                .willReturn(Optional.of(List.of(alreadySubmittedPlayerId, otherPlayerId)));

        var phase = stateManager.markSubmitted(GAME_ID, ERA_NUMBER, push(alreadySubmittedPlayerId));

        assertThat(phase).isEmpty();
        var saved = ArgumentCaptor.forClass(ParadoxResolutionPhase.class);
        then(repository).should().save(saved.capture());
        assertThat(saved.getValue().rosterKnown()).isTrue();
        assertThat(saved.getValue().pendingPlayerIds()).containsExactly(otherPlayerId);
        assertThat(saved.getValue().submissions()).hasSize(1);
    }

    @Test
    void markSubmitted_playerAlreadySubmittedUnderAnUnknownRoster_recordsNothing() {
        var submittingPlayerId = UUID.randomUUID();
        givenLockedPhase(unknownRosterPhase(List.of(push(submittingPlayerId))));
        given(eraPlayers.find(GAME_ID, ERA_NUMBER)).willReturn(Optional.empty());

        var phase = stateManager.markSubmitted(GAME_ID, ERA_NUMBER, push(submittingPlayerId));

        assertThat(phase).isEmpty();
        then(repository).should(never()).save(any());
    }

    @Test
    void markSubmitted_phaseAlreadyClosing_recordsNothing() {
        givenLockedPhase(knownRosterPhase(List.of(UUID.randomUUID()), List.of())
                .withStatus(ParadoxResolutionPhaseStatus.CLOSING));

        var phase = stateManager.markSubmitted(GAME_ID, ERA_NUMBER, push(UUID.randomUUID()));

        assertThat(phase).isEmpty();
        then(repository).should(never()).save(any());
    }

    private void givenLockedPhase(ParadoxResolutionPhase phase) {
        given(repository.findByGameIdAndEraNumberWithLock(GAME_ID, ERA_NUMBER)).willReturn(Optional.of(phase));
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
