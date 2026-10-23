package com.checkout.backend.savings.movement.repository;

import com.checkout.backend.savings.dto.MovementKind;
import com.checkout.backend.savings.dto.MovementResponse;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Ingresos y gastos de un usuario en una sola pagina.
 *
 * La union, el orden y la paginacion se hacen en la base con UNION ALL. La
 * alternativa obvia —pedir una pagina a cada tabla y mezclarlas en memoria— no
 * funciona: la pagina 2 del listado unificado no se puede armar con la pagina 2
 * de cada tabla, y traer todo para cortarlo aqui crece con el historial del
 * usuario.
 *
 * Es SQL nativo porque JPQL no pagina sobre una union. El SQL se mantiene dentro
 * de lo comun a PostgreSQL y H2 (los tests corren en H2): CAST en vez de ::, y
 * LIMIT/OFFSET con parametros. Ningun valor del cliente se concatena: los filtros
 * van como parametros y el ORDER BY sale de una lista cerrada de columnas.
 */
@Repository
public class MovementQueryRepository {

    private static final String LIKE_ESCAPE = " ESCAPE '\\'";

    private final NamedParameterJdbcTemplate jdbc;

    public MovementQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param likePattern patron ya escapado por SearchTerm, o null para no buscar
     * @param pageable    con el orden ya validado por SortWhitelist
     */
    public Page<MovementResponse> findPage(Long userId, LocalDate from, LocalDate to,
                                           String likePattern, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        StringBuilder incomeWhere = new StringBuilder("i.user_id = :userId");
        StringBuilder expenseWhere = new StringBuilder("e.user_id = :userId");

        if (from != null) {
            incomeWhere.append(" AND i.date BETWEEN :from AND :to");
            expenseWhere.append(" AND e.date BETWEEN :from AND :to");
            params.addValue("from", from).addValue("to", to);
        }
        if (likePattern != null) {
            incomeWhere.append(" AND (LOWER(i.source) LIKE :pattern").append(LIKE_ESCAPE)
                    .append(" OR LOWER(i.description) LIKE :pattern").append(LIKE_ESCAPE).append(')');
            expenseWhere.append(" AND LOWER(e.description) LIKE :pattern").append(LIKE_ESCAPE);
            params.addValue("pattern", likePattern);
        }

        Long total = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM incomes i WHERE " + incomeWhere + ")"
                        + " + (SELECT COUNT(*) FROM expenses e WHERE " + expenseWhere + ")",
                params, Long.class);
        long count = total == null ? 0 : total;
        if (count == 0 || pageable.getOffset() >= count) {
            return new PageImpl<>(List.of(), pageable, count);
        }

        String sql = """
                SELECT m.id, m.kind, m.label, m.category, m.amount, m.date, m.created_at
                FROM (
                    SELECT i.id, 'INCOME' AS kind, i.source AS label, CAST(NULL AS VARCHAR(30)) AS category,
                           i.amount, i.date, i.created_at
                    FROM incomes i
                    WHERE %s
                    UNION ALL
                    SELECT e.id, 'EXPENSE' AS kind, e.description AS label, e.category,
                           e.amount, e.date, e.created_at
                    FROM expenses e
                    WHERE %s
                ) m
                ORDER BY %s
                LIMIT :limit OFFSET :offset
                """.formatted(incomeWhere, expenseWhere, orderBy(pageable.getSort()));
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());

        List<MovementResponse> content = jdbc.query(sql, params, (rs, rowNum) -> MovementResponse.builder()
                .id(rs.getLong("id"))
                .kind(MovementKind.valueOf(rs.getString("kind")))
                .label(rs.getString("label"))
                .category(rs.getString("category"))
                .amount(rs.getBigDecimal("amount"))
                .date(rs.getObject("date", LocalDate.class))
                .createdAt(rs.getObject("created_at", LocalDateTime.class))
                .build());
        return new PageImpl<>(content, pageable, count);
    }

    /** Ingresos y gastos de cada mes entre dos fechas. Un mes sin movimientos no aparece en el mapa. */
    public Map<YearMonth, MonthTotals> sumByMonth(Long userId, LocalDate from, LocalDate to) {
        String sql = """
                SELECT EXTRACT(YEAR FROM m.date) AS y, EXTRACT(MONTH FROM m.date) AS mo, m.kind, SUM(m.amount) AS total
                FROM (
                    SELECT i.date, 'INCOME' AS kind, i.amount FROM incomes i
                    WHERE i.user_id = :userId AND i.date BETWEEN :from AND :to
                    UNION ALL
                    SELECT e.date, 'EXPENSE' AS kind, e.amount FROM expenses e
                    WHERE e.user_id = :userId AND e.date BETWEEN :from AND :to
                ) m
                GROUP BY EXTRACT(YEAR FROM m.date), EXTRACT(MONTH FROM m.date), m.kind
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
                .addValue("from", from)
                .addValue("to", to);

        Map<YearMonth, MonthTotals> totals = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            YearMonth month = YearMonth.of(rs.getInt("y"), rs.getInt("mo"));
            MonthTotals current = totals.getOrDefault(month, MonthTotals.ZERO);
            BigDecimal amount = rs.getBigDecimal("total");
            totals.put(month, MovementKind.INCOME.name().equals(rs.getString("kind"))
                    ? new MonthTotals(current.income().add(amount), current.expense())
                    : new MonthTotals(current.income(), current.expense().add(amount)));
        });
        return totals;
    }

    /** Fecha del movimiento mas antiguo del usuario, si tiene alguno. */
    public Optional<LocalDate> firstMovementDate(Long userId) {
        LocalDate first = jdbc.queryForObject("""
                SELECT MIN(x.d) FROM (
                    SELECT MIN(i.date) AS d FROM incomes i WHERE i.user_id = :userId
                    UNION ALL
                    SELECT MIN(e.date) AS d FROM expenses e WHERE e.user_id = :userId
                ) x
                """, new MapSqlParameterSource("userId", userId), LocalDate.class);
        return Optional.ofNullable(first);
    }

    public record MonthTotals(BigDecimal income, BigDecimal expense) {
        public static final MonthTotals ZERO = new MonthTotals(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Traduce el orden a columnas. El desempate por {@code id} que agrega
     * SortWhitelist no alcanza aqui, porque un mismo id existe en las dos tablas:
     * se desempata por fecha de registro, tipo e id, que si es unico.
     */
    private static String orderBy(Sort sort) {
        List<String> columns = new ArrayList<>();
        for (Sort.Order order : sort) {
            String direction = order.isAscending() ? "ASC" : "DESC";
            switch (order.getProperty()) {
                case "date" -> columns.add("m.date " + direction);
                case "amount" -> columns.add("m.amount " + direction);
                case "id" -> {
                    columns.add("m.created_at " + direction);
                    columns.add("m.kind ASC");
                    columns.add("m.id " + direction);
                }
                default -> throw new IllegalArgumentException(
                        "Orden no validado por SortWhitelist: " + order.getProperty());
            }
        }
        return String.join(", ", columns);
    }

}
