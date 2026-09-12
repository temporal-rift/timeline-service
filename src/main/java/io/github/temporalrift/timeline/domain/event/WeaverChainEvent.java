package io.github.temporalrift.timeline.domain.event;

/**
 * Common contract for the event-sourced facts of one Weaver chain stream. Sealed so replay paths switch
 * exhaustively with no defensive default.
 */
public sealed interface WeaverChainEvent permits WeaverChainStarted, ChainLinkAdded, ChainCompleted, ChainBroken {}
