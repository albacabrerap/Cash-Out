package com.checkout.backend.race.service;

import com.checkout.backend.race.model.RaceScenario;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Reglas del motor que dependen del tiempo, con un reloj que avanza a mano. */
class RaceEngineTest {

    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-10-17T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("Una sala que nadie empieza en 2 horas queda vencida; una que ya corre, no")
    void lobbiesExpire() {
        ManualClock clock = new ManualClock();
        RaceEngine engine = new RaceEngine(clock);
        double[] prices = {100, 101, 102};
        RaceEngine.LiveRace waiting = engine.open(1, "AAAAAA", 1, BigDecimal.TEN, RaceScenario.ALL.get(0), prices);
        RaceEngine.LiveRace started = engine.open(2, "BBBBBB", 1, BigDecimal.TEN, RaceScenario.ALL.get(0), prices);
        engine.addRacer(started, 10, 2, "Ana");
        engine.start(started, 1);

        clock.advance(Duration.ofMinutes(119));
        assertThat(engine.staleLobbies(Duration.ofHours(2))).isEmpty();

        clock.advance(Duration.ofMinutes(2));
        assertThat(engine.staleLobbies(Duration.ofHours(2))).containsExactly(waiting);
    }

}
