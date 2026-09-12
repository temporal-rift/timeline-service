package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JpaWeaverChainRepositoryPolicyTest {

    @Test
    void isSnapshotDue_boundarySizes_trigger() {
        assertThat(JpaWeaverChainRepository.isSnapshotDue(20)).isTrue();
        assertThat(JpaWeaverChainRepository.isSnapshotDue(40)).isTrue();
    }

    @Test
    void isSnapshotDue_offBoundarySizes_doNotTrigger() {
        assertThat(JpaWeaverChainRepository.isSnapshotDue(0)).isFalse();
        assertThat(JpaWeaverChainRepository.isSnapshotDue(1)).isFalse();
        assertThat(JpaWeaverChainRepository.isSnapshotDue(19)).isFalse();
        assertThat(JpaWeaverChainRepository.isSnapshotDue(21)).isFalse();
    }
}
