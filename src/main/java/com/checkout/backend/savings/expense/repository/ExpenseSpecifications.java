package com.checkout.backend.savings.expense.repository;

import com.checkout.backend.savings.expense.model.Expense;
import com.checkout.backend.savings.expense.model.ExpenseCategory;
import com.checkout.backend.web.SearchTerm;
import java.time.LocalDate;
import org.springframework.data.jpa.domain.Specification;

/**
 * Las piezas con las que se arma la consulta del listado de gastos.
 *
 * Cada filtro es una Specification independiente y el servicio encadena solo
 * las que vienen en la peticion. Antes habia un metodo de repositorio por cada
 * combinacion de filtros, y por eso categoria y fechas no se podian pedir a la
 * vez: sumar la busqueda habria hecho falta un metodo por cada subconjunto.
 */
public final class ExpenseSpecifications {

    private ExpenseSpecifications() {
    }

    /** Siempre va: es lo que impide ver los gastos de otro usuario. */
    public static Specification<Expense> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    /** Extremos inclusivos, como antes. */
    public static Specification<Expense> dateBetween(LocalDate from, LocalDate to) {
        return (root, query, cb) -> cb.between(root.get("date"), from, to);
    }

    public static Specification<Expense> inCategory(ExpenseCategory category) {
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    /**
     * La descripcion contiene el texto, sin distinguir mayusculas.
     *
     * @param likePattern el patron ya escapado que devuelve SearchTerm
     */
    public static Specification<Expense> descriptionMatches(String likePattern) {
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("description")), likePattern, SearchTerm.ESCAPE);
    }

}
