package com.checkout.backend.race.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Un tramo real del mercado que la carrera repite acelerado. Moneda, fecha y
 * contexto solo se revelan al final.
 *
 * Cada tramo es la ventana de 6 horas con mas recorrido de su dia, buscada en
 * las velas de un minuto de Coinbase. Los textos llevan tildes porque se
 * muestran tal cual en la pantalla final.
 */
public record RaceScenario(String key, String productId, String coinName, Instant start, String title,
                           String description) {

    /** Velas de un minuto por carrera: a 4 por segundo, 90 segundos. */
    public static final int MINUTES = 360;

    public static final List<RaceScenario> ALL = List.of(
            new RaceScenario("btc-2020-03-12", "BTC-USD", "Bitcoin", Instant.parse("2020-03-12T05:30:00Z"),
                    "El jueves negro",
                    "El 12 de marzo de 2020 el miedo al COVID desató ventas de pánico en todos los mercados, "
                            + "y bitcoin perdió más de una cuarta parte de su valor en pocas horas."),
            new RaceScenario("eth-2021-05-19", "ETH-USD", "Ether", Instant.parse("2021-05-19T13:10:00Z"),
                    "El rebote después del desplome",
                    "El 19 de mayo de 2021 la cripto se desplomó en la mañana y rebotó con fuerza en la tarde: "
                            + "quien vendió en el pánico se perdió la subida."),
            new RaceScenario("sol-2022-11-08", "SOL-USD", "Solana", Instant.parse("2022-11-08T15:30:00Z"),
                    "La crisis de FTX",
                    "El 8 de noviembre de 2022 el exchange FTX entró en crisis, y Solana, muy ligada a FTX, "
                            + "subió y cayó con cada noticia."),
            new RaceScenario("sol-2022-11-09", "SOL-USD", "Solana", Instant.parse("2022-11-09T10:20:00Z"),
                    "Subir para devolverlo todo",
                    "Al día siguiente del golpe de FTX, Solana subió más de 30 % en unas horas y devolvió "
                            + "toda la subida antes de terminar el tramo."),
            new RaceScenario("btc-2024-03-05", "BTC-USD", "Bitcoin", Instant.parse("2024-03-05T14:10:00Z"),
                    "Máximo histórico y caída",
                    "El 5 de marzo de 2024 bitcoin superó su máximo histórico y en poco tiempo cayó más de 10 %."),
            new RaceScenario("eth-2024-08-05", "ETH-USD", "Ether", Instant.parse("2024-08-05T00:10:00Z"),
                    "El lunes negro de agosto",
                    "El 5 de agosto de 2024 los mercados del mundo cayeron por el temor a una recesión, "
                            + "y ether perdió una quinta parte de su valor esa madrugada."));

    public static Optional<RaceScenario> byKey(String key) {
        return ALL.stream().filter(scenario -> scenario.key().equals(key)).findFirst();
    }

}
