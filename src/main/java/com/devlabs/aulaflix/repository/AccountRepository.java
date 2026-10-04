package com.devlabs.aulaflix.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devlabs.aulaflix.domain.entity.AccountEntity;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    boolean existsByEmail(String email);
}
