package com.banque.virement.dto;

public record OtpResponse(
        String message,
        long expiresInSeconds,
        /** Code retourne uniquement en profil de developpement (jamais en production) */
        String devCode
) {
}
