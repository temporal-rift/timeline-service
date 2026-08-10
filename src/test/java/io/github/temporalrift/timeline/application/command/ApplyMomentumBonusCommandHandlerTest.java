package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;

@ExtendWith(MockitoExtension.class)
class ApplyMomentumBonusCommandHandlerTest {

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    ProbabilityRulesPort rules;

    private ApplyMomentumBonusCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ApplyMomentumBonusCommandHandler(futureEvents, rules);
    }

    @Test
    void apply_increasesDeclaredOutcomeByConfiguredBonus() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.momentumBonus()).willReturn(10);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);

        handler.apply(eventId, a);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(60);
        assertThat(probabilityOf(futureEvent, a) + probabilityOf(futureEvent, b) + probabilityOf(futureEvent, c))
                .isEqualTo(100);
    }

    @Test
    void apply_sealedDeclaredOutcome_setsSealBreachInsteadOfApplying() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        futureEvent.sealOutcome(a);
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.momentumBonus()).willReturn(10);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);

        handler.apply(eventId, a);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(50);
        assertThat(futureEvent.sealBreach()).isTrue();
    }

    private static FutureEvent drafted(UUID id, Outcome... outcomes) {
        return FutureEvent.replay(id, List.of(new FutureEventDrafted(id, List.of(outcomes))));
    }

    private static Outcome outcome(UUID outcomeId, int probability) {
        return new Outcome(outcomeId, "outcome", probability);
    }

    private static int probabilityOf(FutureEvent futureEvent, UUID outcomeId) {
        return futureEvent.outcomes().stream()
                .filter(o -> o.outcomeId().equals(outcomeId))
                .findFirst()
                .orElseThrow()
                .probability();
    }
}
