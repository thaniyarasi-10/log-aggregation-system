package com.kovanlabs.logcontroller.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.model.UserServiceId;
import com.kovanlabs.logcontroller.model.UserServiceMapping;

public interface UserServiceMappingRepository extends JpaRepository<UserServiceMapping, UserServiceId> {

    @Query("SELECT us.service FROM UserServiceMapping us WHERE us.user.id = :userId")
    List<AppService> findServicesByUserId(@Param("userId") UUID userId);
}
