package com.banque.virement.service;

import com.banque.virement.domain.entity.BankAccount;
import com.banque.virement.domain.entity.TransferBatch;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.AccountStatus;
import com.banque.virement.domain.enums.BatchStatus;
import com.banque.virement.domain.enums.Role;
import com.banque.virement.exception.BusinessException;
import com.banque.virement.exception.ResourceNotFoundException;
import com.banque.virement.repository.BankAccountRepository;
import com.banque.virement.repository.TransferBatchRepository;
import com.banque.virement.util.ReferenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Remises d'entreprise (salaires, fournisseurs) : depot d'un fichier CSV,
 * creation d'un batch puis traitement asynchrone par {@link BatchAsyncProcessor}.
 * Format attendu (une ligne par beneficiaire) : nom;iban;montant;motif[;bic]
 * (le separateur , est auto-detecte si ; est absent).
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);
    private static final int MAX_LINES = 1000;

    private final BankAccountRepository accountRepository;
    private final TransferBatchRepository batchRepository;
    private final BatchAsyncProcessor processor;
    private final ReferenceGenerator referenceGenerator;
    private final AuditService auditService;

    public BatchService(BankAccountRepository accountRepository, TransferBatchRepository batchRepository,
                        BatchAsyncProcessor processor, ReferenceGenerator referenceGenerator,
                        AuditService auditService) {
        this.accountRepository = accountRepository;
        this.batchRepository = batchRepository;
        this.processor = processor;
        this.referenceGenerator = referenceGenerator;
        this.auditService = auditService;
    }

    @Transactional
    public TransferBatch submit(User uploader, Long sourceAccountId, String fileName, List<String> rawLines) {
        if (uploader.getRole() != Role.ENTREPRISE) {
            throw new BusinessException("Le depot de remises est reserve aux comptes entreprise");
        }
        BankAccount source = accountRepository.findById(sourceAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte source introuvable"));
        if (source.getOwnerCompany() == null || uploader.getCompany() == null
                || !source.getOwnerCompany().getId().equals(uploader.getCompany().getId())) {
            throw new BusinessException("Ce compte n'appartient pas a votre entreprise");
        }
        if (source.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException("Le compte source est suspendu ou bloque");
        }

        List<String> lines = rawLines.stream()
                .filter(l -> l != null && !l.isBlank())
                .map(String::trim)
                .filter(l -> !l.startsWith("#"))
                .filter(l -> !l.toLowerCase().startsWith("nom"))
                .toList();
        if (lines.isEmpty()) {
            throw new BusinessException("Le fichier ne contient aucune ligne exploitable");
        }
        if (lines.size() > MAX_LINES) {
            throw new BusinessException("Le fichier depasse la limite de " + MAX_LINES + " lignes");
        }

        TransferBatch batch = TransferBatch.builder()
                .reference(referenceGenerator.nextBatchReference())
                .fileName(fileName)
                .company(uploader.getCompany())
                .uploadedBy(uploader)
                .sourceAccount(source)
                .totalCount(lines.size())
                .status(BatchStatus.EN_COURS)
                .build();
        batch = batchRepository.save(batch);

        auditService.log(uploader.getEmail(), "DEPOT_REMISE", "TransferBatch", String.valueOf(batch.getId()),
                batch.getReference() + " : " + lines.size() + " ligne(s) depuis le fichier " + fileName, null);

        // Traitement asynchrone : l'API repond immediatement 202 Accepted
        processor.processAsync(batch.getId(), sourceAccountId, uploader.getId(), lines);
        return batch;
    }

    public List<TransferBatch> listFor(User user) {
        return batchRepository.findByUploadedByIdOrderByCreatedAtDesc(user.getId());
    }

    public TransferBatch getBatch(User user, Long id) {
        TransferBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Remise introuvable"));
        boolean privileged = user.getRole() == Role.ADMIN || user.getRole() == Role.AGENT_BANCAIRE;
        if (!privileged && !batch.getUploadedBy().getId().equals(user.getId())
                && (user.getCompany() == null || batch.getCompany() == null
                    || !batch.getCompany().getId().equals(user.getCompany().getId()))) {
            throw new ResourceNotFoundException("Remise introuvable");
        }
        return batch;
    }
}
