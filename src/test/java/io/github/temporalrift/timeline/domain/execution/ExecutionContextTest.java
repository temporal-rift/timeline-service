package io.github.temporalrift.timeline.domain.execution;

import static io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData.CASE_KEY;
import static io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData.MANIFEST_DIGEST;
import static io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData.PLAYER_0;
import static io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData.PLAYER_1;
import static io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData.START;
import static io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData.threeSeats;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.temporalrift.timeline.domain.membership.MemberFaction;

class ExecutionContextTest {

    private static final Seed SEED = new Seed("1");

    @ParameterizedTest
    @ValueSource(strings = {"0", "42", "18446744073709551615"})
    @DisplayName("a seed inside the unsigned 64-bit range is accepted")
    void seed_insideRange_isAccepted(String decimal) {
        assertThat(new Seed(decimal).decimal()).isEqualTo(decimal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "042", "18446744073709551616", "1.5", "abc", "99999999999999999999"})
    @DisplayName("a seed that is not a canonical unsigned 64-bit decimal is rejected")
    void seed_outsideRangeOrNonCanonical_isRejected(String decimal) {
        assertThatThrownBy(() -> new Seed(decimal)).isInstanceOf(InvalidExecutionContextException.class);
    }

    @Test
    @DisplayName("a manifest digest must be lowercase SHA-256 hex")
    void manifestDigest_notLowercaseSha256_isRejected() {
        var lowercaseRequired = "A".repeat(64);
        var seats = threeSeats();

        assertThatThrownBy(() ->
                        new ExecutionContext(CASE_KEY, SEED, EntropyVersion.SHA256_V1, lowercaseRequired, START, seats))
                .isInstanceOf(InvalidExecutionContextException.class);
    }

    @Test
    @DisplayName("fewer than three or more than five seats are rejected")
    void seats_outsideSupportedRange_areRejected() {
        var two = threeSeats().subList(0, 2);

        assertThatThrownBy(() -> context(two)).isInstanceOf(InvalidExecutionContextException.class);
    }

    @Test
    @DisplayName("seat indices must be contiguous from zero in order")
    void seats_withGapInIndices_areRejected() {
        var seats = List.of(
                new SimulationSeat(0, PLAYER_0, MemberFaction.ERASERS),
                new SimulationSeat(1, PLAYER_1, MemberFaction.PROPHETS),
                new SimulationSeat(3, ExecutionContextTestData.PLAYER_2, MemberFaction.WEAVERS));

        assertThatThrownBy(() -> context(seats)).isInstanceOf(InvalidExecutionContextException.class);
    }

    @Test
    @DisplayName("one player may not occupy two seats")
    void seats_withRepeatedPlayer_areRejected() {
        var seats = List.of(
                new SimulationSeat(0, PLAYER_0, MemberFaction.ERASERS),
                new SimulationSeat(1, PLAYER_1, MemberFaction.PROPHETS),
                new SimulationSeat(2, PLAYER_0, MemberFaction.WEAVERS));

        assertThatThrownBy(() -> context(seats)).isInstanceOf(InvalidExecutionContextException.class);
    }

    @Test
    @DisplayName("one faction may not be assigned to two seats")
    void seats_withRepeatedFaction_areRejected() {
        var seats = List.of(
                new SimulationSeat(0, PLAYER_0, MemberFaction.ERASERS),
                new SimulationSeat(1, PLAYER_1, MemberFaction.ERASERS),
                new SimulationSeat(2, ExecutionContextTestData.PLAYER_2, MemberFaction.WEAVERS));

        assertThatThrownBy(() -> context(seats)).isInstanceOf(InvalidExecutionContextException.class);
    }

    @Test
    @DisplayName("logical time is held at storage precision so an identical repeat compares equal after a round trip")
    void logicalTime_isTruncatedToMicroseconds() {
        var withNanos = new ExecutionContext(
                CASE_KEY,
                new Seed("1"),
                EntropyVersion.SHA256_V1,
                MANIFEST_DIGEST,
                Instant.parse("2026-03-01T10:00:00.123456789Z"),
                threeSeats());

        assertThat(withNanos.logicalTime()).isEqualTo(Instant.parse("2026-03-01T10:00:00.123456Z"));
    }

    private static ExecutionContext context(List<SimulationSeat> seats) {
        return new ExecutionContext(CASE_KEY, new Seed("1"), EntropyVersion.SHA256_V1, MANIFEST_DIGEST, START, seats);
    }
}
