package com.kovanlabs.logcontroller.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kovanlabs.logcontroller.model.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, String> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    Optional<AppUser> findByEmailIgnoreCaseAndIsActiveTrue(String email);

    List<AppUser> findByIsActiveTrueOrderByUsernameAsc();

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, String id);

    boolean existsByUsernameIgnoreCaseAndIdNot(String username, String id);
}
