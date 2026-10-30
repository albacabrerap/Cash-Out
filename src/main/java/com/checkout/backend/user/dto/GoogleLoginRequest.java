package com.checkout.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** El ID token ("credential") que Google Identity Services entrega a la web. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GoogleLoginRequest {

    @NotBlank
    @Size(max = 4096)
    private String credential;

}
