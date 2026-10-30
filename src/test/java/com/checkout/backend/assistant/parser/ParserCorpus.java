package com.checkout.backend.assistant.parser;

import com.checkout.backend.assistant.parser.ParsedMovement.Currency;
import com.checkout.backend.assistant.parser.ParsedMovement.Hint;
import com.checkout.backend.assistant.parser.ParsedMovement.PaymentMethod;
import com.checkout.backend.assistant.parser.ParsedMovement.Warning;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static com.checkout.backend.assistant.parser.ParsedMovement.Hint.EXPENSE;
import static com.checkout.backend.assistant.parser.ParsedMovement.Hint.INCOME;
import static com.checkout.backend.assistant.parser.ParsedMovement.PaymentMethod.CARD;
import static com.checkout.backend.assistant.parser.ParsedMovement.PaymentMethod.CASH;
import static com.checkout.backend.assistant.parser.ParsedMovement.PaymentMethod.PLIN;
import static com.checkout.backend.assistant.parser.ParsedMovement.PaymentMethod.TRANSFER;
import static com.checkout.backend.assistant.parser.ParsedMovement.PaymentMethod.YAPE;

/**
 * Mas de 10 000 frases armadas con piezas reales: descripciones, montos,
 * fechas, medios de pago, verbos, conectores y la forma de escribir de la gente
 * (mayusculas, sin tildes, con saludos). Cada pieza sabe lo que significa, asi
 * que lo esperado sale de como se armo la frase y no de lo que hace el parser.
 * La semilla es fija: cada corrida prueba las mismas frases.
 *
 * Hoy es el miercoles 28/10/2026, igual que en la tabla.
 */
final class ParserCorpus {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final BigDecimal HIGH = new BigDecimal("100000");

    /** Una frase, su familia y los movimientos esperados en el formato de la tabla. */
    record Case(String family, String text, List<String> expected) {
    }

    /** Un monto escrito de una forma; {@code last}: solo puede cerrar el trozo, como "12 con 50". */
    private record Amt(String text, BigDecimal value, Currency currency, boolean last, boolean count) {
    }

    private record Day(String text, LocalDate date) {
    }

    private record Way(String text, PaymentMethod method) {
    }

    /** Un verbo y el medio que trae: "me yapearon" es Yape. */
    private record Verb(String text, PaymentMethod method) {
    }

    /** Como se dice ("del freelance") y la descripcion que queda ("freelance"). */
    private record Said(String text, String desc) {
    }

    private record Body(String text, Hint hint) {
    }

    private record Item(String text, String desc, Amt amount, Hint hint, PaymentMethod method) {

        Item with(String text, PaymentMethod method) {
            return new Item(text, desc, amount, hint, method);
        }
    }

    private record Mov(String desc, Amt amount, LocalDate date, PaymentMethod method, Hint hint, boolean notSplit) {
    }

    private record Draft(String text, List<Mov> movements) {
    }

    private record Family(String name, int size, Supplier<Draft> next) {
    }

    // ---------- Piezas ----------

    private static final List<String> EXPENSES = List.of(
            "menú", "almuerzo", "desayuno", "cena", "lonche", "chifa", "pollo a la brasa", "ceviche", "broaster",
            "hamburguesa", "pizza", "salchipapa", "anticuchos", "café", "empanada", "jugo de papaya", "emoliente",
            "chicha morada", "inca kola", "gaseosa", "galletas", "chocolate", "helado", "pan con chicharrón", "keke",
            "tamales", "lomo saltado", "arroz chaufa", "tallarines", "sushi", "alitas", "picarones", "cerveza",
            "menú del día", "rappi", "pedidosya", "frutas", "verduras", "mercado", "bodega", "supermercado",
            "plaza vea", "tottus", "wong", "tambo", "oxxo", "starbucks", "bembos", "kfc", "propina",
            "taxi", "uber", "cabify", "didi", "indriver", "pasaje", "pasaje en combi", "micro", "metropolitano",
            "corredor rojo", "colectivo", "mototaxi", "gasolina", "grifo", "peaje", "estacionamiento", "bus a ica",
            "vuelo a cusco",
            "luz", "agua", "internet", "cable", "teléfono", "recarga claro", "plan movistar", "entel", "bitel",
            "balón de gas", "alquiler", "mantenimiento del edificio", "arbitrios", "lavandería", "gasfitero",
            "electricista", "cerrajero",
            "farmacia", "inkafarma", "mifarma", "pastillas", "consulta médica", "dentista", "análisis de sangre",
            "vacuna", "lentes", "gimnasio",
            "pensión de la universidad", "matrícula", "libros", "útiles", "cuadernos", "fotocopias", "impresiones",
            "curso de inglés", "academia",
            "cine", "netflix", "spotify", "disney plus", "entradas al concierto", "discoteca", "karaoke", "teatro",
            "museo", "hotel", "airbnb", "regalo de cumpleaños", "peluquería", "corte de pelo", "manicure", "ropa",
            "zapatillas", "polo", "casaca", "mochila", "audífonos", "cargador", "funda del celular", "steam",
            "comisión del banco", "multa", "papeleta", "notaría", "donación", "colecta", "cuota del club",
            "veterinario", "comida del perro", "arena del gato");

    private static final List<String> PEOPLE = List.of("juan", "maría", "pedro", "ana", "luis", "rosa", "carlos",
            "lucía", "mi mamá", "mi papá", "mi tía", "mi tío", "mi hermano", "mi hermana", "mi abuela", "mi primo",
            "mi enamorada", "mi jefe", "el profe", "el vecino", "la señora del puesto", "el gasfitero");
    private static final List<String> RELATIVES = List.of("mi mamá", "mi papá", "mi tía", "mi hermano", "mi primo",
            "mi enamorada", "mi abuela", "mi jefe");
    private static final List<String> SOLD = List.of("mi bici", "mi celular", "ropa usada", "mi laptop",
            "unas zapatillas", "postres", "mi play", "libros usados", "un ropero");

