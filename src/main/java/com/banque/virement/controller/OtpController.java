package com.banque.virement.controller;

import com.banque.virement.dto.OtpRequest;
import com.banque.virement.dto.OtpResponse;
import com.banque.virement.security.CurrentUser;
import com.banque.virement.service.AuthService;
import com.banque.virement.service.OtpService;
import com.banque.virement.domain.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/otp")
@Tag(name = "OTP", description = "Codes a usage unique pour les operations sensibles (MFA)")
public class OtpController {

    private final OtpService otpService;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public OtpController(OtpService otpService, AuthService authService, CurrentUser currentUser) {
        this.otpService = otpService;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/request")
    @Operation(summary = "Demander un code OTP (virement, ajout/suppression beneficiaire, approbation)")
    public ResponseEntity<OtpResponse> request(@Valid @RequestBody OtpRequest request) {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(otpService.requestOtp(user, request.purpose(), blankToNull(request.context())));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
