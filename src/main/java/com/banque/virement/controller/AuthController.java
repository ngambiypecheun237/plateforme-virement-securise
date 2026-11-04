package com.banque.virement.controller;

import com.banque.virement.domain.enums.OtpPurpose;
import com.banque.virement.dto.*;
import com.banque.virement.exception.BusinessException;
import com.banque.virement.repository.UserRepository;
import com.banque.virement.service.AuthService;
import com.banque.virement.service.OtpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.banque.virement.domain.entity.User;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentification", description = "Inscription, connexion et deconnexion")
public class AuthController {

    private final AuthService authService;
    private final OtpService otpService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, OtpService otpService, UserRepository userRepository) {
        this.authService = authService;
        this.otpService = otpService;
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    @Operation(summary = "Inscription d'un client particulier (B2C)")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "message", "Compte cree avec succes. Connectez-vous via /api/v1/auth/login"));
    }

    @PostMapping("/login")
    @Operation(summary = "Connexion : retourne un token JWT (mot de passe + BCrypt)")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = clientIp(http);
        return ResponseEntity.ok(authService.login(request, ip));
    }

    @GetMapping("/me")
    @Operation(summary = "Profil de l'utilisateur connecte")
    public ResponseEntity<Map<String, Object>> me(org.springframework.security.core.Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));
        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "fullName", user.getFullName(),
                "role", user.getRole().name(),
                "phone", user.getPhone() == null ? "" : user.getPhone(),
                "company", user.getCompany() != null ? user.getCompany().getName() : ""));
    }

    static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
    }
}
