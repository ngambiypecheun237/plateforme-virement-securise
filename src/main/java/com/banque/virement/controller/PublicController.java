package com.banque.virement.controller;

import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.repository.TransferRepository;
import com.banque.virement.service.ReceiptVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verification d'authenticite d'un recu de virement imprime (sans authentification).
 * Ne retourne que la validite du code et le statut du virement : aucune donnee personnelle.
 */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "Verification publique", description = "Verification d'un recu imprime (code affiche sur le recu)")
public class PublicController {

    private final TransferRepository transferRepository;
    private final ReceiptVerificationService verificationService;

    public PublicController(TransferRepository transferRepository, ReceiptVerificationService verificationService) {
        this.transferRepository = transferRepository;
        this.verificationService = verificationService;
    }

    @GetMapping("/verify")
    @Operation(summary = "Verifier un recu : reference + montant + code (imprimes sur le recu)")
    public ResponseEntity<Map<String, Object>> verify(@RequestParam String reference,
                                                      @RequestParam String amount,
                                                      @RequestParam String code) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reference", reference);
        try {
            BigDecimal normalized = new BigDecimal(amount.replace(" ", "").replace(",", "."));
            Transfer transfer = transferRepository.findByReference(reference).orElse(null);
            boolean amountMatches = transfer != null && transfer.getAmount().compareTo(normalized) == 0;
            boolean codeValid = transfer != null && amountMatches
                    && verificationService.matches(reference, transfer.getAmount().toPlainString(), code);
            response.put("valide", codeValid);
            if (codeValid) {
                response.put("statut", transfer.getStatus().name());
                response.put("date", transfer.getCreatedAt().toString());
            }
            return ResponseEntity.ok(response);
        } catch (NumberFormatException e) {
            response.put("valide", false);
            response.put("erreur", "Montant invalide");
            return ResponseEntity.ok(response);
        }
    }
}
