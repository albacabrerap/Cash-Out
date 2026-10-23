package com.checkout.backend.web;

import com.checkout.backend.exceptions.InvalidRequestException;
import java.util.Locale;

/**
 * Convierte el parametro {@code search} de un listado en un patron LIKE seguro.
 *
 * Tres cosas que hace y por que:
 *
 * <ul>
 *   <li><b>Ignora la busqueda en blanco.</b> Un buscador vacio, o con solo
 *       espacios, es lo mismo que no buscar. Responder 400 haria que cada
 *       cliente tuviera que limpiar el campo antes de mandarlo.</li>
 *   <li><b>Escapa los comodines.</b> En LIKE, {@code %} es "cualquier texto" y
 *       {@code _} es "cualquier caracter". Sin escaparlos, buscar "50%" o "_"
 *       devolveria resultados que no contienen esos caracteres, y buscar "%"
 *       devolveria todo. El usuario escribe texto, no patrones.</li>
 *   <li><b>Limita el largo.</b> Un buscador no necesita mas de 100 caracteres, y
 *       un texto arbitrariamente largo es trabajo gratuito para la base.</li>
 * </ul>
 *
 * El patron sale en minusculas y quien lo usa compara contra
 * {@code lower(columna)}, asi la busqueda no distingue mayusculas. No ignora
 * tildes: "practica" no encuentra "Práctica". Eso necesitaria la extension
 * unaccent de PostgreSQL.
 */
public final class SearchTerm {

    public static final int MAX_LENGTH = 100;

    /** Caracter de escape que hay que pasar a {@code CriteriaBuilder.like}. */
    public static final char ESCAPE = '\\';

    private SearchTerm() {
    }

    /**
     * @return el patron {@code %texto%} en minusculas y escapado, o {@code null}
     *         si no hay nada que buscar
     */
    public static String toLikePattern(String raw) {
        String escaped = normalizedAndEscaped(raw);
        return escaped == null ? null : "%" + escaped + "%";
    }

    /**
     * @return el patron {@code texto%}, para "empieza con", o {@code null} si no
     *         hay nada que buscar
     */
    public static String toPrefixPattern(String raw) {
        String escaped = normalizedAndEscaped(raw);
        return escaped == null ? null : escaped + "%";
    }

    private static String normalizedAndEscaped(String raw) {
        if (raw == null) {
            return null;
        }
        String term = raw.strip();
        if (term.isEmpty()) {
            return null;
        }
        if (term.length() > MAX_LENGTH) {
            throw new InvalidRequestException(
                    "La busqueda no puede tener mas de " + MAX_LENGTH + " caracteres.");
        }

        return term.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

}
