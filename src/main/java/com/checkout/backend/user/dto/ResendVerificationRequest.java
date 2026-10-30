package com.checkout.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendVerificationRequest(@NotBlank @Email @Size(max = 180) String email) {
}
