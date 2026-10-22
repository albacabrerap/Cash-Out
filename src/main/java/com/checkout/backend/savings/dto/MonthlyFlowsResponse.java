package com.checkout.backend.savings.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ingresos y gastos sumados por mes, para el grafico del resumen.
 *
 * La serie es continua: un mes sin movimientos viene en cero en vez de faltar,
 * asi el grafico no une dos meses lejanos como si fueran seguidos. Los totales
 * los calcula el servidor para que el cliente no sume dinero en punto flotante.
 *
 * @param from   primer mes de la serie, yyyy-MM
 * @param to     ultimo mes, el mes en curso
 * @param months un elemento por mes, del mas antiguo al mas reciente
 */
public record MonthlyFlowsResponse(
        String from,
        String to,
        List<MonthlyFlow> months,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal totalNet
) {

    /** @param net ingresos menos gastos del mes: lo que se ahorro */
    public record MonthlyFlow(String month, BigDecimal income, BigDecimal expense, BigDecimal net) {
    }

}
