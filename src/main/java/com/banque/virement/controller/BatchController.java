package com.banque.virement.controller;

import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.entity.TransferBatch;
import com.banque.virement.domain.entity.User;
import com.banque.virement.dto.BatchView;
import com.banque.virement.dto.TransferCreateRequest;
import com.banque.virement.security.CurrentUser;
import com.banque.virement.service.AuthService;
import com.banque.virement.service.BatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/transfers/mass")
@Tag(name = "Virements de masse (B2B)", description = "Remises CSV : salaires, fournisseurs")
public class BatchController {

    private final BatchService batchService;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public BatchController(BatchService batchService, AuthService authService, CurrentUser currentUser) {
        this.batchService = batchService;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "Deposer un fichier de remise CSV (nom;iban;montant;motif[;bic]) - traitement asynchrone, retourne 202")
    public ResponseEntity<BatchView> upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam("sourceAccountId") Long sourceAccountId) {
        User user = authService.requireActiveUser(currentUser.email());
        List<String> lines = readLines(file);
        TransferBatch batch = batchService.submit(user, sourceAccountId,
                file.getOriginalFilename() == null ? "remise.csv" : file.getOriginalFilename(), lines);
        return ResponseEntity.accepted().body(BatchView.from(batch));
    }

    @GetMapping("/batches")
    @Operation(summary = "Lister mes remises deposees")
    public ResponseEntity<List<BatchView>> list() {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(batchService.listFor(user).stream().map(BatchView::from).collect(Collectors.toList()));
    }

    @GetMapping("/batches/{id}")
    @Operation(summary = "Detail et avancement d'une remise")
    public ResponseEntity<BatchView> get(@PathVariable Long id) {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(BatchView.from(batchService.getBatch(user, id)));
    }

    private List<String> readLines(MultipartFile file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(l -> l.replace("\uFEFF", "").trim())
                    .filter(l -> !l.isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalArgumentException("Fichier illisible : " + e.getMessage());
        }
    }
}
