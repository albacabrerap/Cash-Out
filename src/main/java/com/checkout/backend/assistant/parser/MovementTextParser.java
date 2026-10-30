package com.checkout.backend.assistant.parser;

import com.checkout.backend.assistant.parser.ParsedMovement.Currency;
import com.checkout.backend.assistant.parser.ParsedMovement.Hint;
import com.checkout.backend.assistant.parser.ParsedMovement.PaymentMethod;
import com.checkout.backend.assistant.parser.ParsedMovement.Warning;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Convierte un mensaje como "ayer taxi 12 y chifa 35,90 con yape" en
 * movimientos. No usa IA: son reglas, y por eso se pueden probar con una tabla.
 *
 * Cada monto es el ancla de un movimiento y se lleva las palabras que lo
 * describen. La fecha, el medio de pago y la pista de ingreso o gasto se sacan
 * antes, trozo por trozo, y pasan a los trozos que no las dicen. La categoria
 * no la decide el parser: es trabajo del clasificador.
 */
@Component
public class MovementTextParser {

    static final int MAX_ITEMS = 20;
    private static final int LONG_DESCRIPTION_WORDS = 8;
    private static final int MAX_DESCRIPTION = 255;
    private static final BigDecimal HIGH_AMOUNT = new BigDecimal("100000");
    /** "3 panes 1.20": un entero chico al empezar, con otro monto despues y en plural, es una cantidad. */
    private static final int MAX_QUANTITY = 20;
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    // Saludos y pedidos al inicio ("hola, anota ...") y cortesias al final ("... porfa"): no son descripcion.
    private static final Pattern LEADING_FILLER = p("^(?:(?:hola|holi|buen[oa]s\\s+(?:d[ií]as|tardes|noches)|buenas|oye|ey"
            + "|porfa|por\\s+favor|pls|plis|(?:an[oó]ta|registra|apunta|agrega|a[ñn]ade|pon|guarda)(?:me|lo|le)?)"
            + "\\b[\\s,.:;]*)*");
    private static final Pattern TRAILING_FILLER = p("(?:[\\s,.;:]*\\b(?:porfa|por\\s+favor|pls|plis|gracias))+[^\\p{L}\\d]*$");

    // Comas que no estan entre digitos: "35,90" es un monto, "taxi 12, chifa 35" son dos. El punto con
    // espacio despues separa frases, salvo el de "s/. 12".
    private static final Pattern CONNECTOR = p("\\s*[;+]\\s*|\\s+y\\s+|\\s+e\\s+|\\s+m[aá]s\\s+|\\s+[-/]\\s+"
            + "|(?<!s/)\\.\\s+|\\s*,(?!\\d)\\s*|(?<!\\d),\\s*");
    private static final Pattern NOT_SPLIT = p("\\b(?:(?:dividid|partid)[oa]s?\\s+)?entre\\s+(?:\\d+|todos|todas"
            + "|dos|tres|cuatro|cinco|seis|siete|ocho|nueve)(?:\\s+personas?)?\\b|\\ba\\s+medias\\b");
    private static final Pattern TIME = p("\\ba\\s+las?\\s+\\d{1,2}(?::\\d{2})?(?:\\s*(?:am|pm|hrs?|h))?\\b"
            + "|\\b\\d{1,2}:\\d{2}(?:\\s*(?:am|pm))?\\b|\\b\\d{1,2}\\s*(?:am|pm)\\b");
    /** Parte del dia: no cambia la fecha, solo no es descripcion. "Anoche" si cambia: es ayer. */
    private static final Pattern DAY_PART = p("\\b(?:en|por|de|a)\\s+la\\s+(?:ma[ñn]ana|tarde|noche|madrugada)\\b"
            + "|\\bal\\s+mediod[ií]a\\b|\\bde\\s+(?:noche|madrugada)\\b");

