package io.github.temporalrift.timeline.domain.membership;

/** The caller participates in the game but does not own a Weaver chain. */
public class NotWeaverException extends RuntimeException {

    public NotWeaverException() {
        super("Chain reads are restricted to the Weavers faction");
    }
}
