package io.github.temporalrift.timeline.infrastructure.adapter.out.entropy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.execution.EntropyCoordinate;
import io.github.temporalrift.timeline.domain.execution.EntropyPurpose;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.execution.IdentityKind;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.EntropyDecisionRepository;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;

@ExtendWith(MockitoExtension.class)
class SeededExecutionEntropyTest {

    private static final UUID GAME_ID = UUID.fromString("9a000000-0000-4000-8000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_EVENT_ID = UUID.fromString("e0000000-0000-4000-8000-000000000002");
    private static final Outcome LEADER = new Outcome(UUID.fromString("0a000000-0000-4000-8000-000000000001"), "a", 45);
    private static final Outcome SECOND = new Outcome(UUID.fromString("0a000000-0000-4000-8000-000000000002"), "b", 35);
    private static final Outcome THIRD = new Outcome(UUID.fromString("0a000000-0000-4000-8000-000000000003"), "c", 20);
    private static final EntropyCoordinate ERA_1_EVENT =
            EntropyCoordinate.none().era(1).subject(EVENT_ID);

    @Mock
    ExecutionRepository executions;

    private final InMemoryDecisions decisions = new InMemoryDecisions();

    private SeededExecutionEntropy entropyFor(String seed) {
        given(executions.find()).willReturn(Optional.of(Execution.configure(ExecutionContextTestData.context(seed))));
        return new SeededExecutionEntropy(new PinnedExecutionContext(executions), decisions);
    }

    private SeededExecutionEntropy cleanEntropy() {
        return new SeededExecutionEntropy(new PinnedExecutionContext(executions), new InMemoryDecisions());
    }

    @Test
    @DisplayName("case C with seed 42 resolves the same outcome in two clean reconstructions")
    void resolve_sameSeedInCleanExecutions_drawsTheSameOutcome() {
        var first = resolve(entropyFor("42"), EVENT_ID);
        var second = resolve(cleanEntropy(), EVENT_ID);

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("independent deterministic seeds keep the weighted 45/35/20 selection instead of the leader")
    void resolve_acrossSeeds_retainsWeightedSelection() {
        var trials = 4000;
        var wins = new HashMap<UUID, Integer>();
        for (var seed = 0; seed < trials; seed++) {
            var entropy = new SeededExecutionEntropy(
                    new PinnedExecutionContext(executionOf(Integer.toString(seed))), new InMemoryDecisions());
            wins.merge(resolve(entropy, EVENT_ID), 1, Integer::sum);
        }

        assertThat(share(wins, LEADER, trials)).isBetween(0.40, 0.50);
        assertThat(share(wins, SECOND, trials)).isBetween(0.30, 0.40);
        assertThat(share(wins, THIRD, trials)).isBetween(0.16, 0.24);
    }

    @Test
    @DisplayName("a resolution repeated after a restart re-reads its realized draw instead of drawing again")
    void resolve_afterRestart_rereadsTheRealizedDraw() {
        var original = resolve(entropyFor("42"), EVENT_ID);
        var recorded = decisions.size();

        var restarted = new SeededExecutionEntropy(new PinnedExecutionContext(executions), decisions);

        assertThat(resolve(restarted, EVENT_ID)).isEqualTo(original);
        assertThat(decisions.size()).isEqualTo(recorded).isPositive();
    }

    @Test
    @DisplayName("a recorded word wins over a fresh derivation, so recovery never changes an earlier choice")
    void generator_recordedWord_isAuthoritative() {
        var entropy = entropyFor("42");
        decisions.save(EntropyPurpose.OUTCOME_RESOLUTION.name(), "era=1;subject=" + EVENT_ID, 0, 1234L);

        assertThat(entropy.generator(EntropyPurpose.OUTCOME_RESOLUTION, ERA_1_EVENT)
                        .nextLong())
                .isEqualTo(1234L);
    }

    @Test
    @DisplayName("draws for other events, in any order or timing, never change an event's own sequence")
    void generator_unrelatedDrawsInAnyOrder_leaveTheSequenceUnchanged() {
        var entropy = entropyFor("42");
        var other = entropy.generator(
                EntropyPurpose.OUTCOME_RESOLUTION,
                EntropyCoordinate.none().era(1).subject(OTHER_EVENT_ID));
        IntStream.range(0, 3).forEach(i -> other.nextLong());
        var afterOtherDraws = draw(entropy, ERA_1_EVENT, 4);

        var alone = draw(cleanEntropy(), ERA_1_EVENT, 4);

        assertThat(afterOtherDraws).containsExactlyElementsOf(alone);
    }

    @Test
    @DisplayName("each era and event draws from its own stream")
    void generator_differentEraOrEvent_isIndependent() {
        var entropy = entropyFor("42");
        var base = draw(entropy, ERA_1_EVENT, 4);

        assertThat(draw(entropy, EntropyCoordinate.none().era(2).subject(EVENT_ID), 4))
                .isNotEqualTo(base);
        assertThat(draw(entropy, EntropyCoordinate.none().era(1).subject(OTHER_EVENT_ID), 4))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("an identity is stable across repetitions and distinct per kind and coordinate")
    void identity_isStablePerKindAndCoordinate() {
        var entropy = entropyFor("42");
        var weaver = EntropyCoordinate.none().player(ExecutionContextTestData.PLAYER_2);

        var chain = entropy.identity(IdentityKind.WEAVER_CHAIN, weaver);

        assertThat(entropy.identity(IdentityKind.WEAVER_CHAIN, weaver)).isEqualTo(chain);
        assertThat(entropy.identity(
                        IdentityKind.WEAVER_CHAIN, EntropyCoordinate.none().player(ExecutionContextTestData.PLAYER_1)))
                .isNotEqualTo(chain);
        assertThat(entropy.identity(IdentityKind.ERA_RESOLUTION_PARADOX, ERA_1_EVENT.slot(0)))
                .isNotEqualTo(entropy.identity(IdentityKind.PHASE_CLOSE_PARADOX, ERA_1_EVENT.slot(0)));
    }

    @Test
    @DisplayName("without a configured execution no choice or identity can be produced")
    void generator_withoutConfiguredExecution_isRejected() {
        given(executions.find()).willReturn(Optional.empty());
        var entropy = new SeededExecutionEntropy(new PinnedExecutionContext(executions), decisions);
        var noCoordinate = EntropyCoordinate.none();

        assertThatThrownBy(() -> entropy.generator(EntropyPurpose.OUTCOME_RESOLUTION, ERA_1_EVENT))
                .isInstanceOf(ExecutionNotConfiguredException.class);
        assertThatThrownBy(() -> entropy.identity(IdentityKind.WEAVER_CHAIN, noCoordinate))
                .isInstanceOf(ExecutionNotConfiguredException.class);
    }

    private static ExecutionRepository executionOf(String seed) {
        var execution = Execution.configure(ExecutionContextTestData.context(seed));
        return new ExecutionRepository() {
            @Override
            public Optional<Execution> find() {
                return Optional.of(execution);
            }

            @Override
            public Optional<Execution> findWithLock() {
                return Optional.of(execution);
            }

            @Override
            public boolean insertIfAbsent(Execution ignored) {
                return false;
            }

            @Override
            public void update(Execution ignored) {
                // read-only fixture
            }
        };
    }

    private static UUID resolve(SeededExecutionEntropy entropy, UUID eventId) {
        var event =
                FutureEvent.replay(eventId, List.of(new FutureEventDrafted(eventId, List.of(LEADER, SECOND, THIRD))));
        var coordinate = EntropyCoordinate.none().era(1).subject(eventId);
        return event.resolve(GAME_ID, 1, entropy.generator(EntropyPurpose.OUTCOME_RESOLUTION, coordinate))
                .winningOutcomeId();
    }

    private static double share(Map<UUID, Integer> wins, Outcome outcome, int trials) {
        return wins.getOrDefault(outcome.outcomeId(), 0) / (double) trials;
    }

    private static List<Long> draw(SeededExecutionEntropy entropy, EntropyCoordinate coordinate, int count) {
        var generator = entropy.generator(EntropyPurpose.OUTCOME_RESOLUTION, coordinate);
        return IntStream.range(0, count).mapToObj(i -> generator.nextLong()).toList();
    }

    private static final class InMemoryDecisions implements EntropyDecisionRepository {

        private final Map<String, Long> words = new HashMap<>();

        @Override
        public Optional<Long> find(String purpose, String coordinate, int drawIndex) {
            return Optional.ofNullable(words.get(key(purpose, coordinate, drawIndex)));
        }

        @Override
        public void save(String purpose, String coordinate, int drawIndex, long word) {
            words.putIfAbsent(key(purpose, coordinate, drawIndex), word);
        }

        int size() {
            return words.size();
        }

        private static String key(String purpose, String coordinate, int drawIndex) {
            return purpose + "|" + coordinate + "|" + drawIndex;
        }
    }
}
