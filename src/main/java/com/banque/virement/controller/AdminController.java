package com.banque.virement.controller;

import com.banque.virement.domain.entity.*;
import com.banque.virement.domain.enums.*;
import com.banque.virement.dto.TransferCreateRequest;
import com.banque.virement.security.CurrentUser;
import com.banque.virement.service.AdminService;
import com.banque.virement.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tableau de bord d'administration (ADMIN et AGENT_BANCAIRE) :
 * supervision des flux, validation manuelle des operations suspectes,
 * analyse des journaux d'audit, gestion des clients et comptes.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration", description = "Supervision bancaire : flux, fraude, audit, clients")
public class AdminController {

    private final AdminService adminService;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public AdminController(AdminService adminService, AuthService authService, CurrentUser currentUser) {
        this.adminService = adminService;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------- statistiques

    @GetMapping("/stats")
    @Operation(summary = "Indicateurs globaux : clients, comptes, virements, volumes, alertes")
    public ResponseEntity<Map<String, Object>> stats() {
        authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(adminService.stats());
    }

    @GetMapping("/stats/daily")
    @Operation(summary = "Serie journaliere des virements executes (30 jours par defaut)")
    public ResponseEntity<List<Map<String, Object>>> daily(@RequestParam(defaultValue = "30") int days) {
        authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(adminService.dailyVolume(Math.min(Math.max(days, 1), 365)));
    }

    @GetMapping("/reports/operations")
    @Operation(summary = "Rapport statistique sur les operations (periode ISO-8601)")
    public ResponseEntity<Map<String, Object>> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(adminService.reportsOperations(from, to));
    }

    // ------------------------------------------------------------ clients

    @GetMapping("/clients")
    @Operation(summary = "Rechercher des clients (nom, email, telephone) et filtrer par role")
    public ResponseEntity<List<Map<String, Object>>> clients(@RequestParam(required = false) String q,
                                                             @RequestParam(required = false) Role role) {
        authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(adminService.searchClients(q, role).stream()
                .map(this::clientSummary)
                .collect(Collectors.toList()));
    }

    @GetMapping("/clients/{id}")
    @Operation(summary = "Detail client : comptes et informations")
    public ResponseEntity<Map<String, Object>> clientDetail(@PathVariable Long id) {
        authService.requireActiveUser(currentUser.email());
        User client = adminService.getClient(id);
        Map<String, Object> detail = clientSummary(client);
        List<BankAccount> accounts = adminService.accountsOf(id);
        detail.put("accounts", accounts.stream().map(AccountSummary::of).collect(Collectors.toList()));
        return ResponseEntity.ok(detail);
    }

    @PutMapping("/clients/{id}/role")
    @Operation(summary = "Modifier le role d'un utilisateur (ADMIN uniquement)")
    public ResponseEntity<Map<String, Object>> updateRole(@PathVariable Long id,
                                                          @RequestBody Map<String, String> body) {
        User admin = authService.requireActiveUser(currentUser.email());
        Role role = Role.valueOf(body.getOrDefault("role", "").toUpperCase());
        User updated = adminService.updateRole(admin, id, role);
        return ResponseEntity.ok(Map.of("id", updated.getId(), "role", updated.getRole().name()));
    }

    @PostMapping("/clients/{id}/activate")
    @Operation(summary = "Reactiver un utilisateur (ADMIN uniquement)")
    public ResponseEntity<Map<String, Object>> activateUser(@PathVariable Long id,
                                                            @RequestBody(required = false) Map<String, String> body) {
        User admin = authService.requireActiveUser(currentUser.email());
        User updated = adminService.setUserActive(admin, id, true, body == null ? null : body.get("reason"));
        return ResponseEntity.ok(Map.of("id", updated.getId(), "active", updated.isActive()));
    }

    @PostMapping("/clients/{id}/deactivate")
    @Operation(summary = "Desactiver un utilisateur (ADMIN uniquement)")
    public ResponseEntity<Map<String, Object>> deactivateUser(@PathVariable Long id,
                                                              @RequestBody(required = false) Map<String, String> body) {
        User admin = authService.requireActiveUser(currentUser.email());
        User updated = adminService.setUserActive(admin, id, false, body == null ? null : body.get("reason"));
        return ResponseEntity.ok(Map.of("id", updated.getId(), "active", updated.isActive()));
    }

    // ------------------------------------------------------------ comptes

    @GetMapping("/accounts")
    @Operation(summary = "Rechercher des comptes (IBAN, titulaire) avec filtres type/statut")
    public ResponseEntity<List<Map<String, Object>>> accounts(@RequestParam(required = false) String q,
                                                              @RequestParam(required = false) String type,
                                                              @RequestParam(required = false) String status) {
        authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(adminService.searchAccounts(q, type, status).stream()
                .map(AccountSummary::of)
                .collect(Collectors.toList()));
    }

    @PostMapping("/accounts/{id}/suspend")
    @Operation(summary = "Suspendre un compte (operations bloquees)")
    public ResponseEntity<Map<String, Object>> suspend(@PathVariable Long id,
                                                       @RequestBody(required = false) Map<String, String> body) {
        User admin = authService.requireActiveUser(currentUser.email());
        BankAccount account = adminService.suspendAccount(id, body == null ? null : body.get("reason"), admin.getEmail());
        return ResponseEntity.ok(Map.of("id", account.getId(), "status", account.getStatus().name()));
    }

