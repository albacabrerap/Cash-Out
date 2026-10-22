package com.checkout.backend.savings.income.repository;

import com.checkout.backend.savings.income.model.Income;
import com.checkout.backend.web.SearchTerm;
import java.time.LocalDate;
import org.springframework.data.jpa.domain.Specification;

/**
 * Las piezas con las que se arma la consulta del listado de ingresos.
 *
 * Mismo enfoque que ExpenseSpecifications: un filtro por pieza, y el servicio
 * encadena las que vienen en la peticion.
 */
public final class IncomeSpecifications {

    private IncomeSpecifications() {
    }

    /** Siempre va: es lo que impide ver los ingresos de otro usuario. */
    public static Specification<Income> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    /** Extremos inclusivos, como antes. */
    public static Specification<Income> dateBetween(LocalDate from, LocalDate to) {
        return (root, query, cb) -> cb.between(root.get("date"), from, to);
    }

    /**
     * La fuente o la descripcion contienen el texto, sin distinguir mayusculas.
     *
     * Se busca en las dos porque es lo que el usuario recuerda de un ingreso:
     * de donde vino ("Freelance") o para que era ("Landing page").
     *
     * @param likePattern el patron ya escapado que devuelve SearchTerm
     */
    public static Specification<Income> sourceOrDescriptionMatches(String likePattern) {
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("source")), likePattern, SearchTerm.ESCAPE),
                cb.like(cb.lower(root.get("description")), likePattern, SearchTerm.ESCAPE));
    }

}
