package com.checkout.backend.portfoliogame.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Un semestre real del mercado. El retorno de cada sector va del ultimo cierre
 * en o antes de {@code from} al ultimo cierre en o antes de {@code to}.
 *
 * Las lecciones se escribieron mirando esos retornos, calculados con los
 * cierres ajustados de Alpaca (feed IEX) el 27/10/2026.
 */
public record PortfolioPeriod(String key, LocalDate from, LocalDate to, String label, String title, String lesson) {

    public static final List<PortfolioPeriod> ALL = List.of(
            new PortfolioPeriod("2022-1", LocalDate.of(2021, 12, 31), LocalDate.of(2022, 6, 30),
                    "primer semestre de 2022",
                    "Inflación récord, guerra en Ucrania y la Fed empieza a subir tasas",
                    "Casi todo cayó a la vez, incluso los bonos: con tasas subiendo pierden valor las acciones de "
                            + "crecimiento y los bonos largos. Solo la energía ganó, porque la guerra disparó el petróleo."),
            new PortfolioPeriod("2022-2", LocalDate.of(2022, 6, 30), LocalDate.of(2022, 12, 31),
                    "segundo semestre de 2022",
                    "La Fed sigue subiendo tasas y la inflación toca techo",
                    "La inflación llegó a 9.1 % en junio y empezó a bajar, pero las tasas siguieron subiendo: los "
                            + "bonos largos volvieron a caer. La energía siguió arriba y los bancos ganaron con tasas "
                            + "más altas."),
            new PortfolioPeriod("2023-1", LocalDate.of(2022, 12, 31), LocalDate.of(2023, 6, 30),
                    "primer semestre de 2023",
                    "Boom de la inteligencia artificial y crisis de bancos regionales",
                    "Tras la llegada de ChatGPT, la tecnología subió como pocas veces. Los bancos quedaron planos "
                            + "por la caída de Silicon Valley Bank, y la energía bajó con el petróleo más barato."),
            new PortfolioPeriod("2023-2", LocalDate.of(2023, 6, 30), LocalDate.of(2023, 12, 31),
                    "segundo semestre de 2023",
                    "Las tasas tocan techo y la Fed anticipa recortes",
                    "La Fed subió por última vez en julio y en diciembre dio a entender que bajaría tasas en 2024. "
                            + "Eso impulsó a casi todo, sobre todo a bancos y tecnología; el consumo básico se quedó atrás."),
            new PortfolioPeriod("2024-1", LocalDate.of(2023, 12, 31), LocalDate.of(2024, 6, 30),
                    "primer semestre de 2024",
                    "La fiebre de la IA sigue y los recortes de tasas se postergan",
                    "La inflación bajó más lento de lo esperado y la Fed no recortó: los bonos largos cayeron. La "
                            + "tecnología siguió liderando con la IA y el oro marcó récords."),
            new PortfolioPeriod("2024-2", LocalDate.of(2024, 6, 30), LocalDate.of(2024, 12, 31),
                    "segundo semestre de 2024",
                    "La Fed empieza a recortar y Trump gana las elecciones",
                    "Bajaron las tasas cortas, pero los bonos largos igual cayeron por el temor a más déficit e "
                            + "inflación. Los bancos subieron con la expectativa de menos regulación, y el oro siguió "
                            + "de récord en récord."),
            new PortfolioPeriod("2025-1", LocalDate.of(2024, 12, 31), LocalDate.of(2025, 6, 30),
                    "primer semestre de 2025",
                    "Guerra de aranceles: el mercado se desploma en abril y rebota",
                    "El miedo a una guerra comercial llevó al oro a máximos: fue lo que más subió. Las acciones "
                            + "cayeron fuerte en abril y se recuperaron cuando se pausaron los aranceles; las small caps "
                            + "terminaron en rojo."),
            new PortfolioPeriod("2025-2", LocalDate.of(2025, 6, 30), LocalDate.of(2025, 12, 31),
                    "segundo semestre de 2025",
                    "La Fed vuelve a recortar y el oro supera los 4000 dólares",
                    "Con tasas más bajas volvió el apetito por riesgo: las small caps, la salud y la tecnología "
                            + "subieron fuerte. El oro tuvo uno de sus mejores semestres; el consumo básico quedó atrás."));

}
