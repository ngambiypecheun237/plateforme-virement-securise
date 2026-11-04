package com.banque.virement.repository;

import com.banque.virement.domain.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AuditLog> findByUserEmailContainingIgnoreCaseOrderByCreatedAtDesc(String userEmail, Pageable pageable);
}
