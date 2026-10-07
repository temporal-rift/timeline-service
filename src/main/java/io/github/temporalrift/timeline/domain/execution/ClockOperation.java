package io.github.temporalrift.timeline.domain.execution;

/** A clock advance together with the acknowledgement it earned, kept so a repeat gets the original answer. */
public record ClockOperation(ClockAdvance request, ClockAcknowledgement acknowledgement) {}
