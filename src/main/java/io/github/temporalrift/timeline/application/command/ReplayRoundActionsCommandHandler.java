package io.github.temporalrift.timeline.application.command;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.ReplayRoundActionsUseCase;
import io.github.temporalrift.timeline.domain.event.BandedProbabilityPublished;
import io.github.temporalrift.timeline.domain.event.CorruptInversionConfirmed;
import io.github.temporalrift.timeline.domain.event.ProbabilityShifted;
import io.github.temporalrift.timeline.domain.event.ResolutionFailed;
import io.github.temporalrift.timeline.domain.event.ResolutionWarning;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityBand;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityBandRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.BufferedAction;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

/**
 * Replays one round's buffered actions in strict priority-tier order (design.md Decision 1, timeline-mvp9-
 * resolution-ordering-paradox-cards): {@code NULLIFY -> SEAL -> ANNIHILATE -> CORRUPT -> AMPLIFY -> remaining
 * cards by submission timestamp}, all in one in-process pass. Folds in what {@code ApplyProbabilityShiftUseCase},
 * {@code PlayCardModifierUseCase}, {@code PlaySpecialActionUseCase}, and {@code ResolvePendingCorruptUseCase}
 * did as standalone per-message handlers (design.md Decision 7) — every "last card"/"pending" concept those
 * needed a durable cross-transaction port for is now either resolved once up front from the buffer (
 * {@code NULLIFY}'s target, {@link #computeNullifyCancellations}) or transient state local to this call
 * ({@code AMPLIFY}'s pending doubling, {@code CORRUPT}'s correlation flag, {@code REDIRECT}'s last-shift-per-
 * event map).
 */
@Service
class ReplayRoundActionsCommandHandler implements ReplayRoundActionsUseCase {

    private static final String FUTURE_EVENT_AGGREGATE_TYPE = "FutureEvent";
    private static final String ERA_AGGREGATE_TYPE = "Era";

    /** Eligible for AMPLIFY's doubling and NULLIFY's cancellation like any other remaining-tier card. */
    private static final Set<String> AMPLIFIABLE_SHIFTER_TYPES = Set.of("PUSH", "SUPPRESS", "SWING", "COLLIDE");

    /** CORRUPT correlates only to these (faction-specials capability) — COLLIDE is never invertible by it. */
    private static final Set<String> CORRUPT_INVERTIBLE_TYPES = Set.of("PUSH", "SUPPRESS", "SWING");

    private final RoundActionBufferPort buffer;
    private final FutureEventRepository futureEvents;
    private final FutureEventEraIndexPort eraIndex;
    private final ProbabilityRulesPort rules;
    private final ProbabilityBandRulesPort bandRules;
    private final TimelineEventPublisher publisher;
    private final Clock clock;

