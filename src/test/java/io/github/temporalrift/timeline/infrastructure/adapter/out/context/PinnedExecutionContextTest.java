package io.github.temporalrift.timeline.infrastructure.adapter.out.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.ExecutionClosed;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;

@ExtendWith(MockitoExtension.class)
class PinnedExecutionContextTest {

    @Mock
    ExecutionRepository executions;

    @Test
    @DisplayName("the context and its flat rules are read once per context")
    void current_readsOnceAndCachesTheRules() {
        var context = ExecutionContextTestData.context("42");
        given(executions.find()).willReturn(Optional.of(Execution.configure(context)));
        var pinned = new PinnedExecutionContext(executions);

        assertThat(pinned.current()).contains(context);
        assertThat(pinned.current()).contains(context);
        assertThat(pinned.currentRules().orElseThrow().digest()).isEqualTo(context.rulesDigest());

        verify(executions, times(1)).find();
    }

    @Test
    @DisplayName("a close clears the pinned context and rules")
    void onClosed_clearsThePins() {
        var context = ExecutionContextTestData.context("42");
        given(executions.find()).willReturn(Optional.of(Execution.configure(context)));
        var pinned = new PinnedExecutionContext(executions);
        assertThat(pinned.currentRules()).isPresent();

        pinned.onClosed(new ExecutionClosed(context.caseKey()));
        given(executions.find()).willReturn(Optional.empty());

        assertThat(pinned.current()).isEmpty();
        assertThat(pinned.currentRules()).isEmpty();
    }

    @Test
    @DisplayName("rules are rebuilt when the open context's digest changes")
    void currentRules_rebuildsForANewDigest() {
        ExecutionContext first = ExecutionContextTestData.context("42");
        var properties = ExecutionContextTestData.baselineProperties();
        properties.put("game.rules.probability.push-shift.II", 25);
        ExecutionContext second = ExecutionContextTestData.context("42", ExecutionContextTestData.rules(properties));
        given(executions.find())
                .willReturn(Optional.of(Execution.configure(first)))
                .willReturn(Optional.of(Execution.configure(second)));
        var pinned = new PinnedExecutionContext(executions);
        assertThat(pinned.currentRules().orElseThrow().digest()).isEqualTo(first.rulesDigest());

        pinned.onClosed(new ExecutionClosed(first.caseKey()));

        assertThat(pinned.currentRules().orElseThrow().digest()).isEqualTo(second.rulesDigest());
    }

    @Test
    @DisplayName("requiring a context without one reports it is not configured")
    void require_withoutContext_isNotConfigured() {
        given(executions.find()).willReturn(Optional.empty());

        assertThatThrownBy(() -> new PinnedExecutionContext(executions).require())
                .isInstanceOf(ExecutionNotConfiguredException.class);
    }
}
