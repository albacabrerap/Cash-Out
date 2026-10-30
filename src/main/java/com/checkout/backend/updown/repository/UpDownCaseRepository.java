package com.checkout.backend.updown.repository;

import com.checkout.backend.updown.model.UpDownCase;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UpDownCaseRepository extends JpaRepository<UpDownCase, Long> {

    List<UpDownCase> findByActiveTrue();

    List<UpDownCase> findAllByOrderByCutoffDesc();

    boolean existsBySymbolAndCutoff(String symbol, LocalDate cutoff);

    @Modifying
    @Query("UPDATE UpDownCase c SET c.timesPlayed = c.timesPlayed + 1, c.lastPlayedAt = :now WHERE c.id IN :ids")
    void markPlayed(@Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);

}
