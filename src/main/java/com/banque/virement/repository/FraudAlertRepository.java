package com.banque.virement.repository;

import com.banque.virement.domain.entity.FraudAlert;
import com.banque.virement.domain.enums.FraudAlertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    List<FraudAlert> findByTransferId(Long transferId);

    Page<FraudAlert> findByStatusOrderByCreatedAtDesc(FraudAlertStatus status, Pageable pageable);

    Page<FraudAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(FraudAlertStatus status);
}
