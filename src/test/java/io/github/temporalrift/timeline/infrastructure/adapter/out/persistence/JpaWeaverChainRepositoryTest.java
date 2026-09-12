package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
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
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.eventstore.AggregateSnapshot;
import io.github.temporalrift.timeline.domain.port.out.AggregateSnapshotPort;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainRepository;
import io.github.temporalrift.timeline.domain.weaverchain.ChainStatus;
import io.github.temporalrift.timeline.domain.weaverchain.ResolvedOutcome;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChainNotFoundException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    TestcontainersConfiguration.class,
    JpaWeaverChainRepositoryTest.TestConfig.class,
    JpaAggregateSnapshotAdapter.class,
    JpaEventStoreAdapter.class,
    EventStoreAppender.class,
    JpaWeaverChainRepository.class
})
class JpaWeaverChainRepositoryTest {

    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    WeaverChainRepository chains;

    @Autowired
    AggregateSnapshotPort snapshots;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    Clock clock;

    @Test
    void find_noStream_throwsNotFound() {
        assertThatThrownBy(() -> chains.findById(UUID.randomUUID())).isInstanceOf(WeaverChainNotFoundException.class);
    }

    @Test
    void append_thenFind_replaysChain() {
        var chainId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        chains.append(chainId, new WeaverChainStarted(chainId, playerId, gameId));
        chains.append(chainId, new ChainLinkAdded(chainId, eventId, outcomeId, 1));

        var chain = chains.findById(chainId);

        assertThat(chain.length()).isEqualTo(1);
        assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
        assertThat(chain.playerId()).isEqualTo(playerId);
        assertThat(chain.gameId()).isEqualTo(gameId);
        assertThat(chain.links().getFirst().eventId()).isEqualTo(eventId);
    }

    @Test
    void find_withSnapshot_loadsViaSnapshotPlusTail() {
        var chainId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var firstEvent = UUID.randomUUID();
        var firstOutcome = UUID.randomUUID();
        chains.append(chainId, new WeaverChainStarted(chainId, playerId, gameId));
        chains.append(chainId, new ChainLinkAdded(chainId, firstEvent, firstOutcome, 1));
        var covered = chains.findById(chainId);
        snapshots.save(new AggregateSnapshot(
                chainId, "WeaverChain", objectMapper.writeValueAsString(covered.snapshot()), 2, clock.instant()));

        var secondEvent = UUID.randomUUID();
        var secondOutcome = UUID.randomUUID();
        chains.append(chainId, new ChainLinkAdded(chainId, secondEvent, secondOutcome, 2));

        var chain = chains.findById(chainId);

        assertThat(chain.length()).isEqualTo(2);
        assertThat(chain.status()).isEqualTo(ChainStatus.ACTIVE);
        assertThat(chain.links())
                .extracting(link -> new ResolvedOutcome(link.eventId(), link.outcomeId()))
                .containsExactly(
                        new ResolvedOutcome(firstEvent, firstOutcome), new ResolvedOutcome(secondEvent, secondOutcome));
    }

    @Test
    void find_snapshotCoveringFullStream_loadsFromSnapshotAlone() {
        var chainId = UUID.randomUUID();
        chains.append(chainId, new WeaverChainStarted(chainId, UUID.randomUUID(), UUID.randomUUID()));
        var covered = chains.findById(chainId);
        snapshots.save(new AggregateSnapshot(
                chainId, "WeaverChain", objectMapper.writeValueAsString(covered.snapshot()), 1, clock.instant()));

        var chain = chains.findById(chainId);

        assertThat(chain.snapshot()).isEqualTo(covered.snapshot());
    }

    @Test
    void appendAll_batchOfLinkPlusCompletion_loadsAsCompleted() {
        var chainId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        chains.append(chainId, new WeaverChainStarted(chainId, playerId, gameId));
        var resolved = new HashSet<ResolvedOutcome>();
        var facts = new ArrayList<Object>();
        var live = chains.findById(chainId);
        for (int era = 1; era <= 3; era++) {
            var eventId = UUID.randomUUID();
            var outcomeId = UUID.randomUUID();
            resolved.add(new ResolvedOutcome(eventId, outcomeId));
            if (era < 3) {
                for (var fact : live.addLink(eventId, outcomeId, era, resolved)) {
                    chains.append(chainId, fact);
                }
                live = chains.findById(chainId);
            } else {
                facts.addAll(live.addLink(eventId, outcomeId, era, resolved));
            }
        }

        chains.appendAll(chainId, facts);

        var chain = chains.findById(chainId);
        assertThat(chain.length()).isEqualTo(3);
        assertThat(chain.status()).isEqualTo(ChainStatus.COMPLETED);
    }
}
