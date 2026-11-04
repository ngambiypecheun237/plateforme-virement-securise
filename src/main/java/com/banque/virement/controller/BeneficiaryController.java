package com.banque.virement.controller;

import com.banque.virement.domain.entity.Beneficiary;
import com.banque.virement.domain.entity.User;
import com.banque.virement.dto.BeneficiaryRequest;
import com.banque.virement.security.CurrentUser;
import com.banque.virement.service.AuthService;
import com.banque.virement.service.BeneficiaryService;
import com.banque.virement.util.IbanUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/beneficiaries")
@Tag(name = "Beneficiaires", description = "Carnet de beneficiaires (IBAN, delai de carence 48h)")
public class BeneficiaryController {

    private final BeneficiaryService service;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public BeneficiaryController(BeneficiaryService service, AuthService authService, CurrentUser currentUser) {
        this.service = service;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Ajouter un beneficiaire (OTP AJOUT_BENEFICIAIRE, contexte = IBAN)")
    public ResponseEntity<BeneficiaryRequest.View> create(@Valid @RequestBody CreateWithOtp request) {
        User user = authService.requireActiveUser(currentUser.email());
        Beneficiary beneficiary = service.create(user, request.toRequest(), request.otp());
        return ResponseEntity.status(HttpStatus.CREATED).body(BeneficiaryRequest.View.from(beneficiary));
    }

    @GetMapping
    @Operation(summary = "Lister mes beneficiaires")
    public ResponseEntity<List<BeneficiaryRequest.View>> list() {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(service.list(user).stream()
                .map(BeneficiaryRequest.View::from)
                .collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detail d'un beneficiaire")
    public ResponseEntity<BeneficiaryRequest.View> get(@PathVariable Long id) {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(BeneficiaryRequest.View.from(service.get(user, id)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier un beneficiaire (l'IBAN est immuable)")
    public ResponseEntity<BeneficiaryRequest.View> update(@PathVariable Long id,
                                                          @Valid @RequestBody BeneficiaryRequest request) {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(BeneficiaryRequest.View.from(service.update(user, id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Supprimer un beneficiaire (OTP SUPPRESSION_BENEFICIAIRE, contexte = id)")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id, @RequestParam String otp) {
        User user = authService.requireActiveUser(currentUser.email());
        service.delete(user, id, otp);
        return ResponseEntity.ok(Map.of("message", "Beneficiaire supprime"));
    }

    @GetMapping("/verify-iban")
    @Operation(summary = "Verifier la validite d'un IBAN (cle modulo 97)")
    public ResponseEntity<Map<String, Object>> verifyIban(@RequestParam String iban) {
        String normalized = IbanUtils.normalize(iban);
        return ResponseEntity.ok(Map.of(
                "iban", normalized == null ? "" : normalized,
                "valide", IbanUtils.isValid(normalized)));
    }

    /** Corps de requete pour la creation avec OTP (le contexte de l'OTP est l'IBAN). */
    public record CreateWithOtp(
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.NotBlank String iban,
            String bic,
            String bankName,
            String country,
            String email,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "\\d{6}", message = "Le code OTP doit contenir 6 chiffres")
            String otp) {

        BeneficiaryRequest toRequest() {
            return new BeneficiaryRequest(name, iban, bic, bankName, country, email);
        }
    }
}
