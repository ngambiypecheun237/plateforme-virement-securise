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
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Jeu de donnees de demonstration (profils default et h2, hors production).
 *
 * Contenu :
 *  - 3 ADMINISTRATEURS et 4 AGENTS BANCAIRES (plus les 2 comptes historiques)
 *  - 100 comptes repartis : 48 COURANT particuliers, 26 EPARGNE, 25 PRO (entreprises), 4 historiques
 *  - ~250 virements historiques (EXECUTE) + des cas d'echec / blocage fraude
 *  - 3 alertes anti-fraude ouvertes, notifications et un journal d'audit riche
 *  - IBAN generes avec cle modulo 97 valide
 *
 * Comptes de demonstration :
 *   admin@banque.cm            / Admin@123  (Alice NGONO - Directrice des Operations)
 *   patrick.mbala@banque.cm    / Admin@123  (Administrateur SI)
 *   sandra.tchami@banque.cm    / Admin@123  (Responsable Conformite)
 *   agent@banque.cm            / Agent@123  (Analyste anti-fraude)
 *   agent.backoffice@banque.cm / Agent@123  (Back-office)
 *   agent.caisse@banque.cm     / Agent@123  (Agent de caisse)
 *   agent.conformite@banque.cm / Agent@123  (Controleur LBC/FT)
 *   jean.martin@client.cm      / Client@123 (Client particulier)
 *   directeur@ekondo.cm        / Ent@123    (Mandataire entreprise, double signature)
 *   comptable@ekondo.cm        / Ent@123    (Comptable entreprise)
 */
