package io.github.temporalrift.timeline.application.port.in;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.event.TerminalResolution;

/**
 * Driving port: decides every CASCADE armed in an era once its target event's terminal state is known. A CASCADE
 * takes effect only when its erased outcome's event carries into the next era; armed rows whose event is absent
 * from {@code terminalResolutions} (still paradox-pending) are left for a later call.
 */
public interface SettleCascadeCarryForwardUseCase {

    void settle(UUID gameId, int eraNumber, List<TerminalResolution> terminalResolutions);
}
