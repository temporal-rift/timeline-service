package io.github.temporalrift.timeline.domain.futureevent;

/**
 * Grade of a graded card instance — varies a card's magnitude or scope. Grade {@code II} is the
 * configured baseline. Independent of {@code game-service}'s {@code io.github.temporalrift.game.shared.CardGrade}:
 * the services share no code.
 */
public enum CardGrade {
    I,
    II,
    III
}
