package com.banque.virement.repository;

import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.enums.TransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByReference(String reference);

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    Page<Transfer> findBySourceAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    Page<Transfer> findByCreatedByIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<Transfer> findByCreatedByIdOrderByCreatedAtDesc(Long userId);

    long countByCreatedById(Long userId);

    long countByCreatedByIdAndStatus(Long userId, TransferStatus status);

    List<Transfer> findByStatusAndScheduledDateBefore(TransferStatus status, Instant now);

    long countByStatus(TransferStatus status);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transfer t WHERE t.status = com.banque.virement.domain.enums.TransferStatus.EXECUTE")
    BigDecimal totalExecutedAmount();

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transfer t WHERE t.status = com.banque.virement.domain.enums.TransferStatus.EXECUTE " +
            "AND t.sourceAccount.id = :accountId AND t.executedAt >= :since")
    BigDecimal sumExecutedSince(@Param("accountId") Long accountId, @Param("since") Instant since);

    long countBySourceAccountIdAndCreatedAtAfter(Long accountId, Instant since);

    @Query("SELECT t FROM Transfer t WHERE t.sourceAccount.id = :accountId " +
            "AND t.createdAt >= :since AND ((:beneficiaryId IS NULL AND t.targetAccount IS NULL AND t.externalIban IS NULL) OR t.beneficiary.id = :beneficiaryId) " +
            "AND t.amount = :amount")
    List<Transfer> findPotentialDuplicates(@Param("accountId") Long accountId,
                                           @Param("beneficiaryId") Long beneficiaryId,
                                           @Param("amount") BigDecimal amount,
                                           @Param("since") Instant since);

    @Query("SELECT t FROM Transfer t WHERE " +
            "(:userId IS NULL OR t.createdBy.id = :userId) AND " +
            "(:status IS NULL OR t.status = :status) AND " +
            "(:from IS NULL OR t.createdAt >= :from) AND " +
            "(:to IS NULL OR t.createdAt <= :to) AND " +
            "(:q IS NULL OR LOWER(t.reference) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "             OR LOWER(t.motif) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "             OR LOWER(t.sourceAccount.iban) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "             OR (t.beneficiary IS NOT NULL AND LOWER(t.beneficiary.name) LIKE LOWER(CONCAT('%', :q, '%'))))")
    Page<Transfer> search(@Param("userId") Long userId,
                          @Param("status") TransferStatus status,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          @Param("q") String q,
                          Pageable pageable);

    @Query("SELECT CAST(t.createdAt AS DATE), COUNT(t), COALESCE(SUM(t.amount), 0) FROM Transfer t " +
            "WHERE t.status = com.banque.virement.domain.enums.TransferStatus.EXECUTE AND t.createdAt >= :since " +
            "GROUP BY CAST(t.createdAt AS DATE) ORDER BY CAST(t.createdAt AS DATE)")
    List<Object[]> dailyVolume(@Param("since") Instant since);

    @Query("SELECT t.status, COUNT(t) FROM Transfer t GROUP BY t.status")
    List<Object[]> countByStatusGrouped();

    @Query("SELECT t FROM Transfer t WHERE t.sourceAccount.ownerCompany.id = :companyId AND t.status = com.banque.virement.domain.enums.TransferStatus.EN_ATTENTE_APPROBATION")
    List<Transfer> findPendingApprovalForCompany(@Param("companyId") Long companyId);

    @Query("SELECT t FROM Transfer t WHERE t.batch.id = :batchId")
    List<Transfer> findByBatchId(@Param("batchId") Long batchId);
}
