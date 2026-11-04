package com.banque.virement.dto;

import com.banque.virement.domain.enums.OtpPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OtpVerifyRequest(
        @NotNull(message = "La finalite est obligatoire") OtpPurpose purpose,
        String context,
        @NotBlank(message = "Le code est obligatoire")
        @Pattern(regexp = "\\d{6}", message = "Le code doit contenir 6 chiffres")
        String code
) {
}
