package com.banque.virement.repository;

import com.banque.virement.domain.entity.TransferBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransferBatchRepository extends JpaRepository<TransferBatch, Long> {

    Optional<TransferBatch> findByReference(String reference);

    List<TransferBatch> findByUploadedByIdOrderByCreatedAtDesc(Long userId);
}
