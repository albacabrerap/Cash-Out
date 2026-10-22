package com.checkout.backend.race.repository;

import com.checkout.backend.race.model.RacePlayer;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RacePlayerRepository extends JpaRepository<RacePlayer, Long> {

    @EntityGraph(attributePaths = "user")
    List<RacePlayer> findByRoomId(Long roomId);

}
