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
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityBandRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;

/**
 * In an isolated simulation deployment the probability and paradox-resolution rules are those of the open execution
 * context. The deployment's own records stay the fallback while no context is open.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class TimelineExecutionRulesConfiguration {

    private static final List<String> GRADES = List.of("I", "II", "III");
    private static final List<String> REQUIRED_RULE_KEYS = requiredRuleKeys();

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
    ExecutionRulesValidator executionProbabilityRulesValidator(Validator validator) {
        return probabilityBinding(validator).validator();
    }

    @Bean
    ExecutionRulesValidator executionParadoxResolutionRulesValidator(Validator validator) {
        return paradoxBinding(validator).validator();
    }

    private static ExecutionRulesBinding<TimelineRulesProperties> probabilityBinding(Validator validator) {
        return new ExecutionRulesBinding<>(TimelineRulesProperties.class, "game.rules.probability", validator);
    }

    private static ExecutionRulesBinding<ParadoxResolutionRulesProperties> paradoxBinding(Validator validator) {
        return new ExecutionRulesBinding<>(ParadoxResolutionRulesProperties.class, "game.rules", validator);
    }

    /**
     * The keys the service reads. Primitive binding cannot tell a missing scalar from its default, so presence is
     * checked explicitly before the binding's own range validation.
     */
    private static List<String> requiredRuleKeys() {
        return Stream.concat(
                        GRADES.stream()
                                .flatMap(grade -> Stream.of(
                                        "game.rules.probability.push-shift." + grade,
                                        "game.rules.probability.suppress-shift." + grade,
                                        "game.rules.probability.swing-shift." + grade,
                                        "game.rules.probability.amplify-multiplier." + grade)),
                        Stream.of(
                                "game.rules.probability.floor",
                                "game.rules.probability.ceiling",
                                "game.rules.probability.momentum-bonus",
                                "game.rules.probability.rally-multiplier",
                                "game.rules.probability.thread-shift",
                                "game.rules.probability.band-low-max",
                                "game.rules.probability.band-medium-max",
                                "game.rules.paradox-resolution-timer-seconds"))
                .toList();
    }
}
