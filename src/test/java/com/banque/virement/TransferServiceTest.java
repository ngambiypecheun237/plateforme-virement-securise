package com.banque.virement;

import com.banque.virement.domain.entity.BankAccount;
import com.banque.virement.domain.entity.Beneficiary;
import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.*;
import com.banque.virement.dto.TransferCreateRequest;
import com.banque.virement.exception.BusinessException;
import com.banque.virement.exception.ResourceNotFoundException;
import com.banque.virement.repository.*;
import com.banque.virement.service.*;
import com.banque.virement.util.ReferenceGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private TransferRepository transferRepository;
    @Mock private BankAccountRepository accountRepository;
    @Mock private BeneficiaryRepository beneficiaryRepository;
    @Mock private UserRepository userRepository;
    @Mock private FraudAlertRepository fraudAlertRepository;
    @Mock private OtpService otpService;
    @Mock private FraudDetectionService fraudDetectionService;
    @Mock private NotificationService notificationService;
    @Mock private AuditService auditService;
    @Mock private CoreBankingGateway coreBankingGateway;

    private TransferService service;

    private User jean;
    private BankAccount jeanCourant;
    private Beneficiary beneficiaire;

    @BeforeEach
    void setUp() {
        service = new TransferService(transferRepository, accountRepository, beneficiaryRepository, userRepository,
                otpService, fraudDetectionService, fraudAlertRepository, notificationService, auditService,
                new ReferenceGenerator(), coreBankingGateway);

        jean = User.builder().id(1L).firstName("Jean").lastName("MARTIN")
                .email("jean@client.cm").passwordHash("x").role(Role.CLIENT).active(true).build();

        jeanCourant = BankAccount.builder().id(10L).iban("CM3810000001123456789012345")
                .type(AccountType.COURANT).balance(new BigDecimal("1000000"))
                .dailyLimit(new BigDecimal("3000000")).weeklyLimit(new BigDecimal("10000000"))
                .status(AccountStatus.ACTIVE).ownerUser(jean).currency("XAF").build();

        beneficiaire = Beneficiary.builder().id(20L).owner(jean).name("Marie NGA")
                .iban("CM6010000009876543210987654")
                .status(BeneficiaryStatus.ACTIVE).verified(true)
                .createdAt(Instant.now().minus(30, ChronoUnit.DAYS))
                .availableAt(Instant.now().minus(29, ChronoUnit.DAYS)).build();
    }

    @Test
    void creationRefuseUnCompteQuiNappartientPasAuClient() {
        BankAccount etranger = BankAccount.builder().id(99L)
                .iban("GA7410000010000000000000123").type(AccountType.COURANT)
                .balance(new BigDecimal("1000")).status(AccountStatus.ACTIVE)
                .ownerUser(User.builder().id(2L).build()).build();
        when(accountRepository.findById(99L)).thenReturn(Optional.of(etranger));

        TransferCreateRequest request = new TransferCreateRequest(99L, 20L, null,
                new BigDecimal("50000"), "test", null, null);

        assertThatThrownBy(() -> service.create(jean, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("appartient");
    }

    @Test
    void creationRefuseUnSoldeInsuffisant() {
        when(accountRepository.findById(10L)).thenReturn(Optional.of(jeanCourant));
        when(beneficiaryRepository.findByIdAndOwnerId(20L, 1L)).thenReturn(Optional.of(beneficiaire));

        TransferCreateRequest request = new TransferCreateRequest(10L, 20L, null,
                new BigDecimal("5000000"), "trop gros", null, null);

        assertThatThrownBy(() -> service.create(jean, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Solde insuffisant");
    }

    @Test
    void creationBloqueParLeDelaiDeCarence() {
        Beneficiary nouveau = Beneficiary.builder().id(21L).owner(jean).name("Nouveau")
                .iban("GA7410000010000000000000123").status(BeneficiaryStatus.ACTIVE).verified(true)
                .createdAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .availableAt(Instant.now().plus(47, ChronoUnit.HOURS)).build();
        when(accountRepository.findById(10L)).thenReturn(Optional.of(jeanCourant));
        when(beneficiaryRepository.findByIdAndOwnerId(21L, 1L)).thenReturn(Optional.of(nouveau));

        TransferCreateRequest request = new TransferCreateRequest(10L, 21L, null,
                new BigDecimal("50000"), "test", null, null);

        assertThatThrownBy(() -> service.create(jean, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("carence");
    }

    @Test
    void creationDetecteLesDoublons() {
        when(accountRepository.findById(10L)).thenReturn(Optional.of(jeanCourant));
        when(beneficiaryRepository.findByIdAndOwnerId(20L, 1L)).thenReturn(Optional.of(beneficiaire));
        when(transferRepository.sumExecutedSince(eq(10L), any())).thenReturn(BigDecimal.ZERO);
        when(transferRepository.findPotentialDuplicates(eq(10L), eq(20L), any(), any()))
                .thenReturn(List.of(mock(Transfer.class)));

        TransferCreateRequest request = new TransferCreateRequest(10L, 20L, null,
                new BigDecimal("50000"), "doublon", null, null);

        assertThatThrownBy(() -> service.create(jean, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("double");
    }

    @Test
    void creationDeclencheUnOtpPourUnClientB2C() {
        when(accountRepository.findById(10L)).thenReturn(Optional.of(jeanCourant));
        when(beneficiaryRepository.findByIdAndOwnerId(20L, 1L)).thenReturn(Optional.of(beneficiaire));
        when(transferRepository.sumExecutedSince(eq(10L), any())).thenReturn(BigDecimal.ZERO);
        when(transferRepository.findPotentialDuplicates(anyLong(), any(), any(), any())).thenReturn(List.of());
        when(accountRepository.findByIban(beneficiaire.getIban())).thenReturn(Optional.empty());
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> {
            Transfer t = inv.getArgument(0);
            t.setId(777L);
            return t;
        });

        TransferCreateRequest request = new TransferCreateRequest(10L, 20L, null,
                new BigDecimal("50000"), "loyer", null, null);

        Transfer created = service.create(jean, request);

        assertThat(created.getStatus()).isEqualTo(TransferStatus.EN_ATTENTE_CONFIRMATION);
        verify(otpService).requestOtp(eq(jean), eq(OtpPurpose.CONFIRMATION_VIREMENT), eq("777"));
    }

    @Test
    void creationImpacteLePlafondJournalier() {
        when(accountRepository.findById(10L)).thenReturn(Optional.of(jeanCourant));
        when(beneficiaryRepository.findByIdAndOwnerId(20L, 1L)).thenReturn(Optional.of(beneficiaire));
        when(transferRepository.sumExecutedSince(eq(10L), any()))
                .thenReturn(new BigDecimal("2900000"));   // deja proche du plafond de 3 000 000

        TransferCreateRequest request = new TransferCreateRequest(10L, 20L, null,
                new BigDecimal("200000"), "depassement", null, null);

        assertThatThrownBy(() -> service.create(jean, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Plafond journalier");
    }

    @Test
    void confirmationExecuteLeVirementInterne() {
        BankAccount target = BankAccount.builder().id(11L).iban("CM6010000009876543210987654")
                .type(AccountType.COURANT).balance(new BigDecimal("0"))
                .status(AccountStatus.ACTIVE).build();

        Transfer transfer = Transfer.builder().id(555L).reference("TRF-TEST-0555")
                .type(TransferType.VIREMENT_UNITAIRE_INTERNE)
                .status(TransferStatus.EN_ATTENTE_CONFIRMATION)
                .sourceAccount(jeanCourant).beneficiary(beneficiaire)
                .amount(new BigDecimal("100000")).currency("XAF")
                .createdBy(jean).createdAt(Instant.now()).build();

        when(transferRepository.findById(555L)).thenReturn(Optional.of(transfer));
        when(fraudDetectionService.screen(transfer)).thenReturn(List.of());
        when(fraudDetectionService.isBlocking(List.of())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(jeanCourant));
        when(accountRepository.findByIban(beneficiaire.getIban())).thenReturn(Optional.of(target));
        when(accountRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(target));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> inv.getArgument(0));

        Transfer executed = service.confirm(jean, 555L, "123456");

        verify(otpService).verify(jean, OtpPurpose.CONFIRMATION_VIREMENT, "555", "123456");
        assertThat(executed.getStatus()).isEqualTo(TransferStatus.EXECUTE);
        assertThat(jeanCourant.getBalance()).isEqualByComparingTo("900000");
        assertThat(target.getBalance()).isEqualByComparingTo("100000");
    }

    @Test
    void confirmationBloqueParLantiFraude() {
        Transfer transfer = Transfer.builder().id(556L).reference("TRF-TEST-0556")
                .type(TransferType.VIREMENT_UNITAIRE_EXTERNE)
                .status(TransferStatus.EN_ATTENTE_CONFIRMATION)
                .sourceAccount(jeanCourant).beneficiary(beneficiaire)
                .amount(new BigDecimal("8000000")).currency("XAF")
                .createdBy(jean).createdAt(Instant.now()).build();

        com.banque.virement.domain.entity.FraudAlert alert =
                com.banque.virement.domain.entity.FraudAlert.builder()
                        .rule("MONTANT_CRITIQUE").severity(FraudSeverity.CRITIQUE).build();

        when(transferRepository.findById(556L)).thenReturn(Optional.of(transfer));
        when(fraudDetectionService.screen(transfer)).thenReturn(List.of(alert));
        when(fraudDetectionService.isBlocking(List.of(alert))).thenReturn(true);

        Transfer blocked = service.confirm(jean, 556L, "123456");

        assertThat(blocked.getStatus()).isEqualTo(TransferStatus.BLOQUE_FRAUDE);
        verify(accountRepository, never()).findByIdForUpdate(anyLong());
    }
}
