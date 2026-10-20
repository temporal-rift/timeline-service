package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.TestcontainersConfiguration;
import io.github.temporalrift.timeline.TimelineServiceApplication;
import io.github.temporalrift.timeline.contract.AggregateSnapshotPortContract;
import io.github.temporalrift.timeline.contract.CascadeCarryForwardPortContract;
import io.github.temporalrift.timeline.contract.EraPlayersPortContract;
import io.github.temporalrift.timeline.contract.EventStorePortContract;
import io.github.temporalrift.timeline.contract.ExecutionProbeContract;
import io.github.temporalrift.timeline.contract.FutureEventEraIndexPortContract;
import io.github.temporalrift.timeline.contract.GameMembershipPortContract;
import io.github.temporalrift.timeline.contract.ParadoxResolutionPhaseRepositoryContract;
import io.github.temporalrift.timeline.contract.ProcessedEventPortContract;
import io.github.temporalrift.timeline.contract.RoundActionBufferPortContract;
import io.github.temporalrift.timeline.contract.ScanEntitlementPortContract;
import io.github.temporalrift.timeline.contract.SimulationRepositoriesContract;
import io.github.temporalrift.timeline.contract.WeaverChainSagaRepositoryContract;
import io.github.temporalrift.timeline.domain.port.out.AggregateSnapshotPort;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;
import io.github.temporalrift.timeline.domain.port.out.ClockOperationRepository;
import io.github.temporalrift.timeline.domain.port.out.EntropyDecisionRepository;
import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;
import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.GameMembershipPort;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.ScanEntitlementPort;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;

/** The port contracts against the PostgreSQL-backed adapters: the reference the in-memory adapters must match. */
@DataJpaTest(properties = "game.simulation.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TimelineServiceApplication.class)
@Import({
    TestcontainersConfiguration.class,
    JpaPortContractTest.Beans.class,
    JpaAggregateSnapshotAdapter.class,
    JpaCascadeCarryForwardAdapter.class,
    JpaEraPlayersAdapter.class,
    JpaEventStoreAdapter.class,
    JpaFutureEventEraIndexAdapter.class,
    JpaGameMembershipAdapter.class,
    JpaParadoxResolutionPhaseAdapter.class,
    JpaProcessedEventAdapter.class,
    JpaRoundActionBufferAdapter.class,
    JpaScanEntitlementAdapter.class,
    JpaWeaverChainSagaAdapter.class,
    ClockOperationRepositoryAdapter.class,
    EntropyDecisionRepositoryAdapter.class,
    ExecutionProbeAdapter.class,
    ExecutionRepositoryAdapter.class
})
// The contracts commit, roll back and lock across threads, so no test-managed transaction may wrap them.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaPortContractTest
        implements AggregateSnapshotPortContract,
                CascadeCarryForwardPortContract,
                EraPlayersPortContract,
                EventStorePortContract,
                ExecutionProbeContract,
                FutureEventEraIndexPortContract,
                GameMembershipPortContract,
                ParadoxResolutionPhaseRepositoryContract,
                ProcessedEventPortContract,
                RoundActionBufferPortContract,
                ScanEntitlementPortContract,
                SimulationRepositoriesContract,
                WeaverChainSagaRepositoryContract {

    static class Beans {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }
    }

    @Autowired
    AggregateSnapshotPort snapshots;

    @Autowired
    CascadeCarryForwardPort carryForwards;

    @Autowired
    EraPlayersPort eraPlayers;

    @Autowired
    EventStorePort eventStore;

    @Autowired
    ExecutionProbe probe;

    @Autowired
    FutureEventEraIndexPort eraIndex;

    @Autowired
    GameMembershipPort memberships;

    @Autowired
    ParadoxResolutionPhaseRepository phases;

    @Autowired
    ProcessedEventPort processedEvents;

    @Autowired
    RoundActionBufferPort actionBuffer;

    @Autowired
    ScanEntitlementPort scanEntitlements;

    @Autowired
    ExecutionRepository executions;

    @Autowired
    ClockOperationRepository clockOperations;

    @Autowired
    EntropyDecisionRepository entropyDecisions;

    @Autowired
    WeaverChainSagaRepository chainSagas;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** The probe and the single execution row describe the whole lane, so each test starts from an empty one. */
    @BeforeEach
    void emptyLane() {
        jdbcTemplate.execute("TRUNCATE TABLE simulation_seat, simulation_execution, simulation_clock_operation, "
                + "simulation_closed_execution, simulation_closed_game, "
                + "game_membership, processed_events, paradox_resolution_phase, outbox_events");
    }

    @Override
    public TransactionOperations transactions() {
        return new TransactionTemplate(transactionManager);
    }

    @Override
    public AggregateSnapshotPort snapshots() {
        return snapshots;
    }

    @Override
    public CascadeCarryForwardPort carryForwards() {
        return carryForwards;
    }

    @Override
    public EraPlayersPort eraPlayers() {
        return eraPlayers;
    }

    @Override
    public EventStorePort eventStore() {
        return eventStore;
    }

    @Override
    public ExecutionProbe probe() {
        return probe;
    }

    @Override
    public ExecutionRepository probeExecutions() {
        return executions;
    }

    @Override
    public ProcessedEventPort probeProcessedEvents() {
        return processedEvents;
    }

    @Override
    public ParadoxResolutionPhaseRepository probePhases() {
        return phases;
    }

    @Override
    public FutureEventEraIndexPort eraIndex() {
        return eraIndex;
    }

    @Override
    public GameMembershipPort memberships() {
        return memberships;
    }

    @Override
    public ParadoxResolutionPhaseRepository phases() {
        return phases;
    }

    @Override
    public ProcessedEventPort processedEvents() {
        return processedEvents;
    }

    @Override
    public RoundActionBufferPort actionBuffer() {
        return actionBuffer;
    }

    @Override
    public ScanEntitlementPort scanEntitlements() {
        return scanEntitlements;
    }

    @Override
    public ExecutionRepository executions() {
        return executions;
    }

    @Override
    public ClockOperationRepository clockOperations() {
        return clockOperations;
    }

    @Override
    public EntropyDecisionRepository entropyDecisions() {
        return entropyDecisions;
    }

    @Override
    public WeaverChainSagaRepository chainSagas() {
        return chainSagas;
    }
}
