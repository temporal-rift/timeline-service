package io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.mapstruct.Mapper;

import io.github.temporalrift.timeline.domain.execution.ClockAcknowledgement;
import io.github.temporalrift.timeline.domain.execution.ClockAdvance;
import io.github.temporalrift.timeline.domain.execution.ExecutionCheckpoint;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.Seed;
import io.github.temporalrift.timeline.domain.execution.SimulationSeat;
import io.github.temporalrift.timeline.domain.execution.SourceWatermark;

@Mapper(componentModel = "spring")
interface SimulationControlMapper {

    ExecutionContext toDomain(
            io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ExecutionContext body);

    SimulationSeat toDomain(
            io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.SimulationSeat seat);

    ClockAdvance toDomain(
            io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ClockAdvance body);

    io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ExecutionCheckpoint toModel(
            ExecutionCheckpoint checkpoint);

    io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.SourceWatermark toModel(
            SourceWatermark watermark);

    io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ClockAcknowledgement toModel(
            ClockAcknowledgement acknowledgement);

    default Seed toSeed(String decimal) {
        return new Seed(decimal);
    }

    default Instant toInstant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    default OffsetDateTime toUtc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