    private static final Pattern RELATIVE_DAY = p("\\b(anteanoche|anteayer|antier|anoche|ayer|hoy"
            + "|esta\\s+(?:ma[ñn]ana|tarde|noche|madrugada))\\b");
    private static final Pattern DAYS_AGO = p("\\bhace\\s+(\\d{1,2}|una?|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve)"
            + "\\s+(d[ií]as?|semanas?)\\b");
    private static final Pattern WEEKDAY = p("\\b(?:(?:el|este)\\s+)?(lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)"
            + "(?:\\s+pasado)?\\b");
    private static final Pattern NUMERIC_DATE = p("(?<![\\d.,/-])(\\d{1,2})([/-])(\\d{1,2})(?:\\2(\\d{4}|\\d{2}))?(?![\\d/-])");
    // "15 de octubre", "15 octubre", "15 oct.": el mes puede ir abreviado y sin "de".
    private static final Pattern WRITTEN_DATE = p("\\b(?:el\\s+)?(\\d{1,2})\\s+(?:de\\s+)?(enero|febrero|marzo|abril|mayo|junio"
            + "|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre|ene|feb|mar|abr|may|jun|jul|ago|sept?|set|oct|nov|dic)"
            + "\\b\\.?(?:\\s+(?:de|del)\\s+(\\d{4}))?");
    private static final List<String> MONTHS = List.of("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep",
            "oct", "nov", "dic");
    private static final List<String> WEEKDAYS = List.of("lunes", "martes", "miercoles", "jueves", "viernes", "sabado",
            "domingo");

    /** Lo que es un ingreso por si mismo. "Propina" y "pension" no estan: pueden ser dadas o recibidas. */
    private static final String INCOME_NOUNS = "sueldo|salario|quincena|freelance|bono|aguinaldo|gratificaci[oó]n|mesada"
            + "|utilidades|cts|reintegro|honorarios|cachuelo|reembolso|devoluci[oó]n|ventas?|premio|beca|horas\\s+extras?"
            + "|remesa|dividendos";
    private static final Pattern INCOME_VERB = p("\\bme\\s+(pag(?:aron|an|[oó])|deposit(?:aron|an|[oó])|yape(?:aron|an|[oó])"
            + "|pline(?:aron|an|[oó])|transfiri(?:eron|[oó])|transfieren|dieron|di[oó]|dan|devolvieron|devolvi[oó]"
            + "|entr(?:aron|[oó])|prest(?:aron|[oó])|envi(?:aron|[oó])|mand(?:aron|[oó])|regal(?:aron|[oó])|pasaron"
            + "|abon(?:aron|[oó])|reembols(?:aron|[oó]))\\b"
            + "|\\b(?:cobr[eé]|cobramos|recib[ií]|recibimos|gan[eé]|ganamos|vend[ií]|vendimos)\\b"
            // "Me cayo un yape", "me llego la quincena". "Me llego el recibo de luz" no: es un gasto.
            + "|\\bme\\s+(?:lleg(?:[oó]|aron)|cay(?:[oó]|eron))\\s+(?:(?:un|una|el|la|mi)\\s+"
            + "(yape|plin|dep[oó]sito|transferencia|pago|abono)\\b|(?=(?:(?:el|la|los|las|mi|mis)\\s+)?(?:" + INCOME_NOUNS + ")\\b))"
            // "Pago a mi persona", "yape para mi": la plata viene hacia quien escribe.
            + "|\\b(pago|abono|dep[oó]sito|transferencia|yape|plin)\\s+(?:a|para)\\s+"
            + "(?:mi\\s+(?:persona|cuenta|nombre)\\b|mí(?!\\p{L})|mi(?=\\s+(?:s/\\.?|us\\$|usd|\\$)?\\s*\\d|\\s*$))");
    private static final Pattern EXPENSE_VERB = p("\\b(?:me\\s+cobraron|gast[eé]|gastamos|pagu[eé]|pagamos|compr[eé]|compramos"
            + "|di|envi[eé]|mand[eé]|prest[eé]|(yape[eé]|pline[eé]|transfer[ií]))\\b");
    /** Pista de ingreso que tambien es la descripcion: no se borra. */
    private static final Pattern INCOME_NOUN = p("\\b(?:" + INCOME_NOUNS + ")\\b");
    // "transferencia" sola puede ser la descripcion de un ingreso, y "credito" el de un prestamo: solo
    // cuentan como medio con "con" o "por".
    private static final Pattern PAYMENT = p("\\b(?:(?:con|por|en|v[ií]a|x)\\s+)?(?:(?:la|mi)\\s+)?(yape|plin|efectivo|cash"
            + "|visa|mastercard|tarjeta(?:\\s+de\\s+(?:cr[eé]dito|d[eé]bito))?)\\b|\\bcon\\s+(d[eé]bito|cr[eé]dito)\\b"
            + "|\\b(?:con|por|v[ií]a|x)\\s+(?:una\\s+)?(transferencia)\\b");

