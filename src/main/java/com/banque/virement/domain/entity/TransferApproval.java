package com.banque.virement.domain.entity;

import com.banque.virement.domain.enums.ApprovalStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "transfer_approvals",
        uniqueConstraints = @UniqueConstraint(name = "uk_approval_transfer_approver", columnNames = {"transfer_id", "approver_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false)
    private Transfer transfer;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "approver_id", nullable = false)
    private User approver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status;

    @Column(length = 500)
    private String comment;

    @Column(nullable = false)
    @Builder.Default
    private Instant decidedAt = Instant.now();
}
