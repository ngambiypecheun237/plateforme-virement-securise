package com.banque.virement.service;

import com.banque.virement.domain.entity.*;
import com.banque.virement.domain.enums.*;
import com.banque.virement.dto.TransferCreateRequest;
import com.banque.virement.exception.BusinessException;
import com.banque.virement.exception.ResourceNotFoundException;
import com.banque.virement.repository.*;
import com.banque.virement.util.ReferenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    /** Fenetre anti-doublons : deux virements identiques dans les 10 minutes sont rejetes. */
    private static final long DUPLICATE_WINDOW_MINUTES = 10;

    private final TransferRepository transferRepository;
    private final BankAccountRepository accountRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final UserRepository userRepository;
    private final OtpService otpService;
    private final FraudDetectionService fraudDetectionService;
    private final FraudAlertRepository fraudAlertRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final ReferenceGenerator referenceGenerator;
    private final CoreBankingGateway coreBankingGateway;

    public TransferService(TransferRepository transferRepository, BankAccountRepository accountRepository,
                           BeneficiaryRepository beneficiaryRepository, UserRepository userRepository, OtpService otpService,
                           FraudDetectionService fraudDetectionService, FraudAlertRepository fraudAlertRepository,
                           NotificationService notificationService, AuditService auditService,
                           ReferenceGenerator referenceGenerator, CoreBankingGateway coreBankingGateway) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.userRepository = userRepository;
        this.otpService = otpService;
        this.fraudDetectionService = fraudDetectionService;
        this.fraudAlertRepository = fraudAlertRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.referenceGenerator = referenceGenerator;
        this.coreBankingGateway = coreBankingGateway;
    }

    // ----------------------------------------------------------------- creation

    @Transactional
    public Transfer create(User user, TransferCreateRequest request) {
        // Idempotence : une meme cle renvoie le virement deja cree
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            var existing = transferRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                log.info("Idempotence : retour du virement {} pour la cle {}", existing.get().getReference(), request.idempotencyKey());
                return existing.get();
            }
        }

        BankAccount source = accountRepository.findById(request.sourceAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Compte source introuvable"));
        validateOwnership(user, source);

        if (source.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException("Operation impossible : le compte est " + source.getStatus() + " (suspendu ou bloque)");
        }

        boolean hasBeneficiary = request.beneficiaryId() != null;
        boolean hasTarget = request.targetAccountId() != null;
        if (hasBeneficiary == hasTarget) {
            throw new BusinessException("Precisez soit un beneficiaire, soit un compte cible interne");
        }

        Beneficiary beneficiary = null;
        BankAccount targetAccount = null;

        if (hasBeneficiary) {
            beneficiary = beneficiaryRepository.findByIdAndOwnerId(request.beneficiaryId(), user.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Beneficiaire introuvable dans votre carnet"));
            if (beneficiary.getStatus() != BeneficiaryStatus.ACTIVE) {
                throw new BusinessException("Ce beneficiaire est bloque");
            }
            if (!beneficiary.isAvailable()) {
                throw new BusinessException("Delai de carence : ce beneficiaire ne sera utilisable qu'a partir du "
                        + beneficiary.getAvailableAt() + " (48h apres son ajout)");
            }
        } else {
            targetAccount = accountRepository.findById(request.targetAccountId())
                    .orElseThrow(() -> new ResourceNotFoundException("Compte cible introuvable"));
            if (targetAccount.getStatus() != AccountStatus.ACTIVE) {
                throw new BusinessException("Le compte cible est suspendu ou bloque");
            }
        }

        BigDecimal amount = request.amount();

        // Verification des fonds
        if (amount.compareTo(source.getBalance()) > 0) {
            throw new BusinessException("Solde insuffisant : disponible " + source.getBalance() + " " + source.getCurrency()
                    + ", demande " + amount + " " + source.getCurrency());
        }

        // Controle des plafonds journaliers / hebdomadaires
        Instant startOfDay = Instant.now().atZone(java.time.ZoneId.of("Africa/Douala"))
                .toLocalDate().atStartOfDay(java.time.ZoneId.of("Africa/Douala")).toInstant();
        BigDecimal dailyUsed = nz(transferRepository.sumExecutedSince(source.getId(), startOfDay));
        if (dailyUsed.add(amount).compareTo(source.getDailyLimit()) > 0) {
            throw new BusinessException("Plafond journalier depasse (" + source.getDailyLimit() + " " + source.getCurrency()
                    + "). Utilise aujourd'hui : " + dailyUsed + " " + source.getCurrency());
        }
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        BigDecimal weeklyUsed = nz(transferRepository.sumExecutedSince(source.getId(), weekAgo));
        if (weeklyUsed.add(amount).compareTo(source.getWeeklyLimit()) > 0) {
            throw new BusinessException("Plafond hebdomadaire depasse (" + source.getWeeklyLimit() + " " + source.getCurrency()
                    + "). Utilise cette semaine : " + weeklyUsed + " " + source.getCurrency());
        }

        // Anti-doublons : meme compte, meme beneficiaire, meme montant, moins de 10 minutes
        List<Transfer> duplicates = transferRepository.findPotentialDuplicates(
                source.getId(), hasBeneficiary ? beneficiary.getId() : null, amount,
                Instant.now().minus(DUPLICATE_WINDOW_MINUTES, ChronoUnit.MINUTES));
        if (!duplicates.isEmpty()) {
            throw new BusinessException("Virement en double detecte (meme montant, meme destinataire, moins de "
                    + DUPLICATE_WINDOW_MINUTES + " minutes). Utilisez une cle d'idempotence ou patientez.");
        }

        // Type de virement
        TransferType type;
        if (targetAccount != null) {
            type = TransferType.VIREMENT_UNITAIRE_INTERNE;
        } else {
            type = accountRepository.findByIban(beneficiary.getIban()).isPresent()
                    ? TransferType.VIREMENT_UNITAIRE_INTERNE
                    : TransferType.VIREMENT_UNITAIRE_EXTERNE;
        }

        boolean isB2B = user.getRole() == Role.ENTREPRISE;
        Transfer transfer = Transfer.builder()
                .reference(referenceGenerator.nextTransferReference())
                .type(type)
                .status(isB2B ? TransferStatus.EN_ATTENTE_APPROBATION : TransferStatus.EN_ATTENTE_CONFIRMATION)
                .sourceAccount(source)
                .beneficiary(beneficiary)
                .targetAccount(targetAccount)
                .amount(amount)
                .currency(source.getCurrency())
                .motif(request.motif())
                .scheduledDate(request.scheduledDate())
                .idempotencyKey(request.idempotencyKey())
                .createdBy(user)
                .createdAt(Instant.now())
                .approvalCount(0)
                .build();
        transfer = transferRepository.save(transfer);

        notificationService.create(user, NotificationType.VIREMENT_CREE, "Virement cree",
                "Votre virement " + transfer.getReference() + " de " + amount + " " + transfer.getCurrency()
                        + " est en attente de confirmation.");

        if (isB2B) {
            // Prevenir les mandataires de l'entreprise qu'une approbation est requise
            if (user.getCompany() != null) {
                List<User> mandataires = userRepository.findByCompanyId(user.getCompany().getId());
                for (User m : mandataires) {
                    if (!m.getId().equals(user.getId())) {
                        notificationService.create(m, NotificationType.APPROBATION_REQUISE,
                                "Signature requise",
                                "Un virement de " + amount + " " + source.getCurrency() + " (" + transfer.getReference()
                                        + ") requiert votre signature dans l'espace d'approbation.");
                    }
                }
                log.info("Virement B2B {} en attente d'approbation ({} mandataire(s) notifie(s))",
                        transfer.getReference(), mandataires.size());
            }
        } else {
            otpService.requestOtp(user, OtpPurpose.CONFIRMATION_VIREMENT, String.valueOf(transfer.getId()));
        }

        auditService.log(user.getEmail(), "CREATION_VIREMENT", "Transfer", String.valueOf(transfer.getId()),
                "Reference " + transfer.getReference() + " montant " + amount + " " + transfer.getCurrency(), null);
        return transfer;
    }

    // ------------------------------------------------------------- confirmation (B2C)

    @Transactional
    public Transfer confirm(User user, Long transferId, String otp) {
        Transfer transfer = getOwnTransfer(user, transferId);
        if (transfer.getStatus() != TransferStatus.EN_ATTENTE_CONFIRMATION) {
            throw new BusinessException("Ce virement n'est pas en attente de confirmation (statut : " + transfer.getStatus() + ")");
        }
        otpService.verify(user, OtpPurpose.CONFIRMATION_VIREMENT, String.valueOf(transfer.getId()), otp);
        return finalizeAfterValidation(user, transfer);
    }

    /** Annulation par le client avant execution. */
    @Transactional
    public Transfer cancel(User user, Long transferId) {
        Transfer transfer = getOwnTransfer(user, transferId);
        if (transfer.getStatus() == TransferStatus.EXECUTE || transfer.getStatus() == TransferStatus.REJETE) {
            throw new BusinessException("Ce virement ne peut plus etre annule (statut : " + transfer.getStatus() + ")");
        }
        transfer.setStatus(TransferStatus.ANNULE);
        transfer.setUpdatedAt(Instant.now());
        transferRepository.save(transfer);
        auditService.log(user.getEmail(), "ANNULATION_VIREMENT", "Transfer", String.valueOf(transfer.getId()),
                "Reference " + transfer.getReference(), null);
        return transfer;
    }

    // ---------------------------------------------------- validation puis execution

    /**
     * Apres validation (OTP B2C ou quorum de signatures B2B) :
     * controle anti-fraude, puis execution immediate ou mise en statut PROGRAMME si differe.
     */
    @Transactional
    public Transfer finalizeAfterValidation(User actor, Transfer transfer) {
        List<FraudAlert> alerts = fraudDetectionService.screen(transfer);
        boolean blocked = fraudDetectionService.isBlocking(alerts);
        alerts.forEach(a -> {
            a.setTransfer(transfer);
            fraudAlertRepository.save(a);
        });
        if (!alerts.isEmpty()) {
            transfer.setFlaggedByFraudRules(true);
        }
        if (blocked) {
            transfer.setStatus(TransferStatus.BLOQUE_FRAUDE);
            transfer.setUpdatedAt(Instant.now());
            transferRepository.save(transfer);
            notificationService.create(transfer.getCreatedBy(), NotificationType.FRAUDE, "Virement bloque (anti-fraude)",
                    "Votre virement " + transfer.getReference() + " a ete bloque pour verification (regle(s) : "
                            + alerts.stream().map(FraudAlert::getRule).distinct().reduce((a, b) -> a + ", " + b).orElse("?") + ").");
            auditService.log(actor.getEmail(), "BLOCAGE_FRAUDE", "Transfer", String.valueOf(transfer.getId()),
                    "Reference " + transfer.getReference(), null);
            return transfer;
        }
        if (transfer.getScheduledDate() != null && transfer.getScheduledDate().isAfter(Instant.now())) {
            transfer.setStatus(TransferStatus.PROGRAMME);
            transfer.setUpdatedAt(Instant.now());
            transferRepository.save(transfer);
            notificationService.create(transfer.getCreatedBy(), NotificationType.VIREMENT_PROGRAMME, "Virement programme",
                    "Votre virement " + transfer.getReference() + " sera execute le " + transfer.getScheduledDate() + ".");
            auditService.log(actor.getEmail(), "PROGRAMMATION_VIREMENT", "Transfer", String.valueOf(transfer.getId()),
                    "Reference " + transfer.getReference() + " pour " + transfer.getScheduledDate(), null);
            return transfer;
        }
        return execute(transfer);
    }

    /** Execution : debit du compte source (verrou pessimiste), credit interne ou routage externe. */
    @Transactional
    public Transfer execute(Transfer transfer) {
        BankAccount source = accountRepository.findByIdForUpdate(transfer.getSourceAccount().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Compte source introuvable"));

        if (source.getStatus() != AccountStatus.ACTIVE) {
            transfer.setStatus(TransferStatus.ECHOUE);
            transfer.setUpdatedAt(Instant.now());
            transferRepository.save(transfer);
            notificationService.create(transfer.getCreatedBy(), NotificationType.VIREMENT_ECHOUE, "Virement echoue",
                    "Le virement " + transfer.getReference() + " a echoue : compte source suspendu ou bloque.");
            return transfer;
        }

        if (source.getBalance().compareTo(transfer.getAmount()) < 0) {
            transfer.setStatus(TransferStatus.ECHOUE);
            transfer.setUpdatedAt(Instant.now());
            transferRepository.save(transfer);
            notificationService.create(transfer.getCreatedBy(), NotificationType.VIREMENT_ECHOUE, "Solde insuffisant",
                    "Le virement " + transfer.getReference() + " a echoue : solde insuffisant au moment de l'execution.");
            auditService.log("SYSTEM", "ECHEC_EXECUTION", "Transfer", String.valueOf(transfer.getId()),
                    "Solde insuffisant", null);
            return transfer;
        }

        source.setBalance(source.getBalance().subtract(transfer.getAmount()));
        accountRepository.save(source);

        // Credit : compte interne explicite, ou IBAN du beneficiaire correspondant a un compte de la banque
        BankAccount internalTarget = null;
        if (transfer.getTargetAccount() != null) {
            internalTarget = accountRepository.findByIdForUpdate(transfer.getTargetAccount().getId()).orElse(null);
        } else if (transfer.getBeneficiary() != null) {
            internalTarget = accountRepository.findByIban(transfer.getBeneficiary().getIban()).orElse(null);
        }

        if (internalTarget != null) {
            // Verrouillage pessimiste cote credit egalement (anti double-depense/ecritures perdues)
            internalTarget = accountRepository.findByIdForUpdate(internalTarget.getId()).orElse(internalTarget);
            internalTarget.setBalance(internalTarget.getBalance().add(transfer.getAmount()));
            accountRepository.save(internalTarget);
            log.info("Virement interne {} : {} debite, {} credite", transfer.getReference(), source.getIban(), internalTarget.getIban());
        } else {
            // Routage vers le SI central (simulation d'echange ISO 20022)
            String creditIban = transfer.getBeneficiary() != null ? transfer.getBeneficiary().getIban()
                    : (transfer.getExternalIban() != null ? transfer.getExternalIban() : "INCONNU");
            try {
                String ack = coreBankingGateway.routeExternalTransfer(transfer.getReference(), source.getIban(),
                        creditIban, transfer.getAmount(), transfer.getCurrency(), transfer.getMotif());
                log.info("Virement externe {} route vers le SI central ({})", transfer.getReference(), ack);
            } catch (IllegalStateException e) {
                // Rejet par le SI : remboursement immediat (tout ou rien)
                source.setBalance(source.getBalance().add(transfer.getAmount()));
                accountRepository.save(source);
                transfer.setStatus(TransferStatus.ECHOUE);
                transfer.setUpdatedAt(Instant.now());
                transferRepository.save(transfer);
                notificationService.create(transfer.getCreatedBy(), NotificationType.VIREMENT_ECHOUE, "Virement rejete par le SI",
                        "Le virement " + transfer.getReference() + " a ete rejete par le systeme central. Les fonds ont ete recreditees.");
                return transfer;
            }
        }

        transfer.setStatus(TransferStatus.EXECUTE);
        transfer.setExecutedAt(Instant.now());
        transfer.setUpdatedAt(Instant.now());
        transferRepository.save(transfer);

        notificationService.create(transfer.getCreatedBy(), NotificationType.VIREMENT_EXECUTE, "Virement execute",
                "Votre virement " + transfer.getReference() + " de " + transfer.getAmount() + " " + transfer.getCurrency()
                        + " a ete execute avec succes.");
        auditService.log("SYSTEM", "EXECUTION_VIREMENT", "Transfer", String.valueOf(transfer.getId()),
                "Reference " + transfer.getReference() + " montant " + transfer.getAmount(), null);
        return transfer;
    }

    // --------------------------------------------------------- virements differes (cron)

    /** Execute les virements differes arrives a echeance (toutes les minutes). */
    @Scheduled(fixedDelayString = "${app.scheduler.fixed-delay-ms:60000}", initialDelay = 10000)
    @Transactional
    public void executeDueScheduledTransfers() {
        List<Transfer> due = transferRepository.findByStatusAndScheduledDateBefore(TransferStatus.PROGRAMME, Instant.now());
        for (Transfer transfer : due) {
            try {
                execute(transfer);
                log.info("Virement differe {} execute", transfer.getReference());
            } catch (Exception e) {
                log.error("Echec de l'execution du virement differe {} : {}", transfer.getReference(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------- requetes

    public Page<Transfer> historyFor(User user, TransferStatus status, Instant from, Instant to, String q, Pageable pageable) {
        // Historique du client : perimetre restreint a ses propres virements
        return transferRepository.search(user.getId(), status, from, to, blankToNull(q), pageable);
    }

    public Transfer getOwnTransfer(User user, Long id) {
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Virement introuvable"));
        boolean privileged = user.getRole() == Role.ADMIN || user.getRole() == Role.AGENT_BANCAIRE;
        if (!privileged) {
            boolean ownerAccount = transfer.getSourceAccount().getOwnerUser() != null
                    && transfer.getSourceAccount().getOwnerUser().getId().equals(user.getId());
            boolean ownerCompany = transfer.getSourceAccount().getOwnerCompany() != null
                    && user.getCompany() != null
                    && transfer.getSourceAccount().getOwnerCompany().getId().equals(user.getCompany().getId());
            boolean createdByUser = transfer.getCreatedBy().getId().equals(user.getId());
            if (!ownerAccount && !ownerCompany && !createdByUser) {
                throw new ResourceNotFoundException("Virement introuvable");
            }
        }
        return transfer;
    }

    public void validateOwnership(User user, BankAccount source) {
        if (user.getRole() == Role.ENTREPRISE) {
            if (user.getCompany() == null
                    || source.getOwnerCompany() == null
                    || !source.getOwnerCompany().getId().equals(user.getCompany().getId())) {
                throw new BusinessException("Ce compte n'appartient pas a votre entreprise");
            }
        } else {
            if (source.getOwnerUser() == null || !source.getOwnerUser().getId().equals(user.getId())) {
                throw new BusinessException("Ce compte ne vous appartient pas");
            }
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
