package io.github.temporalrift.timeline.domain.futureevent;

import java.util.List;

/**
 * The domain events to persist, in order, plus whether each input shift moved any weight when evaluated alone —
 * aligned by index with the shifts passed to {@link FutureEvent#applySimultaneousShifts}.
 */
public record SimultaneousShiftResult(List<Object> events, List<Boolean> movedAlone) {

    public SimultaneousShiftResult {
        events = List.copyOf(events);
        movedAlone = List.copyOf(movedAlone);
    }
}