    private static final List<Said> SOURCES = List.of(said("del freelance", "freelance"), said("de mi tía", "mi tía"),
            said("por las clases", "clases"), said("por la chamba", "chamba"), said("del cliente", "cliente"),
            said("de la empresa", "empresa"), said("por el cachuelo", "cachuelo"), said("de juan", "juan"),
            said("del trabajo", "trabajo"), said("por la venta del celular", "venta del celular"),
            said("por asesoría", "asesoría"), said("de mi jefe", "mi jefe"), said("por el proyecto", "proyecto"),
            said("de la tienda", "tienda"), said("por las tutorías", "tutorías"),
            said("del alquiler del cuarto", "alquiler del cuarto"), said("de mi papá", "mi papá"),
            said("por el diseño del logo", "diseño del logo"), said("de maría", "maría"),
            said("por las horas extra", "horas extra"));
    private static final List<Said> REASONS = List.of(said("por las clases", "clases"), said("por el proyecto", "proyecto"),
            said("de juan", "juan"), said("de mi tía", "mi tía"), said("por la venta", "venta"),
            said("por asesoría", "asesoría"), said("del cliente", "cliente"));
    private static final List<Said> CHARGES = List.of(said("de comisión", "comisión"), said("de mantenimiento", "mantenimiento"),
            said("por el delivery", "delivery"), said("por el envío", "envío"), said("de intereses", "intereses"),
            said("por la multa", "multa"), said("por exceso de equipaje", "exceso de equipaje"),
            said("por la reconexión", "reconexión"), said("de membresía", "membresía"));

    private static final List<String> INCOME_NOUNS = List.of("sueldo", "salario", "quincena", "freelance", "bono",
            "aguinaldo", "gratificación", "mesada", "utilidades", "cts", "reintegro", "honorarios", "cachuelo",
            "reembolso", "devolución de impuestos", "venta de ropa", "ventas del día", "premio", "beca", "horas extra",
            "remesa", "dividendos");

    private static final List<String> SPEND = List.of("gasté", "gaste", "gastamos", "me gasté");
    private static final List<String> PAY = List.of("pagué", "pague", "pagamos");
    private static final List<String> BUY = List.of("compré", "compre", "compramos");
    private static final List<Verb> TO_SOMEONE = List.of(verb("yapeé", YAPE), verb("yapee", YAPE), verb("plineé", PLIN),
            verb("transferí", TRANSFER), verb("le yapeé", YAPE), verb("le pagué", null), verb("le di", null),
            verb("di", null), verb("envié", null), verb("le envié", null), verb("mandé", null), verb("le mandé", null),
            verb("presté", null), verb("le presté", null));
    private static final List<Verb> INCOME_VERBS = List.of(verb("me pagaron", null), verb("me pagan", null),
            verb("me pagó", null), verb("me depositaron", TRANSFER), verb("me depositó", TRANSFER),
            verb("me yapearon", YAPE), verb("me yapeó", YAPE), verb("me plinearon", PLIN), verb("me plineó", PLIN),
            verb("me transfirieron", TRANSFER), verb("me transfirió", TRANSFER), verb("me dieron", null),
            verb("me dio", null), verb("me devolvieron", null), verb("me devolvió", null), verb("me prestaron", null),
            verb("me prestó", null), verb("me enviaron", null), verb("me envió", null), verb("me mandaron", null),
            verb("me mandó", null), verb("me regalaron", null), verb("me regaló", null), verb("me pasaron", null),
            verb("me abonaron", null), verb("me reembolsaron", null), verb("recibí", null), verb("cobré", null),
            verb("gané", null), verb("recibimos", null), verb("cobramos", null), verb("ganamos", null));
    /** Los que dejan decir quien pago: "juan me pago 50", "me pago juan 50". */
    private static final List<Verb> BY_SOMEONE = List.of(verb("me pagó", null), verb("me depositó", TRANSFER),
            verb("me yapeó", YAPE), verb("me plineó", PLIN), verb("me transfirió", TRANSFER), verb("me dio", null),
            verb("me devolvió", null), verb("me prestó", null), verb("me envió", null), verb("me mandó", null),
            verb("me regaló", null));
    private static final List<Verb> ARRIVALS = List.of(verb("me cayó un yape de", YAPE), verb("me llegó un yape de", YAPE),
            verb("me llegó un plin de", PLIN), verb("me cayó un plin de", PLIN),
            verb("me llegó una transferencia de", TRANSFER), verb("me cayó un depósito de", TRANSFER),
            verb("me llegó el pago de", null), verb("me cayó el abono de", null));
    private static final List<Said> ARRIVED = List.of(said("me cayó la quincena", "quincena"),
            said("me llegó el sueldo", "sueldo"), said("me cayó la gratificación", "gratificación"),
            said("me llegó la cts", "cts"), said("me cayó el bono", "bono"),
            said("me llegaron las utilidades", "utilidades"), said("me cayeron los honorarios", "honorarios"));
    private static final List<Way> TOWARD_ME = List.of(way("pago", null), way("abono", null), way("depósito", TRANSFER),
            way("deposito", TRANSFER), way("transferencia", TRANSFER), way("yape", YAPE), way("plin", PLIN));

    private static final List<Day> DAYS = List.of(
            back("hoy", 0), back("ayer", 1), back("antier", 2), back("anteayer", 2), back("anoche", 1),
            back("anteanoche", 2), back("el lunes", 2), back("lunes", 2), back("este lunes", 2),
            back("el lunes pasado", 2), back("el martes", 1), back("el miércoles", 7), back("el miercoles", 7),
            back("el jueves", 6), back("el viernes", 5), back("el sábado", 4), back("el sabado", 4),
            back("el domingo", 3), back("el domingo pasado", 3), back("hace 2 días", 2), back("hace 3 dias", 3),
            back("hace dos días", 2), back("hace tres días", 3), back("hace una semana", 7), back("hace 1 semana", 7),
            back("hace 10 días", 10),
            on("15/10", "2026-10-15"), on("1/10", "2026-10-01"), on("05/09", "2026-09-05"),
            on("15/10/2026", "2026-10-15"), on("15/10/26", "2026-10-15"), on("31/12", "2025-12-31"),
            on("28/10", "2026-10-28"), on("15-10", "2026-10-15"), on("3-9-2026", "2026-09-03"),
            on("3 de octubre", "2026-10-03"), on("el 3 de octubre", "2026-10-03"), on("3 de setiembre", "2026-09-03"),
            on("3 de septiembre", "2026-09-03"), on("20 de diciembre", "2025-12-20"),
            on("1 de enero de 2026", "2026-01-01"), on("15 de oct", "2026-10-15"), on("15 oct", "2026-10-15"),
            on("15 octubre", "2026-10-15"), on("el 2 de nov", "2025-11-02"), on("10 de ago.", "2026-08-10"),
            back("ayer en la noche", 1), back("ayer por la tarde", 1), back("hoy en la mañana", 0),
            back("esta mañana", 0), back("esta tarde", 0), back("el sábado en la noche", 4));