    private static final Pattern TOKEN = p("(?<![\\p{L}\\d])(?<cur1>s/\\.?|us\\$|usd|\\$)?\\s*"
            + "(?<num>\\d{1,3}(?:[.,]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)"
            + "(?<mult>\\s*k\\b|\\s+mil\\b)?"
            + "(?:\\s*(?:(?<cur2>soles|sol|lucas|luca|d[oó]lares|d[oó]lar|usd|pen|c[eé]ntimos)\\b|(?<cur3>\\$)))?"
            // "12 con 50" y "12 soles 50 centimos" son 12.50; "con 50" sin "centimos" solo al final del trozo.
            + "(?:\\s+(?:con\\s+)?(?<cents>\\d{2})\\s*c[eé]ntimos\\b|\\s+con\\s+(?<cents2>\\d{2})(?=[^\\p{L}\\d]*$))?"
            + "|(?<word>\\p{L}[\\p{L}'’-]*)");
    private static final Pattern GROUPED = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+([.,]\\d{1,2})?");

    private static final Pattern WORD = p("\\p{L}+");
    private static final Pattern DIGIT_BEFORE = p("\\d\\s*$");
    private static final Pattern CURRENCY_NEXT = p("\\s+(?:soles|sol|lucas|luca|d[oó]lares|d[oó]lar)\\b");
    /** Sin tildes: se busca con {@link #plain}. */
    private static final Map<String, Integer> NUMBER_WORDS = numberWords();

    private static final Set<String> UNITS = Set.of("kg", "kilo", "kilos", "gr", "gramos", "litro", "litros", "lt",
            "unidad", "unidades", "docena", "docenas", "paquete", "paquetes", "botella", "botellas", "lata", "latas",
            "caja", "cajas");
    private static final Set<String> MULTIPLY = Set.of("de", "a", "por", "x");
    private static final Set<String> EDGE_WORDS = Set.of("me", "en", "de", "del", "por", "el", "la", "los", "las",
            "un", "una", "unos", "unas", "al", "a", "con", "para", "y", "e", "que", "fue", "fueron", "costo", "costó",
            "salio", "salió", "total", "son", "x", "lo", "le", "les", "se", "nos", "también", "tambien", "además",
            "ademas", "aparte");
    private static final Map<String, PaymentMethod> METHOD_WORDS = Map.of("yape", PaymentMethod.YAPE,
            "plin", PaymentMethod.PLIN, "efectivo", PaymentMethod.CASH, "cash", PaymentMethod.CASH,
            "visa", PaymentMethod.CARD, "mastercard", PaymentMethod.CARD, "transferencia", PaymentMethod.TRANSFER);

    private final Clock clock;

    public MovementTextParser(Clock clock) {
        this.clock = clock;
    }

