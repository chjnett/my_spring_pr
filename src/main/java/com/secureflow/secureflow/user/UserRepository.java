package com.secureflow.secureflow.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);

    long countByEmail(String email);

    Optional<User> findByEmail(String email);
}
