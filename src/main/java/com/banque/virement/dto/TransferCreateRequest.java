package com.banque.virement.dto;

import com.banque.virement.domain.entity.BankAccount;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferCreateRequest(
        @NotNull(message = "Le compte a debiter est obligatoire") Long sourceAccountId,
        /** Beneficiaire du carnet (virement vers un compte tiers ou externe) */
        Long beneficiaryId,
        /** Compte cible interne de la banque (virement interne entre comptes du client) */
        Long targetAccountId,
        @NotNull(message = "Le montant est obligatoire")
        @DecimalMin(value = "1.00", message = "Le montant doit etre positif")
        BigDecimal amount,
        @Size(max = 500) String motif,
        /** Date d'execution souhaitee (null = immediat) */
        Instant scheduledDate,
        /** Cle fournie par le client pour garantir l'idempotence (anti-doublons) */
        String idempotencyKey
) {
    public record View(Long id, String reference, String type, String status,
                       String sourceIban, String beneficiaryName, String targetIban,
                       BigDecimal amount, String currency, String motif,
                       Instant scheduledDate, Instant createdAt, Instant executedAt,
                       boolean flaggedByFraudRules, int approvalCount) {

        public static View from(com.banque.virement.domain.entity.Transfer t) {
            return new View(t.getId(), t.getReference(), t.getType().name(), t.getStatus().name(),
                    t.getSourceAccount().getIban(),
                    t.getBeneficiary() != null ? t.getBeneficiary().getName() : null,
                    t.getTargetAccount() != null ? t.getTargetAccount().getIban() : null,
                    t.getAmount(), t.getCurrency(), t.getMotif(),
                    t.getScheduledDate(), t.getCreatedAt(), t.getExecutedAt(),
                    t.isFlaggedByFraudRules(), t.getApprovalCount());
        }
    }

    public record AccountView(Long id, String iban, String type, BigDecimal balance,
                              String currency, String status, BigDecimal dailyLimit,
                              BigDecimal weeklyLimit) {

        public static AccountView from(BankAccount a) {
            return new AccountView(a.getId(), a.getIban(), a.getType().name(), a.getBalance(),
                    a.getCurrency(), a.getStatus().name(), a.getDailyLimit(), a.getWeeklyLimit());
        }
    }
}
