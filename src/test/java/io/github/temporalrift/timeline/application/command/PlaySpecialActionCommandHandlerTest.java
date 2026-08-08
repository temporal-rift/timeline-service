package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.application.port.in.PlaySpecialActionUseCase.SpecialAction;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.PendingCorruptPort;

@ExtendWith(MockitoExtension.class)
class PlaySpecialActionCommandHandlerTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 1;
    private static final int ROUND_NUMBER = 1;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    PendingCorruptPort pendingCorrupt;

    private PlaySpecialActionCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PlaySpecialActionCommandHandler(futureEvents, pendingCorrupt);
    }

    @Test
    void seal_marksTargetOutcomeSealed() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, outcomeId, 50);
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.play(new SpecialAction.Seal(eventId, outcomeId));

        assertThat(futureEvent.outcomes().getFirst().sealed()).isTrue();
    }

    @Test
    void annihilate_marksTargetOutcomeAnnihilated() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, outcomeId, 50);
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.play(new SpecialAction.Annihilate(eventId, outcomeId));

        assertThat(futureEvent.outcomes().getFirst().annihilated()).isTrue();
    }

    @Test
    void corrupt_bufferedUntilRoundCloses_notResolvedImmediately() {
        var targetPlayerId = UUID.randomUUID();

        handler.play(new SpecialAction.Corrupt(GAME_ID, ERA_NUMBER, ROUND_NUMBER, targetPlayerId));

        then(pendingCorrupt).should().save(GAME_ID, ERA_NUMBER, ROUND_NUMBER, targetPlayerId);
        then(futureEvents).shouldHaveNoInteractions();
    }

    @Test
    void noOp_doesNothing() {
        handler.play(new SpecialAction.NoOp());

        then(futureEvents).shouldHaveNoInteractions();
        then(pendingCorrupt).shouldHaveNoInteractions();
    }

    private static FutureEvent draftedFutureEvent(UUID eventId, UUID outcomeId, int probability) {
        return FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(
                        eventId,
                        List.of(
                                new Outcome(outcomeId, "d", probability),
                                new Outcome(UUID.randomUUID(), "e", 100 - probability)))));
    }
}
