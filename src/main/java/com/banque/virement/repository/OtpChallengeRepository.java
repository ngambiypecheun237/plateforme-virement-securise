package com.banque.virement.repository;

import com.banque.virement.domain.entity.OtpChallenge;
import com.banque.virement.domain.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, Long> {

    Optional<OtpChallenge> findTopByUserIdAndPurposeAndContextAndConsumedFalseOrderByCreatedAtDesc(
            Long userId, OtpPurpose purpose, String context);

    List<OtpChallenge> findByUserIdAndPurposeAndConsumedFalse(Long userId, OtpPurpose purpose);
}