@Component
@Profile("!prod")
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final long SEED = 20261104L;

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final BankAccountRepository accountRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final TransferRepository transferRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final NotificationRepository notificationRepository;
    private final AuditLogRepository auditLogRepository;
    private final TransferBatchRepository batchRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, CompanyRepository companyRepository,
                           BankAccountRepository accountRepository, BeneficiaryRepository beneficiaryRepository,
                           TransferRepository transferRepository, FraudAlertRepository fraudAlertRepository,
                           NotificationRepository notificationRepository, AuditLogRepository auditLogRepository,
                           TransferBatchRepository batchRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.accountRepository = accountRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.transferRepository = transferRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.notificationRepository = notificationRepository;
        this.auditLogRepository = auditLogRepository;
        this.batchRepository = batchRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ------------------------------------------------------------------ IBAN

    private final Set<String> usedIbans = new HashSet<>();
    private final Random random = new Random(SEED);

    /** Cree un IBAN camerounais valide (CM + cle modulo 97 + 23 chiffres). */
    private String nextValidIban() {
        while (true) {
            StringBuilder bban = new StringBuilder();
            for (int i = 0; i < 23; i++) {
                bban.append(random.nextInt(10));
            }
            String check = checkDigits("CM", bban.toString());
            String iban = "CM" + check + bban;
            if (usedIbans.add(iban)) {
                return iban;
            }
        }
    }

    private static String checkDigits(String country, String bban) {
        String rearranged = bban + country + "00";
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            numeric.append(Character.isLetter(c) ? (c - 'A' + 10) : c - '0');
        }
        int remainder = new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue();
        return String.format("%02d", 98 - remainder);
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.existsByEmail("admin@banque.cm")) {
            log.info("Donnees de demonstration deja presentes, seed ignore");
            return;
        }
        log.info("Initialisation des donnees de demonstration (100 comptes)...");

        // ============================ EQUIPE BANQUE (3 admins + 4 agents) ============================
        User adminHistorique = createUser("Alice", "NGONO", "admin@banque.cm", "+237600000001",
                "Admin@123", Role.ADMIN, null);
        User adminSi = createUser("Patrick", "MBALA", "patrick.mbala@banque.cm", "+237600000012",
                "Admin@123", Role.ADMIN, null);
        User adminConformite = createUser("Sandra", "TCHAMI", "sandra.tchami@banque.cm", "+237600000013",
                "Admin@123", Role.ADMIN, null);

        User agentHistorique = createUser("Boris", "TCHOUMBA", "agent@banque.cm", "+237600000002",
                "Agent@123", Role.AGENT_BANCAIRE, null);
        createUser("Laurette", "KAMGA", "agent.backoffice@banque.cm", "+237600000014",
                "Agent@123", Role.AGENT_BANCAIRE, null);
        createUser("Olivier", "FOUDA", "agent.caisse@banque.cm", "+237600000015",
                "Agent@123", Role.AGENT_BANCAIRE, null);
        createUser("Nadia", "ESSONO", "agent.conformite@banque.cm", "+237600000016",
                "Agent@123", Role.AGENT_BANCAIRE, null);

        // ============================ ENTREPRISES B2B (EKONDO + 25 generees) ============================
        Company ekondo = companyRepository.save(Company.builder()
                .name("SARL EKONDO & FILS")
                .registrationNumber("RC/DLA/2020/B/001234")
                .taxId("P123456789012B")
                .requiredApprovals(2)
                .singleApprovalThreshold(1_000_000.0)
                .build());

        User directeur = createUser("Cedric", "EKONDO (DG)", "directeur@ekondo.cm", "+237600000010",
                "Ent@123", Role.ENTREPRISE, ekondo);
        User comptable = createUser("Denise", "EKONDO (Comptable)", "comptable@ekondo.cm", "+237600000011",
                "Ent@123", Role.ENTREPRISE, ekondo);

        BankAccount comptePro = accountRepository.save(BankAccount.builder()
                .iban("CM7630000007778889990001122").type(AccountType.PRO)
                .balance(new BigDecimal("25000000")).currency("XAF")
                .status(AccountStatus.ACTIVE)
                .dailyLimit(new BigDecimal("10000000")).weeklyLimit(new BigDecimal("40000000"))
                .ownerCompany(ekondo).build());
        usedIbans.add(comptePro.getIban());

        String[][] entreprises = {
                {"ETS LE PHOENIX", "RC/DLA/2019/B/000123", "P001122334455A"},
                {"SARL MBOA LOGISTICS", "RC/DLA/2021/B/000456", "P001122334466B"},
                {"GIE AGRICOOP CENTRE", "RC/NGA/2018/B/000789", "P001122334477C"},
                {"ETS LA PERLE DU LITTORAL", "RC/DLA/2022/B/000321", "P001122334488D"},
                {"SARL SOMACO", "RC/YDE/2020/B/000654", "P001122334499E"},
                {"SCI LES PALMIERS", "RC/DLA/2017/B/000987", "P001122334500F"},
                {"ETS MBARGA TRANSPORT", "RC/DLA/2023/B/000147", "P001122334511G"},
                {"SARL KAMGA & FILS", "RC/BAF/2019/B/000258", "P001122334522H"},
                {"ETS LA BOUTIQUE CLASSE", "RC/DLA/2021/B/000369", "P001122334533I"},
                {"SARL NGO TECH", "RC/YDE/2022/B/000741", "P001122334544J"},
                {"GIE PALME AFRICAINE", "RC/DLA/2016/B/000852", "P001122334555K"},
                {"ETS COASTAL TRADING", "RC/DLA/2018/B/000963", "P001122334566L"},
                {"SARL ATECA", "RC/YDE/2023/B/000159", "P001122334577M"},
                {"SCI BELINGA IMMO", "RC/DLA/2015/B/000357", "P001122334588N"},
                {"ETS FERME DU SUD", "RC/EBO/2020/B/000468", "P001122334599P"},
                {"SARL CENTRE CINEMA", "RC/YDE/2024/B/000579", "P001122334600Q"},
                {"ETS NOUVELLE MODE", "RC/DLA/2022/B/000681", "P001122334611R"},
                {"SARL HAMSI NEGOCE", "RC/DLA/2021/B/000792", "P001122334622S"},
                {"ETS DIAMANT BOIS", "RC/EBO/2019/B/000813", "P001122334633T"},
                {"SARL LITTORAL PECHE", "RC/DLA/2018/B/000924", "P001122334644U"}
        };

        record EmpresaAccount(Company company, User manager, BankAccount account) {}
        List<EmpresaAccount> entrepriseAccounts = new ArrayList<>();
        String[] managerFirst = {"Serge", "Armand", "Brice", "Fabrice", "Yannick", "Herve", "Christian", "Arnaud",
                "Ghislain", "Landry", "Alain", "Elvis", "Stephane", "Rodrigue", "Joel", "Martial", "Eric", "Patrick",
                "Olivier", "Landry", "Cedric", "Boris", "Marius", "Junior", "David"};

        for (int i = 0; i < entreprises.length; i++) {
            String name = entreprises[i][0];
            Company company = companyRepository.save(Company.builder()
                    .name(name)
                    .registrationNumber(entreprises[i][1])
                    .taxId(entreprises[i][2])
                    .requiredApprovals(1)   // une signature suffit sous seuil (configurable)
                    .singleApprovalThreshold(5_000_000.0)
                    .build());
            String first = managerFirst[i % managerFirst.length];
            String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "");
            User manager = createUser(first, "GERANT " + slug.toUpperCase(Locale.ROOT).substring(0, Math.min(20, slug.length())),
                    "gerant." + slug + "@entreprise.cm",
                    "+237655" + String.format("%06d", 100000 + i),
                    "Ent@123", Role.ENTREPRISE, company);
            BigDecimal balance = BigDecimal.valueOf(5_000_000L + random.nextInt(75_000_000));
            BankAccount account = accountRepository.save(BankAccount.builder()
                    .iban(nextValidIban()).type(AccountType.PRO)
                    .balance(balance).currency("XAF")
                    .status(randomAccountStatus(88, 4, 4, i))
                    .dailyLimit(new BigDecimal("15000000")).weeklyLimit(new BigDecimal("60000000"))
                    .ownerCompany(company).build());
            entrepriseAccounts.add(new EmpresaAccount(company, manager, account));
        }

        // ============================ PARTICULIERS (48 COURANT + 28 EPARGNE) ============================
        String[] maleFirst = {"Jean", "Marius", "Patrick", "Boris", "Cedric", "Herve", "Armand", "Fabrice", "Yannick",
                "Alain", "Christian", "Elvis", "Stephane", "Rodrigue", "Brice", "Arnaud", "Ghislain", "Landry",
                "Aurele", "Junior", "Emmanuel", "Severin", "Landry", "Olivier", "Martial"};
        String[] femaleFirst = {"Marie", "Sylvie", "Carole", "Irene", "Belinda", "Carine", "Diane", "Flore",
                "Gisele", "Larissa", "Martine", "Nadia", "Olivia", "Prisca", "Raissa", "Sandrine", "Tatiana",
                "Vanessa", "Wilna", "Yvette", "Clarisse", "Estelle", "Hortense"};
        String[] lastNames = {"Ngono", "Mballa", "Tchoupo", "Kamga", "Ekindi", "Mbarga", "Ateba", "Essomba",
                "Mvondo", "Oyono", "Bello", "Aboubakar", "Hamadou", "Oumarou", "Ndi", "Fru", "Tabi",
                "Kuate", "Simo", "Tankou", "Ngo", "Fotso", "Kegne", "Tchami", "Njoya", "Belinga", "Moukoko",
                "Etoa", "Afana", "Eyinga", "Mendo", "Bissohong", "Ntamack", "Onana", "Zambo", "Ebongue",
                "Mengue", "Ntyam", "Biya", "Eyenga"};

        record ClientAccounts(User user, BankAccount courant, BankAccount epargne) {}
        List<ClientAccounts> clients = new ArrayList<>();
        int clientIndex = 0;
        for (int i = 0; i < 48; i++) {
            boolean male = i % 2 == 0;
            String first = male ? maleFirst[(i * 5 + 3) % maleFirst.length] : femaleFirst[(i * 7 + 2) % femaleFirst.length];
            String last = lastNames[(i * 11 + 5) % lastNames.length];
            String email = (first + "." + last + clientIndex).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "") + "@client.cm";
            clientIndex++;
            User client = createUser(first, last.toUpperCase(Locale.ROOT), email,
                    "+237677" + String.format("%06d", 200000 + i),
                    "Client@123", Role.CLIENT, null);

            BankAccount courant = accountRepository.save(BankAccount.builder()
                    .iban(nextValidIban()).type(AccountType.COURANT)
                    .balance(randomBalance(150_000, 8_000_000)).currency("XAF")
                    .status(randomAccountStatus(40, i))
                    .dailyLimit(new BigDecimal(List.of(1000000, 2000000, 3000000, 5000000).get(i % 4)))
                    .weeklyLimit(new BigDecimal(List.of(4000000, 8000000, 12000000, 20000000).get(i % 4)))
                    .ownerUser(client).build());

            // 28 clients sur 48 ont aussi un livret epargne
            BankAccount epargne = null;
            if (i < 28) {
                epargne = accountRepository.save(BankAccount.builder()
                        .iban(nextValidIban()).type(AccountType.EPARGNE)
                        .balance(randomBalance(500_000, 15_000_000)).currency("XAF")
                        .status(AccountStatus.ACTIVE)
                        .dailyLimit(new BigDecimal("1000000")).weeklyLimit(new BigDecimal("3000000"))
                        .ownerUser(client).build());
            }
            clients.add(new ClientAccounts(client, courant, epargne));
        }

        // ============================ CLIENTS HISTORIQUES (jean, marie) ============================
        User jean = createUser("Jean", "MARTIN", "jean.martin@client.cm", "+237677123456",
                "Client@123", Role.CLIENT, null);
        User marie = createUser("Marie", "NGA", "marie.nga@client.cm", "+237699654321",
                "Client@123", Role.CLIENT, null);

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
        usedIbans.add(jeanCourant.getIban());
        usedIbans.add(jeanEpargne.getIban());
        usedIbans.add(marieCourant.getIban());

        // ============================ BENEFICIAIRES DE JEAN ============================
        Beneficiary benefMarie = beneficiaryRepository.save(Beneficiary.builder()
                .owner(jean).name("NGA Marie (compte COURANT)")
                .iban("CM6010000009876543210987654").bankName("BANQUE DEMONSTRATION").country("CM")
                .status(BeneficiaryStatus.ACTIVE).verified(true)
                .createdAt(Instant.now().minus(30, ChronoUnit.DAYS))
                .availableAt(Instant.now().minus(29, ChronoUnit.DAYS)).build());

        Beneficiary benefExterne = beneficiaryRepository.save(Beneficiary.builder()
                .owner(jean).name("FOTSING Carolle (famille, Paris)")
                .iban("FR4812345678901234567890123").bankName("BNP PARIBAS").bic("BNPAFRPP").country("FR")
                .status(BeneficiaryStatus.ACTIVE).verified(true)
                .createdAt(Instant.now().minus(15, ChronoUnit.DAYS))
                .availableAt(Instant.now().minus(14, ChronoUnit.DAYS)).build());

        Beneficiary benefCarence = beneficiaryRepository.save(Beneficiary.builder()
                .owner(jean).name("NOUVEAU Beneficiaire (carence 48h)")
                .iban("GA7410000010000000000000123").bankName("BGFI BANK").country("GA")
                .status(BeneficiaryStatus.ACTIVE).verified(true)
                .createdAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .availableAt(Instant.now().plus(46, ChronoUnit.HOURS)).build());
        log.info("Beneficiaires de demonstration : {}", benefMarie.getName());

        // ============================ VIREMENTS HISTORIQUES (~250) ============================
        List<BankAccount> allAccounts = accountRepository.findAll();
        List<Transfer> historical = new ArrayList<>();
        String[] motifs = {"Loyer", "Salaire", "Facture electricite", "Transfert familial", "Frais de scolarite",
                "Achat marchandises", "Remboursement", "Prestation de service", "Assurance", "Cotisation"};

        for (int k = 0; k < 250; k++) {
            BankAccount source = allAccounts.get(random.nextInt(allAccounts.size()));
            if (source.getStatus() != AccountStatus.ACTIVE) {
                continue;
            }
            // Un virement vers un compte interne au hasard (hors self)
            BankAccount target = allAccounts.get(random.nextInt(allAccounts.size()));
            if (target.getId().equals(source.getId())) {
                continue;
            }
            BigDecimal amount = BigDecimal.valueOf(15_000L + random.nextInt(2_485_000));
            long daysAgo = random.nextInt(90);
            TransferStatus status = pickStatus(k);
            User creator = source.getOwnerUser() != null ? source.getOwnerUser()
                    : (source.getOwnerCompany() != null
                        ? userRepository.findByCompanyId(source.getOwnerCompany().getId()).stream().findFirst().orElse(jean)
                        : jean);

            Transfer t = Transfer.builder()
                    .reference("TRF-H-" + String.format("%05d", k))
                    .type(TransferType.VIREMENT_UNITAIRE_INTERNE)
                    .status(status)
                    .sourceAccount(source)
                    .targetAccount(target)
                    .amount(amount)
                    .currency("XAF")
                    .motif(motifs[k % motifs.length])
                    .createdBy(creator)
                    .createdAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS).minus(random.nextInt(14), ChronoUnit.HOURS))
                    .approvalCount(creator.getRole() == Role.ENTREPRISE ? 1 : 0)
                    .build();
            if (status == TransferStatus.EXECUTE) {
                t.setExecutedAt(t.getCreatedAt().plusSeconds(30 + random.nextInt(120)));
                // Debit / credit reels pour que les soldes soient coherents
                source.setBalance(source.getBalance().subtract(amount));
                target.setBalance(target.getBalance().add(amount));
            }
            historical.add(t);
        }
        transferRepository.saveAll(historical);
        accountRepository.saveAll(allAccounts);

        // ============================ CAS METIER EMBLEMATIQUES ============================
        // Jean : virements exemples (pour son historique)
        seedExecutedTransfer(jeanCourant, marieCourant, benefMarie, jean,
                new BigDecimal("150000"), "TRF-SEED-000001", "Remboursement restaurant", 12);
        seedExecutedTransfer(jeanCourant, marieCourant, benefMarie, jean,
                new BigDecimal("75000"), "TRF-SEED-000002", "Participation cadeau anniversaire", 5);
        seedExecutedTransfer(jeanEpargne, null, benefExterne, jean,
                new BigDecimal("500000"), "TRF-SEED-000003", "Virement familial Paris", 20);

        // Virement bloque fraude (demo admin)
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

        // Deux autres alertes ouvertes pour alimenter la file de revue
        seedOpenFraudAlert(allAccounts, "VELOCITE_10MIN", FraudSeverity.HAUTE,
                "4 virements inities depuis le meme compte en moins de 10 minutes", 1);
        seedOpenFraudAlert(allAccounts, "BENEFICIAIRE_RECENT", FraudSeverity.HAUTE,
                "Virement de 2 300 000 XAF vers un beneficiaire ajoute il y a moins de 7 jours", 2);

        // Virement B2B en attente de double signature (demo EKONDO)
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
        transferRepository.save(Transfer.builder()
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
                .build());

        // Remise d'entreprise deja executee (demo batch salaires)
        TransferBatch batch = TransferBatch.builder()
                .reference("BAT-SEED-000001")
                .fileName("salaires-octobre-2026.csv")
                .company(ekondo)
                .uploadedBy(comptable)
                .sourceAccount(comptePro)
                .totalCount(3).successCount(3).failedCount(0)
                .status(BatchStatus.TERMINE)
                .createdAt(Instant.now().minus(6, ChronoUnit.DAYS))
                .completedAt(Instant.now().minus(6, ChronoUnit.DAYS).plusSeconds(120))
                .build();
        batch = batchRepository.save(batch);
        BigDecimal[] salaires = {new BigDecimal("850000"), new BigDecimal("1200000"), new BigDecimal("950000")};
        String[] salairesRefs = {"TRF-SEED-000961", "TRF-SEED-000962", "TRF-SEED-000963"};
        for (int i = 0; i < salaires.length; i++) {
            transferRepository.save(Transfer.builder()
                    .reference(salairesRefs[i])
                    .type(TransferType.VIREMENT_MASSE)
                    .status(TransferStatus.EXECUTE)
                    .sourceAccount(comptePro)
                    .amount(salaires[i])
                    .currency("XAF")
                    .motif("Salaire octobre 2026 - lot " + batch.getReference())
                    .batch(batch)
                    .createdBy(comptable)
                    .createdAt(Instant.now().minus(6, ChronoUnit.DAYS))
                    .executedAt(Instant.now().minus(6, ChronoUnit.DAYS).plusSeconds(60))
                    .approvalCount(2)
                    .build());
            comptePro.setBalance(comptePro.getBalance().subtract(salaires[i]));
        }
        accountRepository.save(comptePro);

        // ============================ NOTIFICATIONS (equipe + jean) ============================
        notificationRepository.saveAll(List.of(
                notif(adminHistorique, NotificationType.FRAUDE, "Alerte anti-fraude critique",
                        "Un virement de 8 500 000 XAF (TRF-SEED-000900) est bloque et requiert votre revue."),
                notif(adminHistorique, NotificationType.FRAUDE, "Revue requise",
                        "2 autres alertes ouvertes : velocite et beneficiaire recent."),
                notif(adminHistorique, NotificationType.SECURITE, "Connexion detectee",
                        "Une connexion depuis un nouvel appareil a ete enregistree pour votre compte."),
                notif(agentHistorique, NotificationType.FRAUDE, "File anti-fraude",
                        "3 alertes ouvertes vous attendent dans le tableau de bord."),
                notif(directeur, NotificationType.APPROBATION_REQUISE, "Signature requise",
                        "Le virement TRF-SEED-000950 (3 500 000 XAF) requiert votre signature."),
                notif(jean, NotificationType.CONNEXION, "Nouvelle connexion",
                        "Une connexion a votre compte a ete enregistree."),
                notif(jean, NotificationType.VIREMENT_EXECUTE, "Virement execute",
                        "Votre virement TRF-SEED-000002 a ete execute avec succes.")
        ));

        // ============================ JOURNAL D'AUDIT (extraits) ============================
        String[] auditActions = {"SEED_DATA", "LOGIN_SUCCES", "CREATION_VIREMENT", "EXECUTION_VIREMENT",
                "AJOUT_BENEFICIAIRE", "LOGIN_SUCCES", "EXECUTION_VIREMENT", "VALIDATION_FRAUDE"};
        for (int i = 0; i < 40; i++) {
            auditLogRepository.save(AuditLog.builder()
                    .userEmail(i % 3 == 0 ? "jean.martin@client.cm" : (i % 3 == 1 ? "comptable@ekondo.cm" : "agent@banque.cm"))
                    .action(auditActions[i % auditActions.length])
                    .entityType(i % 3 == 1 ? "Transfer" : "User")
                    .entityId(String.valueOf(100 + i))
                    .details("Entree d'audit de demonstration n°" + i)
                    .ipAddress("197.234." + (10 + i % 200) + "." + (i % 250 + 1))
                    .createdAt(Instant.now().minus(random.nextInt(60), ChronoUnit.DAYS))
                    .build());
        }

        log.info("Donnees chargees : 3 admins, 4 agents, {} clients particuliers, {} entreprises, {} comptes au total",
                clients.size(), entrepriseAccounts.size(), accountRepository.count());
    }

    // ------------------------------------------------------------------ helpers

    private User createUser(String firstName, String lastName, String email, String phone,
                            String password, Role role, Company company) {
        return userRepository.save(User.builder()
                .firstName(firstName).lastName(lastName)
                .email(email.toLowerCase(Locale.ROOT)).phone(phone)
                .passwordHash(passwordEncoder.encode(password))
                .role(role).active(true).company(company).build());
    }

    private BigDecimal randomBalance(long min, long max) {
        return BigDecimal.valueOf(min + random.nextInt((int) (max - min)));
    }

    private AccountStatus randomAccountStatus(int roll, int salt) {
        // ~90 % ACTIVE par l'increment, deterministe via hash du salt
        int r = Math.floorMod(salt * 37 + 11, 100);
        if (r < 88) {
            return AccountStatus.ACTIVE;
        }
        if (r < 94) {
            return AccountStatus.SUSPENDED;
        }
        return AccountStatus.BLOCKED;
    }

    private AccountStatus randomAccountStatus(int activePct, int suspendedPct, int blockedPct, int salt) {
        int r = Math.floorMod(salt * 53 + 17, 100);
        if (r < activePct) {
            return AccountStatus.ACTIVE;
        }
        if (r < activePct + suspendedPct) {
            return AccountStatus.SUSPENDED;
        }
        return AccountStatus.BLOCKED;
    }

    private TransferStatus pickStatus(int k) {
        int r = k % 20;
        if (r < 15) return TransferStatus.EXECUTE;         // 75%
        if (r < 16) return TransferStatus.ECHOUE;          // 5%
        if (r < 17) return TransferStatus.REJETE;          // 5%
        if (r < 18) return TransferStatus.BLOQUE_FRAUDE;   // 5%
        if (r < 19) return TransferStatus.EXECUTE;         // 5%
        return TransferStatus.ANNULE;                      // 5%
    }

    private Notification notif(User user, NotificationType type, String title, String message) {
        return Notification.builder()
                .user(user).type(type).title(title).message(message)
                .build();
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
        source.setBalance(source.getBalance().subtract(amount));
        accountRepository.save(source);
        if (target != null) {
            target.setBalance(target.getBalance().add(amount));
            accountRepository.save(target);
        }
    }

    private void seedOpenFraudAlert(List<BankAccount> accounts, String rule, FraudSeverity severity,
                                    String details, int hoursAgo) {
        BankAccount source = accounts.stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE)
                .findFirst().orElse(accounts.get(0));
        User creator = source.getOwnerUser() != null ? source.getOwnerUser()
                : userRepository.findByCompanyId(source.getOwnerCompany().getId()).stream().findFirst().orElse(null);
        Transfer t = Transfer.builder()
                .reference("TRF-SEED-FR-" + rule)
                .type(TransferType.VIREMENT_UNITAIRE_EXTERNE)
                .status(TransferStatus.BLOQUE_FRAUDE)
                .sourceAccount(source)
                .amount(new BigDecimal("2300000"))
                .currency("XAF")
                .motif("Revue securite requise")
                .createdBy(creator)
                .createdAt(Instant.now().minus(hoursAgo, ChronoUnit.HOURS))
                .flaggedByFraudRules(true)
                .build();
        t = transferRepository.save(t);
        fraudAlertRepository.save(FraudAlert.builder()
                .transfer(t).rule(rule).severity(severity)
                .status(FraudAlertStatus.OUVERTE)
                .details(details)
                .build());
    }
}