    public List<ParsedMovement> parse(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        String text = message.toLowerCase(Locale.ROOT)
                .replaceAll("\\R+", " ; ")
                .replaceAll("[¡!¿?\"()]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        text = TRAILING_FILLER.matcher(LEADING_FILLER.matcher(text).replaceFirst("")).replaceFirst("");
        text = spelledNumbers(text);

        List<Chunk> chunks = new ArrayList<>();
        for (String piece : CONNECTOR.split(text)) {
            if (!piece.isBlank()) {
                chunks.add(read(piece.strip(), today));
            }
        }
        merge(chunks);
        inherit(chunks, today);

        List<ParsedMovement> movements = new ArrayList<>();
        for (Chunk chunk : chunks) {
            markQuantities(chunk.tokens);
            for (Item item : items(chunk.tokens)) {
                if (movements.size() == MAX_ITEMS) {
                    return movements;
                }
                movements.add(movement(item, chunk));
            }
        }
        return movements;
    }

    /** Saca de un trozo lo que no es descripcion ni monto, y lo que queda lo parte en palabras y montos. */
    private static Chunk read(String piece, LocalDate today) {
        Chunk chunk = new Chunk();
        String s = piece;
        if (NOT_SPLIT.matcher(s).find()) {
            chunk.notSplit = true;
            s = NOT_SPLIT.matcher(s).replaceAll(" ");
        }
        s = TIME.matcher(s).replaceAll(" ");

        chunk.date = firstDate(s, today);
        for (Pattern date : List.of(RELATIVE_DAY, DAYS_AGO, WEEKDAY, NUMERIC_DATE, WRITTEN_DATE, DAY_PART)) {
            s = date.matcher(s).replaceAll(" ");
        }

        Matcher income = INCOME_VERB.matcher(s);
        Matcher expense = EXPENSE_VERB.matcher(s);
        if (income.find()) {
            chunk.hint = Hint.INCOME;
            chunk.verbHint = true;
            chunk.method = methodOfVerb(firstGroup(income));
            s = INCOME_VERB.matcher(s).replaceAll(" ");
        } else if (expense.find()) {
            chunk.hint = Hint.EXPENSE;
            chunk.verbHint = true;
            chunk.method = methodOfVerb(expense.group(1));
            s = EXPENSE_VERB.matcher(s).replaceAll(" ");
        } else if (INCOME_NOUN.matcher(s).find()) {
            chunk.hint = Hint.INCOME;
        }
        chunk.verbMethod = chunk.method != null;

        Matcher payment = PAYMENT.matcher(s);
        if (payment.find() && chunk.method == null) {
            chunk.method = method(payment);
        }
        s = PAYMENT.matcher(s).replaceAll(" ");

        Matcher token = TOKEN.matcher(s);
        while (token.find()) {
            if (token.group("word") != null) {
                chunk.tokens.add(Tok.word(token.group("word")));
            } else {
                chunk.tokens.add(amount(token));
            }
        }
        return chunk;
    }

    private static Tok amount(Matcher token) {
        String raw = token.group("num");
        BigDecimal value = number(raw);
        if (token.group("mult") != null) {
            value = value.multiply(THOUSAND);
        }
        String cur1 = token.group("cur1");
        String cur2 = token.group("cur2") == null ? null : plain(token.group("cur2"));
        String cents = token.group("cents") != null ? token.group("cents") : token.group("cents2");
        if ("centimos".equals(cur2)) {
            value = value.movePointLeft(2);
        }
        if (cents != null) {
            value = value.add(new BigDecimal(cents).movePointLeft(2));
        }
        boolean dollars = token.group("cur3") != null || (cur1 != null && (cur1.contains("$") || cur1.equals("usd")))
                || (cur2 != null && (cur2.startsWith("dolar") || cur2.equals("usd")));
        boolean plain = cur1 == null && cur2 == null && token.group("cur3") == null && token.group("mult") == null
                && cents == null && raw.chars().allMatch(Character::isDigit);
        if (value.signum() <= 0) {
            return Tok.word(raw);
        }
        return new Tok(raw, value, dollars ? Currency.USD : Currency.PEN, plain);
    }

    /** "1,500" y "1.500" son mil quinientos; "35,90" y "35.90", treinta y cinco con noventa. */
    static BigDecimal number(String raw) {
        Matcher grouped = GROUPED.matcher(raw);
        if (grouped.matches()) {
            String decimals = grouped.group(1);
            String integer = (decimals == null ? raw : raw.substring(0, raw.length() - decimals.length()))
                    .replaceAll("[.,]", "");
            return new BigDecimal(decimals == null ? integer : integer + "." + decimals.substring(1));
        }
        return new BigDecimal(raw.replace(',', '.'));
    }

    /**
     * "cien soles" pasa a ser "100 soles" y "treinta y cinco", "35", para que
     * el resto del parser solo vea digitos. Menos de diez solo con la moneda al
     * lado: "un cafe" y "dos menus" no son plata.
     */
    private static String spelledNumbers(String text) {
        List<MatchResult> words = WORD.matcher(text).results().toList();
        StringBuilder out = new StringBuilder();
        int copied = 0;
        for (int i = 0; i < words.size(); i++) {
            List<String> run = new ArrayList<>();
            Integer value = null;
            int end = i;
            for (int j = i; j < words.size(); j++) {
                if (j > i && !text.substring(words.get(j - 1).end(), words.get(j).start()).isBlank()) {
                    break;
                }
                String word = plain(words.get(j).group());
                if (!word.equals("y") && !NUMBER_WORDS.containsKey(word)) {
                    break;
                }
                run.add(word);
                Integer read = spelled(run);
                if (read != null) {
                    value = read;
                    end = j;
                }
            }
            // "2 mil" ya lo lee el monto con digitos.
            if (value == null || run.get(0).equals("mil") && DIGIT_BEFORE.matcher(text).region(0, words.get(i).start()).find()) {
                continue;
            }
            int to = words.get(end).end();
            if (value < 10 && !CURRENCY_NEXT.matcher(text).region(to, text.length()).lookingAt()) {
                continue;
            }
            out.append(text, copied, words.get(i).start()).append(value);
            copied = to;
            i = end;
        }
        return out.append(text.substring(copied)).toString();
    }

    /** "treinta y cinco" es 35 y "dos mil quinientos", 2500; null si las palabras no arman un numero. */
    static Integer spelled(List<String> words) {
        int total = 0;
        int group = 0;
        // 0: nada, 1: centenas, 2: decenas, 3: "y", 4: unidades o "cien": despues solo puede venir "mil".
        int stage = 0;
        boolean thousands = false;
        for (String word : words) {
            if (word.equals("y")) {
                if (stage != 2) {
                    return null;
                }
                stage = 3;
                continue;
            }
            int value = NUMBER_WORDS.get(word);
            if (value == 1000) {
                if (thousands || stage == 3) {
                    return null;
                }
                total = Math.max(group, 1) * 1000;
                group = 0;
                stage = 0;
                thousands = true;
            } else if (value >= 100) {
                if (stage != 0) {
                    return null;
                }
                group += value;
                stage = word.equals("cien") ? 4 : 1;
            } else if (value >= 20 && value % 10 == 0) {
                if (stage > 1) {
                    return null;
                }
                group += value;
                stage = 2;
            } else {
                if (stage == 2 || stage == 4 || (stage == 3 && value > 9)) {
                    return null;
                }
                group += value;
                stage = 4;
            }
        }
        return stage == 3 ? null : total + group;
    }

    private static Map<String, Integer> numberWords() {
        Map<String, Integer> words = new HashMap<>();
        List<String> small = List.of("uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
                "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve",
                "veinte", "veintiuno", "veintidos", "veintitres", "veinticuatro", "veinticinco", "veintiseis",
                "veintisiete", "veintiocho", "veintinueve");
        for (int i = 0; i < small.size(); i++) {
            words.put(small.get(i), i + 1);
        }
        List<String> tens = List.of("treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa");
        for (int i = 0; i < tens.size(); i++) {
            words.put(tens.get(i), (i + 3) * 10);
        }
        List<String> hundreds = List.of("ciento", "doscient", "trescient", "cuatrocient", "quinient", "seiscient",
                "setecient", "ochocient", "novecient");
        words.put("ciento", 100);
        for (int i = 1; i < hundreds.size(); i++) {
            words.put(hundreds.get(i) + "os", (i + 1) * 100);
            words.put(hundreds.get(i) + "as", (i + 1) * 100);
        }
        words.putAll(Map.of("un", 1, "una", 1, "veintiun", 21, "veintiuna", 21, "cien", 100, "mil", 1000));
        return words;
    }

    /** La primera fecha que aparece en el trozo, o null. */
    private static LocalDate firstDate(String s, LocalDate today) {
        List<Dated> found = new ArrayList<>();
        Matcher m = RELATIVE_DAY.matcher(s);
        while (m.find()) {
            int back = switch (m.group(1)) {
                case "ayer", "anoche" -> 1;
                case "anteanoche", "anteayer", "antier" -> 2;
                default -> 0;
            };
            found.add(new Dated(m.start(), today.minusDays(back)));
        }
        m = DAYS_AGO.matcher(s);
        while (m.find()) {
            String count = m.group(1);
            int days = count.chars().allMatch(Character::isDigit) ? Integer.parseInt(count) : NUMBER_WORDS.get(count);
            found.add(new Dated(m.start(), today.minusDays(m.group(2).startsWith("semana") ? days * 7L : days)));
        }
        m = WEEKDAY.matcher(s);
        while (m.find()) {
            int target = WEEKDAYS.indexOf(plain(m.group(1))) + 1;
            // "el lunes" dicho un lunes es el de la semana pasada: el de hoy seria "hoy".
            int back = Math.floorMod(today.getDayOfWeek().getValue() - target, 7);
            found.add(new Dated(m.start(), today.minusDays(back == 0 ? 7 : back)));
        }
        m = NUMERIC_DATE.matcher(s);
        while (m.find()) {
            addDate(found, m.start(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)), m.group(4), today);
        }
        m = WRITTEN_DATE.matcher(s);
        while (m.find()) {
            String month = m.group(2).equals("setiembre") || m.group(2).equals("set") ? "sep" : m.group(2).substring(0, 3);
            addDate(found, m.start(), Integer.parseInt(m.group(1)), MONTHS.indexOf(month) + 1, m.group(3), today);
        }
        return found.stream().min(Comparator.comparingInt(Dated::position)).map(Dated::date).orElse(null);
    }

