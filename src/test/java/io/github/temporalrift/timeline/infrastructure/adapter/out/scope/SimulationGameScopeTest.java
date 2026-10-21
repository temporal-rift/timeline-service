package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository.Bounds;

@ExtendWith(MockitoExtension.class)
class SimulationGameScopeTest {

    private static final UUID GAME = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CASE = UUID.fromString("c0ffee00-0000-4000-8000-000000000001");

    @Mock
    ExecutionRepository executions;

    private SimulationGameScope scope() {
        return new SimulationGameScope(executions);
    }

    @Test
    @DisplayName("with no context open every game is refused")
    void admits_noContext_isRefused() {
        given(executions.findBounds()).willReturn(Optional.empty());

        assertThat(scope().admits(GAME)).isFalse();
    }

    @Test
    @DisplayName("the first admitted game binds the open context and is the only one admitted afterwards")
    void admits_firstGameBindsTheContext() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.bindGame(CASE, GAME)).willReturn(true);
        given(executions.findBoundsForScope()).willReturn(Optional.of(new Bounds(CASE, GAME)));

        assertThat(scope().admits(GAME)).isTrue();
    }

    @Test
    @DisplayName("a game that did not win the binding race is refused")
    void admits_lostBindingRace_isRefused() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.bindGame(CASE, GAME)).willReturn(false);
        given(executions.findBoundsForScope()).willReturn(Optional.of(new Bounds(CASE, OTHER)));

        assertThat(scope().admits(GAME)).isFalse();
    }

    @Test
    @DisplayName("only the bound game is admitted")
    void admits_boundGameOnly() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, GAME)));
        given(executions.findBoundsForScope()).willReturn(Optional.of(new Bounds(CASE, GAME)));

        assertThat(scope().admits(GAME)).isTrue();
        assertThat(scope().admits(OTHER)).isFalse();
    }

    @Test
    @DisplayName("an unbound context never adopts a game that a closed context already owned")
    void admits_closedGame_isNotBound() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.isClosedGame(GAME)).willReturn(true);
        given(executions.findBoundsForScope()).willReturn(Optional.of(new Bounds(CASE, null)));

        assertThat(scope().admits(GAME)).isFalse();
        verify(executions, never()).bindGame(CASE, GAME);
    }

    @Test
    @DisplayName("a record without a game id is refused")
    void admits_missingGame_isRefused() {
        assertThat(scope().admits(null)).isFalse();
    }
}
