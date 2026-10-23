package com.checkout.backend.user.dto;

import com.checkout.backend.user.model.Role;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roles nuevos de una cuenta, completos: reemplazan a los que tenia.
 *
 * Es el conjunto entero y no "agrega" o "quita" uno, porque asi la peticion se
 * puede repetir sin cambiar el resultado y el cliente no tiene que conocer el
 * estado anterior para pedir el que quiere.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRolesRequest {
    @NotEmpty(message = "Una cuenta necesita al menos un rol.")
    private Set<Role> roles;
}