    ReplayRoundActionsCommandHandler(
            RoundActionBufferPort buffer,
            FutureEventRepository futureEvents,
            FutureEventEraIndexPort eraIndex,
            ProbabilityRulesPort rules,
            ProbabilityBandRulesPort bandRules,
            TimelineEventPublisher publisher,
            Clock clock) {
        this.buffer = buffer;
        this.futureEvents = futureEvents;
        this.eraIndex = eraIndex;
        this.rules = rules;
        this.bandRules = bandRules;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    public void replay(UUID gameId, int eraNumber, int roundNumber) {
        var actions = buffer.findByRound(gameId, eraNumber, roundNumber);
        if (!actions.isEmpty()) {
            replayActions(gameId, eraNumber, roundNumber, actions);
        }
        if (roundNumber == 2) {
            publishBandedProbability(gameId, eraNumber);
        }
    }

    private void replayActions(UUID gameId, int eraNumber, int roundNumber, List<BufferedAction> actions) {
        var sorted = actions.stream()
                .sorted(Comparator.comparing(BufferedAction::occurredAt).thenComparing(BufferedAction::envelopeEventId))
                .toList();
        var byEnvelopeId = sorted.stream().collect(Collectors.toMap(BufferedAction::envelopeEventId, a -> a));

        publishTieWarningIfAny(gameId, eraNumber, roundNumber, sorted);

        var cancelled = computeNullifyCancellations(sorted);

        applyTier(sorted, cancelled, a -> isSpecial(a, "SEAL"), this::applySeal);
        applyTier(sorted, cancelled, a -> isSpecial(a, "ANNIHILATE"), this::applyAnnihilate);

        var corruptTargets = resolveCorruptTargets(sorted, cancelled);
        var amplifiedEnvelopeId = resolveAmplifyTarget(sorted, cancelled);

        var lastShiftByEvent = new HashMap<UUID, AppliedShift>();
        var touchedEventIds = new LinkedHashSet<UUID>();
        var tookEffectEnvelopeIds = new HashSet<UUID>();
        for (var a : sorted) {
            if (cancelled.contains(a.envelopeEventId()) || isPriorityTierAction(a)) {
                continue;
            }
            applyRemainingTierAction(
                    a,
                    corruptTargets.containsKey(a.envelopeEventId()),
                    a.envelopeEventId().equals(amplifiedEnvelopeId),
                    lastShiftByEvent,
                    touchedEventIds,
                    tookEffectEnvelopeIds);
        }

        publishCorruptConfirmations(
                gameId, eraNumber, roundNumber, byEnvelopeId, corruptTargets, tookEffectEnvelopeIds);

        validateSums(gameId, eraNumber, touchedEventIds);
    }

    /**
     * GDD §4.3 / banded-probability-publication capability: after Round 2 closes, band every active
     * (non-stalled, non-resolved) {@code FutureEvent}'s outcomes from cumulative Round 1+2 state.
     */
    private void publishBandedProbability(UUID gameId, int eraNumber) {
        var eventStates = eraIndex.findByGameIdAndEraNumber(gameId, eraNumber).stream()
                .map(indexed -> futureEvents.findById(indexed.eventId()))
                .filter(futureEvent -> !futureEvent.stalled() && !futureEvent.resolved())
                .map(this::toBandedEventState)
                .toList();
        publisher.publish(TimelineEventEnvelope.create(
                gameId,
                ERA_AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new BandedProbabilityPublished(gameId, eraNumber, eventStates),
                clock));
    }

    private BandedProbabilityPublished.EventState toBandedEventState(FutureEvent futureEvent) {
        var outcomes = futureEvent.outcomes().stream()
                .map(o -> new BandedProbabilityPublished.OutcomeState(
                        o.outcomeId(),
                        ProbabilityBand.of(o.probability(), bandRules.bandLowMax(), bandRules.bandMediumMax())))
                .toList();
        return new BandedProbabilityPublished.EventState(futureEvent.id(), outcomes);
    }

    /**
     * Each {@code NULLIFY}'s target is the submission-timestamp-previous action not already cancelled by an
     * earlier {@code NULLIFY} — a pure forward pass over the sorted buffer, no persisted state (design.md
     * Decision 2). A {@code NULLIFY} that itself becomes a later {@code NULLIFY}'s target is simply also marked
     * cancelled; it has no tier-6 effect of its own to skip either way.
     */
    private static Set<UUID> computeNullifyCancellations(List<BufferedAction> sorted) {
        var cancelled = new LinkedHashSet<UUID>();
        for (int i = 0; i < sorted.size(); i++) {
            if (!isCardType(sorted.get(i), "NULLIFY")) {
                continue;
            }
            for (int j = i - 1; j >= 0; j--) {
                var candidate = sorted.get(j);
                if (!cancelled.contains(candidate.envelopeEventId())) {
                    cancelled.add(candidate.envelopeEventId());
                    break;
                }
            }
        }
        return cancelled;
    }

    private static void applyTier(
            List<BufferedAction> sorted,
            Set<UUID> cancelled,
            Predicate<BufferedAction> matches,
            Consumer<BufferedAction> apply) {
        for (var a : sorted) {
            if (matches.test(a) && !cancelled.contains(a.envelopeEventId())) {
                apply.accept(a);
            }
        }
    }

    private void applySeal(BufferedAction a) {
        var futureEvent = futureEvents.findById(a.targetEventId());
        futureEvents.append(a.targetEventId(), futureEvent.sealOutcome(a.targetOutcomeId()));
    }

    private void applyAnnihilate(BufferedAction a) {
        var futureEvent = futureEvents.findById(a.targetEventId());
        futureEvents.append(a.targetEventId(), futureEvent.annihilateOutcome(a.targetOutcomeId()));
    }

    /**
     * Maps each correlated shifter card's {@code envelopeEventId} to the corrupting player — a {@code CORRUPT}
     * with no matching {@code PUSH}/{@code SUPPRESS}/{@code SWING} by its target player this round has no
     * effect (faction-specials capability).
     */
    private static Map<UUID, CorruptCorrelation> resolveCorruptTargets(
            List<BufferedAction> sorted, Set<UUID> cancelled) {
        var targets = new HashMap<UUID, CorruptCorrelation>();
        for (var corrupt : sorted) {
            if (!isSpecial(corrupt, "CORRUPT") || cancelled.contains(corrupt.envelopeEventId())) {
                continue;
            }
            sorted.stream()
                    .filter(c -> !cancelled.contains(c.envelopeEventId()))
                    .filter(c -> c.kind() == ActionKind.CARD_PLAYED && CORRUPT_INVERTIBLE_TYPES.contains(c.cardType()))
                    .filter(c -> corrupt.targetPlayerId().equals(c.playerId()))
                    .findFirst()
                    .ifPresent(correlated ->
                            targets.put(correlated.envelopeEventId(), new CorruptCorrelation(corrupt.playerId())));
        }
        return targets;
    }

    /**
     * Multiple {@code AMPLIFY}s collapse to one pending doubling (matching the pre-existing single-flag
     * semantic): the earliest live remaining-tier shifter-eligible action strictly after the earliest live
     * {@code AMPLIFY}'s timestamp.
     */
    private static UUID resolveAmplifyTarget(List<BufferedAction> sorted, Set<UUID> cancelled) {
        var earliestAmplify = sorted.stream()
                .filter(a -> isCardType(a, "AMPLIFY") && !cancelled.contains(a.envelopeEventId()))
                .min(Comparator.comparing(BufferedAction::occurredAt));
        if (earliestAmplify.isEmpty()) {
            return null;
        }
        var amplifyTime = earliestAmplify.get().occurredAt();
        return sorted.stream()
                .filter(a -> !cancelled.contains(a.envelopeEventId()))
                .filter(a -> a.kind() == ActionKind.CARD_PLAYED && AMPLIFIABLE_SHIFTER_TYPES.contains(a.cardType()))
                .filter(a -> a.occurredAt().isAfter(amplifyTime))
                .min(Comparator.comparing(BufferedAction::occurredAt).thenComparing(BufferedAction::envelopeEventId))
                .map(BufferedAction::envelopeEventId)
                .orElse(null);
    }

    private void applyRemainingTierAction(
            BufferedAction a,
            boolean inverted,
            boolean amplified,
            Map<UUID, AppliedShift> lastShiftByEvent,
            Set<UUID> touchedEventIds,
            Set<UUID> tookEffectEnvelopeIds) {
        if (a.kind() != ActionKind.CARD_PLAYED) {
            return;
        }
        switch (a.cardType()) {
            case "PUSH", "SUPPRESS", "SWING", "COLLIDE" ->
                applyShifter(a, inverted, amplified, lastShiftByEvent, touchedEventIds, tookEffectEnvelopeIds);
            case "REDIRECT" -> applyRedirect(a, lastShiftByEvent, touchedEventIds);
            case "STALL" -> applyStall(a);
            default -> {
                // INTERCEPT/SCAN/TRACE/DECOY/JAM and any unsupported future type: no probability/stalled effect.
            }
        }
    }

    private void applyShifter(
            BufferedAction a,
            boolean inverted,
            boolean amplified,
            Map<UUID, AppliedShift> lastShiftByEvent,
            Set<UUID> touchedEventIds,
            Set<UUID> tookEffectEnvelopeIds) {
        var kind = ShiftKind.valueOf(a.cardType());
        var effectiveKind = effectiveKind(kind, inverted);
        var futureEvent = futureEvents.findById(a.targetEventId());
        var preShiftSnapshot = snapshotOf(futureEvent);
        var shift = toProbabilityShift(effectiveKind, a, inverted);
        int magnitude = amplified ? 2 * baseMagnitude(effectiveKind) : baseMagnitude(effectiveKind);

        var result = futureEvent.applyShift(shift, magnitude, rules.probabilityFloor(), rules.probabilityCeiling());
        futureEvents.append(a.targetEventId(), result);
        touchedEventIds.add(a.targetEventId());

        if (result instanceof ProbabilityShifted) {
            tookEffectEnvelopeIds.add(a.envelopeEventId());
            if (effectiveKind != ShiftKind.COLLIDE) {
                var appliedSource = effectiveKind == ShiftKind.SWING
                        ? (inverted ? a.targetOutcomeId() : a.sourceOutcomeId())
                        : null;
                var appliedTarget =
                        effectiveKind == ShiftKind.SWING && inverted ? a.sourceOutcomeId() : a.targetOutcomeId();
                lastShiftByEvent.put(
                        a.targetEventId(),
                        new AppliedShift(effectiveKind, appliedSource, appliedTarget, magnitude, preShiftSnapshot));
            }
        }
    }

    private static ShiftKind effectiveKind(ShiftKind kind, boolean inverted) {
        if (!inverted) {
            return kind;
        }
        return switch (kind) {
            case PUSH -> ShiftKind.SUPPRESS;
            case SUPPRESS -> ShiftKind.PUSH;
            case SWING -> ShiftKind.SWING;
            case COLLIDE -> ShiftKind.COLLIDE;
        };
    }

    private static ProbabilityShift toProbabilityShift(ShiftKind effectiveKind, BufferedAction a, boolean inverted) {
        return switch (effectiveKind) {
            case PUSH -> new ProbabilityShift.Push(a.targetOutcomeId());
            case SUPPRESS -> new ProbabilityShift.Suppress(a.targetOutcomeId());
            case SWING ->
                inverted
                        ? new ProbabilityShift.Swing(a.targetOutcomeId(), a.sourceOutcomeId())
                        : new ProbabilityShift.Swing(a.sourceOutcomeId(), a.targetOutcomeId());
            case COLLIDE -> new ProbabilityShift.Collide(a.sourceOutcomeId(), a.targetOutcomeId());
        };
    }

    private int baseMagnitude(ShiftKind kind) {
        return switch (kind) {
            case PUSH -> rules.pushShift();
            case SUPPRESS -> rules.suppressShift();
            case SWING -> rules.swingShift();
            case COLLIDE -> 0;
        };
    }

    /**
     * Redirects the last shift applied to its named event so far this round's remaining-tier pass (Decision 7 —
     * REDIRECT stays a same-tier, sequential lookup, unlike NULLIFY's preemptive one). A REDIRECT with no prior
     * shift on the event, or naming a SWING's own recorded source, is a no-op.
     */
    private void applyRedirect(BufferedAction a, Map<UUID, AppliedShift> lastShiftByEvent, Set<UUID> touchedEventIds) {
        var last = lastShiftByEvent.get(a.targetEventId());
        if (last == null || isInvalidRedirectTarget(a.targetOutcomeId(), last)) {
            return;
        }
        var futureEvent = futureEvents.findById(a.targetEventId());
        var restored = futureEvent.applyShift(
                new ProbabilityShift.Restore(last.preShiftSnapshot()),
                0,
                rules.probabilityFloor(),
                rules.probabilityCeiling());
        futureEvents.append(a.targetEventId(), restored);

        var reappliedShift =
                switch (last.kind()) {
                    case SWING -> new ProbabilityShift.Swing(last.sourceOutcomeId(), a.targetOutcomeId());
                    case PUSH -> new ProbabilityShift.Push(a.targetOutcomeId());
                    case SUPPRESS -> new ProbabilityShift.Suppress(a.targetOutcomeId());
                    case COLLIDE -> throw new IllegalStateException("COLLIDE is never tracked as a redirect target");
                };
        var reapplied = futureEvent.applyShift(
                reappliedShift, last.magnitude(), rules.probabilityFloor(), rules.probabilityCeiling());
        futureEvents.append(a.targetEventId(), reapplied);
        touchedEventIds.add(a.targetEventId());

        lastShiftByEvent.put(
                a.targetEventId(),
                new AppliedShift(
                        last.kind(),
                        last.kind() == ShiftKind.SWING ? last.sourceOutcomeId() : null,
                        a.targetOutcomeId(),
                        last.magnitude(),
                        last.preShiftSnapshot()));
    }

    private static boolean isInvalidRedirectTarget(UUID targetOutcomeId, AppliedShift last) {
        return last.kind() == ShiftKind.SWING && targetOutcomeId.equals(last.sourceOutcomeId());
    }

    private void applyStall(BufferedAction a) {
        var futureEvent = futureEvents.findById(a.targetEventId());
        if (futureEvent.resolved() || futureEvent.stalled()) {
            return;
        }
        futureEvents.append(a.targetEventId(), futureEvent.markStalled());
    }

    private void publishCorruptConfirmations(
            UUID gameId,
            int eraNumber,
            int roundNumber,
            Map<UUID, BufferedAction> byEnvelopeId,
            Map<UUID, CorruptCorrelation> corruptTargets,
            Set<UUID> tookEffectEnvelopeIds) {
        corruptTargets.forEach((correlatedEnvelopeId, correlation) -> {
            var correlated = byEnvelopeId.get(correlatedEnvelopeId);
            publisher.publish(TimelineEventEnvelope.create(
                    correlated.targetEventId(),
                    FUTURE_EVENT_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new CorruptInversionConfirmed(
                            gameId,
                            eraNumber,
                            roundNumber,
                            correlation.corruptingPlayerId(),
                            correlated.targetEventId(),
                            correlated.targetOutcomeId(),
                            tookEffectEnvelopeIds.contains(correlatedEnvelopeId)),
                    clock));
        });
    }

    private void validateSums(UUID gameId, int eraNumber, Set<UUID> touchedEventIds) {
        for (var eventId : touchedEventIds) {
            var futureEvent = futureEvents.findById(eventId);
            int sum = futureEvent.outcomes().stream()
                    .mapToInt(Outcome::probability)
                    .sum();
            if (sum != 100) {
                publisher.publish(TimelineEventEnvelope.create(
                        eventId,
                        FUTURE_EVENT_AGGREGATE_TYPE,
                        gameId,
                        TimelineEventEnvelope.SCHEMA_VERSION_V1,
                        new ResolutionFailed(gameId, eraNumber, eventId, "PROBABILITY_SUM_INVALID"),
                        clock));
            }
        }
    }

    private void publishTieWarningIfAny(UUID gameId, int eraNumber, int roundNumber, List<BufferedAction> sorted) {
        var remaining = sorted.stream()
                .filter(a -> !isPriorityTierAction(a))
                .sorted(Comparator.comparing(BufferedAction::occurredAt))
                .toList();
        for (int i = 1; i < remaining.size(); i++) {
            if (remaining.get(i - 1).occurredAt().equals(remaining.get(i).occurredAt())) {
                publisher.publish(TimelineEventEnvelope.create(
                        gameId,
                        ERA_AGGREGATE_TYPE,
                        gameId,
                        TimelineEventEnvelope.SCHEMA_VERSION_V1,
                        new ResolutionWarning(
                                gameId,
                                eraNumber,
                                roundNumber,
                                "Identical occurredAt tie in round " + roundNumber
                                        + "'s remaining tier, resolved by envelopeEventId order"),
                        clock));
                return;
            }
        }
    }

    private static Map<UUID, Integer> snapshotOf(
            io.github.temporalrift.timeline.domain.futureevent.FutureEvent futureEvent) {
        return futureEvent.outcomes().stream().collect(Collectors.toMap(Outcome::outcomeId, Outcome::probability));
    }

    private static boolean isSpecial(BufferedAction a, String specialAction) {
        return a.kind() == ActionKind.SPECIAL_ACTION_PLAYED && specialAction.equals(a.specialAction());
    }

    private static boolean isCardType(BufferedAction a, String cardType) {
        return a.kind() == ActionKind.CARD_PLAYED && cardType.equals(a.cardType());
    }

    /** NULLIFY/AMPLIFY have no tier-6 effect of their own; SEAL/ANNIHILATE/CORRUPT already ran in their tiers. */
    private static boolean isPriorityTierAction(BufferedAction a) {
        return isCardType(a, "NULLIFY")
                || isCardType(a, "AMPLIFY")
                || isSpecial(a, "SEAL")
                || isSpecial(a, "ANNIHILATE")
                || isSpecial(a, "CORRUPT");
    }

    private enum ShiftKind {
        PUSH,
        SUPPRESS,
        SWING,
        COLLIDE
    }

    /** As actually applied — after inversion/amplification — so a later REDIRECT retargets the real effect. */
    private record AppliedShift(
            ShiftKind kind,
            UUID sourceOutcomeId,
            UUID targetOutcomeId,
            int magnitude,
            Map<UUID, Integer> preShiftSnapshot) {}

    private record CorruptCorrelation(UUID corruptingPlayerId) {}
}
