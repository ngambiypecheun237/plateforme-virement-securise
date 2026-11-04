package com.banque.virement.domain.entity;

import com.banque.virement.domain.enums.AccountStatus;
import com.banque.virement.domain.enums.AccountType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bank_accounts", indexes = {
        @Index(name = "idx_accounts_iban", columnList = "iban", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 34)
    private String iban;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "XAF";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    /** Plafond journalier de virements sortants */
    @Column(nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal dailyLimit = new BigDecimal("5000000");

    /** Plafond hebdomadaire de virements sortants */
    @Column(nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal weeklyLimit = new BigDecimal("20000000");

    /** Titulaire individuel (CLIENT) */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    /** Titulaire entreprise (B2B) */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "owner_company_id")
    private Company ownerCompany;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** IBAN masque pour affichage : CM38 **** **** 1234 */
    public String maskedIban() {
        if (iban == null || iban.length() < 8) {
            return "****";
        }
        return iban.substring(0, 4) + " **** **** " + iban.substring(iban.length() - 4);
    }
}
