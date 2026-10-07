package io.github.temporalrift.timeline.application.port.in;

import io.github.temporalrift.timeline.domain.execution.ClockAcknowledgement;
import io.github.temporalrift.timeline.domain.execution.ClockAdvance;

public interface AdvanceSimulationClockUseCase {

    ClockAcknowledgement handle(ClockAdvance advance);
}