    private static final List<Way> WAYS = List.of(way("con yape", YAPE), way("por yape", YAPE), way("yape", YAPE),
            way("vía yape", YAPE), way("x yape", YAPE), way("con mi yape", YAPE), way("con plin", PLIN),
            way("por plin", PLIN), way("plin", PLIN), way("con tarjeta", CARD), way("con tarjeta de crédito", CARD),
            way("con tarjeta de debito", CARD), way("con la tarjeta", CARD), way("con mi tarjeta", CARD),
            way("con visa", CARD), way("con mastercard", CARD), way("con débito", CARD), way("con crédito", CARD),
            way("en efectivo", CASH), way("efectivo", CASH), way("cash", CASH), way("en cash", CASH),
            way("por transferencia", TRANSFER), way("con transferencia", TRANSFER), way("vía transferencia", TRANSFER),
            way("por una transferencia", TRANSFER));
    private static final List<Way> INCOME_WAYS = List.of(way("con yape", YAPE), way("por yape", YAPE),
            way("por plin", PLIN), way("en efectivo", CASH), way("efectivo", CASH), way("por transferencia", TRANSFER),
            way("vía transferencia", TRANSFER));

    private static final List<String> CONNECTORS = List.of(" y ", ", ", "; ", " + ", " más ", "\n", " - ", " / ",
            ". ", " y también ", ", y ", " e ");
    private static final List<String> TIMES = List.of("a las 3", "a las 3 pm", "a las 3pm", "a las 10:30", "a la 1",
            "a las 8 de la noche", "a las 7 de la mañana", "3pm", "10:30", "10:30 am", "a las 9 hrs", "a las 11am",
            "a las 12", "a las 6 de la tarde", "a las 2 de la madrugada");
    private static final List<String> SPLITS = List.of("entre 4", "entre 3 personas", "entre 2", "entre cuatro",
            "entre tres personas", "dividido entre 4", "a medias", "entre todos");
    private static final List<String> BIG = List.of("departamento", "terreno", "casa", "auto", "camioneta", "local",
            "inicial del depa");
    private static final List<String> LONG = List.of(
            "almuerzo de cumpleaños de mi prima en el restaurante de la esquina",
            "regalo para mi mamá por el día de la madre en el centro comercial",
            "pasajes de ida a la playa con mis amigos de la universidad",
            "materiales para el trabajo final del curso de programación",
            "cena con la familia de mi enamorada en un restaurante de miraflores",
            "reparación de la pantalla rota del celular de mi hermano",
            "entradas para el partido de la selección en el estadio nacional");
    private static final List<String> PLURALS = List.of("menús", "panes", "cervezas", "gaseosas", "empanadas", "cafés",
            "entradas", "pasajes", "helados", "tamales", "polos", "cuadernos", "chocolates", "jugos", "alfajores");
    /** Unidad en singular, en plural, y lo que se compra asi. */
    private static final List<List<String>> UNITS = List.of(
            List.of("kilo", "kilos", "arroz", "azúcar", "pollo", "carne", "papa", "tomate", "limón", "queso", "cebolla"),
            List.of("kg", "kg", "arroz", "pollo", "carne", "papa"),
            List.of("litro", "litros", "leche", "aceite", "agua", "chicha"),
            List.of("paquete", "paquetes", "fideos", "galletas", "pañales"),
            List.of("docena", "docenas", "huevos", "panes"),
            List.of("lata", "latas", "cerveza", "gaseosa"),
            List.of("botella", "botellas", "agua", "cerveza", "gaseosa"));

    private static final List<String> GREETINGS = List.of("hola", "hola,", "buenas", "buenos días", "oye");
    private static final List<String> LEADING = List.of("anota ", "anota: ", "registra ", "apunta ", "agrega ",
            "hola, ", "hola ", "oye ", "porfa anota ", "buenas, anota ", "anótame ");
    private static final List<String> TRAILING = List.of(" porfa", " por favor", " gracias", " pls", "!", ".", " 🙂",
            " :)", "!!");

    private static final List<BigDecimal> VALUES = decimals("1", "1.50", "2", "2.50", "3.50", "4.50", "5", "6", "7.90",
            "8", "9.90", "10", "12", "12.50", "15", "18", "20", "22", "25", "29.90", "35", "35.90", "39.90", "45",
            "49.90", "50", "60", "75", "80", "89", "99", "100", "120", "150", "199", "250", "300", "450", "500", "800",
            "999", "1000", "1200", "1250.50", "1500", "2000", "2500", "3200", "4500", "10000", "15000", "25000");
    private static final List<BigDecimal> THOUSANDS = decimals("1000", "1500", "2000", "2500", "3000", "4500", "10000",
            "12000", "25000");
    private static final List<BigDecimal> HIGH_VALUES = decimals("120000", "250000", "350000", "480000");
    private static final Map<Integer, String> SPELLED = Map.ofEntries(Map.entry(10, "diez"), Map.entry(12, "doce"),
            Map.entry(15, "quince"), Map.entry(16, "dieciséis"), Map.entry(18, "dieciocho"), Map.entry(20, "veinte"),
            Map.entry(22, "veintidós"), Map.entry(23, "veintitrés"), Map.entry(25, "veinticinco"),
            Map.entry(30, "treinta"), Map.entry(35, "treinta y cinco"), Map.entry(45, "cuarenta y cinco"),
            Map.entry(50, "cincuenta"), Map.entry(60, "sesenta"), Map.entry(75, "setenta y cinco"),
            Map.entry(80, "ochenta"), Map.entry(89, "ochenta y nueve"), Map.entry(99, "noventa y nueve"),
            Map.entry(100, "cien"), Map.entry(120, "ciento veinte"), Map.entry(150, "ciento cincuenta"),
            Map.entry(199, "ciento noventa y nueve"), Map.entry(250, "doscientos cincuenta"),
            Map.entry(300, "trescientos"), Map.entry(450, "cuatrocientos cincuenta"), Map.entry(500, "quinientos"),
            Map.entry(800, "ochocientos"), Map.entry(1000, "mil"), Map.entry(1200, "mil doscientos"),
            Map.entry(1500, "mil quinientos"), Map.entry(2000, "dos mil"), Map.entry(2500, "dos mil quinientos"),
            Map.entry(3200, "tres mil doscientos"), Map.entry(10000, "diez mil"), Map.entry(25000, "veinticinco mil"),
            Map.entry(120000, "ciento veinte mil"), Map.entry(250000, "doscientos cincuenta mil"),
            Map.entry(350000, "trescientos cincuenta mil"), Map.entry(480000, "cuatrocientos ochenta mil"));
    /** Menos de diez solo es monto con la moneda al lado: "un cafe" y "dos menus" no son plata. */
    private static final Map<Integer, String> SPELLED_SMALL = Map.of(2, "dos", 3, "tres", 5, "cinco", 8, "ocho");

