package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.port.out.EventFence;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository.Bounds;
import io.github.temporalrift.timeline.domain.port.out.GameScope.InboundRecord;

@ExtendWith(MockitoExtension.class)
class SimulationGameScopeTest {

    private static final UUID GAME = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CASE = UUID.fromString("c0ffee00-0000-4000-8000-000000000001");
    private static final UUID NEXT_CASE = UUID.fromString("c0ffee00-0000-4000-8000-000000000002");
    private static final String TOPIC = "game.events";
    private static final long OFFSET = 7L;

    @Mock
    ExecutionRepository executions;

    @Mock
    EventFence eventFence;

    private SimulationGameScope scope() {
        return new SimulationGameScope(executions, eventFence);
    }

    private static InboundRecord record(UUID gameId) {
        return new InboundRecord(TOPIC, 0, OFFSET, gameId);
    }

    @Test
    @DisplayName("with no context open every game is refused")
    void admits_noContext_isRefused() {
        given(executions.findBounds()).willReturn(Optional.empty());

        assertThat(scope().admits(record(GAME))).isFalse();
    }

    @Test
    @DisplayName("the first admitted game binds the open context under its exclusive lock")
    void admits_firstGameBindsTheContext() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.findBoundsForBinding()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.bindGame(CASE, GAME)).willReturn(true);

        assertThat(scope().admits(record(GAME))).isTrue();

        var order = inOrder(executions);
        order.verify(executions).findBoundsForBinding();
        order.verify(executions).bindGame(CASE, GAME);
        verify(executions, never()).findBoundsForHandling();
    }

    @Test
    @DisplayName("a game that lost the binding race meets the bound game under the lock and is refused")
    void admits_lostBindingRace_isRefused() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.findBoundsForBinding()).willReturn(Optional.of(new Bounds(CASE, OTHER)));

        assertThat(scope().admits(record(GAME))).isFalse();
        verify(executions, never()).bindGame(any(), any());
    }

    @Test
    @DisplayName("only the bound game is admitted, under the shared lock")
    void admits_boundGameOnly() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, GAME)));
        given(executions.findBoundsForHandling()).willReturn(Optional.of(new Bounds(CASE, GAME)));

        assertThat(scope().admits(record(GAME))).isTrue();
        assertThat(scope().admits(record(OTHER))).isFalse();
        verify(executions, never()).findBoundsForBinding();
    }

    @Test
    @DisplayName("an unbound context never adopts a game that a closed context already owned")
    void admits_closedGame_isNotBound() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.findBoundsForBinding()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.isClosedGame(GAME)).willReturn(true);

        assertThat(scope().admits(record(GAME))).isFalse();
        verify(executions, never()).bindGame(any(), any());
    }

    @Test
    @DisplayName("a record already in the source when the context opened neither binds nor is admitted")
    void admits_fencedRecord_isRefusedWithoutBinding() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.findBoundsForBinding()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(eventFence.isFenced(TOPIC, 0, OFFSET)).willReturn(true);

        assertThat(scope().admits(record(GAME))).isFalse();
        verify(executions, never()).bindGame(any(), any());
    }

    @Test
    @DisplayName("a fenced record of the bound game is refused")
    void admits_fencedRecordOfTheBoundGame_isRefused() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, GAME)));
        given(executions.findBoundsForHandling()).willReturn(Optional.of(new Bounds(CASE, GAME)));
        given(eventFence.isFenced(TOPIC, 0, OFFSET)).willReturn(true);

        assertThat(scope().admits(record(GAME))).isFalse();
    }

    @Test
    @DisplayName("the fence is checked only once the execution row is locked, and the game bound after it")
    void admits_takesTheLockBeforeCheckingTheFence() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.findBoundsForBinding()).willReturn(Optional.of(new Bounds(CASE, null)));
        given(executions.bindGame(CASE, GAME)).willReturn(true);

        scope().admits(record(GAME));

        var order = inOrder(executions, eventFence);
        order.verify(executions).findBoundsForBinding();
        order.verify(eventFence).isFenced(TOPIC, 0, OFFSET);
        order.verify(executions).bindGame(CASE, GAME);
    }

    @Test
    @DisplayName("a record that saw a bound context but meets a newer unbound one under the lock never binds it")
    void admits_contextReopenedSinceTheUnlockedRead_isNotBound() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, OTHER)));
        given(executions.findBoundsForHandling()).willReturn(Optional.of(new Bounds(NEXT_CASE, null)));

        assertThat(scope().admits(record(GAME))).isFalse();
        verify(executions, never()).bindGame(any(), any());
    }

    @Test
    @DisplayName("a context closed since the unlocked read admits nothing")
    void admits_contextClosedSinceTheUnlockedRead_isRefused() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, GAME)));
        given(executions.findBoundsForHandling()).willReturn(Optional.empty());

        assertThat(scope().admits(record(GAME))).isFalse();
    }

    @Test
    @DisplayName("a record without a source position is decided by its game alone")
    void admits_recordWithoutPosition_isNotFenced() {
        given(executions.findBounds()).willReturn(Optional.of(new Bounds(CASE, GAME)));
        given(executions.findBoundsForHandling()).willReturn(Optional.of(new Bounds(CASE, GAME)));

        assertThat(scope().admits(new InboundRecord(null, null, null, GAME))).isTrue();
        verify(eventFence, never()).isFenced(any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("a record without a game id is refused")
    void admits_missingGame_isRefused() {
        assertThat(scope().admits(record(null))).isFalse();
    }
}
