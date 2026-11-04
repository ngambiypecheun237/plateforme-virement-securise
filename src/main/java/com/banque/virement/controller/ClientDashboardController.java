package com.banque.virement.controller;

import com.banque.virement.domain.entity.User;
import com.banque.virement.security.CurrentUser;
import com.banque.virement.service.AuthService;
import com.banque.virement.service.ClientDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/client/dashboard")
@Tag(name = "Tableau de bord client", description = "Soldes, virements et dernieres operations")
public class ClientDashboardController {

    private final ClientDashboardService service;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public ClientDashboardController(ClientDashboardService service, AuthService authService, CurrentUser currentUser) {
        this.service = service;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Tableau de bord du client connecte")
    public ResponseEntity<Map<String, Object>> dashboard() {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(service.dashboard(user));
    }
}
