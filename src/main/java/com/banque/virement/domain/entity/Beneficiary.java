package com.banque.virement.domain.entity;

import com.banque.virement.domain.enums.BeneficiaryStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "beneficiaries",
        uniqueConstraints = @UniqueConstraint(name = "uk_beneficiary_owner_iban", columnNames = {"owner_id", "iban"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Beneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 34)
    private String iban;

    private String bic;

    private String bankName;

    private String country;

    /** Adresse email du beneficiaire (optionnel) */
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BeneficiaryStatus status = BeneficiaryStatus.ACTIVE;

    /** IBAN verifie par controle de la cle modulo 97 */
    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Delai de carence : le beneficiaire ne peut recevoir un virement qu'apres cette date (ex : J+48h) */
    @Column(nullable = false)
    private Instant availableAt;

    public boolean isAvailable() {
        return status == BeneficiaryStatus.ACTIVE && !Instant.now().isBefore(availableAt);
    }
}
