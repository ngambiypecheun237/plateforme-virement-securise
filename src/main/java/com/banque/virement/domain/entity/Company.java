package com.banque.virement.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "companies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Numero RCCM (registre du commerce) */
    @Column(nullable = false, unique = true)
    private String registrationNumber;

    /** Numero d'Identification Unique (contribuable) */
    @Column(unique = true)
    private String taxId;

    /** Nombre de signatures de mandataires requises avant execution d'un virement B2B */
    @Column(nullable = false)
    @Builder.Default
    private int requiredApprovals = 2;

    /** Seuil au-dela duquel une double signature est exigee (sinon 1 signature suffit) */
    private Double singleApprovalThreshold;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
