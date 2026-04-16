package com.kovanlabs.logcontroller.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kovanlabs.logcontroller.model.AppService;

public interface AppServiceRepository extends JpaRepository<AppService, UUID> {
}