    @PostMapping("/accounts/{id}/activate")
    @Operation(summary = "Reactiver un compte suspendu")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable Long id) {
        User admin = authService.requireActiveUser(currentUser.email());
        BankAccount account = adminService.activateAccount(id, admin.getEmail());
        return ResponseEntity.ok(Map.of("id", account.getId(), "status", account.getStatus().name()));
    }

    // -------------------------------------------------------- transactions

    @GetMapping("/transactions")
    @Operation(summary = "Rechercher / filtrer toutes les transactions de la plateforme")
    public ResponseEntity<Page<TransferCreateRequest.View>> transactions(
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(adminService.searchTransactions(status, from, to, q, page, size)
                .map(TransferCreateRequest.View::from));
    }

    // ---------------------------------------------------------- anti-fraude

    @GetMapping("/fraud-alerts")
    @Operation(summary = "Lister les alertes anti-fraude (filtre par statut)")
    public ResponseEntity<Page<Map<String, Object>>> fraudAlerts(
            @RequestParam(required = false) FraudAlertStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(adminService.fraudAlerts(status, page, size).map(this::alertSummary));
    }

    @PostMapping("/fraud-alerts/{id}/review")
    @Operation(summary = "Prendre une alerte en revue")
    public ResponseEntity<Map<String, String>> takeInReview(@PathVariable Long id) {
        User reviewer = authService.requireActiveUser(currentUser.email());
        adminService.takeInReview(reviewer, id);
        return ResponseEntity.ok(Map.of("message", "Alerte prise en revue"));
    }

    @PostMapping("/fraud-alerts/{id}/validate")
    @Operation(summary = "Valider manuellement : executer le virement bloque")
    public ResponseEntity<TransferCreateRequest.View> validate(@PathVariable Long id) {
        User reviewer = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(TransferCreateRequest.View.from(adminService.validateFraudAlert(reviewer, id)));
    }

    @PostMapping("/fraud-alerts/{id}/reject")
    @Operation(summary = "Annuler le virement bloque (fonds non debites)")
    public ResponseEntity<TransferCreateRequest.View> reject(@PathVariable Long id,
                                                             @RequestBody(required = false) Map<String, String> body) {
        User reviewer = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(TransferCreateRequest.View.from(
                adminService.rejectFraudAlert(reviewer, id, body == null ? null : body.get("comment"))));
    }

    // ---------------------------------------------------------------- audit

    @GetMapping("/audit-logs")
    @Operation(summary = "Journaux d'audit immuables (connexions, virements, validations...)")
    public ResponseEntity<Page<Map<String, Object>>> auditLogs(@RequestParam(required = false) String user,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "25") int size) {
        authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(adminService.auditLogs(user, page, size).map(log -> {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("id", log.getId());
            entry.put("userEmail", log.getUserEmail());
            entry.put("action", log.getAction());
            entry.put("entityType", log.getEntityType() == null ? "" : log.getEntityType());
            entry.put("entityId", log.getEntityId() == null ? "" : log.getEntityId());
            entry.put("details", log.getDetails() == null ? "" : log.getDetails());
            entry.put("ipAddress", log.getIpAddress() == null ? "" : log.getIpAddress());
            entry.put("createdAt", log.getCreatedAt().toString());
            return entry;
        }));
    }

    // ------------------------------------------------------------- helpers

    private Map<String, Object> clientSummary(User u) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("id", u.getId());
        summary.put("fullName", u.getFullName());
        summary.put("email", u.getEmail());
        summary.put("phone", u.getPhone() == null ? "" : u.getPhone());
        summary.put("role", u.getRole().name());
        summary.put("active", u.isActive());
        summary.put("company", u.getCompany() != null ? u.getCompany().getName() : "");
        summary.put("createdAt", u.getCreatedAt().toString());
        return summary;
    }

    private Map<String, Object> alertSummary(FraudAlert alert) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("id", alert.getId());
        summary.put("rule", alert.getRule());
        summary.put("severity", alert.getSeverity());
        summary.put("status", alert.getStatus());
        summary.put("details", alert.getDetails());
        summary.put("createdAt", alert.getCreatedAt().toString());
        Transfer t = alert.getTransfer();
        summary.put("transferId", t.getId());
        summary.put("reference", t.getReference());
        summary.put("montant", t.getAmount());
        summary.put("devise", t.getCurrency());
        summary.put("initiator", t.getCreatedBy().getEmail());
        summary.put("sourceIban", t.getSourceAccount().getIban());
        summary.put("beneficiary", t.getBeneficiary() != null ? t.getBeneficiary().getName() : "");
        return summary;
    }

    /** Projection legere pour les comptes (evite le lazy loading serialise). */
    static final class AccountSummary {
        static Map<String, Object> of(BankAccount a) {
            Map<String, Object> summary = new java.util.LinkedHashMap<>();
            summary.put("id", a.getId());
            summary.put("iban", a.getIban());
            summary.put("type", a.getType().name());
            summary.put("status", a.getStatus().name());
            summary.put("balance", a.getBalance());
            summary.put("currency", a.getCurrency());
            summary.put("dailyLimit", a.getDailyLimit());
            summary.put("weeklyLimit", a.getWeeklyLimit());
            String owner;
            if (a.getOwnerUser() != null) {
                owner = a.getOwnerUser().getFullName() + " (" + a.getOwnerUser().getEmail() + ")";
            } else if (a.getOwnerCompany() != null) {
                owner = "Entreprise : " + a.getOwnerCompany().getName();
            } else {
                owner = "";
            }
            summary.put("titulaire", owner);
            return summary;
        }
    }
}
