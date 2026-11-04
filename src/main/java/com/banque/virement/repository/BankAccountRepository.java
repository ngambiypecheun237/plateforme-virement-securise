package com.banque.virement.repository;

import com.banque.virement.domain.entity.BankAccount;
import com.banque.virement.domain.enums.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT a FROM BankAccount a WHERE a.id = :id")
    java.util.Optional<BankAccount> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<BankAccount> findByIban(String iban);

    List<BankAccount> findByOwnerUserId(Long userId);

    List<BankAccount> findByOwnerCompanyId(Long companyId);

    long countByStatus(AccountStatus status);

    @org.springframework.data.jpa.repository.Query(
            "SELECT a FROM BankAccount a LEFT JOIN a.ownerUser u LEFT JOIN a.ownerCompany c " +
            "WHERE LOWER(a.iban) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "   OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "   OR LOWER(u.lastName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "   OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "   OR LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<BankAccount> search(@org.springframework.data.repository.query.Param("q") String q);
}
