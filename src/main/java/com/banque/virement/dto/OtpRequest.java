package com.banque.virement.dto;

import com.banque.virement.domain.enums.OtpPurpose;
import jakarta.validation.constraints.NotNull;

public record OtpRequest(
        @NotNull(message = "La finalite est obligatoire") OtpPurpose purpose,
        String context
) {
}
