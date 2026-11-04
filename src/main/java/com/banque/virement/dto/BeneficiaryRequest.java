package com.banque.virement.dto;

import com.banque.virement.domain.entity.Beneficiary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record BeneficiaryRequest(
        @NotBlank(message = "Le nom est obligatoire") String name,
        @NotBlank(message = "L'IBAN est obligatoire") @Size(max = 34) String iban,
        String bic,
        String bankName,
        String country,
        String email
) {
    public record View(Long id, String name, String iban, String bic, String bankName,
                       String country, String email, String status, boolean verified,
                       Instant createdAt, Instant availableAt) {

        public static View from(Beneficiary b) {
            return new View(b.getId(), b.getName(), b.getIban(), b.getBic(), b.getBankName(),
                    b.getCountry(), b.getEmail(), b.getStatus().name(), b.isVerified(),
                    b.getCreatedAt(), b.getAvailableAt());
        }
    }
}
