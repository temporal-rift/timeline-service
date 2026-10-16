package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

public interface ParadoxResolutionPhaseRepositoryContract extends TransactionalContract {

    Instant DEADLINE = Instant.parse("2026-03-01T10:00:00Z");

    ParadoxResolutionPhaseRepository phases();

    @Test
    @DisplayName("resolution phases: only the first creation for a game and era wins, the second gets the existing one")
    default void phases_createIfAbsentCreatesOnce() {
        var gameId = UUID.randomUUID();
        var first = phase(gameId, 1, ParadoxResolutionPhaseStatus.WAITING, DEADLINE);
        var second = phase(gameId, 1, ParadoxResolutionPhaseStatus.WAITING, DEADLINE.plusSeconds(60));

        var created = create(first);
        var duplicate = create(second);

        assertThat(created.created()).isTrue();
        assertThat(created.phase()).isEqualTo(first);
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.phase()).isEqualTo(first);
        assertThat(lockedByGameAndEra(gameId, 1)).contains(first);
    }

    @Test
    @DisplayName("resolution phases: a saved update is found by saga id and by game and era")
    default void phases_saveUpdatesThePhase() {
        var gameId = UUID.randomUUID();
        var waiting = phase(gameId, 1, ParadoxResolutionPhaseStatus.WAITING, DEADLINE);
        create(waiting);
        var completed = new ParadoxResolutionPhase(
                waiting.sagaId(),
                gameId,
                1,
                ParadoxResolutionPhaseStatus.COMPLETED,
                List.of(),
                List.of(),
                true,
                List.of(),
                List.of(),
                DEADLINE);

        assertThat(phases().save(completed)).isEqualTo(completed);

        assertThat(lockedBySaga(waiting.sagaId())).contains(completed);
        assertThat(lockedByGameAndEra(gameId, 1)).contains(completed);
        assertThat(lockedBySaga(UUID.randomUUID())).isEmpty();
        assertThat(lockedByGameAndEra(gameId, 2)).isEmpty();
    }

    @Test
    @DisplayName("resolution phases: a phase with an unknown roster stays unknown after a round trip")
    default void phases_unknownRosterSurvivesARoundTrip() {
        var gameId = UUID.randomUUID();
        var unknownRoster = ParadoxResolutionPhase.withUnknownRoster(
                UUID.randomUUID(),
                gameId,
                1,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(),
                List.of(),
                List.of(),
                DEADLINE);
        create(unknownRoster);

        var found = lockedByGameAndEra(gameId, 1).orElseThrow();

        assertThat(found.rosterKnown()).isFalse();
        assertThat(found).isEqualTo(unknownRoster);
    }

    @Test
    @DisplayName("resolution phases: the sweep query returns waiting phases due by the deadline, earliest first")
    default void phases_sweepReturnsDueWaitingPhasesEarliestFirst() {
        var late = phase(UUID.randomUUID(), 1, ParadoxResolutionPhaseStatus.WAITING, DEADLINE.plusSeconds(20));
        var early = phase(UUID.randomUUID(), 1, ParadoxResolutionPhaseStatus.WAITING, DEADLINE.plusSeconds(10));
        var notDue = phase(UUID.randomUUID(), 1, ParadoxResolutionPhaseStatus.WAITING, DEADLINE.plusSeconds(500));
        var completed = phase(UUID.randomUUID(), 1, ParadoxResolutionPhaseStatus.COMPLETED, DEADLINE.plusSeconds(5));
        for (var phase : List.of(late, early, notDue, completed)) {
            create(phase);
        }

        var due = phases().findWaitingDueBy(DEADLINE.plusSeconds(20)).stream()
                .filter(phase -> List.of(late, early, notDue, completed).contains(phase))
                .toList();

        assertThat(due).containsExactly(early, late);
    }

    @Test
    @DisplayName("resolution phases: a second transaction locking the same phase waits for the first to commit")
    default void phases_lockWaitsForTheHolder() throws Exception {
        var gameId = UUID.randomUUID();
        var waiting = phase(gameId, 1, ParadoxResolutionPhaseStatus.WAITING, DEADLINE);
        create(waiting);
        var held = holdTransaction(() -> phases().findByGameIdAndEraNumberWithLock(gameId, 1));

        var second = CompletableFuture.supplyAsync(
                () -> transactions().execute(status -> phases().findBySagaIdWithLock(waiting.sagaId())));

        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(second).isNotDone());
        assertThat(held.commit()).contains(waiting);
        assertThat(second.get(30, TimeUnit.SECONDS)).contains(waiting);
    }

    /** Row locks, like creation, only exist inside the caller's transaction. */
    default Optional<ParadoxResolutionPhase> lockedByGameAndEra(UUID gameId, int eraNumber) {
        return transactions().execute(status -> phases().findByGameIdAndEraNumberWithLock(gameId, eraNumber));
    }

    default Optional<ParadoxResolutionPhase> lockedBySaga(UUID sagaId) {
        return transactions().execute(status -> phases().findBySagaIdWithLock(sagaId));
    }

    /** Creation is an insert-first statement that only runs inside the caller's transaction, as in production. */
    default ParadoxResolutionPhaseRepository.CreateResult create(ParadoxResolutionPhase candidate) {
        return transactions().execute(status -> phases().createIfAbsent(candidate));
    }

    static ParadoxResolutionPhase phase(
            UUID gameId, int eraNumber, ParadoxResolutionPhaseStatus status, Instant timerExpiresAt) {
        return ParadoxResolutionPhase.withKnownRoster(
                UUID.randomUUID(),
                gameId,
                eraNumber,
                status,
                List.of(),
                List.of(),
                List.of(UUID.randomUUID()),
                List.of(),
                timerExpiresAt);
    }
}
