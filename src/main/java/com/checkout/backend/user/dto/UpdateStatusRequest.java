package com.checkout.backend.user.dto;

import com.checkout.backend.user.model.UserStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Estado que un administrador le pone a una cuenta: ACTIVE o BLOCKED.
 *
 * INACTIVE no se acepta aqui. Es la baja que pide el propio usuario, y que un
 * administrador la ponga mezclaria "se fue" con "lo sacamos"; para impedir el
 * acceso esta BLOCKED.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStatusRequest {
    @NotNull(message = "Hay que indicar el estado.")
    private UserStatus status;
}