    private final Random random = new Random(2026);

    static List<Case> generate() {
        ParserCorpus corpus = new ParserCorpus();
        Map<String, Case> cases = new LinkedHashMap<>();
        for (Family family : corpus.families()) {
            int added = 0;
            for (int tries = 0; added < family.size() && tries < family.size() * 50; tries++) {
                Case next = corpus.write(family.name(), family.next().get());
                Case before = cases.putIfAbsent(next.text(), next);
                if (before == null) {
                    added++;
                } else if (!before.expected().equals(next.expected())) {
                    throw new IllegalStateException("La frase \"" + next.text() + "\" salio con dos resultados esperados");
                }
            }
        }
        return List.copyOf(cases.values());
    }

    private List<Family> families() {
        return List.of(
                new Family("Un gasto", 2600, this::expense),
                new Family("Un ingreso con verbo", 1500, this::incomeByVerb),
                new Family("Un ingreso por lo que es", 500, this::incomeByNoun),
                new Family("Plata hacia mi", 500, this::towardMe),
                new Family("Varios con conectores", 1800, this::connected),
                new Family("Varios sin conectores", 700, this::unconnected),
                new Family("Cantidades", 600, this::quantities),
                new Family("Fechas", 900, this::dates),
                new Family("Montos", 1100, this::amounts),
                new Family("Medios de pago", 600, this::methods),
                new Family("Avisos", 500, this::warnings),
                new Family("Horas", 350, this::hours),
                new Family("Sin movimientos", 150, this::nothing));
    }

    // ---------- Familias ----------

    private Draft expense() {
        Amt a = amount();
        String desc = pick(EXPENSES);
        Hint hint = EXPENSE;
        PaymentMethod method = null;
        String text;
        int form = random.nextInt(20);
        if (form < 3) {
            Verb v = pick(TO_SOMEONE);
            String who = pick(PEOPLE);
            text = a.last() || chance(0.4) ? v.text() + " a " + who + " " + a.text() : v.text() + " " + a.text() + " a " + who;
            desc = bare(who);
            method = v.method();
        } else if (form == 3) {
            Said charge = pick(CHARGES);
            text = a.last() ? "me cobraron " + charge.desc() + " " + a.text() : "me cobraron " + a.text() + " " + charge.text();
            desc = charge.desc();
        } else if (form == 4 && !a.last()) {
            text = pick(List.of("di ", "le di ")) + a.text() + " de propina";
            desc = "propina";
        } else {
            Body body = expenseBody(desc, a);
            text = body.text();
            hint = body.hint();
        }
        if (method == null && chance(0.35)) {
            Way w = pick(WAYS);
            text = chance(0.75) ? text + " " + w.text() : w.text() + " " + text;
            method = w.method();
        }
        return dated(text, desc, a, method, hint, 0.45);
    }

    private Body expenseBody(String d, Amt a) {
        List<Body> forms = new ArrayList<>(List.of(
                new Body(d + " " + a.text(), null),
                new Body(d + ": " + a.text(), null),
                new Body(d + " de " + a.text(), null),
                new Body(pick(SPEND) + " en " + d + " " + a.text(), EXPENSE),
                new Body(pick(PAY) + " " + d + " " + a.text(), EXPENSE),
                new Body(pick(BUY) + " " + d + " " + a.text(), EXPENSE),
                new Body(pick(BUY) + " " + d + " a " + a.text(), EXPENSE)));
        if (!a.last()) {
            forms.addAll(List.of(
                    new Body(a.text() + " de " + d, null),
                    new Body(a.text() + " en " + d, null),
                    new Body(pick(SPEND) + " " + a.text() + " en " + d, EXPENSE),
                    new Body(pick(PAY) + " " + a.text() + " de " + d, EXPENSE),
                    new Body(pick(PAY) + " " + a.text() + " por " + d, EXPENSE),
                    new Body(pick(BUY) + " " + a.text() + " de " + d, EXPENSE)));
        }
        return pick(forms);
    }

    private Draft incomeByVerb() {
        Amt a = amount();
        String desc = null;
        String text;
        PaymentMethod method;
        switch (random.nextInt(7)) {
            case 0 -> {
                Verb v = pick(INCOME_VERBS);
                text = v.text() + " " + a.text();
                method = v.method();
            }
            case 1, 2 -> {
                Verb v = pick(INCOME_VERBS);
                Said source = pick(SOURCES);
                text = a.last() || chance(0.3)
                        ? v.text() + " " + source.text() + " " + a.text()
                        : v.text() + " " + a.text() + " " + source.text();
                desc = source.desc();
                method = v.method();
            }
            case 3 -> {
                Verb v = pick(BY_SOMEONE);
                String who = pick(PEOPLE);
                text = who + " " + v.text() + " " + a.text();
                desc = bare(who);
                method = v.method();
            }
            case 4 -> {
                Verb v = pick(BY_SOMEONE);
                String who = pick(PEOPLE);
                text = a.last() || chance(0.5) ? v.text() + " " + who + " " + a.text() : v.text() + " " + a.text() + " " + who;
                desc = bare(who);
                method = v.method();
            }
            case 5 -> {
                String item = pick(SOLD);
                text = pick(List.of("vendí ", "vendi ", "vendimos ")) + item + " " + a.text();
                desc = bare(item);
                method = null;
            }
            default -> {
                if (chance(0.5)) {
                    Verb v = pick(ARRIVALS);
                    Said source = a.last() || chance(0.5) ? null : pick(SOURCES);
                    text = v.text() + " " + a.text() + (source == null ? "" : " " + source.text());
                    desc = source == null ? null : source.desc();
                    method = v.method();
                } else {
                    Said arrived = pick(ARRIVED);
                    text = arrived.text() + " " + a.text();
                    desc = arrived.desc();
                    method = null;
                }
            }
        }
        if (method == null && chance(0.3)) {
            Way w = pick(INCOME_WAYS);
            text = text + " " + w.text();
            method = w.method();
        }
        return dated(text, desc, a, method, INCOME, 0.4);
    }