    /** Sin año, una fecha que todavia no llega es del año pasado: nadie anota gastos del futuro. */
    private static void addDate(List<Dated> found, int position, int day, int month, String year, LocalDate today) {
        try {
            int y = year == null ? today.getYear() : year.length() == 2 ? 2000 + Integer.parseInt(year) : Integer.parseInt(year);
            LocalDate date = LocalDate.of(y, month, day);
            found.add(new Dated(position, year == null && date.isAfter(today) ? date.minusYears(1) : date));
        } catch (DateTimeException e) {
            // "31/02" no es una fecha; se ignora.
        }
    }

    private static String firstGroup(Matcher matcher) {
        for (int i = 1; i <= matcher.groupCount(); i++) {
            if (matcher.group(i) != null) {
                return matcher.group(i);
            }
        }
        return null;
    }

    private static PaymentMethod methodOfVerb(String verb) {
        if (verb == null) {
            return null;
        }
        if (verb.startsWith("yape")) {
            return PaymentMethod.YAPE;
        }
        if (verb.startsWith("plin")) {
            return PaymentMethod.PLIN;
        }
        return verb.startsWith("dep") || verb.startsWith("transf") ? PaymentMethod.TRANSFER : null;
    }

    private static PaymentMethod method(Matcher payment) {
        if (payment.group(2) != null) {
            return PaymentMethod.CARD;
        }
        String word = payment.group(1) != null ? payment.group(1) : payment.group(3);
        return word.startsWith("tarjeta") ? PaymentMethod.CARD : METHOD_WORDS.get(word);
    }

