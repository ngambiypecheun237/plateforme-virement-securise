package com.banque.virement.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Le prenom est obligatoire") String firstName,
        @NotBlank(message = "Le nom est obligatoire") String lastName,
        @NotBlank(message = "L'email est obligatoire") @Email(message = "Email invalide") String email,
        String phone,
        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(min = 8, message = "Le mot de passe doit contenir au moins 8 caracteres")
        @jakarta.validation.constraints.Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
                message = "Le mot de passe doit contenir au moins une majuscule, une minuscule et un chiffre")
        String password
) {
}
