package com.checkout.backend.race.repository;

import com.checkout.backend.race.model.RaceRoom;
import com.checkout.backend.race.model.RaceStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RaceRoomRepository extends JpaRepository<RaceRoom, Long> {

    boolean existsByCode(String code);

    List<RaceRoom> findByStatusIn(Collection<RaceStatus> statuses);

}
