package com.banque.virement.service;

import com.banque.virement.domain.entity.*;
import com.banque.virement.domain.enums.*;
import com.banque.virement.repository.*;
import com.banque.virement.util.IbanUtils;
import com.banque.virement.util.ReferenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Traitement asynchrone des remises : chaque ligne devient un virement B2B
 * en attente d'approbation (double signature). Les beneficiaires sont crees
 * a la volee ; le delai de carence est leve pour les remises d'entreprise
 * signees (decision documentee, cf. Guide utilisateur).
 */
@Component
public class BatchAsyncProcessor {

    private static final Logger log = LoggerFactory.getLogger(BatchAsyncProcessor.class);

    private final TransferBatchRepository batchRepository;
    private final BankAccountRepository accountRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final UserRepository userRepository;
    private final TransferRepository transferRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final ReferenceGenerator referenceGenerator;

    public BatchAsyncProcessor(TransferBatchRepository batchRepository, BankAccountRepository accountRepository,
                               BeneficiaryRepository beneficiaryRepository, UserRepository userRepository,
                               TransferRepository transferRepository, NotificationService notificationService,
                               AuditService auditService, ReferenceGenerator referenceGenerator) {
        this.batchRepository = batchRepository;
        this.accountRepository = accountRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.userRepository = userRepository;
        this.transferRepository = transferRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.referenceGenerator = referenceGenerator;
    }

    @Async
    @Transactional
    public void processAsync(Long batchId, Long sourceAccountId, Long uploaderId, List<String> lines) {
        TransferBatch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            log.error("Batch {} introuvable pour traitement", batchId);
            return;
        }
        User uploader = userRepository.findById(uploaderId).orElse(null);
        BankAccount source = accountRepository.findById(sourceAccountId).orElse(null);
        if (uploader == null || source == null) {
            batch.setStatus(BatchStatus.ECHOUE);
            batch.setErrorDetails("Compte ou utilisateur introuvable");
            batchRepository.save(batch);
            return;
        }

        int success = 0;
        int failed = 0;
        StringBuilder errors = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            try {
                processLine(line, lineNo, batch, source, uploader);
                success++;
            } catch (Exception e) {
                failed++;
                errors.append("ligne ").append(lineNo).append(": ").append(e.getMessage()).append(" | ");
                log.warn("Batch {} ligne {} rejetee : {}", batch.getReference(), lineNo, e.getMessage());
            }
        }

        batch.setSuccessCount(success);
        batch.setFailedCount(failed);
        batch.setStatus(failed == 0 ? BatchStatus.TERMINE : (success == 0 ? BatchStatus.ECHOUE : BatchStatus.PARTIEL));
        batch.setErrorDetails(errors.length() > 0 ? errors.substring(0, Math.min(errors.length(), 1000)) : null);
        batch.setCompletedAt(Instant.now());
        batchRepository.save(batch);

        notificationService.create(uploader, NotificationType.BATCH_TERMINE, "Remise traitee",
                "La remise " + batch.getReference() + " (" + batch.getFileName() + ") est traitee : " + success
                        + " virement(s) cree(s), " + failed + " echec(s). Les virements sont en attente de signature(s).");
        auditService.log(uploader.getEmail(), "TRAITEMENT_REMISE", "TransferBatch", String.valueOf(batch.getId()),
                success + " succes / " + failed + " echecs", null);
        log.info("Batch {} termine : {} succes, {} echecs", batch.getReference(), success, failed);
    }

    private void processLine(String line, int lineNo, TransferBatch batch, BankAccount source, User uploader) {
        String[] parts = splitLine(line);
        if (parts.length < 4) {
            throw new IllegalArgumentException("format invalide (attendu : nom;iban;montant;motif[;bic])");
        }
        String name = parts[0].trim();
        String iban = IbanUtils.normalize(parts[1]);
        if (name.length() < 2) {
            throw new IllegalArgumentException("nom beneficiaire manquant");
        }
        if (!IbanUtils.isValid(iban)) {
            throw new IllegalArgumentException("IBAN invalide : " + parts[1]);
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(parts[2].trim().replace(" ", "").replace(",", "."));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("montant invalide : " + parts[2]);
        }
        if (amount.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("montant doit etre >= 1");
        }
        String motif = parts[3].trim();
        String bic = parts.length >= 5 ? parts[4].trim() : null;

        // Beneficiaire cree a la volee si absent (carence levee pour remise signee entreprise)
        Beneficiary beneficiary = beneficiaryRepository.findByOwnerIdAndIban(uploader.getId(), iban)
                .orElseGet(() -> beneficiaryRepository.save(Beneficiary.builder()
                        .owner(uploader)
                        .name(name)
                        .iban(iban)
                        .bic(bic)
                        .country(iban.substring(0, 2))
                        .verified(true)
                        .status(BeneficiaryStatus.ACTIVE)
                        .createdAt(Instant.now())
                        .availableAt(Instant.now())   // remise d'entreprise : pas de carence
                        .build()));

        Transfer transfer = Transfer.builder()
                .reference(referenceGenerator.nextTransferReference())
                .type(TransferType.VIREMENT_MASSE)
                .status(TransferStatus.EN_ATTENTE_APPROBATION)
                .sourceAccount(source)
                .beneficiary(beneficiary)
                .amount(amount)
                .currency(source.getCurrency())
                .motif("Remise " + batch.getReference() + " - " + motif)
                .batch(batch)
                .createdBy(uploader)
                .createdAt(Instant.now())
                .approvalCount(0)
                .build();
        transferRepository.save(transfer);
    }

    private String[] splitLine(String line) {
        String delimiter = line.contains(";") ? ";" : ",";
        return line.split(java.util.regex.Pattern.quote(delimiter), -1);
    }
}
