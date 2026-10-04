package com.devlabs.aulaflix.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.Role;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    boolean existsByEmail(String email);

    Optional<AccountEntity> findByEmailAndRole(String email, Role role);
}
