package com.banque.virement.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Journal d'audit immuable : aucune API ne permet de modifier ou supprimer une entree.
 * Satisfait l'exigence de tracabilite totale (LBC/FT, DSP2, piste d'audit).
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_created_at", columnList = "createdAt"),
        @Index(name = "idx_audit_user", columnList = "userEmail"),
        @Index(name = "idx_audit_entity", columnList = "entityType, entityId")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userEmail;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(length = 100)
    private String entityType;

    private String entityId;

    @Column(length = 2000)
    private String details;

    private String ipAddress;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
