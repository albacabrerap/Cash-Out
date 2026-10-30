package com.checkout.backend.assistant.classifier;

import com.checkout.backend.assistant.classifier.Classification.Kind;
import com.checkout.backend.assistant.classifier.Classification.Option;
import com.checkout.backend.assistant.classifier.Classification.Source;
import com.checkout.backend.assistant.parser.ParsedMovement.Hint;
import com.checkout.backend.savings.expense.model.ExpenseCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * El ultimo eslabon: un diccionario de palabras peruanas por categoria. Siempre
 * responde, pero con confianza baja, para que la tarjeta muestre que es una
 * adivinanza y ofrezca alternativas.
 */
@Component
@Order(100)
public class KeywordClassifier implements MovementClassifier {

    /** Ni con todas las palabras a favor pasa de aqui: una regla del usuario o Jev saben mas. */
    static final double MAX_CONFIDENCE = 0.6;
    static final double UNKNOWN_CONFIDENCE = 0.2;

    private static final Map<ExpenseCategory, List<String>> WORDS = Map.of(
            ExpenseCategory.FOOD, List.of("menu", "almuerzo", "desayuno", "cena", "lonche", "comida", "chifa",
                    "polleria", "pollo", "pollo a la brasa", "broaster", "ceviche", "cevicheria", "restaurante",
                    "restaurant", "cafe", "cafeteria", "panaderia", "pan", "mercado", "bodega", "supermercado",
                    "supermercados", "tottus", "plaza vea", "plazavea", "wong", "vivanda", "tambo", "oxxo", "mass",
                    "makro", "kfc", "mcdonalds", "bembos", "pizza", "pizzeria", "starbucks", "rappi", "pedidosya",
                    "delivery", "snack", "galletas", "gaseosa", "agua mineral", "fruta", "verduras", "empanada",
                    "hamburguesa", "sanguche", "jugo", "postre", "helado", "leche", "arroz", "huevos"),
            ExpenseCategory.TRANSPORT, List.of("taxi", "uber", "didi", "cabify", "indriver", "combi", "micro", "bus",
                    "pasaje", "pasajes", "metropolitano", "corredor", "tren", "linea 1", "colectivo", "mototaxi",
                    "gasolina", "grifo", "combustible", "peaje", "estacionamiento", "cochera", "scooter", "vuelo",
                    "avion"),
            ExpenseCategory.SERVICES, List.of("luz", "agua", "internet", "telefono", "celular", "recarga", "plan",
                    "claro", "movistar", "entel", "bitel", "enel", "luz del sur", "sedapal", "calidda", "gas",
                    "alquiler", "mantenimiento", "cable", "lavanderia"),
            ExpenseCategory.EDUCATION, List.of("universidad", "utec", "pension", "matricula", "curso", "libro",
                    "libros", "copias", "impresiones", "utiles", "cuaderno", "taller", "academia", "udemy",
                    "coursera", "platzi", "colegio"),
            ExpenseCategory.LEISURE, List.of("cine", "cineplanet", "cinemark", "netflix", "spotify", "disney",
                    "hbo", "youtube premium", "juego", "juegos", "steam", "playstation", "xbox", "concierto",
                    "entrada", "entradas", "fiesta", "discoteca", "bar", "chela", "chelas", "cerveza", "trago",
                    "tragos", "salida", "viaje", "regalo", "cumple", "cumpleanos"),
            ExpenseCategory.HEALTH, List.of("farmacia", "farmacias", "inkafarma", "mifarma", "botica", "boticas",
                    "medicina", "medicinas", "pastillas", "doctor", "medico", "consulta", "clinica", "hospital",
                    "dentista", "analisis", "gimnasio", "gym", "smart fit", "vacuna"));

    private static final List<String> INCOME_WORDS = List.of("sueldo", "salario", "quincena", "freelance", "bono",
            "aguinaldo", "gratificacion", "mesada", "cachuelo", "reembolso", "devolucion", "venta");

    @Override
    public Optional<Classification> classify(ClassificationInput input) {
        String text = input.searchable();
        boolean incomeWord = INCOME_WORDS.stream().anyMatch(word -> Texts.containsWords(text, word));
        Kind kind = input.hint() == Hint.INCOME || (input.hint() == null && incomeWord) ? Kind.INCOME : Kind.EXPENSE;
        if (kind == Kind.INCOME) {
            return Optional.of(new Classification(Kind.INCOME, null, input.hint() != null ? MAX_CONFIDENCE : 0.5,
                    List.of(), Source.KEYWORDS));
        }

        Map<ExpenseCategory, Integer> votes = votes(text);
        if (votes.isEmpty()) {
            return Optional.of(new Classification(Kind.EXPENSE, ExpenseCategory.OTHER, UNKNOWN_CONFIDENCE, List.of(),
                    Source.KEYWORDS));
        }
        int total = votes.values().stream().mapToInt(Integer::intValue).sum();
        List<Option> ranked = votes.entrySet().stream()
                .sorted(Map.Entry.<ExpenseCategory, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(vote -> new Option(vote.getKey(), MAX_CONFIDENCE * vote.getValue() / total))
                .toList();
        Option best = ranked.get(0);
        return Optional.of(new Classification(Kind.EXPENSE, best.category(), best.probability(),
                ranked.subList(1, ranked.size()), Source.KEYWORDS));
    }

    /**
     * Cuantas palabras de cada categoria aparecen. Si una frase contiene a otra
     * que tambien aparecio ("agua mineral" y "agua"), solo cuenta la larga.
     */
    private static Map<ExpenseCategory, Integer> votes(String text) {
        List<Map.Entry<String, ExpenseCategory>> found = new ArrayList<>();
        WORDS.forEach((category, words) -> words.stream()
                .filter(word -> Texts.containsWords(text, word))
                .forEach(word -> found.add(Map.entry(word, category))));
        found.sort(Comparator.comparingInt((Map.Entry<String, ExpenseCategory> entry) -> entry.getKey().length()).reversed());

        Map<ExpenseCategory, Integer> votes = new EnumMap<>(ExpenseCategory.class);
        List<String> counted = new ArrayList<>();
        for (Map.Entry<String, ExpenseCategory> entry : found) {
            if (counted.stream().noneMatch(longer -> Texts.containsWords(longer, entry.getKey()))) {
                counted.add(entry.getKey());
                votes.merge(entry.getValue(), 1, Integer::sum);
            }
        }
        return votes;
    }

}
