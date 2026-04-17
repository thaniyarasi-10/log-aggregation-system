package com.kovanlabs.logcontroller.repository;

import com.kovanlabs.logcontroller.model.AppRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppRoleRepository extends JpaRepository<AppRole, UUID> {
    Optional<AppRole> findByNameIgnoreCase(String name);
}
