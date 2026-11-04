package com.banque.virement.controller;

import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.TransferStatus;
import com.banque.virement.dto.OtpConfirmRequest;
import com.banque.virement.dto.TransferCreateRequest;
import com.banque.virement.security.CurrentUser;
import com.banque.virement.service.AuthService;
import com.banque.virement.service.ExportService;
import com.banque.virement.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Virements", description = "Initiation, confirmation (OTP), historique, exports")
public class TransferController {

    private final TransferService transferService;
    private final ExportService exportService;
    private final AuthService authService;
    private final CurrentUser currentUser;
    private final com.banque.virement.service.AdminService adminService;

    public TransferController(TransferService transferService, ExportService exportService,
                              AuthService authService, CurrentUser currentUser,
                              com.banque.virement.service.AdminService adminService) {
        this.transferService = transferService;
        this.exportService = exportService;
        this.authService = authService;
        this.currentUser = currentUser;
        this.adminService = adminService;
    }

    @PostMapping
    @Operation(summary = "Creer un virement unitaire (B2C : OTP requis ensuite ; B2B : double signature)")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody TransferCreateRequest request) {
        User user = authService.requireActiveUser(currentUser.email());
        Transfer transfer = transferService.create(user, request);
        return ResponseEntity.status(201).body(Map.of(
                "transfer", TransferCreateRequest.View.from(transfer),
                "message", transfer.getStatus() == TransferStatus.EN_ATTENTE_APPROBATION
                        ? "Virement enregistre, en attente des signatures des mandataires"
                        : "Virement enregistre. Un code OTP vous a ete envoye pour confirmation.",
                "nextStep", transfer.getStatus() == TransferStatus.EN_ATTENTE_APPROBATION
                        ? "POST /api/v1/approvals/" + transfer.getId() + "/approve"
                        : "POST /api/v1/transfers/" + transfer.getId() + "/confirm"));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirmer un virement B2C avec le code OTP")
    public ResponseEntity<TransferCreateRequest.View> confirm(@PathVariable Long id,
                                                              @Valid @RequestBody OtpConfirmRequest request) {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(TransferCreateRequest.View.from(transferService.confirm(user, id, request.otp())));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Annuler un virement avant execution")
    public ResponseEntity<TransferCreateRequest.View> cancel(@PathVariable Long id) {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(TransferCreateRequest.View.from(transferService.cancel(user, id)));
    }

    @GetMapping
    @Operation(summary = "Historique de mes virements (filtres : statut, periode, recherche)")
    public ResponseEntity<Page<TransferCreateRequest.View>> history(
            @Parameter(description = "Statut (EXECUTE, EN_ATTENTE_CONFIRMATION, BLOQUE_FRAUDE...)")
            @RequestParam(required = false) TransferStatus status,
            @Parameter(description = "Date de debut (ISO-8601, ex : 2026-01-01T00:00:00Z)")
            @RequestParam(required = false) String from,
            @Parameter(description = "Date de fin (ISO-8601)")
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        User user = authService.requireActiveUser(currentUser.email());
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(transferService
                .historyFor(user, status, parseInstant(from), parseInstant(to), q, pageable)
                .map(TransferCreateRequest.View::from));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detail d'un virement")
    public ResponseEntity<TransferCreateRequest.View> get(@PathVariable Long id) {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(TransferCreateRequest.View.from(transferService.getOwnTransfer(user, id)));
    }

    @GetMapping(value = "/{id}/receipt.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Telecharger l'avis d'operation au format PDF")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        User user = authService.requireActiveUser(currentUser.email());
        Transfer transfer = transferService.getOwnTransfer(user, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=avis-" + transfer.getReference() + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(exportService.receiptPdf(transfer));
    }

    @GetMapping("/export")
    @Operation(summary = "Exporter l'historique au format PDF ou Excel")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q) {
        User user = authService.requireActiveUser(currentUser.email());
        boolean privileged = user.getRole() == com.banque.virement.domain.enums.Role.ADMIN
                || user.getRole() == com.banque.virement.domain.enums.Role.AGENT_BANCAIRE;
        java.util.List<Transfer> transfers;
        if (privileged) {
            // L'equipe interne exporte l'ensemble des flux de la plateforme
            transfers = adminService.searchTransactions(status, parseInstant(from), parseInstant(to), q, 0, 500).getContent();
        } else {
            PageRequest pageable = PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "createdAt"));
            transfers = transferService.historyFor(user, status, parseInstant(from), parseInstant(to), q, pageable).getContent();
        }
        if ("xlsx".equalsIgnoreCase(format) || "excel".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=historique-virements.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(exportService.historyXlsx(transfers));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=historique-virements.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(exportService.historyPdf(transfers));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}
