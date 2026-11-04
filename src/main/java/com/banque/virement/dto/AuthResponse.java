package com.banque.virement.dto;

public record AuthResponse(
        String token,
        String type,
        long expiresIn,
        String email,
        String fullName,
        String role
) {
}
