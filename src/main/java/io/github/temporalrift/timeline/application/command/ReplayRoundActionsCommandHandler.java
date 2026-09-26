package io.github.temporalrift.timeline.application.command;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.ReplayRoundActionsUseCase;
import io.github.temporalrift.timeline.application.port.in.WeaverChainSagaUseCase;
import io.github.temporalrift.timeline.domain.event.AdjustedBandsPublished;
import io.github.temporalrift.timeline.domain.event.CorruptInversionConfirmed;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateRevealed;
import io.github.temporalrift.timeline.domain.event.ResolutionFailed;
import io.github.temporalrift.timeline.domain.event.SpecialRejectedEvent;
import io.github.temporalrift.timeline.domain.futureevent.CardGrade;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.FutureEventNotFoundException;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityBand;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.futureevent.SimultaneousShift;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityBandRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.BufferedAction;
import io.github.temporalrift.timeline.domain.port.out.ScanEntitlementPort;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

/**
 * Replays one round's buffered actions in strict priority-tier order: {@code NULLIFY -> SEAL -> ANNIHILATE ->
 * CASCADE -> CORRUPT -> MIMIC -> AMPLIFY -> remaining cards}, all in one in-process pass. The remaining cards and
 * MIMIC copies resolve simultaneously per event, so no result depends on submission timestamp. Every player-targeted
 * correlation ({@code NULLIFY}, {@code AMPLIFY}, {@code REDIRECT}, and {@code CORRUPT}) is resolved once up front from
 * the complete round buffer.
 */
