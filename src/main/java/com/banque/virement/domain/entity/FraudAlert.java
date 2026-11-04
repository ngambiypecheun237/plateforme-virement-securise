package com.banque.virement.domain.entity;

import com.banque.virement.domain.enums.FraudAlertStatus;
import com.banque.virement.domain.enums.FraudSeverity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "fraud_alerts", indexes = {
        @Index(name = "idx_fraud_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false)
    private Transfer transfer;

    /** Code de la regle de detection (ex : MONTANT_ELEVE, VELOCITE_10MIN, BENEFICIAIRE_RECENT) */
    @Column(nullable = false, length = 100)
    private String rule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FraudSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FraudAlertStatus status = FraudAlertStatus.OUVERTE;

    @Column(length = 1000)
    private String details;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    private Instant reviewedAt;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
