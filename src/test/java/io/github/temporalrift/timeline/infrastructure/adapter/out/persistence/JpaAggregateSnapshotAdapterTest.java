package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.TestcontainersConfiguration;
import io.github.temporalrift.timeline.domain.port.out.AggregateSnapshotPort;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    TestcontainersConfiguration.class,
    JpaAggregateSnapshotAdapterTest.TestConfig.class,
    JpaAggregateSnapshotAdapter.class,
    JpaEventStoreAdapter.class
})
class JpaAggregateSnapshotAdapterTest {

    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }
    }

    @Autowired
    AggregateSnapshotPort snapshots;

    @Autowired
    EventStorePort events;

    @Test
    void find_noSnapshot_returnsEmpty() {
        assertThat(snapshots.findByAggregateId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void save_thenFind_returnsSnapshot() {
        var aggregateId = UUID.randomUUID();
        var snapshot = TestSnapshots.snapshot(aggregateId, 20);

        snapshots.save(snapshot);

        assertThat(snapshots.findByAggregateId(aggregateId)).contains(snapshot);
    }

    @Test
    void save_twice_overwritesPreviousSnapshot() {
        var aggregateId = UUID.randomUUID();

        snapshots.save(TestSnapshots.snapshot(aggregateId, 20));
        var newer = TestSnapshots.snapshot(aggregateId, 40);
        snapshots.save(newer);

        assertThat(snapshots.findByAggregateId(aggregateId)).contains(newer);
    }

    @Test
    void save_doesNotAffectEventStoreStreams() {
        var aggregateId = UUID.randomUUID();

        snapshots.save(TestSnapshots.snapshot(aggregateId, 20));

        assertThat(events.readStream(aggregateId)).isEmpty();
    }
}
