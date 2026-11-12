package io.github.temporalrift.timeline.domain.futureevent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One erasure a {@link FutureEvent} received this era, kept as the weights just before and just after it so its own
 * redistribution can be given back later. {@code erasedOutcomeIds} are the outcomes it newly annihilated, in event
 * order; every other outcome received the share {@code after - before}.
 */
record Erasure(List<UUID> erasedOutcomeIds, Map<UUID, Integer> weightsBefore, Map<UUID, Integer> weightsAfter) {

    Erasure {
        erasedOutcomeIds = List.copyOf(erasedOutcomeIds);
        weightsBefore = Map.copyOf(weightsBefore);
        weightsAfter = Map.copyOf(weightsAfter);
    }

    /**
     * The erasure that turned {@code before} into {@code after}, or empty when no outcome holding weight became
     * annihilated. An outcome erased at 0 took nothing from the draw, so it is never judged, even when the bounds made
     * the survivors rebalance.
     */
    static Optional<Erasure> between(List<Outcome> before, List<Outcome> after) {
        var weightedAndEligible = before.stream()
                .filter(o -> !o.annihilated() && o.probability() > 0)
                .map(Outcome::outcomeId)
                .collect(Collectors.toSet());
        var erased = after.stream()
                .filter(o -> o.annihilated() && weightedAndEligible.contains(o.outcomeId()))
                .map(Outcome::outcomeId)
                .toList();
        if (erased.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Erasure(erased, weights(before), weights(after)));
    }

    /**
     * The outcome {@code roll} selects when this erasure's redistribution is given back on {@code finalOutcomes}: each
     * erased outcome regains its weight before the erasure, every other outcome loses the share it received, clamped
     * at 0, and later shifts are kept. The walk takes the first outcome, in event order, whose cumulative weight is
     * above {@code roll}; when {@code roll} is not below the total, the last outcome with positive weight.
     */
    UUID counterfactualWinner(List<Outcome> finalOutcomes, int roll) {
        int cumulative = 0;
        UUID lastPositive = null;
        for (var outcome : finalOutcomes) {
            int weight = counterfactualWeight(outcome);
            if (weight <= 0) {
                continue;
            }
            cumulative += weight;
            lastPositive = outcome.outcomeId();
            if (roll < cumulative) {
                return lastPositive;
            }
        }
        if (lastPositive == null) {
            throw new IllegalStateException("Counterfactual weights hold no positive weight");
        }
        return lastPositive;
    }

    private int counterfactualWeight(Outcome outcome) {
        var outcomeId = outcome.outcomeId();
        if (erasedOutcomeIds.contains(outcomeId)) {
            return weightsBefore.get(outcomeId);
        }
        int share = weightsAfter.getOrDefault(outcomeId, 0) - weightsBefore.getOrDefault(outcomeId, 0);
        return Math.max(0, outcome.probability() - share);
    }

    private static Map<UUID, Integer> weights(List<Outcome> outcomes) {
        return outcomes.stream().collect(Collectors.toMap(Outcome::outcomeId, Outcome::probability));
    }
}
