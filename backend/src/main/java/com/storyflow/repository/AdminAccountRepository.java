package com.storyflow.repository;

import com.storyflow.entity.AdminAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {
    Optional<AdminAccount> findByUsernameIgnoreCase(String username);
    Optional<AdminAccount> findByUserId(Long userId);
    boolean existsByUsernameIgnoreCase(String username);
}