    /**
     * Un trozo sin monto se une al siguiente que tenga ("pan y leche 8") o, si
     * no hay, al anterior ("chifa 35 y el postre"). Si ningun trozo tiene monto,
     * todo es un solo movimiento sin monto.
     */
    private static void merge(List<Chunk> chunks) {
        if (chunks.stream().noneMatch(Chunk::hasAmount)) {
            Chunk first = chunks.stream().filter(chunk -> !chunk.tokens.isEmpty()).findFirst().orElse(null);
            for (Chunk chunk : chunks) {
                if (first != null && chunk != first && !chunk.tokens.isEmpty()) {
                    first.tokens.add(Tok.word("y"));
                    first.tokens.addAll(chunk.tokens);
                    chunk.tokens.clear();
                }
            }
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (chunk.hasAmount()) {
                continue;
            }
            Chunk next = nearest(chunks, i, 1);
            Chunk previous = nearest(chunks, i, -1);
            if (chunk.notSplit) {
                Objects.requireNonNullElse(previous, next).notSplit = true;
            }
            if (chunk.tokens.isEmpty()) {
                continue;
            }
            if (next != null) {
                next.tokens.add(0, Tok.word("y"));
                next.tokens.addAll(0, chunk.tokens);
            } else {
                previous.tokens.add(Tok.word("y"));
                previous.tokens.addAll(chunk.tokens);
            }
            chunk.tokens.clear();
        }
    }