    private Draft incomeByNoun() {
        String noun = pick(INCOME_NOUNS);
        Amt a = amount();
        String desc = noun;
        String text = switch (random.nextInt(4)) {
            case 0 -> noun + " " + a.text();
            case 1 -> a.last() ? noun + " de " + a.text() : a.text() + " de " + noun;
            case 2 -> {
                if (noun.contains(" ")) {
                    yield noun + " " + a.text();
                }
                desc = "mi " + noun;
                yield "mi " + noun + " " + a.text();
            }
            default -> noun + " de " + a.text();
        };
        PaymentMethod method = null;
        if (chance(0.3)) {
            Way w = pick(INCOME_WAYS);
            text = text + " " + w.text();
            method = w.method();
        }
        return dated(text, desc, a, method, INCOME, 0.4);
    }

    /** "Pago a mi persona 100" es un ingreso; "pago a mi mama 50", no. */
    private Draft towardMe() {
        Way noun = pick(TOWARD_ME);
        Amt a = amount();
        if (chance(0.3)) {
            String who = pick(RELATIVES);
            boolean app = noun.method() == YAPE || noun.method() == PLIN;
            return dated(noun.text() + " a " + who + " " + a.text(), app ? who : noun.text() + " a " + who, a,
                    app ? noun.method() : null, null, 0.3);
        }
        String target = pick(List.of("a mi persona", "a mí", "para mí", "a mi", "a mi cuenta", "a mi nombre"));
        String text = noun.text() + " " + target + " " + a.text();
        String desc = null;
        if (!a.last() && chance(0.4)) {
            Said reason = pick(REASONS);
            text = text + " " + reason.text();
            desc = reason.desc();
        }
        return dated(text, desc, a, noun.method(), INCOME, 0.3);
    }

    private Draft connected() {
        int n = 2 + random.nextInt(3);
        int mode = random.nextInt(10);
        List<Item> items = new ArrayList<>();
        boolean verbMethod = false;
        if (mode <= 5) {
            for (int i = 0; i < n; i++) {
                items.add(plainItem(null));
            }
        } else if (mode == 6) {
            Amt a = amount();
            if (chance(0.4)) {
                Verb v = pick(TO_SOMEONE);
                String who = pick(PEOPLE);
                String text = a.last() ? v.text() + " a " + who + " " + a.text() : v.text() + " " + a.text() + " a " + who;
                items.add(new Item(text, bare(who), a, EXPENSE, v.method()));
                verbMethod = v.method() != null;
                for (int i = 1; i < n; i++) {
                    Amt next = amountNotLast();
                    String other = pick(PEOPLE);
                    items.add(new Item(next.text() + " a " + other, bare(other), next, EXPENSE, v.method()));
                }
            } else {
                String d = pick(EXPENSES);
                items.add(new Item(expenseBodyWithVerb(d, a), d, a, EXPENSE, null));
                for (int i = 1; i < n; i++) {
                    items.add(plainItem(EXPENSE));
                }
            }
        } else if (mode == 7) {
            Verb v = pick(INCOME_VERBS);
            Amt a = amountNotLast();
            Said source = pick(SOURCES);
            items.add(new Item(v.text() + " " + a.text() + " " + source.text(), source.desc(), a, INCOME, v.method()));
            verbMethod = v.method() != null;
            for (int i = 1; i < n; i++) {
                Amt next = amountNotLast();
                Said other = pick(SOURCES);
                items.add(new Item(next.text() + " " + other.text(), other.desc(), next, INCOME, v.method()));
            }
        } else if (mode == 8) {
            Verb v = pick(INCOME_VERBS);
            Amt a = amountNotLast();
            Said source = pick(SOURCES);
            items.add(new Item(v.text() + " " + a.text() + " " + source.text(), source.desc(), a, INCOME, v.method()));
            Amt spent = amount();
            String d = pick(EXPENSES);
            items.add(new Item(expenseBodyWithVerb(d, spent), d, spent, EXPENSE, null));
            for (int i = 2; i < n; i++) {
                items.add(plainItem(EXPENSE));
            }
        } else {
            for (int i = 0; i < n; i++) {
                if (i == 0 || chance(0.3)) {
                    String noun = pick(INCOME_NOUNS);
                    Amt a = amount();
                    items.add(new Item(noun + " " + a.text(), noun, a, INCOME, null));
                } else {
                    items.add(plainItem(null));
                }
            }
        }

        // 0: sin medio, 1: uno al final para todos, 2: cada uno el suyo, 3: uno al inicio para todos
        int methodMode = random.nextInt(4);
        if (mode == 6 && verbMethod || mode == 7 && verbMethod) {
            methodMode = 0;
        } else if (mode == 7 && methodMode > 1 || mode >= 8 && methodMode % 2 == 1) {
            methodMode = 0;
        }
        Way shared = mode == 7 ? pick(INCOME_WAYS) : pick(WAYS);
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (methodMode == 2 && !(mode == 8 && i == 0 && item.method() != null)) {
                Way own = pick(WAYS);
                items.set(i, item.with(item.text() + " " + own.text(), own.method()));
            } else if ((methodMode == 1 || methodMode == 3) && item.method() == null) {
                items.set(i, item.with(item.text(), shared.method()));
            }
        }

