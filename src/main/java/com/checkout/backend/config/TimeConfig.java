package com.checkout.backend.config;

import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Que dia es "hoy" para las reglas del negocio.
 *
 * El servidor corre en UTC (Docker, AWS), y entre las 19:00 y la medianoche de
 * Lima ya vive en el dia siguiente. Sin un reloj propio, en esa franja un gasto
 * de "hoy" pasaba por futuro, una meta que vence "manana" se rechazaba y otra
 * vencia horas antes de tiempo.
 *
 * Este reloj solo decide fechas de calendario: LocalDate.now(clock) y las
 * anotaciones @Past/@Future. Las marcas de tiempo (createdAt, expiracion de
 * tokens) siguen en la hora del servidor, porque son instantes que solo se
 * comparan entre si y el frontend ya las lee como UTC.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock businessClock(@Value("${app.time-zone:America/Lima}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }

    /** @Past, @Future y compania comparan contra el reloj de negocio y no contra la zona del servidor. */
    @Bean
    public ValidationConfigurationCustomizer businessClockForValidation(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }

    /**
     * Hibernate vuelve a validar las entidades al guardarlas, y por defecto lo
     * hace con un validador propio que usa el reloj del sistema. Con esto usa el
     * mismo que el resto de la aplicacion.
     */
    @Bean
    public HibernatePropertiesCustomizer sharedValidatorForEntities(ValidatorFactory validatorFactory) {
        return properties -> properties.put("jakarta.persistence.validation.factory", validatorFactory);
    }

}
