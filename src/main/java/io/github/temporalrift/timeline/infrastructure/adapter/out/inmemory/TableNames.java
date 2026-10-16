package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

/** Table names of the in-memory database, mirroring the relational schema the JPA adapters use. */
final class TableNames {

    static final String AGGREGATE_SNAPSHOT = "aggregate_snapshot";
    static final String CASCADE_CARRY_FORWARD = "cascade_carry_forward";
    static final String ERA_PLAYERS = "era_players";
    static final String EVENT_STORE = "event_store";
    static final String FUTURE_EVENT_ERA_INDEX = "future_event_era_index";
    static final String GAME_MEMBERSHIP = "game_membership";
    static final String PARADOX_RESOLUTION_PHASE = "paradox_resolution_phase";
    static final String PROCESSED_EVENTS = "processed_events";
    static final String ROUND_ACTION_BUFFER = "round_action_buffer";
    static final String SCAN_ENTITLEMENT = "scan_entitlement";
    static final String WEAVER_CHAIN_SAGA = "weaver_chain_saga";
    static final String SIMULATION_EXECUTION = "simulation_execution";
    static final String SIMULATION_CLOCK_OPERATION = "simulation_clock_operation";
    static final String SIMULATION_ENTROPY_DECISION = "simulation_entropy_decision";
    static final String OUTBOX_EVENTS = "outbox_events";

    private TableNames() {}
}
