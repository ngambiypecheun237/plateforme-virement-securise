package com.banque.virement.config;

import com.banque.virement.domain.entity.*;
import com.banque.virement.domain.enums.*;
import com.banque.virement.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Jeu de donnees de demonstration (profils default et h2, hors production) :
 * 1 admin, 1 agent bancaire, 2 clients B2C, 1 entreprise B2B avec 2 mandataires,
 * comptes, beneficiaires, virements exemples, une alerte fraude ouverte.
 *
 * Comptes de demonstration (voir README) :
 *   admin@banque.cm / Admin@123        (ADMIN)
 *   agent@banque.cm / Agent@123        (AGENT_BANCAIRE)
 *   jean.martin@client.cm / Client@123 (CLIENT - 2 comptes)
 *   marie.nga@client.cm / Client@123   (CLIENT)
 *   directeur@ekondo.cm / Ent@123      (ENTREPRISE - mandataire 1)
 *   comptable@ekondo.cm / Ent@123      (ENTREPRISE - mandataire 2)
 */
@Component
@Profile("!prod")
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final BankAccountRepository accountRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final TransferRepository transferRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final NotificationRepository notificationRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, CompanyRepository companyRepository,
                           BankAccountRepository accountRepository, BeneficiaryRepository beneficiaryRepository,
                           TransferRepository transferRepository, FraudAlertRepository fraudAlertRepository,
                           NotificationRepository notificationRepository, AuditLogRepository auditLogRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.accountRepository = accountRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.transferRepository = transferRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.notificationRepository = notificationRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.existsByEmail("admin@banque.cm")) {
            log.info("Donnees de demonstration deja presentes, seed ignore");
            return;
        }
        log.info("Initialisation des donnees de demonstration...");

        // ------------------------------------------------- equipe banque
        User admin = userRepository.save(User.builder()
                .firstName("Alice").lastName("NGONO (Admin)")
                .email("admin@banque.cm").phone("+237600000001")
                .passwordHash(passwordEncoder.encode("Admin@123"))
                .role(Role.ADMIN).active(true).build());

        User agent = userRepository.save(User.builder()
                .firstName("Boris").lastName("TCHOUMBA (Agent)")
                .email("agent@banque.cm").phone("+237600000002")
                .passwordHash(passwordEncoder.encode("Agent@123"))
                .role(Role.AGENT_BANCAIRE).active(true).build());

        // ------------------------------------------------- entreprise B2B
        Company ekondo = companyRepository.save(Company.builder()
                .name("SARL EKONDO & FILS")
                .registrationNumber("RC/DLA/2020/B/001234")
                .taxId("P123456789012B")
                .requiredApprovals(2)
                .singleApprovalThreshold(1_000_000.0)
                .build());

        User directeur = userRepository.save(User.builder()
                .firstName("Cedric").lastName("EKONDO (DG)")
                .email("directeur@ekondo.cm").phone("+237600000010")
                .passwordHash(passwordEncoder.encode("Ent@123"))
                .role(Role.ENTREPRISE).active(true).company(ekondo).build());

        User comptable = userRepository.save(User.builder()
                .firstName("Denise").lastName("EKONDO (Comptable)")
                .email("comptable@ekondo.cm").phone("+237600000011")
                .passwordHash(passwordEncoder.encode("Ent@123"))
                .role(Role.ENTREPRISE).active(true).company(ekondo).build());

        BankAccount comptePro = accountRepository.save(BankAccount.builder()
                .iban("CM7630000007778889990001122").type(AccountType.PRO)
                .balance(new BigDecimal("25000000")).currency("XAF")
                .status(AccountStatus.ACTIVE)
                .dailyLimit(new BigDecimal("10000000")).weeklyLimit(new BigDecimal("40000000"))
                .ownerCompany(ekondo).build());

        // ------------------------------------------------- clients B2C
        User jean = userRepository.save(User.builder()
                .firstName("Jean").lastName("MARTIN")
                .email("jean.martin@client.cm").phone("+237677123456")
                .passwordHash(passwordEncoder.encode("Client@123"))
                .role(Role.CLIENT).active(true).build());

        User marie = userRepository.save(User.builder()
                .firstName("Marie").lastName("NGA")
                .email("marie.nga@client.cm").phone("+237699654321")
                .passwordHash(passwordEncoder.encode("Client@123"))
                .role(Role.CLIENT).active(true).build());

        BankAccount jeanCourant = accountRepository.save(BankAccount.builder()
                .iban("CM3810000001123456789012345").type(AccountType.COURANT)
                .balance(new BigDecimal("12000000")).currency("XAF").status(AccountStatus.ACTIVE)
                .dailyLimit(new BigDecimal("3000000")).weeklyLimit(new BigDecimal("10000000"))
                .ownerUser(jean).build());

        BankAccount jeanEpargne = accountRepository.save(BankAccount.builder()
                .iban("CM3420000005554443332221111").type(AccountType.EPARGNE)
                .balance(new BigDecimal("2300000")).currency("XAF").status(AccountStatus.ACTIVE)
                .dailyLimit(new BigDecimal("1000000")).weeklyLimit(new BigDecimal("3000000"))
                .ownerUser(jean).build());

        BankAccount marieCourant = accountRepository.save(BankAccount.builder()
                .iban("CM6010000009876543210987654").type(AccountType.COURANT)
                .balance(new BigDecimal("1200000")).currency("XAF").status(AccountStatus.ACTIVE)
                .dailyLimit(new BigDecimal("2000000")).weeklyLimit(new BigDecimal("6000000"))
                .ownerUser(marie).build());

        // ------------------------------------------------- beneficiaires de Jean
        Beneficiary benefMarie = beneficiaryRepository.save(Beneficiary.builder()
                .owner(jean).name("NGA Marie (compte COURANT)")
                .iban("CM6010000009876543210987654").bankName("BANQUE DE DEMONSTRATION").country("CM")
                .status(BeneficiaryStatus.ACTIVE).verified(true)
                .createdAt(Instant.now().minus(30, ChronoUnit.DAYS))
                .availableAt(Instant.now().minus(29, ChronoUnit.DAYS)).build());

        Beneficiary benefExterne = beneficiaryRepository.save(Beneficiary.builder()
                .owner(jean).name("FOTSING Carolle (famille, Paris)")
                .iban("FR4812345678901234567890123").bankName("BNP PARIBAS").bic("BNPAFRPP").country("FR")
                .status(BeneficiaryStatus.ACTIVE).verified(true)
                .createdAt(Instant.now().minus(15, ChronoUnit.DAYS))
                .availableAt(Instant.now().minus(14, ChronoUnit.DAYS)).build());

        beneficiaryRepository.save(Beneficiary.builder()
                .owner(jean).name("NOUVEAU Beneficiaire (carence 48h)")
                .iban("GA7410000010000000000000123").bankName("BGFI BANK").country("GA")
                .status(BeneficiaryStatus.ACTIVE).verified(true)
                .createdAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .availableAt(Instant.now().plus(46, ChronoUnit.HOURS)).build());

        // ------------------------------------------------- virements d'exemple (jean)
        seedExecutedTransfer(jeanCourant, marieCourant, benefMarie, jean,
                new BigDecimal("150000"), "TRF-SEED-000001", "Remboursement restaurant", 12);
        seedExecutedTransfer(jeanCourant, marieCourant, benefMarie, jean,
                new BigDecimal("75000"), "TRF-SEED-000002", "Participation cadeau anniversaire", 5);
        seedExecutedTransfer(jeanEpargne, null, benefExterne, jean,
                new BigDecimal("500000"), "TRF-SEED-000003", "Virement familial Paris", 20);

        // ------------------------------------------------- virement bloque fraude (demo admin)
        Transfer blocked = Transfer.builder()
                .reference("TRF-SEED-000900")
                .type(TransferType.VIREMENT_UNITAIRE_EXTERNE)
                .status(TransferStatus.BLOQUE_FRAUDE)
                .sourceAccount(jeanCourant)
                .beneficiary(benefExterne)
                .amount(new BigDecimal("8500000"))
                .currency("XAF")
                .motif("Transfert exceptionnel famille")
                .createdBy(jean)
                .createdAt(Instant.now().minus(3, ChronoUnit.HOURS))
                .flaggedByFraudRules(true)
                .approvalCount(0)
                .build();
        blocked = transferRepository.save(blocked);
        fraudAlertRepository.save(FraudAlert.builder()
                .transfer(blocked).rule("MONTANT_ELEVE").severity(FraudSeverity.HAUTE)
                .status(FraudAlertStatus.OUVERTE)
                .details("Virement de 8 500 000 XAF vers le beneficiaire FOTSING Carolle (seuil d'alerte depasse : 5 000 000 XAF)")
                .build());

        // ------------------------------------------------- virement B2B en attente (demo double signature)
        Beneficiary benefFournisseur = beneficiaryRepository.findByOwnerIdAndIban(directeur.getId(), "FR4812345678901234567890123")
                .orElse(null);
        if (benefFournisseur == null) {
            benefFournisseur = beneficiaryRepository.save(Beneficiary.builder()
                    .owner(directeur).name("FOURNISSEUR SCI LE PALMIER")
                    .iban("FR4812345678901234567890123").bankName("BNP PARIBAS").bic("BNPAFRPP").country("FR")
                    .status(BeneficiaryStatus.ACTIVE).verified(true)
                    .createdAt(Instant.now().minus(60, ChronoUnit.DAYS))
                    .availableAt(Instant.now().minus(59, ChronoUnit.DAYS)).build());
        }
        Transfer b2bPending = Transfer.builder()
                .reference("TRF-SEED-000950")
                .type(TransferType.VIREMENT_UNITAIRE_EXTERNE)
                .status(TransferStatus.EN_ATTENTE_APPROBATION)
                .sourceAccount(comptePro)
                .beneficiary(benefFournisseur)
                .amount(new BigDecimal("3500000"))
                .currency("XAF")
                .motif("Facture fournisseur n°2026-118")
                .createdBy(comptable)
                .createdAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .approvalCount(0)
                .build();
        transferRepository.save(b2bPending);

        // ------------------------------------------------- notifications & audit
        notificationRepository.save(Notification.builder()
                .user(admin).type(NotificationType.FRAUDE)
                .title("Alerte anti-fraude")
                .message("Un virement de 8 500 000 XAF est bloque et requiert votre revue (regle MONTANT_ELEVE).")
                .build());
        notificationRepository.save(Notification.builder()
                .user(directeur).type(NotificationType.APPROBATION_REQUISE)
                .title("Signature requise")
                .message("Le virement TRF-SEED-000950 (3 500 000 XAF) requiert votre signature.")
                .build());
        notificationRepository.save(Notification.builder()
                .user(jean).type(NotificationType.CONNEXION)
                .title("Nouvelle connexion")
                .message("Une connexion a votre compte a ete enregistree.")
                .build());

        auditLogRepository.save(AuditLog.builder()
                .userEmail("system").action("SEED_DATA").entityType("System").entityId("seed")
                .details("Initialisation des donnees de demonstration (admin, agent, 2 clients, 1 entreprise B2B)")
                .ipAddress("-").build());

        log.info("Donnees de demonstration chargees : admin@banque.cm / agent@banque.cm / jean.martin@client.cm / directeur@ekondo.cm");
    }

    private void seedExecutedTransfer(BankAccount source, BankAccount target, Beneficiary beneficiary, User creator,
                                      BigDecimal amount, String reference, String motif, int daysAgo) {
        Transfer transfer = Transfer.builder()
                .reference(reference)
                .type(target != null ? TransferType.VIREMENT_UNITAIRE_INTERNE : TransferType.VIREMENT_UNITAIRE_EXTERNE)
                .status(TransferStatus.EXECUTE)
                .sourceAccount(source)
                .targetAccount(target)
                .beneficiary(beneficiary)
                .amount(amount)
                .currency("XAF")
                .motif(motif)
                .createdBy(creator)
                .createdAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS))
                .executedAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS).plusSeconds(45))
                .approvalCount(0)
                .build();
        transferRepository.save(transfer);
        // Debit du solde simule
        source.setBalance(source.getBalance().subtract(amount));
        accountRepository.save(source);
        if (target != null) {
            target.setBalance(target.getBalance().add(amount));
            accountRepository.save(target);
        }
    }
}
