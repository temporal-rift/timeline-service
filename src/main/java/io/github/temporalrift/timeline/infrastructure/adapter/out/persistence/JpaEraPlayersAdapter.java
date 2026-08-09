package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;

@Repository
class JpaEraPlayersAdapter implements EraPlayersPort {

    private final EraPlayersJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    JpaEraPlayersAdapter(EraPlayersJpaRepository jpaRepository, ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void save(UUID gameId, int eraNumber, List<UUID> playerIds) {
        jpaRepository.save(new EraPlayersEntity(gameId, eraNumber, objectMapper.writeValueAsString(playerIds)));
    }

    @Override
    public List<UUID> find(UUID gameId, int eraNumber) {
        return jpaRepository
                .findByGameIdAndEraNumber(gameId, eraNumber)
                .map(entity -> List.of(objectMapper.readValue(entity.getPlayerIds(), UUID[].class)))
                .orElseGet(List::of);
    }
}
