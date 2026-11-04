package com.banque.virement;

import com.banque.virement.domain.entity.BankAccount;
import com.banque.virement.domain.entity.Beneficiary;
import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.AccountStatus;
import com.banque.virement.domain.enums.AccountType;
import com.banque.virement.domain.enums.BeneficiaryStatus;
import com.banque.virement.domain.enums.FraudAlertStatus;
import com.banque.virement.domain.enums.FraudSeverity;
import com.banque.virement.domain.enums.Role;
import com.banque.virement.domain.enums.TransferStatus;
import com.banque.virement.domain.enums.TransferType;
import com.banque.virement.repository.TransferRepository;
import com.banque.virement.service.FraudDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock
    private TransferRepository transferRepository;

    private FraudDetectionService service;

    private BankAccount source;
    private User creator;

    @BeforeEach
    void setUp() {
        service = new FraudDetectionService(transferRepository);
        ReflectionTestUtils.setField(service, "montantCritique", new BigDecimal("25000000"));
        ReflectionTestUtils.setField(service, "montantEleve", new BigDecimal("5000000"));
        ReflectionTestUtils.setField(service, "montantNuit", new BigDecimal("1000000"));
        ReflectionTestUtils.setField(service, "beneficiaireRecentJours", 7);
        ReflectionTestUtils.setField(service, "beneficiaireRecentMontant", new BigDecimal("2000000"));
        ReflectionTestUtils.setField(service, "velociteMax", 3);
        ReflectionTestUtils.setField(service, "velociteFenetreMinutes", 10);

        creator = User.builder().id(1L).firstName("Jean").lastName("MARTIN")
                .email("jean@client.cm").passwordHash("x").role(Role.CLIENT).active(true).build();

        source = BankAccount.builder().id(42L).iban("CM3810000001123456789012345")
                .type(AccountType.COURANT).balance(new BigDecimal("100000000"))
                .status(AccountStatus.ACTIVE).ownerUser(creator).build();
    }

    private Transfer transferOf(BigDecimal amount, Beneficiary beneficiary) {
        return Transfer.builder()
                .id(99L)
                .reference("TRF-TEST-0001")
                .type(TransferType.VIREMENT_UNITAIRE_EXTERNE)
                .status(TransferStatus.EN_ATTENTE_CONFIRMATION)
                .sourceAccount(source)
                .beneficiary(beneficiary)
                .amount(amount)
                .currency("XAF")
                .createdBy(creator)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void montantCritiqueDeclencheUneAlerteCritiqueBloquante() {
        Transfer t = transferOf(new BigDecimal("30000000"), ancienBeneficiaire());
        when(transferRepository.countBySourceAccountIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);

        List<com.banque.virement.domain.entity.FraudAlert> alerts = service.screen(t);

        assertThat(alerts).extracting(com.banque.virement.domain.entity.FraudAlert::getRule)
                .contains("MONTANT_CRITIQUE");
        assertThat(service.isBlocking(alerts)).isTrue();
    }

    @Test
    void montantModereNeBlocquePas() {
        Transfer t = transferOf(new BigDecimal("50000"), ancienBeneficiaire());
        when(transferRepository.countBySourceAccountIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);

        List<com.banque.virement.domain.entity.FraudAlert> alerts = service.screen(t);

        // Aucune alerte MOYENNE/HAUTE attendue pour un petit montant en journée normale
        assertThat(alerts).extracting(com.banque.virement.domain.entity.FraudAlert::getSeverity)
                .doesNotContain(FraudSeverity.HAUTE, FraudSeverity.CRITIQUE);
    }

    @Test
    void beneficiaireRecentAvecGrosMontantBloque() {
        Beneficiary recent = Beneficiary.builder()
                .id(7L).owner(creator).name("Nouveau").iban("GA7410000010000000000000123")
                .status(BeneficiaryStatus.ACTIVE).verified(true)
                .createdAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .availableAt(Instant.now().minus(1, ChronoUnit.DAYS)).build();

        Transfer t = transferOf(new BigDecimal("2500000"), recent);
        when(transferRepository.countBySourceAccountIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);

        List<com.banque.virement.domain.entity.FraudAlert> alerts = service.screen(t);

        assertThat(alerts).extracting(com.banque.virement.domain.entity.FraudAlert::getRule)
                .contains("BENEFICIAIRE_RECENT");
        assertThat(service.isBlocking(alerts)).isTrue();
    }

    @Test
    void velociteDetecteLesrafalesDeVirements() {
        Transfer t = transferOf(new BigDecimal("100000"), ancienBeneficiaire());
        when(transferRepository.countBySourceAccountIdAndCreatedAtAfter(anyLong(), any())).thenReturn(5L);

        List<com.banque.virement.domain.entity.FraudAlert> alerts = service.screen(t);

        assertThat(alerts).extracting(com.banque.virement.domain.entity.FraudAlert::getRule)
                .anyMatch(rule -> rule.startsWith("VELOCITE"));
        assertThat(service.isBlocking(alerts)).isTrue();
    }

    private Beneficiary ancienBeneficiaire() {
        return Beneficiary.builder()
                .id(8L).owner(creator).name("Ancien").iban("FR4812345678901234567890123")
                .status(BeneficiaryStatus.ACTIVE).verified(true)
                .createdAt(Instant.now().minus(120, ChronoUnit.DAYS))
                .availableAt(Instant.now().minus(119, ChronoUnit.DAYS)).build();
    }
}
