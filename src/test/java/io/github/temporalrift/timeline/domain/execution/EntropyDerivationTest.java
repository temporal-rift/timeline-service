package io.github.temporalrift.timeline.domain.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntropyDerivationTest {

    private static final ExecutionContext CASE_42 = ExecutionContextTestData.context("42");
    private static final ExecutionContext CASE_43 = ExecutionContextTestData.context("43");

    @Test
    @DisplayName("the same seed, purpose, coordinate and draw index always derive the same word")
    void word_sameInputs_isRepeatable() {
        assertThat(EntropyDerivation.word(
                        CASE_42, "OUTCOME_RESOLUTION", "era=1;subject=e0000000-0000-4000-8000-000000000001", 0))
                .isEqualTo(EntropyDerivation.word(
                        CASE_42, "OUTCOME_RESOLUTION", "era=1;subject=e0000000-0000-4000-8000-000000000001", 0));
    }

    @Test
    @DisplayName("a different seed, purpose, coordinate or draw index derives a different word")
    void word_anyInputChanged_changesTheWord() {
        var base = EntropyDerivation.word(
                CASE_42, "OUTCOME_RESOLUTION", "era=1;subject=e0000000-0000-4000-8000-000000000001", 0);

        assertThat(EntropyDerivation.word(
                        CASE_43, "OUTCOME_RESOLUTION", "era=1;subject=e0000000-0000-4000-8000-000000000001", 0))
                .isNotEqualTo(base);
        assertThat(EntropyDerivation.word(
                        CASE_42, "EVENT_DECK_SHUFFLE", "era=1;subject=e0000000-0000-4000-8000-000000000001", 0))
                .isNotEqualTo(base);
        assertThat(EntropyDerivation.word(
                        CASE_42, "OUTCOME_RESOLUTION", "era=1;subject=e0000000-0000-4000-8000-000000000002", 0))
                .isNotEqualTo(base);
        assertThat(EntropyDerivation.word(
                        CASE_42, "OUTCOME_RESOLUTION", "era=1;subject=e0000000-0000-4000-8000-000000000001", 1))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("length-prefixing keeps adjacent components from aliasing each other")
    void word_shiftedBoundaryBetweenComponents_doesNotAlias() {
        assertThat(EntropyDerivation.word(CASE_42, "AB", "C", 0))
                .isNotEqualTo(EntropyDerivation.word(CASE_42, "A", "BC", 0));
    }

    @Test
    @DisplayName("a gameplay identity is stable for the case and does not depend on the seed")
    void identity_isStableForTheCase() {
        var first = EntropyDerivation.identity(
                CASE_42, "CARD_INSTANCE", "era=1;subject=e0000000-0000-4000-8000-000000000001;slot=0");

        assertThat(EntropyDerivation.identity(
                        CASE_42, "CARD_INSTANCE", "era=1;subject=e0000000-0000-4000-8000-000000000001;slot=0"))
                .isEqualTo(first);
        assertThat(EntropyDerivation.identity(
                        CASE_43, "CARD_INSTANCE", "era=1;subject=e0000000-0000-4000-8000-000000000001;slot=0"))
                .isEqualTo(first);
    }

    @Test
    @DisplayName("distinct kinds or coordinates yield distinct identities, and a different case yields different ones")
    void identity_distinctInputs_yieldDistinctValues() {
        var base = EntropyDerivation.identity(CASE_42, "CARD_INSTANCE", "era=1;slot=0");
        var otherCase = new ExecutionContext(
                UUID.fromString("c0ffee00-0000-4000-8000-000000000002"),
                new Seed("42"),
                EntropyVersion.SHA256_V1,
                ExecutionContextTestData.MANIFEST_DIGEST,
                ExecutionContextTestData.START,
                ExecutionContextTestData.threeSeats());

        assertThat(EntropyDerivation.identity(CASE_42, "DECOY_CARD_INSTANCE", "era=1;slot=0"))
                .isNotEqualTo(base);
        assertThat(EntropyDerivation.identity(CASE_42, "CARD_INSTANCE", "era=1;slot=1"))
                .isNotEqualTo(base);
        assertThat(EntropyDerivation.identity(otherCase, "CARD_INSTANCE", "era=1;slot=0"))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("an identity is a well-formed name-based UUID")
    void identity_hasNameBasedVersionAndVariant() {
        var identity = EntropyDerivation.identity(CASE_42, "GAME", "");

        assertThat(identity.version()).isEqualTo(5);
        assertThat(identity.variant()).isEqualTo(2);
    }
}
