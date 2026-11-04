package com.banque.virement.domain.entity;

import com.banque.virement.domain.enums.TransferStatus;
import com.banque.virement.domain.enums.TransferType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transfers", indexes = {
        @Index(name = "idx_transfers_reference", columnList = "reference", unique = true),
        @Index(name = "idx_transfers_status", columnList = "status"),
        @Index(name = "idx_transfers_created_at", columnList = "createdAt"),
        @Index(name = "idx_transfers_source_account", columnList = "source_account_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TransferStatus status = TransferStatus.EN_ATTENTE_CONFIRMATION;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false)
    private BankAccount sourceAccount;

    /** Beneficiaire externe (null pour un virement vers un compte de la meme banque) */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "beneficiary_id")
    private Beneficiary beneficiary;

    /** Compte cible interne (virement interne) */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "target_account_id")
    private BankAccount targetAccount;

    /** Libelle libre si le beneficiaire n'est pas au carnet (usage interne) */
    private String externalIban;

    private String externalName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "XAF";

    @Column(length = 500)
    private String motif;

    /** Date d'execution souhaitee ; null = immediat */
    private Instant scheduledDate;

    /** Cle d'idempotence fournie par le client pour empecher les doublons */
    @Column(unique = true, length = 100)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id")
    private TransferBatch batch;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    private Instant executedAt;

    /** Ramp logical flag pour le tableau de bord admin */
    @Column(nullable = false)
    @Builder.Default
    private boolean flaggedByFraudRules = false;

    /** Nombre de signatures distinctes obtenues (denormalise pour les listes) */
    @Column(nullable = false)
    @Builder.Default
    private int approvalCount = 0;
}
