package com.checkout.backend.assistant.parser;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * La tabla de frases del parser. Hoy es el miercoles 28/10/2026 en Lima.
 *
 * Cada movimiento esperado va en una linea: descripcion · monto · fecha ·
 * medio · pista, y al final la moneda si no es soles y los avisos si hay.
 * "∅" es que falta el dato.
 */
class MovementTextParserTest {

    private static final String TODAY = "2026-10-28";
    private static final String YESTERDAY = "2026-10-27";

    private final MovementTextParser parser = new MovementTextParser(
            Clock.fixed(Instant.parse("2026-10-28T15:00:00Z"), ZoneId.of("America/Lima")));

    static Stream<Arguments> phrases() {
        return Stream.of(
                // Partir en movimientos
                arguments("ayer taxi 12 y chifa 35,90 con yape", List.of(
                        "Taxi · 12.00 · " + YESTERDAY + " · YAPE · -",
                        "Chifa · 35.90 · " + YESTERDAY + " · YAPE · -")),
                arguments("luz 89 agua 45 internet 99", List.of(
                        "Luz · 89.00 · " + TODAY + " · - · -",
                        "Agua · 45.00 · " + TODAY + " · - · -",
                        "Internet · 99.00 · " + TODAY + " · - · -")),
                arguments("12 taxi 35 chifa", List.of(
                        "Taxi · 12.00 · " + TODAY + " · - · -",
                        "Chifa · 35.00 · " + TODAY + " · - · -")),
                arguments("12 y 35", List.of(
                        "∅ · 12.00 · " + TODAY + " · - · -",
                        "∅ · 35.00 · " + TODAY + " · - · -")),
                arguments("café 4,50, empanada 6", List.of(
                        "Café · 4.50 · " + TODAY + " · - · -",
                        "Empanada · 6.00 · " + TODAY + " · - · -")),
                arguments("luz 89\nagua 45", List.of(
                        "Luz · 89.00 · " + TODAY + " · - · -",
                        "Agua · 45.00 · " + TODAY + " · - · -")),
                arguments("taxi 12 más propina 2", List.of(
                        "Taxi · 12.00 · " + TODAY + " · - · -",
                        "Propina · 2.00 · " + TODAY + " · - · -")),

                // Trozos sin monto: se unen al vecino
                arguments("pan y leche 8", List.of("Pan y leche · 8.00 · " + TODAY + " · - · -")),
                arguments("chifa 35 y el postre", List.of("Chifa y el postre · 35.00 · " + TODAY + " · - · -")),
                arguments("almuerzo con yape", List.of("Almuerzo · ∅ · " + TODAY + " · YAPE · - · [MISSING_AMOUNT]")),

                // Descripciones en cualquier orden
                arguments("gasté 35 en chifa", List.of("Chifa · 35.00 · " + TODAY + " · - · EXPENSE")),
                arguments("pasaje en combi 2", List.of("Pasaje en combi · 2.00 · " + TODAY + " · - · -")),
                arguments("35 soles de pollo a la brasa", List.of("Pollo a la brasa · 35.00 · " + TODAY + " · - · -")),
                arguments("S/ 35.90 en farmacia", List.of("Farmacia · 35.90 · " + TODAY + " · - · -")),
                arguments("plin 25 a maría por el cumple", List.of("María por el cumple · 25.00 · " + TODAY + " · PLIN · -")),

                // Montos
                arguments("alquiler 1,500", List.of("Alquiler · 1500.00 · " + TODAY + " · - · -")),
                arguments("sueldo 2 mil", List.of("Sueldo · 2000.00 · " + TODAY + " · - · INCOME")),
                arguments("1.5k de bono", List.of("Bono · 1500.00 · " + TODAY + " · - · INCOME")),
                arguments("netflix $10", List.of("Netflix · 10.00 · " + TODAY + " · - · - · USD · [FOREIGN_CURRENCY]")),
                arguments("departamento 250000", List.of("Departamento · 250000.00 · " + TODAY + " · - · - · [HIGH_AMOUNT]")),

                // Cantidades
                arguments("2 menús de 12", List.of("2 menús · 24.00 · " + TODAY + " · - · -")),
                arguments("dos menús de 12", List.of("Dos menús · 24.00 · " + TODAY + " · - · -")),
                arguments("tres gaseosas a 2.50", List.of("Tres gaseosas · 7.50 · " + TODAY + " · - · -")),
                // Una palabra suelta no es monto: "dos personas" no parte el pasaje en dos.
                arguments("pasaje dos personas 10", List.of("Pasaje dos personas · 10.00 · " + TODAY + " · - · -")),
                arguments("1 kilo de arroz 5", List.of("1 kilo de arroz · 5.00 · " + TODAY + " · - · -")),
                arguments("3 panes 1.20", List.of("3 panes · 1.20 · " + TODAY + " · - · -")),
                arguments("cena 120 entre 4", List.of("Cena · 120.00 · " + TODAY + " · - · - · [NOT_SPLIT]")),
                arguments("cumple de ana 50 entre 5 personas", List.of("Cumple de ana · 50.00 · " + TODAY + " · - · - · [NOT_SPLIT]")),

                // Fechas
                arguments("15/10 luz 89", List.of("Luz · 89.00 · 2026-10-15 · - · -")),
                arguments("el lunes almuerzo 15", List.of("Almuerzo · 15.00 · 2026-10-26 · - · -")),
                arguments("almuerzo 15 el miércoles", List.of("Almuerzo · 15.00 · 2026-10-21 · - · -")),
                arguments("3 de octubre cine 25", List.of("Cine · 25.00 · 2026-10-03 · - · -")),
                arguments("20 de diciembre regalo 100", List.of("Regalo · 100.00 · 2025-12-20 · - · -")),
                arguments("taxi 12 y chifa 35 ayer", List.of(
                        "Taxi · 12.00 · " + YESTERDAY + " · - · -",
                        "Chifa · 35.00 · " + YESTERDAY + " · - · -")),
                arguments("taxi a las 3 pm 12", List.of("Taxi · 12.00 · " + TODAY + " · - · -")),

                // Medio de pago
                arguments("menú 12 con tarjeta", List.of("Menú · 12.00 · " + TODAY + " · CARD · -")),
                arguments("pagué 45 de luz con tarjeta de crédito", List.of("Luz · 45.00 · " + TODAY + " · CARD · EXPENSE")),
                arguments("taxi 12 en efectivo y chifa 35 con yape", List.of(
                        "Taxi · 12.00 · " + TODAY + " · CASH · -",
                        "Chifa · 35.00 · " + TODAY + " · YAPE · -")),
                arguments("yapeé 20 a juan", List.of("Juan · 20.00 · " + TODAY + " · YAPE · EXPENSE")),

                // Pista de ingreso o gasto
                arguments("me yapearon 100 de mi tía", List.of("Mi tía · 100.00 · " + TODAY + " · YAPE · INCOME")),
                arguments("me pagaron 1500 del freelance", List.of("Freelance · 1500.00 · " + TODAY + " · - · INCOME")),
                arguments("recibí 200 de mi papá", List.of("Mi papá · 200.00 · " + TODAY + " · - · INCOME")),
                arguments("me depositaron el sueldo 2500", List.of("Sueldo · 2500.00 · " + TODAY + " · TRANSFER · INCOME")),
                arguments("me cobraron 15 de comisión", List.of("Comisión · 15.00 · " + TODAY + " · - · EXPENSE")),
                // La plata viene hacia quien escribe, aunque el verbo sea "pago".
                arguments("pago a mi persona 100", List.of("∅ · 100.00 · " + TODAY + " · - · INCOME")),
                arguments("pago a mí 80 por las clases", List.of("Clases · 80.00 · " + TODAY + " · - · INCOME")),
                arguments("yape para mí 25", List.of("∅ · 25.00 · " + TODAY + " · YAPE · INCOME")),
                arguments("me pagó 50 juan", List.of("Juan · 50.00 · " + TODAY + " · - · INCOME")),
                arguments("me yapeó 30 mi tía", List.of("Mi tía · 30.00 · " + TODAY + " · YAPE · INCOME")),
                arguments("me transfirió 400 el cliente", List.of("Cliente · 400.00 · " + TODAY + " · TRANSFER · INCOME")),
                // "A mi mamá" es a otra persona, y un recibo que llega es un gasto.
                arguments("pago a mi mamá 50", List.of("Pago a mi mamá · 50.00 · " + TODAY + " · - · -")),
                arguments("pago de luz 89", List.of("Pago de luz · 89.00 · " + TODAY + " · - · -")),
                arguments("me llegó el recibo de luz 89", List.of("Llegó el recibo de luz · 89.00 · " + TODAY + " · - · -")),
                arguments("sueldo 1200 y bono 300", List.of(
                        "Sueldo · 1200.00 · " + TODAY + " · - · INCOME",
                        "Bono · 300.00 · " + TODAY + " · - · INCOME")),
                // La pista pasa hacia adelante, nunca hacia atras.
                arguments("taxi 12 y me pagaron 50", List.of(
                        "Taxi · 12.00 · " + TODAY + " · - · -",
                        "∅ · 50.00 · " + TODAY + " · - · INCOME")),

                // Texto largo: no se adivina, se avisa. "Compramos" es un verbo de gasto y sale de la descripcion.
                arguments("hoy fui al centro con mis amigos de la universidad y compramos regalos 80", List.of(
                        "Fui al centro con mis amigos de la universidad y regalos · 80.00 · " + TODAY
                                + " · - · EXPENSE · [LONG_DESCRIPTION]")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("phrases")
    void parsesThePhrase(String phrase, List<String> expected) {
        assertThat(parser.parse(phrase).stream().map(MovementTextParserTest::line).toList())
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("Sin texto, o con solo fecha y medio de pago, no hay movimientos")
    void nothingToParse() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse("ayer")).isEmpty();
        assertThat(parser.parse("con yape")).isEmpty();
    }

    @Test
    @DisplayName("Un mensaje enorme da como mucho 20 movimientos")
    void capsTheNumberOfMovements() {
        String many = IntStream.rangeClosed(1, 30).mapToObj(i -> "gasto " + (i + 30)).collect(Collectors.joining(", "));

        assertThat(parser.parse(many)).hasSize(MovementTextParser.MAX_ITEMS);
    }

    @Test
    @DisplayName("Los separadores de miles y decimales siguen la costumbre peruana y la europea")
    void readsNumbers() {
        assertThat(MovementTextParser.number("1,500")).isEqualByComparingTo("1500");
        assertThat(MovementTextParser.number("1.500")).isEqualByComparingTo("1500");
        assertThat(MovementTextParser.number("35,90")).isEqualByComparingTo("35.90");
        assertThat(MovementTextParser.number("1,500.50")).isEqualByComparingTo("1500.50");
        assertThat(MovementTextParser.number("1.500,50")).isEqualByComparingTo("1500.50");
        assertThat(MovementTextParser.number("12")).isEqualByComparingTo("12");
    }

    static String line(ParsedMovement movement) {
        StringBuilder line = new StringBuilder(String.join(" · ",
                movement.description() == null ? "∅" : movement.description(),
                movement.amount() == null ? "∅" : movement.amount().toPlainString(),
                movement.date().toString(),
                movement.paymentMethod() == null ? "-" : movement.paymentMethod().name(),
                movement.hint() == null ? "-" : movement.hint().name()));
        if (movement.currency() != ParsedMovement.Currency.PEN) {
            line.append(" · ").append(movement.currency());
        }
        if (!movement.warnings().isEmpty()) {
            line.append(" · ").append(movement.warnings());
        }
        return line.toString();
    }

}
