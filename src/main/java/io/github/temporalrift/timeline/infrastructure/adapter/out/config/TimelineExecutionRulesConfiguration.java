package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import java.util.List;
import java.util.stream.Stream;

import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.temporalrift.timeline.domain.execution.InvalidExecutionRulesException;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRules;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRulesValidator;
import io.github.temporalrift.timeline.domain.port.out.FactionBetWindowRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ForesightRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ParadoxContestRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ParadoxDetectionRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityBandRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.TapestryRulesPort;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainRulesPort;

/**
 * In an isolated simulation deployment the probability, paradox-resolution and Weaver chain rules are those of the
 * open execution context. The deployment's own records stay the fallback while no context is open.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class TimelineExecutionRulesConfiguration {

    private static final String RULES_PREFIX = "game.rules";
    private static final String PROBABILITY_PREFIX = RULES_PREFIX + ".probability";
    private static final String VICTORY_PREFIX = RULES_PREFIX + ".victory";

    private static final List<String> REQUIRED_RULE_KEYS = Stream.of(
                    ExecutionRulesBinding.requiredKeys(TimelineRulesProperties.class, PROBABILITY_PREFIX),
                    ExecutionRulesBinding.requiredKeys(ParadoxResolutionRulesProperties.class, RULES_PREFIX),
                    ExecutionRulesBinding.requiredKeys(WeaverChainRulesProperties.class, VICTORY_PREFIX))
            .flatMap(List::stream)
            .toList();

    @Bean
    ExecutionRulesValidator executionRequiredRulesValidator() {
        return properties -> REQUIRED_RULE_KEYS.stream()
                .filter(key -> !properties.containsKey(key))
                .findFirst()
                .ifPresent(key -> {
                    throw new InvalidExecutionRulesException(key, "is required");
                });
    }

    @Bean
    @Primary
    ProbabilityRulesPort executionProbabilityRules(
            ExecutionRules active, TimelineRulesProperties deployment, Validator validator) {
        return ExecutionRulesBinding.servedFrom(
                ProbabilityRulesPort.class, active, probabilityBinding(validator), deployment);
    }

    @Bean
    @Primary
    ProbabilityBandRulesPort executionProbabilityBands(
            ExecutionRules active, TimelineRulesProperties deployment, Validator validator) {
        return ExecutionRulesBinding.servedFrom(
                ProbabilityBandRulesPort.class, active, probabilityBinding(validator), deployment);
    }

    @Bean
    @Primary
    ParadoxResolutionRulesPort executionParadoxResolutionRules(
            ExecutionRules active, ParadoxResolutionRulesProperties deployment, Validator validator) {
        return ExecutionRulesBinding.servedFrom(
                ParadoxResolutionRulesPort.class, active, paradoxBinding(validator), deployment);
    }

    @Bean
    @Primary
    WeaverChainRulesPort executionWeaverChainRules(
            ExecutionRules active, WeaverChainRulesProperties deployment, Validator validator) {
        return ExecutionRulesBinding.servedFrom(
                WeaverChainRulesPort.class, active, weaverChainBinding(validator), deployment);
    }

    @Bean
    ExecutionRulesValidator executionWeaverChainRulesValidator(Validator validator) {
        return weaverChainBinding(validator).validator();
    }

    @Bean
    @Primary
    TapestryRulesPort executionTapestryRules(
            ExecutionRules active, TapestryRulesProperties deployment, Validator validator) {
        return ExecutionRulesBinding.servedFrom(
                TapestryRulesPort.class, active, tapestryBinding(validator), deployment);
    }

    @Bean
    ExecutionRulesValidator executionTapestryRulesValidator(Validator validator) {
        return tapestryBinding(validator).validator();
    }

    @Bean
    @Primary
    ForesightRulesPort executionForesightRules(
            ExecutionRules active, ForesightRulesProperties deployment, Validator validator) {
        return ExecutionRulesBinding.servedFrom(
                ForesightRulesPort.class, active, foresightBinding(validator), deployment);
    }

    @Bean
    ExecutionRulesValidator executionForesightRulesValidator(Validator validator) {
        return foresightBinding(validator).validator();
    }

    @Bean
    @Primary
    FactionBetWindowRulesPort executionFactionBetWindowRules(
            ExecutionRules active, FactionBetWindowRulesProperties deployment, Validator validator) {
        return ExecutionRulesBinding.servedFrom(
                FactionBetWindowRulesPort.class, active, factionBetWindowBinding(validator), deployment);
    }

    @Bean
    ExecutionRulesValidator executionFactionBetWindowRulesValidator(Validator validator) {
        return factionBetWindowBinding(validator).validator();
    }

    @Bean
    @Primary
    ParadoxDetectionRulesPort executionParadoxDetectionRules(
            ExecutionRules active, ParadoxRulesProperties deployment, Validator validator) {
        return ExecutionRulesBinding.servedFrom(
                ParadoxDetectionRulesPort.class, active, paradoxSwitchesBinding(validator), deployment);
    }

    @Bean
    @Primary
    ParadoxContestRulesPort executionParadoxContestRules(
            ExecutionRules active, ParadoxRulesProperties deployment, Validator validator) {
        return ExecutionRulesBinding.servedFrom(
                ParadoxContestRulesPort.class, active, paradoxSwitchesBinding(validator), deployment);
    }

    @Bean
    ExecutionRulesValidator executionParadoxSwitchesValidator(Validator validator) {
        return paradoxSwitchesBinding(validator).validator();
    }

    @Bean
    ExecutionRulesValidator executionProbabilityRulesValidator(Validator validator) {
        return probabilityBinding(validator).validator();
    }

    @Bean
    ExecutionRulesValidator executionParadoxResolutionRulesValidator(Validator validator) {
        return paradoxBinding(validator).validator();
    }

    private static ExecutionRulesBinding<TimelineRulesProperties> probabilityBinding(Validator validator) {
        return new ExecutionRulesBinding<>(TimelineRulesProperties.class, PROBABILITY_PREFIX, validator);
    }

    private static ExecutionRulesBinding<ParadoxResolutionRulesProperties> paradoxBinding(Validator validator) {
        return new ExecutionRulesBinding<>(ParadoxResolutionRulesProperties.class, RULES_PREFIX, validator);
    }

    private static ExecutionRulesBinding<WeaverChainRulesProperties> weaverChainBinding(Validator validator) {
        return new ExecutionRulesBinding<>(WeaverChainRulesProperties.class, VICTORY_PREFIX, validator);
    }

    private static ExecutionRulesBinding<TapestryRulesProperties> tapestryBinding(Validator validator) {
        return new ExecutionRulesBinding<>(TapestryRulesProperties.class, RULES_PREFIX, validator);
    }

    private static ExecutionRulesBinding<ForesightRulesProperties> foresightBinding(Validator validator) {
        return new ExecutionRulesBinding<>(ForesightRulesProperties.class, RULES_PREFIX + ".foresight", validator);
    }

    private static ExecutionRulesBinding<FactionBetWindowRulesProperties> factionBetWindowBinding(Validator validator) {
        return new ExecutionRulesBinding<>(FactionBetWindowRulesProperties.class, RULES_PREFIX, validator);
    }

    private static ExecutionRulesBinding<ParadoxRulesProperties> paradoxSwitchesBinding(Validator validator) {
        return new ExecutionRulesBinding<>(ParadoxRulesProperties.class, RULES_PREFIX + ".paradox", validator);
    }
}
