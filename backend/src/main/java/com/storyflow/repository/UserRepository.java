package com.storyflow.repository;

import com.storyflow.entity.User;
import com.storyflow.entity.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    long countByRole(UserRole role);

    long countByEnabledTrue();

    @Query("""
            SELECT u FROM User u
            WHERE (:q IS NULL OR :q = ''
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:role IS NULL OR u.role = :role)
              AND (:enabled IS NULL OR u.enabled = :enabled)
            """)
    Page<User> search(@Param("q") String q,
                      @Param("role") UserRole role,
                      @Param("enabled") Boolean enabled,
                      Pageable pageable);
}
