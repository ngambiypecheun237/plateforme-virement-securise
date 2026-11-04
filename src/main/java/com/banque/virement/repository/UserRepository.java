package com.banque.virement.repository;

import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByCompanyIdAndRole(Long companyId, Role role);

    List<User> findByCompanyId(Long companyId);

    long countByRole(Role role);

    @Query("SELECT u FROM User u WHERE (LOWER(u.firstName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(u.lastName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND (:role IS NULL OR u.role = :role)")
    List<User> search(@Param("q") String q, @Param("role") Role role);
}
