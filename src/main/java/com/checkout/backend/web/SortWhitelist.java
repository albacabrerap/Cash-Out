package com.checkout.backend.web;

import com.checkout.backend.exceptions.InvalidRequestException;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Limita el parametro {@code sort} de un listado a campos conocidos.
 *
 * Spring resuelve {@code ?sort=campo,desc} solo, pero acepta cualquier texto y
 * lo convierte en una ruta de propiedad. Dos consecuencias que pasaban antes de
 * esta clase:
 *
 * <ul>
 *   <li>Un campo que no existe, como {@code ?sort=precio}, llegaba a la consulta
 *       y reventaba con un 500. El cliente no tenia forma de distinguirlo de un
 *       fallo del servidor.</li>
 *   <li>Un campo con punto navega a la entidad relacionada:
 *       {@code ?sort=user.passwordHash} respondia 200. En estos listados no
 *       filtra datos de otros —ya estan acotados al dueno—, pero el ordenamiento
 *       no tiene por que alcanzar columnas que la API nunca expone.</li>
 * </ul>
 *
 * Con una lista blanca el contrato es explicito: lo que no esta en ella es 400,
 * con el nombre del campo y los permitidos en el mensaje.
 */
public final class SortWhitelist {

    private SortWhitelist() {
    }

    /**
     * Devuelve la misma pagina con un orden ya validado.
     *
     * Siempre se anade {@code id} descendente como desempate. Sin el, dos filas
     * con la misma fecha o el mismo monto no tienen un orden definido, y la base
     * puede devolverlas en orden distinto en cada consulta: al pasar de pagina
     * una fila aparece dos veces y otra ninguna.
     *
     * @param allowed     campos por los que se puede ordenar
     * @param defaultSort el orden cuando el cliente no pide ninguno
     */
    public static Pageable restrict(Pageable pageable, List<String> allowed, Sort defaultSort) {
        Sort requested = pageable.getSort();

        for (Sort.Order order : requested) {
            if (!allowed.contains(order.getProperty())) {
                throw new InvalidRequestException(
                        "No se puede ordenar por '" + order.getProperty()
                                + "'. Campos permitidos: " + String.join(", ", allowed) + ".");
            }
        }

        Sort sort = requested.isSorted() ? requested : defaultSort;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                sort.and(Sort.by(Sort.Direction.DESC, "id")));
    }

}
