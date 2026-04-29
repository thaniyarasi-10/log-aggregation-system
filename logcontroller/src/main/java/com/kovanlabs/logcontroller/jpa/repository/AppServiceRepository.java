package com.kovanlabs.logcontroller.jpa.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kovanlabs.logcontroller.model.AppService;

public interface AppServiceRepository extends JpaRepository<AppService, UUID> {

	List<AppService> findByIsActiveTrue();

	List<AppService> findByIsActiveTrueOrderByNameAsc();

	Optional<AppService> findByNameIgnoreCase(String name);

	boolean existsByNameAndIsActiveTrue(String name);

	boolean existsByNameIgnoreCaseAndIsActiveTrue(String name);
}