    private static Chunk nearest(List<Chunk> chunks, int from, int step) {
        for (int i = from + step; i >= 0 && i < chunks.size(); i += step) {
            if (chunks.get(i).hasAmount()) {
                return chunks.get(i);
            }
        }
        return null;
    }

    /**
     * La fecha y el medio dicho con palabras ("con yape") pasan a los trozos
     * que no los dicen, tambien hacia atras ("taxi 12 y chifa 35 ayer"). La
     * pista de un verbo pasa solo hacia adelante, y con ella el medio que trae
     * el verbo: en "taxi 12 y me yapearon 50" el taxi no es un ingreso ni se
     * pago con Yape. La pista de un sustantivo es solo de su trozo: en "sueldo
     * 1200 y taxi 12" el taxi no es sueldo.
     */
    private static void inherit(List<Chunk> chunks, LocalDate today) {
        LocalDate date = chunks.stream().map(chunk -> chunk.date).filter(Objects::nonNull).findFirst().orElse(today);
        PaymentMethod said = chunks.stream().filter(chunk -> !chunk.verbMethod).map(chunk -> chunk.method)
                .filter(Objects::nonNull).findFirst().orElse(null);
        Hint hint = null;
        PaymentMethod verbMethod = null;
        for (Chunk chunk : chunks) {
            date = chunk.date != null ? chunk.date : date;
            chunk.date = date;
            if (chunk.method != null && !chunk.verbMethod) {
                said = chunk.method;
            }
            if (chunk.verbHint) {
                hint = chunk.hint;
                verbMethod = chunk.verbMethod ? chunk.method : null;
            } else {
                chunk.hint = chunk.hint != null ? chunk.hint : hint;
                // "me yapearon 50 de juan y 30 por el cachuelo": el cachuelo tambien llego por Yape.
                if (chunk.hint == hint && chunk.method == null) {
                    chunk.method = verbMethod;
                }
            }
            chunk.method = chunk.method != null ? chunk.method : said;
        }
    }

