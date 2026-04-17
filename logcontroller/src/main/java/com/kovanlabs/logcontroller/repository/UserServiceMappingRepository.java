package com.kovanlabs.logcontroller.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.model.UserServiceMapping;

public interface UserServiceMappingRepository extends JpaRepository<UserServiceMapping, UUID> {

    @Query("SELECT us.service FROM UserServiceMapping us WHERE us.user.id = :userId AND us.service.isActive = true")
    List<AppService> findServicesByUserId(@Param("userId") String userId);

    @Query("SELECT DISTINCT us.service.name FROM UserServiceMapping us WHERE us.user.id = :userId AND us.service.isActive = true")
    List<String> findServiceNamesByUserId(@Param("userId") String userId);

    void deleteByUser_Id(String userId);
}
