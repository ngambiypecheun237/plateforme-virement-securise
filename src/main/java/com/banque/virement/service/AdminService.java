package com.banque.virement.service;

import com.banque.virement.domain.entity.*;
import com.banque.virement.domain.enums.*;
import com.banque.virement.exception.BusinessException;
import com.banque.virement.exception.ResourceNotFoundException;
import com.banque.virement.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Fonctions reservees aux equipes internes (ADMIN et AGENT_BANCAIRE) :
 * tableau de bord, gestion des clients/comptes, alertes anti-fraude, audit.
 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final BankAccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final AuditLogRepository auditLogRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final TransferService transferService;

    public AdminService(UserRepository userRepository, BankAccountRepository accountRepository,
                        TransferRepository transferRepository, FraudAlertRepository fraudAlertRepository,
                        AuditLogRepository auditLogRepository, NotificationService notificationService,
                        AuditService auditService, TransferService transferService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.auditLogRepository = auditLogRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.transferService = transferService;
    }

    // ------------------------------------------------------------ statistiques

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalClients", userRepository.countByRole(Role.CLIENT));
        stats.put("totalEntreprises", userRepository.countByRole(Role.ENTREPRISE));
        stats.put("totalComptes", accountRepository.count());
        stats.put("comptesSuspendus", accountRepository.countByStatus(AccountStatus.SUSPENDED)
                + accountRepository.countByStatus(AccountStatus.BLOCKED));
        stats.put("totalVirements", transferRepository.count());
        stats.put("virementsEnAttente", transferRepository.countByStatus(TransferStatus.EN_ATTENTE_CONFIRMATION)
                + transferRepository.countByStatus(TransferStatus.EN_ATTENTE_APPROBATION));
        stats.put("virementsExecutes", transferRepository.countByStatus(TransferStatus.EXECUTE));
        stats.put("virementsBloquesFraude", transferRepository.countByStatus(TransferStatus.BLOQUE_FRAUDE));
        stats.put("montantTotalExecute", nz(transferRepository.totalExecutedAmount()));
        stats.put("devise", "XAF");
        stats.put("alertesFraudeOuvertes", fraudAlertRepository.countByStatus(FraudAlertStatus.OUVERTE));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : transferRepository.countByStatusGrouped()) {
            byStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        stats.put("repartitionParStatut", byStatus);
        return stats;
    }

    public List<Map<String, Object>> dailyVolume(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Map<String, Object>> series = new ArrayList<>();
        for (Object[] row : transferRepository.dailyVolume(since)) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", String.valueOf(row[0]));
            point.put("count", ((Number) row[1]).longValue());
            point.put("volume", (BigDecimal) row[2]);
            series.add(point);
        }
        return series;
    }

    // ------------------------------------------------------ clients et comptes

    public List<User> searchClients(String q, Role role) {
        String query = (q == null || q.isBlank()) ? "" : q.trim();
        return userRepository.search(query, role);
    }

    public User getClient(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client introuvable"));
    }

    public List<BankAccount> searchAccounts(String q, String type, String status) {
        List<BankAccount> base;
        if (q == null || q.isBlank()) {
            base = accountRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        } else {
            base = accountRepository.search(q.trim());
        }
        return base.stream()
                .filter(a -> type == null || type.isBlank() || a.getType().name().equalsIgnoreCase(type))
                .filter(a -> status == null || status.isBlank() || a.getStatus().name().equalsIgnoreCase(status))
                .collect(java.util.stream.Collectors.toList());
    }

    public List<BankAccount> accountsOf(Long userId) {
        return accountRepository.findByOwnerUserId(userId);
    }

    @Transactional
    public BankAccount suspendAccount(Long accountId, String reason, String adminEmail) {
        BankAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));
        account.setStatus(AccountStatus.SUSPENDED);
        accountRepository.save(account);
        notifyOwner(account, "Compte suspendu",
                "Votre compte " + account.getIban() + " a ete suspendu par la banque. Motif : " + nz(reason));
        auditService.log(adminEmail, "SUSPENSION_COMPTE", "BankAccount", String.valueOf(accountId),
                "Motif : " + nz(reason), null);
        return account;
    }

    @Transactional
    public BankAccount activateAccount(Long accountId, String adminEmail) {
        BankAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));
        account.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);
        notifyOwner(account, "Compte reactive",
                "Votre compte " + account.getIban() + " est de nouveau actif.");
        auditService.log(adminEmail, "REACTIVATION_COMPTE", "BankAccount", String.valueOf(accountId), null, null);
        return account;
    }

    // ------------------------------------------------------------ transactions

    public Page<Transfer> searchTransactions(TransferStatus status, Instant from, Instant to, String q, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return transferRepository.search(null, status, from, to, blankToNull(q), pageable);
    }

    // ------------------------------------------------------------- anti-fraude

    public Page<FraudAlert> fraudAlerts(FraudAlertStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return status != null
                ? fraudAlertRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : fraudAlertRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /** Validation manuelle : autorise l'execution d'un virement bloque par le moteur anti-fraude. */
    @Transactional
    public Transfer validateFraudAlert(User reviewer, Long alertId) {
        FraudAlert alert = fraudAlertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alerte introuvable"));
        if (alert.getStatus() != FraudAlertStatus.OUVERTE && alert.getStatus() != FraudAlertStatus.EN_REVUE) {
            throw new BusinessException("Cette alerte est deja traitee");
        }
        alert.setStatus(FraudAlertStatus.VALIDEE);
        alert.setReviewedBy(reviewer);
        alert.setReviewedAt(Instant.now());
        fraudAlertRepository.save(alert);

        Transfer transfer = alert.getTransfer();
        if (transfer.getStatus() == TransferStatus.BLOQUE_FRAUDE) {
            transfer = transferService.execute(transfer);
        }
        notifyFraudReviewed(alert, "validee");
        auditService.log(reviewer.getEmail(), "VALIDATION_FRAUDE", "FraudAlert", String.valueOf(alertId),
                "Reference " + transfer.getReference(), null);
        return transfer;
    }

    /** Rejet manuel : le virement bloque est annule, les fonds ne sont jamais debites. */
    @Transactional
    public Transfer rejectFraudAlert(User reviewer, Long alertId, String comment) {
        FraudAlert alert = fraudAlertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alerte introuvable"));
        if (alert.getStatus() != FraudAlertStatus.OUVERTE && alert.getStatus() != FraudAlertStatus.EN_REVUE) {
            throw new BusinessException("Cette alerte est deja traitee");
        }
        alert.setStatus(FraudAlertStatus.ANNULEE);
        alert.setReviewedBy(reviewer);
        alert.setReviewedAt(Instant.now());
        fraudAlertRepository.save(alert);

        Transfer transfer = alert.getTransfer();
        if (transfer.getStatus() == TransferStatus.BLOQUE_FRAUDE) {
            transfer.setStatus(TransferStatus.REJETE);
            transfer.setUpdatedAt(Instant.now());
            transferRepository.save(transfer);
        }
        notifyFraudReviewed(alert, "rejetee" + (comment != null && !comment.isBlank() ? " : " + comment : ""));
        auditService.log(reviewer.getEmail(), "REJET_FRAUDE", "FraudAlert", String.valueOf(alertId),
                "Reference " + transfer.getReference(), null);
        return transfer;
    }

    public void takeInReview(User reviewer, Long alertId) {
        FraudAlert alert = fraudAlertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alerte introuvable"));
        alert.setStatus(FraudAlertStatus.EN_REVUE);
        alert.setReviewedBy(reviewer);
        alert.setReviewedAt(Instant.now());
        fraudAlertRepository.save(alert);
    }

    // ------------------------------------------------------------------- audit

    public Page<AuditLog> auditLogs(String userEmail, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return (userEmail == null || userEmail.isBlank())
                ? auditLogRepository.findAllByOrderByCreatedAtDesc(pageable)
                : auditLogRepository.findByUserEmailContainingIgnoreCaseOrderByCreatedAtDesc(userEmail.trim(), pageable);
    }

    @Transactional
    public User setUserActive(User admin, Long userId, boolean active, String reason) {
        if (admin.getRole() != Role.ADMIN) {
            throw new BusinessException("Seul un administrateur peut activer ou desactiver un utilisateur");
        }
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));
        if (target.getId().equals(admin.getId())) {
            throw new BusinessException("Vous ne pouvez pas desactiver votre propre compte");
        }
        target.setActive(active);
        target.setUpdatedAt(Instant.now());
        userRepository.save(target);
        if (!active) {
            notificationService.create(target, NotificationType.SECURITE, "Compte desactive",
                    "Votre compte a ete desactive par un administrateur. Motif : " + (reason == null ? "non precise" : reason));
        } else {
            notificationService.create(target, NotificationType.SECURITE, "Compte reactive",
                    "Votre compte a ete reactive par un administrateur.");
        }
        auditService.log(admin.getEmail(), active ? "REACTIVATION_UTILISATEUR" : "DESACTIVATION_UTILISATEUR",
                "User", String.valueOf(userId),
                "Motif : " + (reason == null ? "non precise" : reason), null);
        return target;
    }

    // ------------------------------------------------------------------ roles

    @Transactional
    public User updateRole(User admin, Long userId, Role newRole) {
        if (admin.getRole() != Role.ADMIN) {
            throw new BusinessException("Seul un administrateur peut modifier les roles");
        }
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));
        if (target.getId().equals(admin.getId())) {
            throw new BusinessException("Vous ne pouvez pas modifier votre propre role");
        }
        target.setRole(newRole);
        target.setCompany(newRole == Role.ENTREPRISE ? target.getCompany() : null);
        target.setUpdatedAt(Instant.now());
        userRepository.save(target);
        auditService.log(admin.getEmail(), "CHANGEMENT_ROLE", "User", String.valueOf(userId),
                "Nouveau role : " + newRole, null);
        return target;
    }

    // ------------------------------------------------------------------ divers

    public Map<String, Object> reportsOperations(Instant from, Instant to) {
        Instant effectiveFrom = from != null ? from : Instant.now().minus(30, ChronoUnit.DAYS);
        Instant effectiveTo = to != null ? to : Instant.now();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("periode", Map.of("de", effectiveFrom.toString(), "a", effectiveTo.toString()));
        report.put("serieJournaliere", dailyVolume(30));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : transferRepository.countByStatusGrouped()) {
            byStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        report.put("repartitionParStatut", byStatus);
        report.put("montantTotalExecute", nz(transferRepository.totalExecutedAmount()));
        return report;
    }

    private void notifyOwner(BankAccount account, String title, String message) {
        if (account.getOwnerUser() != null) {
            notificationService.create(account.getOwnerUser(), NotificationType.SECURITE, title, message);
        }
    }

    private void notifyFraudReviewed(FraudAlert alert, String decision) {
        User owner = alert.getTransfer().getCreatedBy();
        notificationService.create(owner, NotificationType.FRAUDE, "Revue de securite",
                "Votre virement " + alert.getTransfer().getReference() + " a ete " + decision + " apres revue de securite.");
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String nz(String value) {
        return value == null || value.isBlank() ? "non precise" : value;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
