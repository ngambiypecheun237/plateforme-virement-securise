package com.banque.virement.repository;

import com.banque.virement.domain.entity.TransferApproval;
import com.banque.virement.domain.enums.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransferApprovalRepository extends JpaRepository<TransferApproval, Long> {

    List<TransferApproval> findByTransferId(Long transferId);

    Optional<TransferApproval> findByTransferIdAndApproverId(Long transferId, Long approverId);

    long countByTransferIdAndStatus(Long transferId, ApprovalStatus status);
}
