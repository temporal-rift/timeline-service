package io.github.temporalrift.timeline.domain.execution;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import io.github.temporalrift.timeline.domain.membership.MemberFaction;

/** The immutable deterministic inputs of an isolated case; times are kept at microsecond precision. */
public record ExecutionContext(
        UUID caseKey,
        Seed seed,
        EntropyVersion entropyVersion,
        String manifestDigest,
        Instant logicalTime,
        List<SimulationSeat> seats) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final int MIN_SEATS = 3;
    private static final int MAX_SEATS = 5;

    public ExecutionContext {
        Objects.requireNonNull(caseKey, "caseKey");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(entropyVersion, "entropyVersion");
        logicalTime = Objects.requireNonNull(logicalTime, "logicalTime").truncatedTo(ChronoUnit.MICROS);
        if (manifestDigest == null || !SHA256_HEX.matcher(manifestDigest).matches()) {
            throw new InvalidExecutionContextException("manifestDigest must be a lowercase SHA-256 hex digest");
        }
        seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
        requireValidSeats(seats);
    }

    /** The seat a player occupies, or {@code null} for an identity outside the configured case. */
    public SimulationSeat seatOf(UUID playerId) {
        return seats.stream()
                .filter(seat -> seat.playerId().equals(playerId))
                .findFirst()
                .orElse(null);
    }

    private static void requireValidSeats(List<SimulationSeat> seats) {
        if (seats.size() < MIN_SEATS || seats.size() > MAX_SEATS) {
            throw new InvalidExecutionContextException("seats must hold between 3 and 5 entries");
        }
        if (!IntStream.range(0, seats.size()).allMatch(index -> seats.get(index).seatIndex() == index)) {
            throw new InvalidExecutionContextException("seat indices must be contiguous from 0 in order");
        }
        if (seats.stream().anyMatch(seat -> seat.playerId() == null || seat.faction() == null)) {
            throw new InvalidExecutionContextException("every seat needs a player and a faction");
        }
        var players = new HashSet<UUID>();
        var factions = EnumSet.noneOf(MemberFaction.class);
        for (var seat : seats) {
            if (!players.add(seat.playerId())) {
                throw new InvalidExecutionContextException("a player may occupy only one seat");
            }
            if (!factions.add(seat.faction())) {
                throw new InvalidExecutionContextException("a faction may be assigned to only one seat");
            }
        }
    }
}
