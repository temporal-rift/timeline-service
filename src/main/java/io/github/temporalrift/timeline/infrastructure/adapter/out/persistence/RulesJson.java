package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.domain.execution.EffectiveRules;

/** Reads back the canonical JSON in which a context's rules are stored. */
final class RulesJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private RulesJson() {}

    static EffectiveRules parse(String canonicalJson) {
        return new EffectiveRules(MAPPER.readValue(canonicalJson, new TypeReference<Map<String, Object>>() {}));
    }
}
