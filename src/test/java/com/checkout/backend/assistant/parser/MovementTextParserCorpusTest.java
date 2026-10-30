package com.checkout.backend.assistant.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Corre el parser sobre las frases de {@link ParserCorpus} y junta los fallos
 * por familia, para ver que forma de escribir falla y no cientos de lineas
 * sueltas. La lista completa queda en target/parser-corpus-fallos.txt.
 */
class MovementTextParserCorpusTest {

    private static final List<ParserCorpus.Case> CASES = ParserCorpus.generate();
    private static final List<String> FAILURES = new ArrayList<>();

    private final MovementTextParser parser = new MovementTextParser(
            Clock.fixed(Instant.parse("2026-10-28T15:00:00Z"), ZoneId.of("America/Lima")));

    @Test
    @DisplayName("Hay al menos 10 000 frases distintas")
    void hasTenThousandPhrases() {
        assertThat(CASES).hasSizeGreaterThanOrEqualTo(10_000);
    }

    @TestFactory
    Stream<DynamicTest> everyFamilyParses() {
        Map<String, List<ParserCorpus.Case>> families = CASES.stream()
                .collect(Collectors.groupingBy(ParserCorpus.Case::family, LinkedHashMap::new, Collectors.toList()));
        return families.entrySet().stream().map(family -> DynamicTest.dynamicTest(
                family.getKey() + " (" + family.getValue().size() + " frases)", () -> {
                    List<String> failures = family.getValue().stream().map(this::failure).filter(f -> f != null).toList();
                    synchronized (FAILURES) {
                        failures.forEach(f -> FAILURES.add("[" + family.getKey() + "] " + f));
                    }
                    if (!failures.isEmpty()) {
                        fail(failures.size() + " de " + family.getValue().size() + " frases fallan. Las primeras:\n"
                                + String.join("\n", failures.subList(0, Math.min(15, failures.size()))));
                    }
                }));
    }

    @AfterAll
    static void writeFailures() throws IOException {
        Path report = Path.of("target", "parser-corpus-fallos.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, String.join("\n", FAILURES));
    }

    private String failure(ParserCorpus.Case c) {
        List<String> actual;
        try {
            actual = parser.parse(c.text()).stream().map(MovementTextParserTest::line).toList();
        } catch (RuntimeException e) {
            actual = List.of(e.toString());
        }
        return actual.equals(c.expected()) ? null
                : "«" + c.text().replace("\n", "⏎") + "»\n    esperado " + c.expected() + "\n    obtuvo   " + actual;
    }

}
