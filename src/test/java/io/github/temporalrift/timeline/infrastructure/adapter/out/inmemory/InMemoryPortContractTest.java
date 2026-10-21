package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.temporalrift.timeline.contract.AggregateSnapshotPortContract;
import io.github.temporalrift.timeline.contract.CascadeCarryForwardPortContract;
import io.github.temporalrift.timeline.contract.EraPlayersPortContract;
import io.github.temporalrift.timeline.contract.EventStorePortContract;
import io.github.temporalrift.timeline.contract.ExecutionProbeContract;
import io.github.temporalrift.timeline.contract.FutureEventEraIndexPortContract;
import io.github.temporalrift.timeline.contract.GameMembershipPortContract;
import io.github.temporalrift.timeline.contract.GameplayDiscardContract;
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
import io.github.temporalrift.timeline.domain.port.out.GameplayDiscard;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.ScanEntitlementPort;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;

/**
 * The same contracts as the PostgreSQL-backed adapters run, against the in-memory adapters. Each test gets a fresh
 * context and so an empty lane.
 */
class InMemoryPortContractTest
        implements AggregateSnapshotPortContract,
                CascadeCarryForwardPortContract,
                EraPlayersPortContract,
                EventStorePortContract,
                ExecutionProbeContract,
                FutureEventEraIndexPortContract,
                GameMembershipPortContract,
                GameplayDiscardContract,
                ParadoxResolutionPhaseRepositoryContract,
                ProcessedEventPortContract,
                RoundActionBufferPortContract,
                ScanEntitlementPortContract,
                SimulationRepositoriesContract,
                WeaverChainSagaRepositoryContract {

    // Deliberately not a @Configuration: the application's component scan would otherwise pick it up in every context.
    @EnableTransactionManagement
    static class Wiring {
        @Bean
        InMemoryDatabase database() {
            return new InMemoryDatabase();
        }

        @Bean
        PlatformTransactionManager transactionManager(InMemoryDatabase database) {
            return new InMemoryTransactionManager(database);
        }
    }

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void startContext() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("in-memory");
        context.register(
                Wiring.class,
                InMemoryAggregateSnapshotAdapter.class,
                InMemoryCascadeCarryForwardAdapter.class,
                InMemoryClockOperationRepository.class,
                InMemoryEntropyDecisionRepository.class,
                InMemoryEraPlayersAdapter.class,
                InMemoryEventStoreAdapter.class,
                InMemoryExecutionProbe.class,
                InMemoryExecutionRepository.class,
                InMemoryFutureEventEraIndexAdapter.class,
                InMemoryGameMembershipAdapter.class,
                InMemoryGameplayDiscard.class,
                InMemoryOutboxStore.class,
                InMemoryParadoxResolutionPhaseAdapter.class,
                InMemoryProcessedEventAdapter.class,
                InMemoryRoundActionBufferAdapter.class,
                InMemoryScanEntitlementAdapter.class,
                InMemoryWeaverChainSagaAdapter.class);
        context.refresh();
    }

    @AfterEach
    void stopContext() {
        context.close();
    }

    @Override
    public TransactionOperations transactions() {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @Override
    public AggregateSnapshotPort snapshots() {
        return context.getBean(AggregateSnapshotPort.class);
    }

    @Override
    public CascadeCarryForwardPort carryForwards() {
        return context.getBean(CascadeCarryForwardPort.class);
    }

    @Override
    public EraPlayersPort eraPlayers() {
        return context.getBean(EraPlayersPort.class);
    }

    @Override
    public EventStorePort eventStore() {
        return context.getBean(EventStorePort.class);
    }

    @Override
    public ExecutionProbe probe() {
        return context.getBean(ExecutionProbe.class);
    }

    @Override
    public ExecutionRepository probeExecutions() {
        return executions();
    }

    @Override
    public ProcessedEventPort probeProcessedEvents() {
        return processedEvents();
    }

    @Override
    public GameplayDiscard gameplayDiscard() {
        return context.getBean(GameplayDiscard.class);
    }

    @Override
    public GameMembershipPort gameplayMemberships() {
        return memberships();
    }

    @Override
    public ExecutionRepository gameplayExecutions() {
        return executions();
    }

    @Override
    public ParadoxResolutionPhaseRepository probePhases() {
        return phases();
    }

    @Override
    public FutureEventEraIndexPort eraIndex() {
        return context.getBean(FutureEventEraIndexPort.class);
    }

    @Override
    public GameMembershipPort memberships() {
        return context.getBean(GameMembershipPort.class);
    }

    @Override
    public ParadoxResolutionPhaseRepository phases() {
        return context.getBean(ParadoxResolutionPhaseRepository.class);
    }

    @Override
    public ProcessedEventPort processedEvents() {
        return context.getBean(ProcessedEventPort.class);
    }

    @Override
    public RoundActionBufferPort actionBuffer() {
        return context.getBean(RoundActionBufferPort.class);
    }

    @Override
    public ScanEntitlementPort scanEntitlements() {
        return context.getBean(ScanEntitlementPort.class);
    }

    @Override
    public ExecutionRepository executions() {
        return context.getBean(ExecutionRepository.class);
    }

    @Override
    public ClockOperationRepository clockOperations() {
        return context.getBean(ClockOperationRepository.class);
    }

    @Override
    public EntropyDecisionRepository entropyDecisions() {
        return context.getBean(EntropyDecisionRepository.class);
    }

    @Override
    public WeaverChainSagaRepository chainSagas() {
        return context.getBean(WeaverChainSagaRepository.class);
    }
}
