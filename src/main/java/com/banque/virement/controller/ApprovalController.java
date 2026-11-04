package com.banque.virement.controller;

import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.entity.User;
import com.banque.virement.dto.ApprovalDecisionRequest;
import com.banque.virement.dto.TransferCreateRequest;
import com.banque.virement.security.CurrentUser;
import com.banque.virement.service.ApprovalService;
import com.banque.virement.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Approbations B2B", description = "Double signature des mandataires (workflow hierarchique)")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public ApprovalController(ApprovalService approvalService, AuthService authService, CurrentUser currentUser) {
        this.approvalService = approvalService;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @GetMapping("/pending")
    @Operation(summary = "Virements en attente de signature (mon entreprise)")
    public ResponseEntity<List<TransferCreateRequest.View>> pending() {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(approvalService.pendingForCompany(user).stream()
                .map(TransferCreateRequest.View::from)
                .collect(Collectors.toList()));
    }

    @PostMapping("/{transferId}/approve")
    @Operation(summary = "Signer (approuver) un virement - OTP requis. Quorum atteint => execution")
    public ResponseEntity<TransferCreateRequest.View> approve(@PathVariable Long transferId,
                                                              @Valid @RequestBody ApprovalDecisionRequest request) {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(TransferCreateRequest.View.from(
                approvalService.approve(user, transferId, request.otp(), request.comment())));
    }

    @PostMapping("/{transferId}/reject")
    @Operation(summary = "Rejeter un virement - OTP requis")
    public ResponseEntity<TransferCreateRequest.View> reject(@PathVariable Long transferId,
                                                             @Valid @RequestBody ApprovalDecisionRequest request) {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(TransferCreateRequest.View.from(
                approvalService.reject(user, transferId, request.otp(), request.comment())));
    }

    @PostMapping("/batch/{batchId}/approve-all")
    @Operation(summary = "Signer en lot tous les virements en attente d'un batch (OTP, contexte = BATCH-<id>)")
    public ResponseEntity<Map<String, Object>> approveBatch(@PathVariable Long batchId,
                                                            @Valid @RequestBody ApprovalDecisionRequest request) {
        User user = authService.requireActiveUser(currentUser.email());
        int executed = approvalService.approveBatch(user, batchId, request.otp(), request.comment());
        return ResponseEntity.ok(Map.of(
                "batchId", batchId,
                "virementsExecutes", executed,
                "message", "Signature de lot enregistree"));
    }
}
