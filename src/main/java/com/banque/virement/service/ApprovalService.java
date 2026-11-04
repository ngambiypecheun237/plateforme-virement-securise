package com.banque.virement.service;

import com.banque.virement.domain.entity.Company;
import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.entity.TransferApproval;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.*;
import com.banque.virement.exception.BusinessException;
import com.banque.virement.exception.ResourceNotFoundException;
import com.banque.virement.repository.TransferApprovalRepository;
import com.banque.virement.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Workflow d'approbation hierarchique pour les virements B2B :
 * chaque mandataire distinct signe (OTP) ; le quorum est atteint quand
 * le nombre de signatures distinctes couvre le seuil de l'entreprise.
 */
@Service
public class ApprovalService {

    private final TransferRepository transferRepository;
    private final TransferApprovalRepository approvalRepository;
    private final OtpService otpService;
    private final TransferService transferService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public ApprovalService(TransferRepository transferRepository, TransferApprovalRepository approvalRepository,
                           OtpService otpService, TransferService transferService,
                           NotificationService notificationService, AuditService auditService) {
        this.transferRepository = transferRepository;
        this.approvalRepository = approvalRepository;
        this.otpService = otpService;
        this.transferService = transferService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    public List<Transfer> pendingForCompany(User user) {
        if (user.getCompany() == null) {
            return List.of();
        }
        return transferRepository.findPendingApprovalForCompany(user.getCompany().getId());
    }

    @Transactional
    public Transfer approve(User approver, Long transferId, String otp, String comment) {
        Transfer transfer = loadB2BTransfer(approver, transferId);
        otpService.verify(approver, OtpPurpose.APPROBATION_VIREMENT, String.valueOf(transferId), otp);

        if (approvalRepository.findByTransferIdAndApproverId(transferId, approver.getId()).isPresent()) {
            throw new BusinessException("Vous avez deja signe ce virement");
        }

        approvalRepository.save(TransferApproval.builder()
                .transfer(transfer)
                .approver(approver)
                .status(ApprovalStatus.APPROVED)
                .comment(comment)
                .decidedAt(Instant.now())
                .build());

        long signatures = approvalRepository.countByTransferIdAndStatus(transferId, ApprovalStatus.APPROVED);
        transfer.setApprovalCount((int) signatures);
        transfer.setUpdatedAt(Instant.now());

        Company company = approver.getCompany();
        int required = requiredSignatures(company, transfer);

        auditService.log(approver.getEmail(), "APPROBATION", "Transfer", String.valueOf(transferId),
                "Signature " + signatures + "/" + required + " pour " + transfer.getReference(), null);

        if (signatures >= required) {
            // Quorum atteint : anti-fraude puis execution (ou programmation si differe)
            transfer = transferService.finalizeAfterValidation(approver, transfer);
        } else {
            transferRepository.save(transfer);
            notificationService.create(transfer.getCreatedBy(), NotificationType.APPROBATION_REQUISE,
                    "Signature supplementaire requise",
                    "Le virement " + transfer.getReference() + " a recu " + signatures + "/" + required
                            + " signature(s). Il sera execute une fois le quorum atteint.");
        }
        return transfer;
    }

    @Transactional
    public Transfer reject(User approver, Long transferId, String otp, String comment) {
        Transfer transfer = loadB2BTransfer(approver, transferId);
        otpService.verify(approver, OtpPurpose.APPROBATION_VIREMENT, String.valueOf(transferId), otp);

        approvalRepository.save(TransferApproval.builder()
                .transfer(transfer)
                .approver(approver)
                .status(ApprovalStatus.REJECTED)
                .comment(comment)
                .decidedAt(Instant.now())
                .build());

        transfer.setStatus(TransferStatus.REJETE);
        transfer.setUpdatedAt(Instant.now());
        transferRepository.save(transfer);

        notificationService.create(transfer.getCreatedBy(), NotificationType.VIREMENT_REJETE, "Virement rejete",
                "Le virement " + transfer.getReference() + " a ete rejete par " + approver.getFullName()
                        + (comment != null && !comment.isBlank() ? " : " + comment : "."));
        auditService.log(approver.getEmail(), "REJET_APPROBATION", "Transfer", String.valueOf(transferId),
                "Reference " + transfer.getReference(), null);
        return transfer;
    }

    /** Approuve en lot tous les virements en attente d'un batch (une seule OTP pour le lot). */
    @Transactional
    public int approveBatch(User approver, Long batchId, String otp, String comment) {
        if (approver.getCompany() == null) {
            throw new BusinessException("Reserve aux mandataires d'entreprise");
        }
        otpService.verify(approver, OtpPurpose.APPROBATION_VIREMENT, "BATCH-" + batchId, otp);
        int approved = 0;
        List<Transfer> pending = transferRepository.findByBatchId(batchId).stream()
                .filter(t -> t.getStatus() == TransferStatus.EN_ATTENTE_APPROBATION)
                .toList();
        for (Transfer transfer : pending) {
            if (transfer.getSourceAccount().getOwnerCompany() == null
                    || !transfer.getSourceAccount().getOwnerCompany().getId().equals(approver.getCompany().getId())) {
                continue;
            }
            if (approvalRepository.findByTransferIdAndApproverId(transfer.getId(), approver.getId()).isPresent()) {
                continue;
            }
            approvalRepository.save(TransferApproval.builder()
                    .transfer(transfer).approver(approver).status(ApprovalStatus.APPROVED)
                    .comment(comment == null ? "Signature de lot" : comment).decidedAt(Instant.now()).build());
            long signatures = approvalRepository.countByTransferIdAndStatus(transfer.getId(), ApprovalStatus.APPROVED);
            transfer.setApprovalCount((int) signatures);
            transfer.setUpdatedAt(Instant.now());
            Company company = approver.getCompany();
            int required = requiredSignatures(company, transfer);
            if (signatures >= required) {
                transferService.finalizeAfterValidation(approver, transfer);
                approved++;
            } else {
                transferRepository.save(transfer);
            }
        }
        auditService.log(approver.getEmail(), "APPROBATION_LOT", "TransferBatch", String.valueOf(batchId),
                approved + " virement(s) execute(s) suite a la signature de lot", null);
        return approved;
    }

    private Transfer loadB2BTransfer(User approver, Long transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Virement introuvable"));
        if (approver.getRole() != Role.ENTREPRISE || approver.getCompany() == null) {
            throw new BusinessException("Seuls les mandataires d'entreprise peuvent approuver");
        }
        if (transfer.getSourceAccount().getOwnerCompany() == null
                || !transfer.getSourceAccount().getOwnerCompany().getId().equals(approver.getCompany().getId())) {
            throw new BusinessException("Ce virement n'appartient pas a votre entreprise");
        }
        // Maker-checker : le createur PEUT signer, mais il faut toujours un quorum de signatures
        // de mandataires DISTINCTS (jamais une seule personne).
        if (transfer.getStatus() != TransferStatus.EN_ATTENTE_APPROBATION) {
            throw new BusinessException("Ce virement n'est pas en attente d'approbation (statut : " + transfer.getStatus() + ")");
        }
        return transfer;
    }

    /** Double signature obligatoire au-dela du seuil ; une signature suffit sous le seuil si configure. */
    private int requiredSignatures(Company company, Transfer transfer) {
        if (company == null) {
            return 1;
        }
        Double threshold = company.getSingleApprovalThreshold();
        if (threshold != null && transfer.getAmount().doubleValue() <= threshold) {
            return 1;
        }
        return Math.max(1, company.getRequiredApprovals());
    }
}
