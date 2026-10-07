package io.github.temporalrift.timeline.domain.execution;

import java.util.UUID;

import io.github.temporalrift.timeline.domain.membership.MemberFaction;

public record SimulationSeat(int seatIndex, UUID playerId, MemberFaction faction) {}
