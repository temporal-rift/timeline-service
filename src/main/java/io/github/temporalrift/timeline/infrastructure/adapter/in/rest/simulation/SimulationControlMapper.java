package io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.mapstruct.Mapper;

import io.github.temporalrift.timeline.domain.execution.ClockAcknowledgement;
import io.github.temporalrift.timeline.domain.execution.ClockAdvance;
import io.github.temporalrift.timeline.domain.execution.EffectiveRules;
import io.github.temporalrift.timeline.domain.execution.EntropyVersion;
import io.github.temporalrift.timeline.domain.execution.ExecutionCheckpoint;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.InvalidExecutionContextException;
import io.github.temporalrift.timeline.domain.execution.Seed;
import io.github.temporalrift.timeline.domain.execution.SimulationSeat;
import io.github.temporalrift.timeline.domain.execution.SourceWatermark;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;

@Mapper(componentModel = "spring")
interface SimulationControlMapper {

    default ExecutionContext toDomain(
            io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ExecutionContext body) {
        if (body == null) {
            throw new InvalidExecutionContextException("The execution context is required");
        }
        if (body.getRules() == null) {
            throw new InvalidExecutionContextException("rules are required");
        }
        if (body.getCaseKey() == null) {
            throw new InvalidExecutionContextException("caseKey is required");
        }
        if (body.getSeed() == null) {
            throw new InvalidExecutionContextException("seed is required");
        }
        if (body.getEntropyVersion() == null) {
            throw new InvalidExecutionContextException("entropyVersion is required");
        }
        if (body.getLogicalTime() == null) {
            throw new InvalidExecutionContextException("logicalTime is required");
        }
        if (body.getSeats() == null) {
            throw new InvalidExecutionContextException("seats are required");
        }
        return new ExecutionContext(
                body.getCaseKey(),
                new Seed(body.getSeed()),
                EntropyVersion.valueOf(body.getEntropyVersion().getValue()),
                body.getManifestDigest(),
                new EffectiveRules(body.getRules().getProperties()),
                body.getRulesDigest(),
                body.getLogicalTime().toInstant(),
                body.getSeats().stream().map(this::toDomain).toList());
    }

    SimulationSeat toDomain(
            io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.SimulationSeat seat);

    default MemberFaction toDomain(
            io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.Faction faction) {
        return faction == null ? null : MemberFaction.valueOf(faction.getValue());
    }

    ClockAdvance toDomain(
            io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ClockAdvance body);

    io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ExecutionCheckpoint toModel(
            ExecutionCheckpoint checkpoint);

    io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.SourceWatermark toModel(
            SourceWatermark watermark);

    io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ClockAcknowledgement toModel(
            ClockAcknowledgement acknowledgement);

    default Instant toInstant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    default OffsetDateTime toUtc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