@Service
class ReplayRoundActionsCommandHandler implements ReplayRoundActionsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReplayRoundActionsCommandHandler.class);

    private static final String FUTURE_EVENT_AGGREGATE_TYPE = "FutureEvent";
    private static final String ERA_AGGREGATE_TYPE = "Era";

    private static final String CARD_TYPE_PUSH = "PUSH";
    private static final String CARD_TYPE_SUPPRESS = "SUPPRESS";
    private static final String CARD_TYPE_SWING = "SWING";
    private static final String CARD_TYPE_COLLIDE = "COLLIDE";
    private static final String CARD_TYPE_REDIRECT = "REDIRECT";
    private static final String CARD_TYPE_STALL = "STALL";

    private static final String SPECIAL_ACTION_MIMIC = "MIMIC";
    private static final String SPECIAL_ACTION_CASCADE = "CASCADE";

    /** Eligible for AMPLIFY's doubling and NULLIFY's cancellation like any other remaining-tier card. */
    private static final Set<String> AMPLIFIABLE_SHIFTER_TYPES =
            Set.of(CARD_TYPE_PUSH, CARD_TYPE_SUPPRESS, CARD_TYPE_SWING, CARD_TYPE_COLLIDE);

    /** REDIRECT applies only to directional shifts; COLLIDE equalizes a pair and has no destination to retarget. */
    private static final Set<String> REDIRECTABLE_SHIFTER_TYPES =
            Set.of(CARD_TYPE_PUSH, CARD_TYPE_SUPPRESS, CARD_TYPE_SWING);

    /** CORRUPT correlates only to these — COLLIDE is never invertible by it. */
    private static final Set<String> CORRUPT_INVERTIBLE_TYPES =
            Set.of(CARD_TYPE_PUSH, CARD_TYPE_SUPPRESS, CARD_TYPE_SWING);

    /**
     * MIMIC correlates only to these — the same "direct transfer" vocabulary Rally
     * boosts, though Rally's own eligibility additionally excludes
     * SUPPRESS and a SWING's source side because Rally and Momentum share the same direct-transfer eligibility.
     */
    private static final Set<String> DIRECT_TRANSFER_TYPES =
            Set.of(CARD_TYPE_PUSH, CARD_TYPE_SUPPRESS, CARD_TYPE_SWING);

    private final RoundActionBufferPort buffer;
    private final FutureEventRepository futureEvents;
    private final FutureEventEraIndexPort eraIndex;
    private final ScanEntitlementPort scanEntitlements;
    private final CascadeCarryForwardPort cascadeCarryForward;
    private final ProbabilityRulesPort rules;
    private final ProbabilityBandRulesPort bandRules;
    private final TimelineEventPublisher publisher;
    private final WeaverChainSagaUseCase weaverChainSaga;
    private final Clock clock;

    ReplayRoundActionsCommandHandler(
            RoundActionBufferPort buffer,
            FutureEventRepository futureEvents,
            FutureEventEraIndexPort eraIndex,
            ScanEntitlementPort scanEntitlements,
            CascadeCarryForwardPort cascadeCarryForward,
            ProbabilityRulesPort rules,
            ProbabilityBandRulesPort bandRules,
            TimelineEventPublisher publisher,
            WeaverChainSagaUseCase weaverChainSaga,
            Clock clock) {
        this.buffer = buffer;
        this.futureEvents = futureEvents;
        this.eraIndex = eraIndex;
        this.scanEntitlements = scanEntitlements;
        this.cascadeCarryForward = cascadeCarryForward;
        this.rules = rules;
        this.bandRules = bandRules;
        this.publisher = publisher;
        this.weaverChainSaga = weaverChainSaga;
        this.clock = clock;
    }

    @Override
    public void replay(UUID gameId, int eraNumber, int roundNumber) {
        var actions = buffer.findByRound(gameId, eraNumber, roundNumber);
        if (!actions.isEmpty()) {
            replayActions(gameId, eraNumber, roundNumber, actions);
        }
        // Every round close republishes current state for entitlements earned in an earlier round, including a
        // round with no buffered actions at all.
        publishScanReveals(gameId, eraNumber, roundNumber);
        if (roundNumber == 2) {
            publishBandedProbability(gameId, eraNumber);
        }
    }

    private void replayActions(UUID gameId, int eraNumber, int roundNumber, List<BufferedAction> actions) {
        var sorted = actions.stream()
                .sorted(Comparator.comparing(BufferedAction::occurredAt).thenComparing(BufferedAction::envelopeEventId))
                .toList();
        var byEnvelopeId = sorted.stream().collect(Collectors.toMap(BufferedAction::envelopeEventId, a -> a));
        var selectedActionByPlayer = indexActionsByPlayer(sorted);

        var cancelled = computeNullifyCancellations(sorted, selectedActionByPlayer);

        applyTier(sorted, cancelled, a -> isSpecial(a, "SEAL"), this::applySeal);
        applyAnnihilateTier(gameId, eraNumber, sorted, cancelled);
        applyCascadeTier(gameId, eraNumber, sorted, cancelled);

        var corruptTargets = resolveCorruptTargets(sorted, cancelled);
        var amplifyMultipliers = resolveAmplifyMultipliers(sorted, selectedActionByPlayer, cancelled);
        var redirectedActions = resolveRedirectedActions(sorted, selectedActionByPlayer, cancelled);
        var mimicCorrelations = resolveMimicTargets(sorted, cancelled);
        // Defensive, not just relying on the producer-side invariant that RALLY is only ever buffered into
        // round 1 — even if a RALLY entry somehow reached another round's buffer, it would not be consulted.
        var rallyDeclaredOutcomes = roundNumber == 1 ? resolveRallyDeclaredOutcomes(sorted, cancelled) : Set.<UUID>of();

        var effectsByEvent = new LinkedHashMap<UUID, List<RoundEffect>>();
        var loadedEvents = new HashMap<UUID, Optional<FutureEvent>>();
        for (var correlated : mimicCorrelations.values()) {
            effectsByEvent
                    .computeIfAbsent(correlated.targetEventId(), _ -> new ArrayList<>())
                    .add(mimicEffect(correlated, rallyDeclaredOutcomes));
        }
        for (var a : sorted) {
            if (!isLiveShifter(a, cancelled)) {
                continue;
            }
            loadedEvents
                    .computeIfAbsent(a.targetEventId(), this::tryFindEvent)
                    .ifPresent(futureEvent -> effectsByEvent
                            .computeIfAbsent(a.targetEventId(), _ -> new ArrayList<>())
                            .add(shifterEffect(
                                    a,
                                    futureEvent,
                                    corruptTargets.containsKey(a.envelopeEventId()),
                                    amplifyMultipliers.getOrDefault(a.envelopeEventId(), 1.0),
                                    redirectedActions.contains(a.envelopeEventId()),
                                    rallyDeclaredOutcomes)));
        }

        var touchedEventIds = new LinkedHashSet<UUID>();
        var tookEffectEnvelopeIds = new HashSet<UUID>();
        effectsByEvent.forEach((eventId, effects) -> loadedEvents
                .computeIfAbsent(eventId, this::tryFindEvent)
                .ifPresent(futureEvent -> {
                    applySimultaneously(futureEvent, effects, tookEffectEnvelopeIds);
                    touchedEventIds.add(eventId);
                }));

        applyTier(sorted, cancelled, a -> isCardType(a, CARD_TYPE_STALL), this::applyStall);

        publishCorruptConfirmations(
                gameId, eraNumber, roundNumber, byEnvelopeId, corruptTargets, tookEffectEnvelopeIds);

        validateSums(gameId, eraNumber, touchedEventIds);

        resolveScanEntitlements(gameId, eraNumber, sorted, cancelled);
    }

    /**
     * Records one durable entitlement per non-nullified SCAN's still-active selected event, using the final
     * post-round state every other effect in this round has already been applied against — resolving NULLIFY
     * and every modifier first means a same-round-nullified SCAN, or a target stalled/resolved by this same
     * round's final effective actions, records nothing.
     */
    private void resolveScanEntitlements(UUID gameId, int eraNumber, List<BufferedAction> sorted, Set<UUID> cancelled) {
        for (var scan : sorted) {
            if (!isCardType(scan, "SCAN") || cancelled.contains(scan.envelopeEventId())) {
                continue;
            }
            for (var eventId : effectiveScanTargets(scan)) {
                tryFindEvent(eventId).ifPresent(futureEvent -> {
                    if (!futureEvent.stalled() && !futureEvent.resolved()) {
                        scanEntitlements.upsert(gameId, eraNumber, scan.playerId(), eventId);
                    }
                });
            }
        }
    }

    /** The 1-3 event ids a list-mode SCAN selected, or the single id a scalar-mode SCAN targeted. */
    private static List<UUID> effectiveScanTargets(BufferedAction scan) {
        if (!scan.targetEventIds().isEmpty()) {
            return scan.targetEventIds();
        }
        return scan.targetEventId() != null ? List.of(scan.targetEventId()) : List.of();
    }

    /**
     * Republishes every active SCAN entitlement's current exact outcome state, addressed only to its player.
     * Runs at every round close, including one with no buffered actions, so a live entitlement from an earlier
     * round keeps revealing. An entitlement whose event has since stalled or resolved is silently skipped rather
     * than deleted — {@code EraEnded}/{@code GameEnded} own cleanup.
     */
    private void publishScanReveals(UUID gameId, int eraNumber, int roundNumber) {
        for (var entitlement : scanEntitlements.findByGameAndEra(gameId, eraNumber)) {
            tryFindEvent(entitlement.eventId()).ifPresent(futureEvent -> {
                if (futureEvent.stalled() || futureEvent.resolved()) {
                    return;
                }
                publisher.publish(TimelineEventEnvelope.create(
                        entitlement.eventId(),
                        FUTURE_EVENT_AGGREGATE_TYPE,
                        gameId,
                        TimelineEventEnvelope.SCHEMA_VERSION_V1,
                        new ProbabilityStateRevealed(
                                gameId,
                                eraNumber,
                                roundNumber,
                                entitlement.playerId(),
                                entitlement.eventId(),
                                toRevealedOutcomeStates(futureEvent)),
                        clock));
            });
        }
    }

    private static List<ProbabilityStateRevealed.OutcomeState> toRevealedOutcomeStates(FutureEvent futureEvent) {
        return futureEvent.outcomes().stream()
                .map(o -> new ProbabilityStateRevealed.OutcomeState(
                        o.outcomeId(), o.probability(), o.annihilated(), o.sealed()))
                .toList();
    }

    /**
     * After Round 2's priority-ordered replay completes, band every active
     * (non-stalled, non-resolved) {@code FutureEvent}'s outcomes from the fully-applied replayed state, superseding
     * the game-owned preview for the same game and era.
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
                new AdjustedBandsPublished(gameId, eraNumber, eventStates),
                clock));
    }

    private AdjustedBandsPublished.EventState toBandedEventState(FutureEvent futureEvent) {
        var outcomes = futureEvent.outcomes().stream()
                .map(o -> new AdjustedBandsPublished.OutcomeState(
                        o.outcomeId(),
                        ProbabilityBand.of(o.probability(), bandRules.bandLowMax(), bandRules.bandMediumMax())))
                .toList();
        return new AdjustedBandsPublished.EventState(futureEvent.id(), outcomes);
    }

    private static Map<UUID, BufferedAction> indexActionsByPlayer(List<BufferedAction> sorted) {
        var byPlayerId = new LinkedHashMap<UUID, BufferedAction>();
        for (var action : sorted) {
            byPlayerId.putIfAbsent(action.playerId(), action);
        }
        return byPlayerId;
    }

    /** Every NULLIFY contributes its named target simultaneously, including mutually-targeting NULLIFY cards. */
    private static Set<UUID> computeNullifyCancellations(
            List<BufferedAction> sorted, Map<UUID, BufferedAction> byPlayerId) {
        var cancelled = new LinkedHashSet<UUID>();
        for (var nullify : sorted) {
            if (isCardType(nullify, "NULLIFY")) {
                var target = byPlayerId.get(nullify.targetPlayerId());
                if (target != null) {
                    cancelled.add(target.envelopeEventId());
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
        tryFindEvent(a.targetEventId())
                .ifPresent(futureEvent ->
                        futureEvents.append(a.targetEventId(), futureEvent.sealOutcome(a.targetOutcomeId())));
    }

    /**
     * Applies each still-live ANNIHILATE in submission order, then notifies the Weaver chain saga so any
     * chain link on the removed outcome is invalidated (or TAPESTRY-consumed). Runs here — not at
     * SpecialActionPlayed consumption — so a NULLIFY-cancelled ANNIHILATE never invalidates a chain.
     */
    private void applyAnnihilateTier(UUID gameId, int eraNumber, List<BufferedAction> sorted, Set<UUID> cancelled) {
        for (var a : sorted) {
            if (!isSpecial(a, "ANNIHILATE") || cancelled.contains(a.envelopeEventId())) {
                continue;
            }
            tryFindEvent(a.targetEventId()).ifPresent(futureEvent -> {
                futureEvents.append(a.targetEventId(), futureEvent.annihilateOutcome(a.targetOutcomeId()));
                if (a.targetOutcomeId() != null) {
                    weaverChainSaga.annihilateOutcome(gameId, eraNumber, a.targetEventId(), a.targetOutcomeId());
                }
            });
        }
    }

    /**
     * Records each still-live CASCADE as an armed carry-forward intent for this era. No immediate
     * {@code FutureEvent} effect: whether the named outcome is erased and its event carries is only known once
     * the event reaches a terminal state, so settlement happens then instead of here — this only needs to survive
     * same-round NULLIFY cancellation. A CASCADE missing its target coordinate is rejected immediately instead of
     * silently arming nothing.
     */
    private void applyCascadeTier(UUID gameId, int eraNumber, List<BufferedAction> sorted, Set<UUID> cancelled) {
        for (var a : sorted) {
            if (!isSpecial(a, SPECIAL_ACTION_CASCADE) || cancelled.contains(a.envelopeEventId())) {
                continue;
            }
            if (a.targetEventId() != null && a.targetOutcomeId() != null) {
                cascadeCarryForward.arm(gameId, eraNumber, a.playerId(), a.targetEventId(), a.targetOutcomeId());
            } else {
                publishCascadeMissingCoordinate(gameId, eraNumber, a);
            }
        }
    }

    private void publishCascadeMissingCoordinate(UUID gameId, int eraNumber, BufferedAction a) {
        publisher.publish(TimelineEventEnvelope.create(
                a.playerId(),
                FUTURE_EVENT_AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new SpecialRejectedEvent(
                        gameId,
                        eraNumber,
                        a.playerId(),
                        SPECIAL_ACTION_CASCADE,
                        null,
                        a.targetEventId(),
                        a.targetOutcomeId(),
                        "MISSING_COORDINATE"),
                clock));
    }

    /**
     * A buffered action's target must reference an id this service actually drew ({@code FutureEventDrafted}
     * recorded) — {@code findById} throws otherwise. Rather than let that abort the whole round's transaction,
     * skip only the action that named the unresolvable id and keep replaying the rest of the round. Every caller
     * of this method goes on to mutate the returned aggregate (seal/annihilate/shift/stall), each of which throws
     * {@code FutureEventAlreadyResolvedException} for a resolved event — an already-resolved target is skipped
     * here the same way, rather than letting a stale or redelivered round replay (e.g. a duplicated
     * {@code ActionRoundClosed} arriving after that era's own resolution) crash the whole transaction.
     */
    private Optional<FutureEvent> tryFindEvent(UUID eventId) {
        try {
            var futureEvent = futureEvents.findById(eventId);
            if (futureEvent.resolved()) {
                log.warn("Buffered action targets already-resolved FutureEvent {} — skipping its effect", eventId);
                return Optional.empty();
            }
            return Optional.of(futureEvent);
        } catch (FutureEventNotFoundException _) {
            log.warn("Buffered action targets unknown FutureEvent {} — skipping its effect", eventId);
            return Optional.empty();
        }
    }

    /**
     * Maps each correlated shifter card's {@code envelopeEventId} to the corrupting player — a {@code CORRUPT}
     * with no matching {@code PUSH}/{@code SUPPRESS}/{@code SWING} by its target player this round has no
     * effect.
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
                    .filter(c -> Objects.equals(corrupt.targetPlayerId(), c.playerId()))
                    .findFirst()
                    .ifPresent(correlated ->
                            targets.put(correlated.envelopeEventId(), new CorruptCorrelation(corrupt.playerId())));
        }
        return targets;
    }

    /**
     * Maps each {@code MIMIC}'s {@code envelopeEventId} to the same-round {@code PUSH}/{@code SUPPRESS}/
     * {@code SWING} played by a different player targeting the same outcome of the same {@code FutureEvent}. Among
     * several, the strongest is copied — never the earliest, so submission timing cannot decide whose card is
     * mimicked. A {@code MIMIC} with no matching card in that round has no effect.
     */
    private Map<UUID, BufferedAction> resolveMimicTargets(List<BufferedAction> sorted, Set<UUID> cancelled) {
        var correlations = new LinkedHashMap<UUID, BufferedAction>();
        for (var mimic : sorted) {
            if (!isSpecial(mimic, SPECIAL_ACTION_MIMIC) || cancelled.contains(mimic.envelopeEventId())) {
                continue;
            }
            sorted.stream()
                    .filter(c -> !cancelled.contains(c.envelopeEventId()))
                    .filter(c -> c.kind() == ActionKind.CARD_PLAYED && DIRECT_TRANSFER_TYPES.contains(c.cardType()))
                    .filter(c -> !Objects.equals(c.playerId(), mimic.playerId()))
                    .filter(c -> Objects.equals(c.targetEventId(), mimic.targetEventId()))
                    .filter(c -> Objects.equals(c.targetOutcomeId(), mimic.targetOutcomeId()))
                    .min(strongestMimicCandidateFirst())
                    .ifPresent(correlated -> correlations.put(mimic.envelopeEventId(), correlated));
        }
        return correlations;
    }

    /** Largest configured base magnitude, then a card raising the mimicked outcome over SUPPRESS, then player id. */
    private Comparator<BufferedAction> strongestMimicCandidateFirst() {
        return Comparator.<BufferedAction>comparingInt(
                        c -> -Math.abs(baseMagnitude(ShiftKind.valueOf(c.cardType()), c.grade())))
                .thenComparing(c -> CARD_TYPE_SUPPRESS.equals(c.cardType()))
                .thenComparing(BufferedAction::playerId);
    }

    /**
     * A second, independent copy of the correlated card at its configured base magnitude — not the original's
     * post-amplify magnitude, and independent of whether that same card was also the subject of a same-round
     * {@code CORRUPT}. A copy landing on a Rally-declared outcome is boosted identically to an ordinary card because
     * Rally and Momentum share the same direct-transfer eligibility.
     */
    private RoundEffect mimicEffect(BufferedAction correlated, Set<UUID> rallyDeclaredOutcomes) {
        var kind = ShiftKind.valueOf(correlated.cardType());
        int magnitude = rallyAdjustedMagnitude(
                rallyDeclaredOutcomes, kind, correlated.targetOutcomeId(), baseMagnitude(kind, correlated.grade()));
        return new RoundEffect(
                null,
                new SimultaneousShift(
                        toProbabilityShift(kind, correlated.sourceOutcomeId(), correlated.targetOutcomeId()),
                        magnitude));
    }

    /**
     * Every still-live (not {@code NULLIFY}-cancelled) {@code RALLY} entry's declared outcome — a set membership
     * check, not a per-declaration multiplier stack, so two Activists declaring the same outcome still boost a
     * matching transfer only once. Only ever non-empty for round 1: {@code CardPlayedAndResolutionKafkaConsumer}
     * buffers a {@code RALLY} declaration into
     * round 1's own buffer unconditionally, so it can never appear in any other round's {@code sorted} list.
     */
    private static Set<UUID> resolveRallyDeclaredOutcomes(List<BufferedAction> sorted, Set<UUID> cancelled) {
        return sorted.stream()
                .filter(a -> isSpecial(a, "RALLY") && !cancelled.contains(a.envelopeEventId()))
                .map(BufferedAction::targetOutcomeId)
                .collect(Collectors.toSet());
    }

    /**
     * {@code SUPPRESS} is never Rally-boosted: it has no destination outcome, and Rally does not boost its indirect
     * redistribution. {@code COLLIDE} has no configured magnitude to boost. A {@code PUSH}/{@code SWING} on a
     * declared outcome applies the configured Rally multiplier to its otherwise-determined magnitude.
     */
    private int rallyAdjustedMagnitude(
            Set<UUID> rallyDeclaredOutcomes, ShiftKind kind, UUID destinationOutcomeId, int magnitude) {
        if (kind == ShiftKind.SUPPRESS
                || kind == ShiftKind.COLLIDE
                || !rallyDeclaredOutcomes.contains(destinationOutcomeId)) {
            return magnitude;
        }
        return (int) Math.round(magnitude * rules.rallyMultiplier());
    }

    private Map<UUID, Double> resolveAmplifyMultipliers(
            List<BufferedAction> sorted, Map<UUID, BufferedAction> selectedActionByPlayer, Set<UUID> cancelled) {
        var multipliers = new LinkedHashMap<UUID, Double>();
        for (var amplify : sorted) {
            if (!isCardType(amplify, "AMPLIFY") || cancelled.contains(amplify.envelopeEventId())) {
                continue;
            }
            var target = findLiveShifter(selectedActionByPlayer, amplify.targetPlayerId(), cancelled);
            if (target != null) {
                multipliers.merge(
                        target.envelopeEventId(),
                        rules.amplifyMultiplier(amplify.grade()),
                        (left, right) -> left * right);
            }
        }
        return multipliers;
    }

    private static Set<UUID> resolveRedirectedActions(
            List<BufferedAction> sorted, Map<UUID, BufferedAction> selectedActionByPlayer, Set<UUID> cancelled) {
        var redirectedActionIds = new HashSet<UUID>();
        for (var redirect : sorted) {
            if (!isCardType(redirect, CARD_TYPE_REDIRECT) || cancelled.contains(redirect.envelopeEventId())) {
                continue;
            }
            var target = findLiveRedirectableShifter(selectedActionByPlayer, redirect.targetPlayerId(), cancelled);
            if (target != null) {
                redirectedActionIds.add(target.envelopeEventId());
            }
        }
        return redirectedActionIds;
    }

    private static boolean isLiveShifter(BufferedAction action, Set<UUID> cancelled) {
        return action != null
                && action.kind() == ActionKind.CARD_PLAYED
                && AMPLIFIABLE_SHIFTER_TYPES.contains(action.cardType())
                && !cancelled.contains(action.envelopeEventId());
    }

    private static BufferedAction findLiveShifter(
            Map<UUID, BufferedAction> selectedActionByPlayer, UUID targetPlayerId, Set<UUID> cancelled) {
        var selected = selectedActionByPlayer.get(targetPlayerId);
        return isLiveShifter(selected, cancelled) ? selected : null;
    }

    private static BufferedAction findLiveRedirectableShifter(
            Map<UUID, BufferedAction> selectedActionByPlayer, UUID targetPlayerId, Set<UUID> cancelled) {
        var selected = selectedActionByPlayer.get(targetPlayerId);
        return selected != null
                        && selected.kind() == ActionKind.CARD_PLAYED
                        && REDIRECTABLE_SHIFTER_TYPES.contains(selected.cardType())
                        && !cancelled.contains(selected.envelopeEventId())
                ? selected
                : null;
    }

    /**
     * The shifter's single effect after its CORRUPT inversion, REDIRECT destination, AMPLIFY multiplier, and Rally
     * boost — resolved later together with every other effect on the same event.
     */
    private RoundEffect shifterEffect(
            BufferedAction a,
            FutureEvent futureEvent,
            boolean inverted,
            double amplifierMultiplier,
            boolean redirected,
            Set<UUID> rallyDeclaredOutcomes) {
        var effectiveKind = effectiveKind(ShiftKind.valueOf(a.cardType()), inverted);
        var sourceOutcomeId = appliedSourceOutcomeId(effectiveKind, inverted, a);
        var submittedTargetOutcomeId = appliedTargetOutcomeId(effectiveKind, inverted, a);
        var targetOutcomeId = redirected
                ? redirectDestination(futureEvent, sourceOutcomeId, submittedTargetOutcomeId)
                : submittedTargetOutcomeId;
        var shift = toProbabilityShift(effectiveKind, sourceOutcomeId, targetOutcomeId);
        int amplifiedMagnitude = (int) Math.round(baseMagnitude(effectiveKind, a.grade()) * amplifierMultiplier);
        int magnitude =
                rallyAdjustedMagnitude(rallyDeclaredOutcomes, effectiveKind, targetOutcomeId, amplifiedMagnitude);
        return new RoundEffect(a.envelopeEventId(), new SimultaneousShift(shift, magnitude));
    }

    /**
     * Resolves every same-round effect on one event at once and persists the result. A card took effect when its
     * own effect against the round's starting weights moved them — a seal-blocked or fully clamped shift did not.
     */
    private void applySimultaneously(
            FutureEvent futureEvent, List<RoundEffect> effects, Set<UUID> tookEffectEnvelopeIds) {
        var result = futureEvent.applySimultaneousShifts(
                effects.stream().map(RoundEffect::shift).toList(),
                rules.probabilityFloor(),
                rules.probabilityCeiling());
        result.events().forEach(event -> futureEvents.append(futureEvent.id(), event));
        for (int i = 0; i < effects.size(); i++) {
            var envelopeEventId = effects.get(i).envelopeEventId();
            if (envelopeEventId != null && result.movedAlone().get(i)) {
                tookEffectEnvelopeIds.add(envelopeEventId);
            }
        }
    }

    /**
     * Advances cyclically through the event's declared outcome order. A sealed outcome remains selectable so the
     * aggregate's ordinary blocked-shift handling leaves weights unchanged; annihilated outcomes never receive a
     * redirected shift. Returning the submitted destination means REDIRECT is a no-op if there is no alternative.
     */
    private static UUID redirectDestination(
            FutureEvent futureEvent, UUID sourceOutcomeId, UUID submittedTargetOutcomeId) {
        var outcomes = futureEvent.outcomes();
        int submittedTargetIndex = -1;
        for (int index = 0; index < outcomes.size(); index++) {
            if (outcomes.get(index).outcomeId().equals(submittedTargetOutcomeId)) {
                submittedTargetIndex = index;
                break;
            }
        }
        if (submittedTargetIndex < 0) {
            return submittedTargetOutcomeId;
        }
        for (int offset = 1; offset < outcomes.size(); offset++) {
            var candidate = outcomes.get((submittedTargetIndex + offset) % outcomes.size());
            if (!candidate.annihilated()
                    && !candidate.outcomeId().equals(submittedTargetOutcomeId)
                    && !candidate.outcomeId().equals(sourceOutcomeId)) {
                return candidate.outcomeId();
            }
        }
        return submittedTargetOutcomeId;
    }

    /** {@code null} unless this is a SWING or COLLIDE, the two shifters with a source outcome. */
    private static UUID appliedSourceOutcomeId(ShiftKind effectiveKind, boolean inverted, BufferedAction a) {
        return switch (effectiveKind) {
            case SWING -> inverted ? a.targetOutcomeId() : a.sourceOutcomeId();
            case COLLIDE -> a.sourceOutcomeId();
            case PUSH, SUPPRESS -> null;
        };
    }

    private static UUID appliedTargetOutcomeId(ShiftKind effectiveKind, boolean inverted, BufferedAction a) {
        if (effectiveKind == ShiftKind.SWING && inverted) {
            return a.sourceOutcomeId();
        }
        return a.targetOutcomeId();
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

    private static ProbabilityShift toProbabilityShift(
            ShiftKind effectiveKind, UUID sourceOutcomeId, UUID targetOutcomeId) {
        return switch (effectiveKind) {
            case PUSH -> new ProbabilityShift.Push(targetOutcomeId);
            case SUPPRESS -> new ProbabilityShift.Suppress(targetOutcomeId);
            case SWING -> new ProbabilityShift.Swing(sourceOutcomeId, targetOutcomeId);
            case COLLIDE -> new ProbabilityShift.Collide(sourceOutcomeId, targetOutcomeId);
        };
    }

    private int baseMagnitude(ShiftKind kind, CardGrade grade) {
        return switch (kind) {
            case PUSH -> rules.pushShift(grade);
            case SUPPRESS -> rules.suppressShift(grade);
            case SWING -> rules.swingShift(grade);
            case COLLIDE -> 0;
        };
    }

    private void applyStall(BufferedAction a) {
        tryFindEvent(a.targetEventId()).ifPresent(futureEvent -> {
            if (futureEvent.resolved() || futureEvent.stalled()) {
                return;
            }
            futureEvents.append(a.targetEventId(), futureEvent.markStalled());
        });
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

    private static boolean isSpecial(BufferedAction a, String specialAction) {
        return a.kind() == ActionKind.SPECIAL_ACTION_PLAYED && specialAction.equals(a.specialAction());
    }

    private static boolean isCardType(BufferedAction a, String cardType) {
        return a.kind() == ActionKind.CARD_PLAYED && cardType.equals(a.cardType());
    }

    private enum ShiftKind {
        PUSH,
        SUPPRESS,
        SWING,
        COLLIDE
    }

    private record CorruptCorrelation(UUID corruptingPlayerId) {}

    /** One effect of the simultaneous tier; {@code envelopeEventId} is {@code null} for a MIMIC copy. */
    private record RoundEffect(UUID envelopeEventId, SimultaneousShift shift) {}
}
