package com.banque.virement.repository;

import com.banque.virement.domain.entity.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {

    List<Beneficiary> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    Optional<Beneficiary> findByIdAndOwnerId(Long id, Long ownerId);

    Optional<Beneficiary> findByOwnerIdAndIban(Long ownerId, String iban);
}
