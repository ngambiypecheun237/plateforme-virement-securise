package com.banque.virement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ApprovalDecisionRequest(
        @NotBlank(message = "Le code OTP est obligatoire pour signer la decision")
        @Pattern(regexp = "\\d{6}", message = "Le code doit contenir 6 chiffres")
        String otp,
        String comment
) {
}