    /** Los numeros que son cantidades pasan a ser parte de la descripcion. */
    private static void markQuantities(List<Tok> tokens) {
        // "2 menus de 12", "dos menus de 12" y "3 x 10": la cantidad por el precio.
        for (int i = 0; i < tokens.size(); i++) {
            BigDecimal count = count(tokens.get(i));
            if (count == null) {
                continue;
            }
            for (int words = 0; words <= 2; words++) {
                int link = i + 1 + words;
                if (link + 1 >= tokens.size()) {
                    break;
                }
                Tok connector = tokens.get(link);
                Tok price = tokens.get(link + 1);
                boolean between = tokens.subList(i + 1, link).stream().noneMatch(Tok::isAmount);
                if (between && !connector.isAmount() && MULTIPLY.contains(connector.text()) && price.isAmount()
                        && (words > 0 || connector.text().equals("x"))) {
                    tokens.set(link + 1, new Tok(price.text(), price.amount().multiply(count), price.currency(), false));
                    tokens.remove(link);
                    tokens.set(i, Tok.word(tokens.get(i).text()));
                    break;
                }
            }
        }
        // "1 kilo de arroz 5": el numero pegado a una unidad es cantidad.
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).plain() && UNITS.contains(tokens.get(i + 1).text())) {
                tokens.set(i, Tok.word(tokens.get(i).text()));
            }
        }
        // "3 panes 1.20" es una cantidad; "12 taxi 35 chifa" son dos montos, y lo delata el singular.
        if (tokens.size() > 2 && tokens.get(0).plain() && tokens.get(0).amount().intValue() <= MAX_QUANTITY
                && !tokens.get(1).isAmount() && tokens.subList(2, tokens.size()).stream().anyMatch(Tok::isAmount)
                && (tokens.get(0).amount().intValue() == 1 || tokens.get(1).text().endsWith("s"))) {
            tokens.set(0, Tok.word(tokens.get(0).text()));
        }
    }

    /**
     * Lo que puede ser una cantidad: un entero sin moneda de hasta 50, o una
     * palabra de dos a nueve. "Un" no cambia la cuenta y "diez" ya llega en digitos.
     */
    private static BigDecimal count(Tok token) {
        if (token.isAmount()) {
            return token.plain() && token.amount().intValue() <= 50 ? token.amount() : null;
        }
        Integer value = NUMBER_WORDS.get(plain(token.text()));
        return value != null && value >= 2 && value <= 9 ? BigDecimal.valueOf(value) : null;
    }

    /**
     * Parte un trozo en movimientos. Si empieza con un monto ("12 taxi 35
     * chifa") cada monto abre el suyo; si no ("taxi 12 chifa 35"), cada monto
     * cierra el suyo y lo que sobra al final va con el ultimo.
     */
    private static List<Item> items(List<Tok> tokens) {
        List<Item> items = new ArrayList<>();
        long amounts = tokens.stream().filter(Tok::isAmount).count();
        if (amounts <= 1) {
            if (!tokens.isEmpty()) {
                items.add(new Item(tokens.stream().filter(t -> !t.isAmount()).map(Tok::text).toList(),
                        tokens.stream().filter(Tok::isAmount).findFirst().orElse(null)));
            }
            return items;
        }
        if (tokens.get(0).isAmount()) {
            Item current = null;
            for (Tok token : tokens) {
                if (token.isAmount()) {
                    current = new Item(new ArrayList<>(), token);
                    items.add(current);
                } else {
                    current.words().add(token.text());
                }
            }
            return items;
        }
        List<String> pending = new ArrayList<>();
        for (Tok token : tokens) {
            if (token.isAmount()) {
                items.add(new Item(pending, token));
                pending = new ArrayList<>();
            } else {
                pending.add(token.text());
            }
        }
        items.get(items.size() - 1).words().addAll(pending);
        return items;
    }

    private static ParsedMovement movement(Item item, Chunk chunk) {
        List<String> words = new ArrayList<>(item.words());
        while (!words.isEmpty() && EDGE_WORDS.contains(words.get(0))) {
            words.remove(0);
        }
        while (!words.isEmpty() && EDGE_WORDS.contains(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        String description = words.isEmpty() ? null : capitalize(String.join(" ", words));
        if (description != null && description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION);
        }

        Tok amount = item.amount();
        BigDecimal value = amount == null ? null : amount.amount().setScale(2, RoundingMode.HALF_UP);
        Currency currency = amount == null ? Currency.PEN : amount.currency();
        List<Warning> warnings = new ArrayList<>();
        addIf(warnings, value == null, Warning.MISSING_AMOUNT);
        addIf(warnings, currency == Currency.USD, Warning.FOREIGN_CURRENCY);
        addIf(warnings, chunk.notSplit, Warning.NOT_SPLIT);
        addIf(warnings, words.size() > LONG_DESCRIPTION_WORDS, Warning.LONG_DESCRIPTION);
        addIf(warnings, value != null && value.compareTo(HIGH_AMOUNT) > 0, Warning.HIGH_AMOUNT);
        return new ParsedMovement(description, value, currency, chunk.date, chunk.method, chunk.hint, List.copyOf(warnings));
    }

    private static void addIf(List<Warning> warnings, boolean condition, Warning warning) {
        if (condition) {
            warnings.add(warning);
        }
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String plain(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Con la clase Unicode, \b y \w entienden las tildes: sin ella, "pagué" no termina en un limite de palabra. */
    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static final class Chunk {
        final List<Tok> tokens = new ArrayList<>();
        LocalDate date;
        PaymentMethod method;
        Hint hint;
        boolean notSplit;
        /** La pista vino de un verbo ("me pagaron"), no de un sustantivo ("sueldo"). */
        boolean verbHint;
        /** El medio vino del verbo ("me yapearon"), no de "con yape". */
        boolean verbMethod;

        boolean hasAmount() {
            return tokens.stream().anyMatch(Tok::isAmount);
        }
    }

    /** Una palabra o un monto; {@code plain} es un entero sin moneda, que puede ser una cantidad. */
    private record Tok(String text, BigDecimal amount, Currency currency, boolean plain) {

        static Tok word(String text) {
            return new Tok(text, null, null, false);
        }

        boolean isAmount() {
            return amount != null;
        }
    }

    private record Item(List<String> words, Tok amount) {
    }

    private record Dated(int position, LocalDate date) {
    }

}
