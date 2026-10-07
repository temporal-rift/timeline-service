package io.github.temporalrift.timeline.domain.execution;

import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;

/**
 * Stable semantic position of a choice or identity inside a game: never wall time, delivery order or a transport
 * identifier. Components that do not apply stay {@code null}.
 */
public record EntropyCoordinate(Integer era, UUID player, Integer slot, UUID subject) {

    private static final EntropyCoordinate NONE = new EntropyCoordinate(null, null, null, null);

    public static EntropyCoordinate none() {
        return NONE;
    }

    public EntropyCoordinate era(int value) {
        return new EntropyCoordinate(value, player, slot, subject);
    }

    public EntropyCoordinate player(UUID value) {
        return new EntropyCoordinate(era, value, slot, subject);
    }

    public EntropyCoordinate slot(int value) {
        return new EntropyCoordinate(era, player, value, subject);
    }

    public EntropyCoordinate subject(UUID value) {
        return new EntropyCoordinate(era, player, slot, value);
    }

    /** Renders the canonical text, letting the caller substitute a stable label for the player component. */
    public String render(Function<UUID, String> playerLabel) {
        var joiner = new StringJoiner(";");
        if (era != null) {
            joiner.add("era=" + era);
        }
        if (player != null) {
            joiner.add("player=" + playerLabel.apply(player));
        }
        if (slot != null) {
            joiner.add("slot=" + slot);
        }
        if (subject != null) {
            joiner.add("subject=" + subject);
        }
        return joiner.toString();
    }
}
