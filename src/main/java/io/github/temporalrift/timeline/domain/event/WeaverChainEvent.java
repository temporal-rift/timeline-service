package io.github.temporalrift.timeline.domain.event;
/**
 * Common contract for the event-sourced facts of one Weaver chain stream. Facts that mutate an open chain implement
 * {@link ChainFact}; the opening fact stands alone so reconstruction cannot route it into the applier.
 */
public sealed interface WeaverChainEvent permits WeaverChainStarted, ChainFact {}
