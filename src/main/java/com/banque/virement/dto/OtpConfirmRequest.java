package com.banque.virement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OtpConfirmRequest(
        @NotBlank(message = "Le code OTP est obligatoire")
        @Pattern(regexp = "\\d{6}", message = "Le code doit contenir 6 chiffres")
        String otp
) {
}
