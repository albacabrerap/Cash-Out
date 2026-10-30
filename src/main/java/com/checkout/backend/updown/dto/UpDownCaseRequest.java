package com.checkout.backend.updown.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Un caso del banco. Que haya cierres para esa fecha lo revisa UpDownCaseService contra Alpaca. */
public record UpDownCaseRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z.]{0,11}", message = "Escribe el símbolo, como SPY o BRK.B.") String symbol,
        @NotNull @Past LocalDate cutoff,
        @NotBlank @Size(max = 60) String sector,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 300) String context,
        @NotBlank @Size(max = 600) String lesson,
        @NotNull Boolean active
) {
}
