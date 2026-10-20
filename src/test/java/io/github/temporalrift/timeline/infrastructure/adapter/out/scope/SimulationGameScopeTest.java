package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;

@ExtendWith(MockitoExtension.class)
class SimulationGameScopeTest {

    private static final UUID GAME = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Mock
    ExecutionRepository executions;

    private SimulationGameScope scope() {
        return new SimulationGameScope(executions);
    }

    @Test
    @DisplayName("with no context open every game is refused")
    void admits_noContext_isRefused() {
        given(executions.find()).willReturn(Optional.empty());

        assertThat(scope().admits(GAME)).isFalse();
    }

    @Test
    @DisplayName("the first admitted game binds the open context and is the only one admitted afterwards")
    void admits_firstGameBindsTheContext() {
        var open = Execution.configure(ExecutionContextTestData.context("42"));
        given(executions.find()).willReturn(Optional.of(open));
        given(executions.bindGame(open.context().caseKey(), GAME)).willReturn(true);

        assertThat(scope().admits(GAME)).isTrue();
    }

    @Test
    @DisplayName("a game that did not win the binding race is refused")
    void admits_lostBindingRace_isRefused() {
        var open = Execution.configure(ExecutionContextTestData.context("42"));
        var bound = new Execution(open.context(), 0, open.logicalTime(), OTHER);
        given(executions.find()).willReturn(Optional.of(open)).willReturn(Optional.of(bound));
        given(executions.bindGame(open.context().caseKey(), GAME)).willReturn(false);

        assertThat(scope().admits(GAME)).isFalse();
    }

    @Test
    @DisplayName("only the bound game is admitted, and a closed game is never admitted")
    void admits_boundGameOnly() {
        var bound = new Execution(ExecutionContextTestData.context("42"), 0, ExecutionContextTestData.START, GAME);
        given(executions.find()).willReturn(Optional.of(bound));

        assertThat(scope().admits(GAME)).isTrue();
        assertThat(scope().admits(OTHER)).isFalse();

        given(executions.isClosedGame(OTHER)).willReturn(true);
        assertThat(scope().admits(OTHER)).isFalse();
    }

    @Test
    @DisplayName("a record without a game id is refused")
    void admits_missingGame_isRefused() {
        assertThat(scope().admits(null)).isFalse();
    }
}