        // 0: hoy, 1: al inicio, 2: al final, 3: cada uno la suya
        int dateMode = random.nextInt(4);
        Day day = pick(DAYS);
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (dateMode == 3) {
                Day own = pick(DAYS);
                items.set(i, items.get(i).with(own.text() + " " + items.get(i).text(), items.get(i).method()));
                dates.add(own.date());
            } else {
                dates.add(dateMode == 0 ? TODAY : day.date());
            }
        }

        StringBuilder text = new StringBuilder(items.get(0).text());
        for (int i = 1; i < items.size(); i++) {
            text.append(connector(items.get(i).text())).append(items.get(i).text());
        }
        String joined = text.toString();
        if (methodMode == 1) {
            joined = joined + " " + shared.text();
        } else if (methodMode == 3) {
            joined = shared.text() + " " + joined;
        }
        if (dateMode == 1) {
            joined = day.text() + " " + joined;
        } else if (dateMode == 2) {
            joined = joined + " " + day.text();
        }
        List<Mov> movements = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            movements.add(new Mov(item.desc(), item.amount(), dates.get(i), item.method(), item.hint(), false));
        }
        return new Draft(joined, movements);
    }

    private Draft unconnected() {
        int n = 2 + random.nextInt(3);
        boolean amountFirst = chance(0.35);
        List<String> parts = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String d = pick(EXPENSES);
            Amt a = amountFirst ? leadingAmount() : amountNotLast();
            if (amountFirst && i == 0 && a.count() && a.value().intValue() <= 20
                    && (a.value().intValue() == 1 || d.split(" ")[0].endsWith("s"))) {
                // "3 panes 1.20": un entero chico delante de un plural es una cantidad, no un monto.
                a = pen(a.text() + " soles", a.value(), false);
            }
            parts.add(amountFirst ? a.text() + " " + d : d + " " + a.text());
            items.add(new Item(null, d, a, null, null));
        }
        String text = String.join(" ", parts);
        PaymentMethod method = null;
        if (chance(0.35)) {
            Way w = pick(WAYS);
            text = chance(0.7) ? text + " " + w.text() : w.text() + " " + text;
            method = w.method();
        }
        LocalDate date = TODAY;
        if (chance(0.4)) {
            Day day = pick(DAYS);
            text = chance(0.5) ? day.text() + " " + text : text + " " + day.text();
            date = day.date();
        }
        List<Mov> movements = new ArrayList<>();
        for (Item item : items) {
            movements.add(new Mov(item.desc(), item.amount(), date, method, null, false));
        }
        return new Draft(text, movements);
    }

    private Draft quantities() {
        String text;
        String desc;
        Amt total;
        switch (random.nextInt(3)) {
            case 0 -> {
                int count = 2 + random.nextInt(5);
                String plural = pick(PLURALS);
                BigDecimal unit = pick(decimals("1.50", "2", "2.50", "3.50", "5", "6", "8", "12", "15", "18", "25"));
                String price = pick(List.of(digits(unit), digits(unit), "S/ " + digits(unit), digits(unit) + " soles"));
                desc = countText(count) + " " + plural;
                text = desc + " " + pick(List.of("de", "a", "x")) + " " + price;
                total = pen(price, unit.multiply(BigDecimal.valueOf(count)), false);
            }
            case 1 -> {
                int count = 1 + random.nextInt(20);
                String what = count == 1 ? pick(List.of("menú", "pan", "polo", "cuaderno", "helado")) : pick(PLURALS);
                total = amount();
                desc = countText(count) + " " + what;
                text = desc + " " + total.text();
            }
            default -> {
                List<String> unit = pick(UNITS);
                String what = unit.get(2 + random.nextInt(unit.size() - 2));
                int count = 1 + random.nextInt(5);
                String amount = chance(0.15) && unit.get(0).equals("kilo") ? "medio kilo" : count + " " + unit.get(count == 1 ? 0 : 1);
                total = amount();
                text = amount + " de " + what + " " + total.text();
                desc = amount + " de " + what;
            }
        }
        Hint hint = null;
        if (chance(0.3)) {
            text = pick(BUY) + " " + text;
            hint = EXPENSE;
        }
        PaymentMethod method = null;
        if (chance(0.3)) {
            Way w = pick(WAYS);
            text = text + " " + w.text();
            method = w.method();
        }
        return dated(text, desc, total, method, hint, 0.3);
    }

    private Draft dates() {
        Day day = pick(DAYS);
        String d = pick(EXPENSES);
        Amt a = simpleAmount();
        String text = switch (random.nextInt(3)) {
            case 0 -> day.text() + " " + d + " " + a.text();
            case 1 -> d + " " + a.text() + " " + day.text();
            default -> d + " " + day.text() + " " + a.text();
        };
        return draft(text, new Mov(d, a, day.date(), null, null, false));
    }

    private Draft amounts() {
        Amt a = amount();
        String d = pick(EXPENSES);
        List<Draft> forms = new ArrayList<>(List.of(
                draft(d + " " + a.text(), new Mov(d, a, TODAY, null, null, false)),
                draft(d + " de " + a.text(), new Mov(d, a, TODAY, null, null, false)),
                draft(pick(PAY) + " " + d + " " + a.text(), new Mov(d, a, TODAY, null, EXPENSE, false)),
                draft("me pagaron " + a.text(), new Mov(null, a, TODAY, null, INCOME, false)),
                draft(a.text(), new Mov(null, a, TODAY, null, null, false))));
        if (!a.last()) {
            forms.addAll(List.of(
                    draft(a.text() + " de " + d, new Mov(d, a, TODAY, null, null, false)),
                    draft(a.text() + " en " + d, new Mov(d, a, TODAY, null, null, false)),
                    draft(pick(SPEND) + " " + a.text() + " en " + d, new Mov(d, a, TODAY, null, EXPENSE, false))));
        }
        return pick(forms);
    }

    private Draft methods() {
        Way w = pick(WAYS);
        String d = pick(EXPENSES);
        Amt a = simpleAmount();
        String text = switch (random.nextInt(3)) {
            case 0 -> d + " " + a.text() + " " + w.text();
            case 1 -> w.text() + " " + d + " " + a.text();
            default -> d + " " + w.text() + " " + a.text();
        };
        return dated(text, d, a, w.method(), null, 0.3);
    }

    private Draft warnings() {
        switch (random.nextInt(4)) {
            case 0 -> {
                String d = pick(EXPENSES);
                Amt a = amountNotLast();
                String split = pick(SPLITS);
                boolean verb = chance(0.3);
                String text = verb ? "gastamos " + a.text() + " en " + d + " " + split : d + " " + a.text() + " " + split;
                return draft(text, new Mov(d, a, TODAY, null, verb ? EXPENSE : null, true));
            }
            case 1 -> {
                String d = pick(BIG);
                BigDecimal v = pick(HIGH_VALUES);
                String k = v.divide(BigDecimal.valueOf(1000)).toBigInteger().toString();
                String written = pick(List.of(v.toBigInteger().toString(), group(v.toBigInteger().toString(), ","),
                        group(v.toBigInteger().toString(), "."), k + " mil", k + "k", "S/ " + group(v.toBigInteger().toString(), ","),
                        k + " mil soles", SPELLED.get(v.intValue())));
                Amt a = pen(written, v, false);
                return dated(d + " " + a.text(), d, a, null, null, 0.3);
            }
            case 2 -> {
                String d = pick(LONG);
                Amt a = simpleAmount();
                return dated(d + " " + a.text(), d, a, null, null, 0.4);
            }
            default -> {
                String d = pick(EXPENSES);
                return switch (random.nextInt(4)) {
                    case 0 -> {
                        Way w = pick(WAYS);
                        yield draft(d + " " + w.text(), new Mov(d, null, TODAY, w.method(), null, false));
                    }
                    case 1 -> draft(pick(SPEND) + " en " + d, new Mov(d, null, TODAY, null, EXPENSE, false));
                    case 2 -> {
                        Said source = pick(SOURCES);
                        yield draft("me pagaron " + source.text(), new Mov(source.desc(), null, TODAY, null, INCOME, false));
                    }
                    default -> {
                        Day day = pick(DAYS);
                        yield draft(day.text() + " " + d, new Mov(d, null, day.date(), null, null, false));
                    }
                };
            }
        }
    }

    private Draft hours() {
        String time = pick(TIMES);
        String d = pick(EXPENSES);
        Amt a = simpleAmount();
        String text = switch (random.nextInt(3)) {
            case 0 -> d + " " + a.text() + " " + time;
            case 1 -> time + " " + d + " " + a.text();
            default -> d + " " + time + " " + a.text();
        };
        return dated(text, d, a, null, null, 0.3);
    }

    private Draft nothing() {
        List<String> parts = new ArrayList<>();
        if (chance(0.3)) {
            parts.add(pick(GREETINGS));
        }
        if (chance(0.6)) {
            parts.add(pick(DAYS).text());
        }
        if (chance(0.5)) {
            parts.add(pick(WAYS).text());
        }
        if (chance(0.25)) {
            parts.add(pick(List.of("porfa", "gracias", "por favor")));
        }
        if (parts.isEmpty()) {
            parts.add(pick(GREETINGS));
        }
        return new Draft(String.join(" ", parts), List.of());
    }

    // ---------- Partes de las familias ----------

    /** "3" o "tres": la gente escribe las cantidades chicas de las dos formas. */
    private String countText(int count) {
        List<String> words = List.of("dos", "tres", "cuatro", "cinco", "seis");
        return count >= 2 && count <= 6 && chance(0.35) ? words.get(count - 2) : String.valueOf(count);
    }

    /** Un gasto suelto dentro de un mensaje con varios; la pista la hereda del verbo de antes, si hay. */
    private Item plainItem(Hint inherited) {
        String d = pick(EXPENSES);
        Amt a = amount();
        List<String> forms = new ArrayList<>(List.of(d + " " + a.text(), d + " de " + a.text(), d + ": " + a.text()));
        if (!a.last()) {
            forms.add(a.text() + " de " + d);
            forms.add(a.text() + " en " + d);
        }
        return new Item(pick(forms), d, a, inherited, null);
    }

    private String expenseBodyWithVerb(String d, Amt a) {
        Body body;
        do {
            body = expenseBody(d, a);
        } while (body.hint() == null);
        return body.text();
    }

    /** Un conector que la gente usa; "e" solo antes de "i", y el punto solo antes de una palabra. */
    private String connector(String next) {
        while (true) {
            String connector = pick(CONNECTORS);
            if (connector.equals(" e ") && !next.startsWith("i")) {
                continue;
            }
            if (connector.equals(". ") && !Character.isLetter(next.charAt(0))) {
                continue;
            }
            return connector;
        }
    }

    private Draft dated(String text, String desc, Amt a, PaymentMethod method, Hint hint, double probability) {
        LocalDate date = TODAY;
        if (chance(probability)) {
            Day day = pick(DAYS);
            text = chance(0.5) ? day.text() + " " + text : text + " " + day.text();
            date = day.date();
        }
        return draft(text, new Mov(desc, a, date, method, hint, false));
    }

    // ---------- Montos ----------

    private Amt amount() {
        return switch (random.nextInt(12)) {
            case 0, 1, 2, 3 -> digitsAmount();
            case 4 -> withSign();
            case 5 -> withWord();
            case 6 -> thousands();
            case 7 -> spelledAmount();
            case 8 -> dollars();
            case 9 -> withCents();
            case 10 -> centimos();
            default -> digitsAmount();
        };
    }

    private Amt amountNotLast() {
        Amt a;
        do {
            a = amount();
        } while (a.last());
        return a;
    }

    /** Lo que se escribe antes de la descripcion en "12 taxi 35 chifa". */
    private Amt leadingAmount() {
        return switch (random.nextInt(5)) {
            case 0 -> withSign();
            case 1 -> withWord();
            case 2 -> dollars();
            default -> digitsAmount();
        };
    }

    private Amt simpleAmount() {
        return switch (random.nextInt(4)) {
            case 0 -> withSign();
            case 1 -> withWord();
            default -> digitsAmount();
        };
    }

    private Amt digitsAmount() {
        BigDecimal v = pick(VALUES);
        return pen(digits(v), v, whole(v));
    }

    private Amt withSign() {
        BigDecimal v = pick(VALUES);
        return pen(pick(List.of("S/ ", "S/", "s/ ", "S/. ", "S/.", "s/.")) + digits(v), v, false);
    }

    private Amt withWord() {
        BigDecimal v = pick(VALUES);
        if (v.compareTo(BigDecimal.ONE) == 0) {
            return pen("1 " + pick(List.of("sol", "luca")), v, false);
        }
        return pen(digits(v) + pick(List.of(" soles", " lucas", " soles")), v, false);
    }

    private Amt thousands() {
        BigDecimal v = pick(THOUSANDS);
        String k = v.divide(BigDecimal.valueOf(1000)).stripTrailingZeros().toPlainString();
        if (chance(0.3)) {
            k = k.replace('.', ',');
        }
        String text = pick(List.of(k + " mil", k + "k", k + " mil soles", k + "k soles", "S/ " + k + " mil"));
        return pen(text.equals("1 mil") ? "1k" : text, v, false);
    }

    private Amt spelledAmount() {
        if (chance(0.25)) {
            int small = pick(List.of(1, 2, 3, 5, 8));
            String word = small == 1 ? pick(List.of("un sol", "una luca")) : SPELLED_SMALL.get(small) + pick(List.of(" soles", " lucas"));
            return pen(word, BigDecimal.valueOf(small), false);
        }
        int value = pick(SPELLED.keySet().stream().filter(v -> v < 100000).sorted().toList());
        return pen(SPELLED.get(value) + pick(List.of("", "", " soles", " lucas")), BigDecimal.valueOf(value), false);
    }

    private Amt dollars() {
        BigDecimal v = pick(VALUES.subList(0, 36));
        if (chance(0.15) && SPELLED.containsKey(v.intValue()) && whole(v)) {
            return usd(SPELLED.get(v.intValue()) + " dólares", v);
        }
        String n = digits(v);
        return usd(pick(List.of("$" + n, "$ " + n, "US$ " + n, "US$" + n, n + " dólares", n + " dolares", n + " usd",
                "USD " + n, n + "$")), v);
    }

    /** "12 con 50" y "12 soles 50 centimos": como se dicen los centimos. */
    private Amt withCents() {
        BigDecimal v = pick(decimals("1.50", "2.50", "3.50", "4.50", "7.90", "9.90", "12.50", "29.90", "35.90", "39.90"));
        String units = v.toBigInteger().toString();
        String cents = v.remainder(BigDecimal.ONE).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        Map<String, String> spelled = Map.of("12.50", "doce con cincuenta", "29.90", "veintinueve con noventa",
                "35.90", "treinta y cinco con noventa", "39.90", "treinta y nueve con noventa");
        if (chance(0.2) && spelled.containsKey(v.toPlainString())) {
            return new Amt(spelled.get(v.toPlainString()), v, Currency.PEN, true, false);
        }
        return switch (random.nextInt(4)) {
            case 0 -> new Amt(units + " con " + cents, v, Currency.PEN, true, false);
            case 1 -> new Amt(units + " soles con " + cents, v, Currency.PEN, true, false);
            case 2 -> pen(units + " soles " + cents + " céntimos", v, false);
            default -> pen(units + " con " + cents + " centimos", v, false);
        };
    }

    private Amt centimos() {
        BigDecimal v = pick(decimals("0.20", "0.50", "0.80", "0.90"));
        String cents = v.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        return pen(cents + pick(List.of(" céntimos", " centimos")), v, false);
    }

    /** "12", "35,90", "1,500": como se escribe un numero con digitos. */
    private String digits(BigDecimal v) {
        if (whole(v)) {
            String integer = v.toBigInteger().toString();
            return v.compareTo(BigDecimal.valueOf(1000)) >= 0 && chance(0.5) ? group(integer, pick(List.of(",", "."))) : integer;
        }
        String text = v.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        if (v.compareTo(BigDecimal.valueOf(1000)) >= 0 && chance(0.6)) {
            String integer = v.toBigInteger().toString();
            String decimals = text.substring(text.indexOf('.') + 1);
            return chance(0.5) ? group(integer, ",") + "." + decimals : group(integer, ".") + "," + decimals;
        }
        if (text.endsWith("0") && chance(0.3)) {
            text = text.substring(0, text.length() - 1);
        }
        return chance(0.4) ? text.replace('.', ',') : text;
    }

    private static String group(String integer, String separator) {
        StringBuilder grouped = new StringBuilder(integer);
        for (int i = integer.length() - 3; i > 0; i -= 3) {
            grouped.insert(i, separator);
        }
        return grouped.toString();
    }

    private static boolean whole(BigDecimal v) {
        return v.stripTrailingZeros().scale() <= 0;
    }

    private static Amt pen(String text, BigDecimal value, boolean count) {
        return new Amt(text, value, Currency.PEN, false, count);
    }

    private static Amt usd(String text, BigDecimal value) {
        return new Amt(text, value, Currency.USD, false, false);
    }

    // ---------- Escribir la frase ----------

    /** La forma de escribir: mayuscula al inicio, todo en mayusculas, sin tildes, con saludo o "gracias". */
    private Case write(String family, Draft draft) {
        String text = draft.text();
        boolean noAccents = chance(0.2);
        if (noAccents) {
            text = withoutAccents(text);
        }
        if (chance(0.12)) {
            text = pick(LEADING) + text;
        }
        if (chance(0.12)) {
            text = text + pick(TRAILING);
        }
        int style = random.nextInt(10);
        if (style < 3) {
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        } else if (style == 3) {
            text = text.toUpperCase(Locale.ROOT);
        }
        if (chance(0.08)) {
            text = text.replaceFirst(" ", "  ");
        }
        return new Case(family, text, draft.movements().stream().map(movement -> line(movement, noAccents)).toList());
    }

    private static String line(Mov m, boolean noAccents) {
        String desc = m.desc() == null ? null : capitalize(noAccents ? withoutAccents(m.desc()) : m.desc());
        BigDecimal value = m.amount() == null ? null : m.amount().value().setScale(2, RoundingMode.HALF_UP);
        Currency currency = m.amount() == null ? Currency.PEN : m.amount().currency();
        List<Warning> warnings = new ArrayList<>();
        if (value == null) {
            warnings.add(Warning.MISSING_AMOUNT);
        }
        if (currency == Currency.USD) {
            warnings.add(Warning.FOREIGN_CURRENCY);
        }
        if (m.notSplit()) {
            warnings.add(Warning.NOT_SPLIT);
        }
        if (desc != null && desc.split(" ").length > 8) {
            warnings.add(Warning.LONG_DESCRIPTION);
        }
        if (value != null && value.compareTo(HIGH) > 0) {
            warnings.add(Warning.HIGH_AMOUNT);
        }
        return MovementTextParserTest.line(new ParsedMovement(desc, value, currency, m.date(), m.method(), m.hint(), warnings));
    }

    /** Solo las vocales: la ñ se escribe igual con o sin apuro. */
    private static String withoutAccents(String text) {
        StringBuilder plain = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            int i = "áéíóúüÁÉÍÓÚÜ".indexOf(c);
            plain.append(i < 0 ? c : "aeiouuAEIOUU".charAt(i));
        }
        return plain.toString();
    }

    /** "el profe" queda "profe": los articulos del borde no son descripcion. */
    private static String bare(String text) {
        return text.replaceFirst("^(?:el|la|los|las|un|una|unos|unas) ", "");
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static Draft draft(String text, Mov movement) {
        return new Draft(text, List.of(movement));
    }

    private <T> T pick(List<T> options) {
        return options.get(random.nextInt(options.size()));
    }

    private boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    private static List<BigDecimal> decimals(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    private static Said said(String text, String desc) {
        return new Said(text, desc);
    }

    private static Verb verb(String text, PaymentMethod method) {
        return new Verb(text, method);
    }

    private static Way way(String text, PaymentMethod method) {
        return new Way(text, method);
    }

    private static Day back(String text, int days) {
        return new Day(text, TODAY.minusDays(days));
    }

    private static Day on(String text, String date) {
        return new Day(text, LocalDate.parse(date));
    }

}
